"""Unit tests for the scorer (tools/eval/scorer.py) on synthetic replay sets and EvalLogs.

These are the §12 DoD 1 checks: the scorer is exercised on hand-built logs whose metrics are known by
construction, so a passing test means the scorer computes the right numbers, not that it agrees with
itself.
"""

from __future__ import annotations

import json
import os
import tempfile
import unittest

import evallog
import replay_v0
import scorer


def _snapshot(snapshot_id: int = 3):
    nodes = [
        replay_v0.node(0, "n0", 0, "com.android.settings", "txt", "Battery",
                       replay_v0.bounds(0, 0, 200, 40)),
        replay_v0.node(1, "n1", 0, "com.android.settings", "btn", "OK",
                       replay_v0.bounds(520, 1200, 700, 1280), actions=["CLICK"]),
        replay_v0.node(2, "n2", 0, "com.android.settings", "edit", "Search",
                       replay_v0.bounds(0, 200, 500, 260),
                       actions=["SET_TEXT", "SCROLL_FORWARD", "SCROLL_BACKWARD"]),
        replay_v0.node(3, "n3", 0, "com.android.settings", "btn", "Cancel",
                       replay_v0.bounds(0, 0, 100, 100), actions=["CLICK"]),
    ]
    return replay_v0.snapshot(snapshot_id, nodes, foreground_package="com.android.settings",
                              windows=[replay_v0.window(0, "com.android.settings", root_node_index=0)])


def _prediction(name, element=None, *, text=None, direction=None, package=None, point=None, raw="?"):
    parsed = {"name": name, "element": element, "text": text, "direction": direction,
              "package": package, "point": point}
    return {"raw": raw, "parsed": parsed}


def _replay_records():
    """Six steps whose per-step outcomes are fixed by construction."""
    snap = _snapshot()
    return [
        replay_v0.meta("op-test"),
        # 1: correct tap on the gold element.
        replay_v0.step(0, "T0-01", "goal", snap, {"action": "click", "element": 1, "point": [600, 1240]},
                       prediction=_prediction("click", 1, point=[600, 1240])),
        # 2: wrong element, no cross-point overlap -> miss, not executable (node 0 has no CLICK).
        replay_v0.step(1, "T0-01", "goal", snap, {"action": "click", "element": 1, "point": [600, 1240]},
                       prediction=_prediction("click", 0)),
        # 3: input_text with different whitespace/case -> hit.
        replay_v0.step(2, "T0-01", "goal", snap, {"action": "input_text", "text": "hello world"},
                       prediction=_prediction("set_text", 2, text="Hello   World")),
        # 4: scroll down -> hit.
        replay_v0.step(3, "T0-02", "goal", snap, {"action": "scroll", "direction": "down"},
                       prediction=_prediction("scroll", 2, direction="down")),
        # 5: open_app -> hit.
        replay_v0.step(4, "T0-02", "goal", snap, {"action": "open_app", "app": "com.android.settings"},
                       prediction=_prediction("launch_app", package="com.android.settings")),
        # 6: the model emitted something unparseable -> miss and the parse-rate denominator grows.
        replay_v0.step(5, "T0-02", "goal", snap, {"action": "click", "element": 1, "point": [600, 1240]},
                       prediction={"raw": "i think maybe click??", "parsed": None}),
        # 7: no prediction at all -> in the accuracy denominator only.
        replay_v0.step(6, "T0-02", "goal", snap, {"action": "click", "element": 1, "point": [600, 1240]}),
    ]


class ReplayScoringTest(unittest.TestCase):

    def test_metrics_on_synthetic_replay(self):
        metrics = scorer.score_replay(_replay_records())
        self.assertEqual(metrics["steps"], 7)
        self.assertEqual(metrics["steps_with_prediction"], 6)
        self.assertAlmostEqual(metrics["raw_parse_rate"], 5 / 6)
        self.assertAlmostEqual(metrics["valid_and_executable_rate"], 4 / 6)
        self.assertAlmostEqual(metrics["step_accuracy"], 4 / 7)
        self.assertAlmostEqual(metrics["per_task_accuracy"]["T0-01"], 2 / 3)
        self.assertAlmostEqual(metrics["per_task_accuracy"]["T0-02"], 2 / 4)

    def test_bbox_rule_matches_crossing_points(self):
        snap = _snapshot()
        gold = {"action": "click", "element": 1, "point": [600, 1240]}
        prediction = {"raw": "tap", "parsed": {"name": "click", "element": 3, "point": [600, 1240]}}
        self.assertTrue(scorer.step_matches(prediction, gold, snap))

    def test_unknown_verb_is_not_valid(self):
        self.assertFalse(scorer.action_is_valid({"name": "send_money"}, _snapshot()))

    def test_set_text_on_password_is_not_executable(self):
        snap = _snapshot()
        snap["nodes"][2]["state"] = ["PASSWORD"]
        parsed = {"name": "set_text", "element": 2, "text": "x"}
        self.assertTrue(scorer.action_is_valid(parsed, snap))
        self.assertFalse(scorer.action_is_executable(parsed, snap))

    def test_missing_element_is_not_executable(self):
        self.assertFalse(scorer.action_is_executable({"name": "click"}, _snapshot()))

    def test_global_action_is_executable_without_a_snapshot(self):
        self.assertTrue(scorer.action_is_executable({"name": "home"}, None))


def _evallog_lines():
    def step(step_no, task_id, run, read, prefill, decode, decide, act, settle, parse_ok=True):
        return {"type": "step", "taskId": task_id, "run": run, "step": step_no, "ts": 1000 + step_no,
                "t_read_ms": read, "t_prefill_ms": prefill, "t_decode_ms": decode,
                "t_decide_ms": decide, "t_act_ms": act, "t_settle_ms": settle,
                "n_prompt": 512, "n_gen": 32, "parse_ok": parse_ok, "raw": "tap 1",
                "action": {"name": "click", "element": 1}, "valid": True, "executable": True,
                "gate": {"events": []}, "vm_hwm_kb": 100000, "headroom": 0.9,
                "charge_counter_uah": 3000000, "current_now_ua": -500000}

    return [
        {"type": "task", "taskId": "T0-01", "run": 1, "seed": 1001, "status": "done", "answer": "ok"},
        {"type": "task", "taskId": "T0-01", "run": 2, "seed": 1002, "status": "aborted"},
        {"type": "task", "taskId": "T0-02", "run": 1, "seed": 2001, "status": "done"},
        step(0, "T0-01", 1, 10, 100, 200, 5, 30, 40),
        step(1, "T0-01", 1, 20, 110, 210, 6, 31, 41),
        step(2, "T0-01", 1, 30, 120, 220, 7, 32, 42, parse_ok=False),
        step(3, "T0-02", 1, 40, 130, 230, 8, 33, 43),
        step(4, "T0-02", 1, 50, 140, 240, 9, 34, 44),
    ]


class EvalLogScoringTest(unittest.TestCase):

    def test_latency_p50_p95(self):
        metrics = scorer.score_evallog(_evallog_lines())
        read = metrics["latency_ms"]["t_read_ms"]
        self.assertEqual(read["n"], 5)
        self.assertEqual(read["p50"], 30)   # sorted 10,20,30,40,50
        self.assertAlmostEqual(read["p95"], 48)  # (n-1)*0.95 = 3.8 -> 40 + 0.8*(50-40)
        self.assertEqual(metrics["latency_ms"]["t_prefill_ms"]["p50"], 120)
        self.assertEqual(metrics["steps"], 5)
        self.assertAlmostEqual(metrics["raw_parse_rate"], 4 / 5)
        self.assertAlmostEqual(metrics["valid_and_executable_rate"], 5 / 5)
        self.assertAlmostEqual(metrics["tasks"]["T0-01"]["success_rate"], 0.5)
        self.assertAlmostEqual(metrics["tasks"]["T0-02"]["success_rate"], 1.0)

    def test_end_to_end_is_the_component_sum(self):
        metrics = scorer.score_evallog(_evallog_lines())
        composite = metrics["latency_ms"][evallog.END_TO_END_FIELD]
        self.assertEqual(composite["n"], 5)
        self.assertEqual(composite["p50"], 451)  # sorted sums: 385, 418, 451, 484, 517

    def test_percentile_edge_cases(self):
        self.assertIsNone(scorer.percentile([], 50))
        self.assertEqual(scorer.percentile([7], 95), 7)
        self.assertEqual(scorer.summarise([])["n"], 0)


class BuildMetricsTest(unittest.TestCase):

    def test_build_metrics_writes_numbers_only(self):
        with tempfile.TemporaryDirectory() as tmp:
            replay_path = os.path.join(tmp, "op-replay-v0.jsonl")
            log_path = os.path.join(tmp, "evallog.jsonl")
            replay_v0.write_jsonl(replay_path, _replay_records())
            with open(log_path, "w", encoding="utf-8") as handle:
                for line in _evallog_lines():
                    handle.write(json.dumps(line) + "\n")

            metrics = scorer.build_metrics(replay_path=replay_path, evallog_path=log_path)
            self.assertEqual(metrics["schema"], "operator-metrics-v0")
            self.assertIsNotNone(metrics["replay"]["step_accuracy"])
            self.assertIsNotNone(metrics["run"]["latency_ms"])
            # The document must be JSON-serialisable and carry no screen text.
            blob = json.dumps(metrics)
            self.assertNotIn("Battery", blob)
            self.assertIn("valid_and_executable_rate", metrics["replay"])


if __name__ == "__main__":
    unittest.main()
