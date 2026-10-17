"""EvalLog: the on-device per-step JSONL the `dev` build writes (FOUNDATION §12; ADR-0015).

`EvalLog.kt` (app/src/dev) appends one JSON object per line under the app's files dir, e.g.
``/data/data/dev.operator/files/eval/evallog.jsonl``.  The laptop driver pulls that file with
``adb exec-out run-as dev.operator cat files/eval/evallog.jsonl`` into ``~/operator-eval/<date>-<sha>``
and never into the repo (OQ-2 default (b)).  ``scorer.py`` reads it for the latency components.

Record kinds
------------
task
    ``{"type": "task", "taskId": str, "run": int, "seed": int, "goal": str, "status":
       "started"|"done"|"aborted"|"failed"|"refused", "ts": int, "answer": str|null}``
step
    ``{"type": "step", "taskId": str, "run": int, "step": int, "ts": int,
       "t_read_ms": int, "t_prefill_ms": int, "t_decode_ms": int, "t_decide_ms": int,
       "t_act_ms": int, "t_settle_ms": int, "n_prompt": int, "n_gen": int,
       "parse_ok": bool, "raw": str,
       "action": {"name": str, "element": int|null, "text": str|null, "direction": str|null,
                  "package": str|null, "point": [x, y]|null},
       "valid": bool, "executable": bool,
       "gate": {"events": [str, ...]},
       "vm_hwm_kb": int|null, "headroom": float|null,
       "charge_counter_uah": int|null, "current_now_ua": int|null,
       "snapshot": <op-replay-v0 snapshot>?}      # only while recording a replay set

Only stdlib; readers are pure (no adb, no phone).
"""

from __future__ import annotations

import json
from typing import Any, Dict, Iterator, List, Optional

TASK = "task"
STEP = "step"

TERMINAL_STATUSES = ("done", "aborted", "failed", "refused")

#: The §12 latency components, in milliseconds, and the end-to-end composite.
LATENCY_FIELDS = (
    "t_read_ms", "t_prefill_ms", "t_decode_ms", "t_decide_ms", "t_act_ms", "t_settle_ms",
)
END_TO_END_FIELD = "t_step_ms"

#: The §12 resource fields carried alongside each step.
RESOURCE_FIELDS = ("vm_hwm_kb", "headroom", "charge_counter_uah", "current_now_ua")


def iter_lines(path: str) -> Iterator[Dict[str, Any]]:
    """Yield the JSONL records of an EvalLog file; malformed lines raise with the line number."""
    with open(path, "r", encoding="utf-8") as handle:
        for number, line in enumerate(handle, 1):
            line = line.strip()
            if not line:
                continue
            try:
                record = json.loads(line)
            except json.JSONDecodeError as exc:
                raise ValueError("%s:%d: not JSON: %s" % (path, number, exc)) from exc
            if not isinstance(record, dict):
                raise ValueError("%s:%d: record is not an object" % (path, number))
            yield record


def task_records(records: Iterator[Dict[str, Any]]) -> List[Dict[str, Any]]:
    return [r for r in records if r.get("type") == TASK]


def step_records(records: Iterator[Dict[str, Any]]) -> List[Dict[str, Any]]:
    return [r for r in records if r.get("type") == STEP]


def step_total_ms(record: Dict[str, Any]) -> Optional[int]:
    """End-to-end step time: the six components when present, else the logged composite."""
    if END_TO_END_FIELD in record and record[END_TO_END_FIELD] is not None:
        try:
            return int(record[END_TO_END_FIELD])
        except (TypeError, ValueError):
            return None
    parts = []
    for field in LATENCY_FIELDS:
        value = record.get(field)
        if value is None:
            return None
        try:
            parts.append(int(value))
        except (TypeError, ValueError):
            return None
    return sum(parts)


def latency_series(records: List[Dict[str, Any]]) -> Dict[str, List[int]]:
    """Collect one series per §12 latency component plus the composite, dropping absent values."""
    series: Dict[str, List[int]] = {field: [] for field in LATENCY_FIELDS}
    series[END_TO_END_FIELD] = []
    for record in records:
        for field in LATENCY_FIELDS:
            value = record.get(field)
            if value is None:
                continue
            try:
                series[field].append(int(value))
            except (TypeError, ValueError):
                continue
        total = step_total_ms(record)
        if total is not None:
            series[END_TO_END_FIELD].append(total)
    return series


def resource_series(records: List[Dict[str, Any]]) -> Dict[str, List[float]]:
    series: Dict[str, List[float]] = {field: [] for field in RESOURCE_FIELDS}
    for record in records:
        for field in RESOURCE_FIELDS:
            value = record.get(field)
            if value is None:
                continue
            try:
                series[field].append(float(value))
            except (TypeError, ValueError):
                continue
    return series
