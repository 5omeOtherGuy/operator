"""Unit tests for the op-replay-v0 format module (tools/eval/replay_v0.py)."""

from __future__ import annotations

import json
import os
import tempfile
import unittest

import replay_v0


def _snap():
    nodes = [
        replay_v0.node(0, "aaaa", 0, "com.android.settings", "txt", "Battery", replay_v0.bounds(0, 0, 100, 20)),
        replay_v0.node(1, "bbbb", 0, "com.android.settings", "btn", "OK",
                       replay_v0.bounds(520, 1200, 700, 1280), actions=["CLICK"]),
    ]
    return replay_v0.snapshot(7, nodes, foreground_package="com.android.settings",
                              windows=[replay_v0.window(0, "com.android.settings", root_node_index=0)],
                              structural_hash=11, full_hash=22)


class NodeTest(unittest.TestCase):

    def test_node_rejects_unknown_role(self):
        with self.assertRaises(ValueError):
            replay_v0.node(0, "k", 0, "p", "bogus", "x", replay_v0.bounds(0, 0, 1, 1))

    def test_node_rejects_unknown_action(self):
        with self.assertRaises(ValueError):
            replay_v0.node(0, "k", 0, "p", "btn", "x", replay_v0.bounds(0, 0, 1, 1), actions=["BOOM"])

    def test_snapshot_rejects_misnumbered_node(self):
        node = replay_v0.node(3, "k", 0, "p", "txt", "x", replay_v0.bounds(0, 0, 1, 1))
        with self.assertRaises(ValueError):
            replay_v0.snapshot(0, [node])


class StepTest(unittest.TestCase):

    def test_step_rejects_unknown_gold_action(self):
        with self.assertRaises(ValueError):
            replay_v0.step(0, "T", "g", _snap(), {"action": "delete_everything"})

    def test_step_keeps_prediction(self):
        record = replay_v0.step(0, "T0-01", "goal", _snap(),
                                {"action": "click", "element": 1, "point": [600, 1240]},
                                prediction={"raw": "tap 1", "parsed": {"name": "click", "element": 1}})
        self.assertEqual(record["prediction"]["parsed"]["element"], 1)
        self.assertEqual(record["gold"]["action"], "click")


class JsonlRoundTripTest(unittest.TestCase):

    def test_write_then_iter(self):
        records = [
            replay_v0.meta("op-test", recorded_at_ms=5, device="phone"),
            replay_v0.step(0, "T0-01", "goal", _snap(), {"action": "click", "element": 1}),
        ]
        with tempfile.TemporaryDirectory() as tmp:
            path = os.path.join(tmp, "op-replay-v0.jsonl")
            self.assertEqual(replay_v0.write_jsonl(path, records), 2)
            read = list(replay_v0.iter_records(path))
            self.assertEqual([r["type"] for r in read], ["meta", "step"])
            self.assertEqual(read[1]["snapshot"]["nodes"][1]["role"], "btn")
            only_steps = list(replay_v0.iter_records(path, kinds=["step"]))
            self.assertEqual(len(only_steps), 1)

    def test_iter_records_rejects_bad_json(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = os.path.join(tmp, "bad.jsonl")
            with open(path, "w", encoding="utf-8") as handle:
                handle.write("{not json}\n")
            with self.assertRaises(ValueError):
                list(replay_v0.iter_records(path))


class GeometryTest(unittest.TestCase):

    def test_point_in_bbox_is_inclusive(self):
        box = replay_v0.bounds(10, 20, 30, 40)
        self.assertTrue(replay_v0.point_in_bbox((10, 20), (10, 20, 30, 40)))
        self.assertTrue(replay_v0.point_in_bbox((30, 40), (10, 20, 30, 40)))
        self.assertFalse(replay_v0.point_in_bbox((31, 40), (10, 20, 30, 40)))
        self.assertEqual(replay_v0.bbox_of({"bounds": box}), (10, 20, 30, 40))
        self.assertIsNone(replay_v0.bbox_of({"bounds": "nope"}))

    def test_element_bbox_bounds_check(self):
        snap = _snap()
        self.assertEqual(replay_v0.element_bbox(snap, 1), (520, 1200, 700, 1280))
        self.assertIsNone(replay_v0.element_bbox(snap, 99))
        self.assertIsNone(replay_v0.element_bbox(snap, None))

    def test_normalise_text(self):
        self.assertEqual(replay_v0.normalise_text("  Hello   World "), "hello world")
        self.assertEqual(replay_v0.normalise_text(None), "")


if __name__ == "__main__":
    unittest.main()
