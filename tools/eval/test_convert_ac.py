"""Unit tests for the AndroidControl -> op-replay-v0 converter (tools/eval/convert_ac.py).

The real dataset is 49.9 GB of TFRecord; the CI job extracts it to JSONL and this converter is the
deterministic mapping under test.  The fixtures below use the AndroidControl proto field names.
"""

from __future__ import annotations

import json
import os
import tempfile
import unittest

import convert_ac
import replay_v0


def _forest():
    return {
        "trees": [{
            "nodes": [
                {"node_id": 1, "parent_id": None, "class_name": "android.widget.FrameLayout",
                 "bounds_in_screen": {"left": 0, "top": 0, "right": 1080, "bottom": 2400},
                 "package_name": "com.android.settings", "window_id": 42},
                {"node_id": 2, "parent_id": 1, "class_name": "android.widget.TextView",
                 "text": "Battery", "bounds_in_screen": {"left": 0, "top": 100, "right": 400, "bottom": 160},
                 "package_name": "com.android.settings", "window_id": 42, "is_enabled": True},
                {"node_id": 3, "parent_id": 1, "class_name": "android.widget.Button",
                 "text": "OK", "bounds_in_screen": {"left": 520, "top": 1200, "right": 700, "bottom": 1280},
                 "package_name": "com.android.settings", "window_id": 42, "is_clickable": True},
                {"node_id": 4, "parent_id": 3, "class_name": "android.widget.EditText",
                 "content_description": "Search", "bounds_in_screen": {"left": 0, "top": 200, "right": 500, "bottom": 260},
                 "package_name": "com.android.settings", "window_id": 42, "is_editable": True},
            ]
        }]
    }


def _click_record(x=600, y=1240):
    return {
        "episode_id": "AC-0001", "goal": "Turn on Bluetooth",
        "step_instruction": "tap OK",
        "accessibility_tree": _forest(),
        "action": {"action_type": "click", "x": x, "y": y},
    }


class ForestTest(unittest.TestCase):

    def test_forest_maps_roles_labels_and_actions(self):
        snap = convert_ac.forest_to_snapshot(_click_record())
        self.assertEqual(snap["foregroundPackage"], "com.android.settings")
        roles = [node["role"] for node in snap["nodes"]]
        self.assertEqual(roles[0], "txt")
        self.assertEqual(roles[1], "txt")
        self.assertEqual(roles[2], "btn")
        self.assertEqual(roles[3], "edit")
        self.assertIn("CLICK", snap["nodes"][2]["actions"])
        self.assertIn("SET_TEXT", snap["nodes"][3]["actions"])
        self.assertEqual(snap["nodes"][1]["label"], "Battery")
        self.assertEqual(snap["nodes"][3]["label"], "Search")
        self.assertEqual(snap["nodes"][2]["parentIndex"], 0)
        self.assertEqual(snap["nodes"][0]["children"], [1, 2])
        self.assertEqual(snap["nodes"][0]["windowId"], 42)

    def test_bare_node_list_is_accepted(self):
        record = {"accessibility_tree": _forest()["trees"][0]["nodes"], "action": {"action_type": "click", "x": 1, "y": 1}}
        snap = convert_ac.forest_to_snapshot(record)
        self.assertEqual(len(snap["nodes"]), 4)


class GoldTest(unittest.TestCase):

    def test_click_gold_is_the_smallest_clickable_containing_the_point(self):
        snap = convert_ac.forest_to_snapshot(_click_record())
        self.assertEqual(convert_ac.smallest_interactable_containing(snap, (600, 1240)), 2)
        # 1000,1000 hits no clickable node.
        self.assertIsNone(convert_ac.smallest_interactable_containing(snap, (1000, 1000)))

    def test_text_scroll_and_app_actions(self):
        snap = convert_ac.forest_to_snapshot(_click_record())
        self.assertEqual(convert_ac.gold_from_action({"action_type": "input_text", "text": "hi"}, snap),
                         {"action": "input_text", "text": "hi"})
        self.assertEqual(convert_ac.gold_from_action({"action_type": "scroll", "direction": "DOWN"}, snap),
                         {"action": "scroll", "direction": "down"})
        self.assertEqual(convert_ac.gold_from_action({"action_type": "open_app", "app_name": "Settings"}, snap),
                         {"action": "open_app", "app": "Settings"})
        self.assertEqual(convert_ac.gold_from_action({"action_type": "navigate_back"}, snap),
                         {"action": "navigate_back"})

    def test_unknown_action_is_none(self):
        snap = convert_ac.forest_to_snapshot(_click_record())
        self.assertIsNone(convert_ac.gold_from_action({"action_type": "swipe_left"}, snap))


class StreamTest(unittest.TestCase):

    def test_convert_record_produces_a_replay_step(self):
        step = convert_ac.convert_record(_click_record(), 0)
        self.assertEqual(step["type"], "step")
        self.assertEqual(step["taskId"], "AC-0001")
        self.assertEqual(step["gold"]["action"], "click")
        self.assertEqual(step["gold"]["element"], 2)
        self.assertEqual(step["instruction"], "tap OK")

    def test_action_as_json_string(self):
        record = _click_record()
        record["action"] = json.dumps(record["action"])
        step = convert_ac.convert_record(record, 0)
        self.assertEqual(step["gold"]["point"], [600, 1240])

    def test_convert_stream_respects_max_steps(self):
        records = [_click_record() for _ in range(10)]
        out = convert_ac.convert_stream(records, max_steps=3)
        self.assertEqual(len(out), 4)  # meta + 3 steps
        self.assertEqual(out[0]["type"], "meta")

    def test_unconvertible_records_are_skipped(self):
        records = [{"goal": "x", "action": {"action_type": "swipe_left"}}, _click_record()]
        out = convert_ac.convert_stream(records, max_steps=5)
        self.assertEqual(len(out), 2)  # meta + the one convertible step

    def test_convert_file_round_trip(self):
        with tempfile.TemporaryDirectory() as tmp:
            src = os.path.join(tmp, "ac.jsonl")
            out = os.path.join(tmp, "op-replay-v0.jsonl")
            with open(src, "w", encoding="utf-8") as handle:
                for _ in range(4):
                    handle.write(json.dumps(_click_record()) + "\n")
            count = convert_ac.convert_file(src, out, max_steps=2)
            self.assertEqual(count, 2)
            records = list(replay_v0.iter_records(out))
            self.assertEqual(records[0]["source"], "androidcontrol")
            self.assertEqual(len(records) - 1, 2)


if __name__ == "__main__":
    unittest.main()
