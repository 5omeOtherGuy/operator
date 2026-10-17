#!/usr/bin/env python3
"""record.py — the op-replay-v0 recorder (FOUNDATION §12; ADR-0015; research/07 §R1/F3).

op-replay-v0 is recorded on the phone with the fixtures while a **scripted reference policy** drives
the suite (research 07 §R1: "trajectories recorded on the OnePlus by running the suite with scripted
reference actions").  The dev build's ``EvalLog`` writes one ``step`` line per step; in record mode the
line also carries the ``snapshot`` it acted on and the ``gold`` reference action.  This module converts
those lines into an ``op-replay-v0`` set, which then scores step accuracy with ``scorer.py``.

Typical use (laptop, after ``driver.py`` pulled the EvalLog):

    python3 tools/eval/record.py --evallog ~/operator-eval/2026-09-27-<sha>/evallog.jsonl \\
        --out ~/operator-eval/2026-09-27-<sha>/op-replay-v0.jsonl --device "OnePlus 13"

Nothing here touches a phone; ``--evallog`` is a file already on the laptop.  Python 3 stdlib only.
"""

from __future__ import annotations

import argparse
import os
import sys
from typing import Any, Dict, Iterable, Iterator, List, Optional

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import evallog  # noqa: E402
import replay_v0  # noqa: E402


def _gold_of(record: Dict[str, Any]) -> Optional[Dict[str, Any]]:
    """The reference action of a recorded step, from ``gold`` or ``reference``."""
    for key in ("gold", "reference"):
        gold = record.get(key)
        if isinstance(gold, dict) and gold.get("action"):
            return gold
    # A recorded step may carry the reference under "action" when it was executed by the scripted
    # policy rather than by the model; accept it only when it names a gold action.
    action = record.get("action")
    if isinstance(action, dict) and action.get("gold_action"):
        return {"action": action["gold_action"], "element": action.get("element"),
                "point": action.get("point"), "text": action.get("text"),
                "direction": action.get("direction"), "app": action.get("package")}
    return None


def record_steps(records: Iterable[Dict[str, Any]], *, task_id: Optional[str] = None,
                 goal: Optional[str] = None) -> List[Dict[str, Any]]:
    """Turn EvalLog ``step`` lines that carry a ``snapshot`` and a reference action into replay steps."""
    steps: List[Dict[str, Any]] = []
    for record in records:
        if record.get("type") != evallog.STEP:
            continue
        snap = record.get("snapshot")
        if not isinstance(snap, dict):
            continue
        gold = _gold_of(record)
        if gold is None:
            continue
        step_task = task_id or record.get("taskId") or ""
        step_goal = goal or record.get("goal") or ""
        index = len(steps)
        steps.append(replay_v0.step(
            index,
            step_task,
            step_goal,
            snap,
            gold,
            instruction=record.get("instruction", "") or "",
            prediction=record.get("prediction"),
        ))
    return steps


def record_evallog(path: str, out_path: str, *, task_id: Optional[str] = None,
                   goal: Optional[str] = None, source: str = "op-test", device: str = "",
                   app: str = "", recorded_at_ms: int = 0) -> int:
    """Convert one EvalLog file into an op-replay-v0 JSONL set; returns the step count."""
    steps = record_steps(evallog.iter_lines(path), task_id=task_id, goal=goal)
    records: List[Dict[str, Any]] = [replay_v0.meta(source, recorded_at_ms=recorded_at_ms,
                                                   device=device, app=app,
                                                   note="recorded from %s" % os.path.basename(path))]
    records.extend(steps)
    replay_v0.write_jsonl(out_path, records)
    return len(steps)


def main(argv: Optional[List[str]] = None) -> int:
    parser = argparse.ArgumentParser(description="convert EvalLog recordings into op-replay-v0")
    parser.add_argument("--evallog", required=True, help="EvalLog JSONL recorded on the phone")
    parser.add_argument("--out", required=True, help="the op-replay-v0 JSONL to write")
    parser.add_argument("--task-id", help="override the task id for every step")
    parser.add_argument("--goal", help="override the goal for every step")
    parser.add_argument("--device", default="", help="device string for the meta line")
    parser.add_argument("--app", default="", help="operator build string for the meta line")
    parser.add_argument("--source", default="op-test", choices=("op-test", "androidcontrol"))
    parser.add_argument("--recorded-at-ms", type=int, default=0)
    args = parser.parse_args(argv)

    count = record_evallog(args.evallog, args.out, task_id=args.task_id, goal=args.goal,
                           source=args.source, device=args.device, app=args.app,
                           recorded_at_ms=args.recorded_at_ms)
    print("%d steps -> %s" % (count, args.out))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
