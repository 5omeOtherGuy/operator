#!/usr/bin/env python3
"""scorer.py — the operator evaluation scorer (FOUNDATION §12; ADR-0015; research/07 §R1).

It reads

* an **op-replay-v0** set (``replay_v0.py``) whose ``step`` records carry a ``gold`` action and,
  when the run produced one, a ``prediction`` (the model's raw string plus its parse); and
* an **EvalLog** JSONL (``evallog.py``) pulled from the phone,

and writes the fixed metric set of research 07 §R1 as ``metrics.json`` (numbers only, so it is the
one artefact that may enter the repo under ``eval/results/``).

Implemented now (§12 DoD 1): step accuracy, valid-and-executable action rate, raw parse rate and the
per-component latency p50/p95.  The remaining §R1 metrics are placeholders with ``null`` until their
inputs exist (decide decision set, benchmarks, energy runs).

Python 3 stdlib + unittest only.  Nothing here touches a device.
"""

from __future__ import annotations

import argparse
import json
import os
import statistics
import sys
from typing import Any, Dict, Iterable, List, Optional, Tuple

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import evallog  # noqa: E402
import replay_v0  # noqa: E402

#: The §7.2 tool catalogue names (ToolCall.kt).  A parse is "valid" only if it names one of these.
KNOWN_TOOLS = frozenset({
    "read_screen", "screenshot_internal", "list_notifications", "next_alarm", "calendar_query",
    "ask_owner", "finish", "wait", "click", "long_click", "set_text", "scroll", "back", "home",
    "recents", "notifications", "dismiss_shade", "quick_settings", "lock_screen", "media",
    "launch_app", "set_alarm", "set_timer", "dismiss_alarm", "calendar_insert", "torch",
    "send_sms", "call", "reply_notification", "notification_action", "calendar_delete",
    "headset_hook", "hide_app", "suspend_app", "set_permission", "install_apk", "uninstall", "reboot",
})

ELEMENT_TOOLS = frozenset({"click", "long_click", "set_text", "scroll"})
GLOBAL_TOOLS = frozenset({
    "back", "home", "recents", "notifications", "dismiss_shade", "quick_settings", "lock_screen",
    "wait", "read_screen", "screenshot_internal", "list_notifications", "next_alarm", "finish",
    "ask_owner",
})

ACTION_FOR_TOOL = {
    "click": ("CLICK",),
    "long_click": ("LONG_CLICK",),
    "set_text": ("SET_TEXT",),
    "scroll": ("SCROLL_FORWARD", "SCROLL_BACKWARD"),
}


# --------------------------------------------------------------------------------------------------
# statistics
# --------------------------------------------------------------------------------------------------

def percentile(values: Iterable[float], q: float) -> Optional[float]:
    """Linear-interpolation percentile (the numpy default).  ``None`` for an empty series."""
    ordered = sorted(float(v) for v in values)
    if not ordered:
        return None
    if len(ordered) == 1:
        return ordered[0]
    position = (len(ordered) - 1) * (float(q) / 100.0)
    low = int(position)
    high = min(low + 1, len(ordered) - 1)
    fraction = position - low
    return ordered[low] + (ordered[high] - ordered[low]) * fraction


def summarise(values: Iterable[float]) -> Dict[str, Optional[float]]:
    series = [float(v) for v in values]
    if not series:
        return {"n": 0, "mean": None, "p50": None, "p95": None}
    return {
        "n": len(series),
        "mean": statistics.fmean(series),
        "p50": percentile(series, 50),
        "p95": percentile(series, 95),
    }


def _ratio(numerator: int, denominator: int) -> Optional[float]:
    if denominator <= 0:
        return None
    return numerator / denominator


# --------------------------------------------------------------------------------------------------
# replay scoring
# --------------------------------------------------------------------------------------------------

def action_is_valid(parsed: Optional[Dict[str, Any]], snap: Optional[Dict[str, Any]]) -> bool:
    """§7.4 step 4 on paper: a known verb with the arguments it needs."""
    if not parsed:
        return False
    name = parsed.get("name")
    if name not in KNOWN_TOOLS:
        return False
    if name in ELEMENT_TOOLS and parsed.get("element") is None:
        return False
    if name == "set_text" and parsed.get("text") is None:
        return False
    if name == "scroll" and parsed.get("direction") is None:
        return False
    if name == "launch_app" and not parsed.get("package"):
        return False
    if name in ("send_sms", "call") and not parsed.get("number"):
        return False
    return True


def action_is_executable(parsed: Optional[Dict[str, Any]], snap: Optional[Dict[str, Any]]) -> bool:
    """The reference element exists, is present in the snapshot and supports the action (§7.4 step 4)."""
    if not action_is_valid(parsed, snap):
        return False
    name = parsed["name"]
    if name in GLOBAL_TOOLS:
        return True
    if name not in ELEMENT_TOOLS:
        return True
    if snap is None:
        return False
    nodes = snap.get("nodes") or []
    index = parsed.get("element")
    try:
        index = int(index)
    except (TypeError, ValueError):
        return False
    if index < 0 or index >= len(nodes):
        return False
    node = nodes[index]
    actions = set(node.get("actions") or ())
    if name == "set_text" and "PASSWORD" in set(node.get("state") or ()):
        return False
    return any(a in actions for a in ACTION_FOR_TOOL.get(name, ()))


def _tap_match(prediction: Dict[str, Any], parsed: Dict[str, Any], gold: Dict[str, Any],
               snap: Optional[Dict[str, Any]]) -> bool:
    """AndroidControl rule (research 07 §F3): the bboxes contain each other's points."""
    gold_point = gold.get("point")
    gold_element = gold.get("element")
    gold_box = replay_v0.element_bbox(snap, gold_element) if snap is not None else None
    pred_point = parsed.get("point")
    pred_box = replay_v0.element_bbox(snap, parsed.get("element")) if snap is not None else None

    if gold_point and pred_box and replay_v0.point_in_bbox(tuple(gold_point), pred_box):
        return True
    if pred_point and gold_box and replay_v0.point_in_bbox(tuple(pred_point), gold_box):
        return True
    if gold_element is not None and parsed.get("element") is not None:
        try:
            return int(parsed["element"]) == int(gold_element)
        except (TypeError, ValueError):
            return False
    return False


def step_matches(prediction: Dict[str, Any], gold: Dict[str, Any], snap: Optional[Dict[str, Any]]) -> bool:
    parsed = prediction.get("parsed")
    if not parsed:
        return False
    gold_action = gold.get("action")
    want_tool = replay_v0.TOOL_FOR_GOLD.get(gold_action)
    if want_tool is None:
        return False
    if parsed.get("name") != want_tool:
        return False

    if gold_action in replay_v0.TAP_GOLD:
        if not action_is_executable(parsed, snap):
            return False
        return _tap_match(prediction, parsed, gold, snap)
    if gold_action == "input_text":
        return replay_v0.normalise_text(parsed.get("text")) == replay_v0.normalise_text(gold.get("text"))
    if gold_action == "scroll":
        return (parsed.get("direction") or "").lower() == (gold.get("direction") or "").lower()
    if gold_action == "open_app":
        want = replay_v0.normalise_text(gold.get("app"))
        got = replay_v0.normalise_text(parsed.get("package"))
        return bool(want) and (got == want or got.endswith(want) or want.endswith(got))
    # navigate_home / navigate_back / wait: the verb alone decides.
    return True


def score_replay(records: Iterable[Dict[str, Any]]) -> Dict[str, Any]:
    """Step accuracy, valid-and-executable rate and raw parse rate over an op-replay-v0 set."""
    steps: List[Dict[str, Any]] = []
    with_prediction = 0
    parsed_ok = 0
    valid_and_executable = 0
    hits = 0
    per_task: Dict[str, List[int]] = {}

    for record in records:
        if record.get("type") != replay_v0.STEP:
            continue
        steps.append(record)
        prediction = record.get("prediction")
        gold = record.get("gold") or {}
        snap = record.get("snapshot")
        if prediction is not None:
            with_prediction += 1
            parsed = prediction.get("parsed")
            if parsed is not None:
                parsed_ok += 1
                executable = prediction.get("executable")
                if executable is None:
                    executable = action_is_executable(parsed, snap)
                valid = prediction.get("valid")
                if valid is None:
                    valid = action_is_valid(parsed, snap)
                if valid and executable:
                    valid_and_executable += 1
        # A step with no prediction is a miss, not an omission: it stays in the denominator.
        hit = step_matches(prediction, gold, snap) if prediction is not None else False
        hits += int(hit)
        per_task.setdefault(str(record.get("taskId")), []).append(int(hit))

    total = len(steps)
    return {
        "steps": total,
        "steps_with_prediction": with_prediction,
        "step_accuracy": _ratio(hits, total),
        "raw_parse_rate": _ratio(parsed_ok, with_prediction),
        "valid_and_executable_rate": _ratio(valid_and_executable, with_prediction),
        "per_task_accuracy": {task: _ratio(sum(v), len(v)) for task, v in sorted(per_task.items())},
    }


# --------------------------------------------------------------------------------------------------
# EvalLog scoring
# --------------------------------------------------------------------------------------------------

def score_evallog(records: Iterable[Dict[str, Any]]) -> Dict[str, Any]:
    """Latency p50/p95 per §12 component, plus the parse and validity rates the app logged."""
    materialised = list(records)
    task_lines = [r for r in materialised if r.get("type") == evallog.TASK]
    step_lines = [r for r in materialised if r.get("type") == evallog.STEP]

    series = evallog.latency_series(step_lines)
    latency = {field: summarise(series.get(field, []))
               for field in evallog.LATENCY_FIELDS + (evallog.END_TO_END_FIELD,)}
    resources = {field: summarise(vals) for field, vals in evallog.resource_series(step_lines).items()}

    parse_ok = sum(1 for r in step_lines if r.get("parse_ok") is True)
    executable = sum(1 for r in step_lines if r.get("valid") is True and r.get("executable") is True)

    by_task: Dict[str, Dict[str, Any]] = {}
    for line in task_lines:
        if line.get("status") in evallog.TERMINAL_STATUSES:
            entry = by_task.setdefault(str(line.get("taskId")), {"runs": 0, "done": 0})
            entry["runs"] += 1
            if line.get("status") == "done":
                entry["done"] += 1
    for task, entry in by_task.items():
        entry["success_rate"] = _ratio(entry["done"], entry["runs"])

    return {
        "tasks": {task: {"runs": e["runs"], "done": e["done"], "success_rate": e.get("success_rate")}
                  for task, e in sorted(by_task.items())},
        "steps": len(step_lines),
        "raw_parse_rate": _ratio(parse_ok, len(step_lines)),
        "valid_and_executable_rate": _ratio(executable, len(step_lines)),
        "latency_ms": latency,
        "resources": resources,
    }


# --------------------------------------------------------------------------------------------------
# metrics.json
# --------------------------------------------------------------------------------------------------

def build_metrics(
    *,
    replay_path: Optional[str] = None,
    evallog_path: Optional[str] = None,
    replay_source: Optional[str] = None,
    evallog_source: Optional[str] = None,
) -> Dict[str, Any]:
    """Assemble the §R1 metric document.  Only numbers and paths go in; no screen text."""
    metrics: Dict[str, Any] = {
        "schema": "operator-metrics-v0",
        "inputs": {
            "replay": os.path.basename(replay_path) if replay_path else None,
            "evallog": os.path.basename(evallog_path) if evallog_path else None,
        },
        # Filled from inputs when they are present; null until their producer exists (§R1).
        "replay": None,
        "run": None,
        "decide": None,
        "bench": None,
        "energy": None,
    }
    if replay_path:
        records = list(replay_v0.iter_records(replay_path))
        metrics["replay"] = score_replay(records)
        metrics["replay_source"] = replay_source or "op-replay-v0"
    if evallog_path:
        metrics["run"] = score_evallog(evallog.iter_lines(evallog_path))
        metrics["evallog_source"] = evallog_source or "EvalLog"
    return metrics


def main(argv: Optional[List[str]] = None) -> int:
    parser = argparse.ArgumentParser(description="operator evaluation scorer (research 07 §R1)")
    parser.add_argument("--replay", help="op-replay-v0 JSONL with predictions")
    parser.add_argument("--evallog", help="EvalLog JSONL pulled from the phone")
    parser.add_argument("--out", help="write metrics.json here (default: stdout)")
    args = parser.parse_args(argv)

    if not args.replay and not args.evallog:
        parser.error("at least one of --replay or --evallog is required")

    metrics = build_metrics(replay_path=args.replay, evallog_path=args.evallog)
    text = json.dumps(metrics, indent=2, sort_keys=True)
    if args.out:
        with open(args.out, "w", encoding="utf-8") as handle:
            handle.write(text)
            handle.write("\n")
    else:
        print(text)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
