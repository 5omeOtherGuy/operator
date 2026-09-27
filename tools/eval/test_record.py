"""Unit tests for the op-replay-v0 recorder (tools/eval/record.py)."""

from __future__ import annotations

import json
import os
import tempfile
import unittest

import record
import replay_v0


def _snapshot():
    nodes = [
        replay_v0.node(0, "n0", 0, "com.android.settings", "txt", "Battery",
                       replay_v0.bounds(0, 0, 100, 20)),
        replay_v0.node(1, "n1", 0, "com.android.settings", "btn", "OK",
                       replay_v0.bounds(520, 1200, 700, 1280), actions=["CLICK"]),
    ]
    return replay_v0.snapshot(4, nodes, foreground_package="com.android.settings")


def _evallog_lines():
    return [
        {"type": "task", "taskId": "T0-01", "run": 1, "status": "done"},
        {"type": "step", "taskId": "T0-01", "run": 1, "step": 0, "t_read_ms": 5,
         "snapshot": _snapshot(),
         "gold": {"action": "click", "element": 1, "point": [600, 1240]}},
        # A step with no snapshot is not recordable.
        {"type": "step", "taskId": "T0-01", "run": 1, "step": 1, "gold": {"action": "click", "element": 0}},
        # A step with a snapshot but no reference action is not recordable.
        {"type": "step", "taskId": "T0-01", "run": 1, "step": 2, "snapshot": _snapshot()},
    ]


class RecordTest(unittest.TestCase):

    def test_record_steps_keeps_only_recordable_lines(self):
        steps = record.record_steps(_evallog_lines())
        self.assertEqual(len(steps), 1)
        self.assertEqual(steps[0]["gold"]["element"], 1)
        self.assertEqual(steps[0]["taskId"], "T0-01")
        self.assertEqual(steps[0]["snapshot"]["nodes"][1]["role"], "btn")

    def test_reference_key_is_accepted(self):
        lines = [{"type": "step", "snapshot": _snapshot(),
                  "reference": {"action": "scroll", "direction": "down"}}]
        steps = record.record_steps(lines)
        self.assertEqual(steps[0]["gold"]["action"], "scroll")

    def test_task_and_goal_overrides(self):
        steps = record.record_steps(_evallog_lines(), task_id="OVERRIDE", goal="do a thing")
        self.assertEqual(steps[0]["taskId"], "OVERRIDE")
        self.assertEqual(steps[0]["goal"], "do a thing")

    def test_record_evallog_writes_meta_then_steps(self):
        with tempfile.TemporaryDirectory() as tmp:
            log = os.path.join(tmp, "evallog.jsonl")
            out = os.path.join(tmp, "op-replay-v0.jsonl")
            with open(log, "w", encoding="utf-8") as handle:
                for line in _evallog_lines():
                    handle.write(json.dumps(line) + "\n")
            count = record.record_evallog(log, out, device="OnePlus 13")
            self.assertEqual(count, 1)
            records = list(replay_v0.iter_records(out))
            self.assertEqual(records[0]["type"], "meta")
            self.assertEqual(records[0]["v"], "op-replay-v0")
            self.assertEqual(records[0]["device"], "OnePlus 13")
            self.assertEqual([r["type"] for r in records[1:]], ["step"])


if __name__ == "__main__":
    unittest.main()
