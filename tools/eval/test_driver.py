"""Unit tests for the laptop driver's pure helpers (tools/eval/driver.py).

The driver itself is never run here: the tests build commands and plans and check the safety rules
(USB only, never wireless; results never inside the repo).
"""

from __future__ import annotations

import datetime as _dt
import os
import unittest

import driver
import suite_v0


class SerialGuardTest(unittest.TestCase):

    def test_wireless_serial_is_rejected(self):
        with self.assertRaises(ValueError):
            driver.check_serial("192.168.1.44:5555")

    def test_emulator_serial_is_rejected(self):
        with self.assertRaises(ValueError):
            driver.check_serial("emulator-5554")

    def test_usb_serial_is_accepted(self):
        self.assertEqual(driver.check_serial("3B1F4A2C"), "3B1F4A2C")

    def test_adb_argv_validates_first(self):
        with self.assertRaises(ValueError):
            driver.adb_argv("10.0.0.2:5555", ["shell", "id"])

    def test_parse_and_pick_devices(self):
        listing = (
            "List of devices attached\n"
            "3B1F4A2C\tdevice usb:1-2 product:oneplus model:CPH1234\n"
            "192.168.1.44:5555\tdevice product:oneplus\n"
            "emulator-5554\tdevice\n"
        )
        devices = driver.parse_adb_devices(listing)
        self.assertEqual([d["serial"] for d in devices], ["3B1F4A2C", "192.168.1.44:5555", "emulator-5554"])
        self.assertEqual(driver.pick_usb_device(devices), "3B1F4A2C")

    def test_pick_devices_rejects_two_usb_devices(self):
        devices = driver.parse_adb_devices("List of devices attached\nAAAA\tdevice\nBBBB\tdevice\n")
        with self.assertRaises(ValueError):
            driver.pick_usb_device(devices)

    def test_pick_devices_rejects_none(self):
        with self.assertRaises(ValueError):
            driver.pick_usb_device(driver.parse_adb_devices("List of devices attached\n"))


class PlanTest(unittest.TestCase):

    def test_t0_runs_five_times_in_order(self):
        ids = driver.task_ids_for_tiers(["T0"])
        self.assertEqual(ids, ["T0-0%d" % n for n in range(1, 10)] + ["T0-10"])
        plan = driver.plan_runs(ids, 5, today=_dt.date(2026, 9, 27))
        self.assertEqual(len(plan), 50)
        self.assertEqual(plan[0]["taskId"], "T0-01")
        self.assertEqual(plan[0]["run"], 1)
        self.assertEqual(plan[4]["run"], 5)
        self.assertEqual(plan[5]["taskId"], "T0-02")
        # Seeds are distinct across runs of one task.
        t01_seeds = {entry["seed"] for entry in plan if entry["taskId"] == "T0-01"}
        self.assertEqual(len(t01_seeds), 5)

    def test_goal_template_is_rendered(self):
        plan = driver.plan_runs(["T0-03"], 1, today=_dt.date(2026, 9, 27))
        self.assertIn("2026-09-28", plan[0]["goal"])

    def test_nonce_is_per_run(self):
        plan = driver.plan_runs(["T0-04"], 2)
        self.assertIn("op-test", plan[0]["goal"])
        self.assertNotEqual(plan[0]["nonce"], plan[1]["nonce"])

    def test_suite_has_the_v0_tiers(self):
        for tier in suite_v0.TIERS:
            self.assertTrue(suite_v0.tasks_for(tier), "tier %s is empty" % tier)
        self.assertEqual(len(suite_v0.tasks_for("T0")), 10)


class CommandTest(unittest.TestCase):

    def test_run_task_broadcast_targets_the_receiver(self):
        argv = driver.run_task_argv("3B1F4A2C", "T0-01", "Set an alarm for 06:45", 1001, 1)
        self.assertEqual(argv[:2], ["adb", "-s"])
        joined = " ".join(argv)
        self.assertIn("dev.operator/dev.operator.eval.EvalReceiver", joined)
        self.assertIn("dev.operator.eval.RUN_TASK", joined)
        self.assertIn("--es taskId T0-01", joined)
        self.assertIn("--el seed 1001", joined)
        self.assertIn("--ei run 1", joined)

    def test_goal_with_spaces_is_quoted(self):
        argv = driver.run_task_argv("3B1F4A2C", "T0-02", "Start a 3-minute timer", 2001, 1)
        self.assertIn("'Start a 3-minute timer'", " ".join(argv))

    def test_evallog_uses_run_as(self):
        argv = driver.evallog_cat_argv("3B1F4A2C")
        self.assertEqual(argv, ["adb", "-s", "3B1F4A2C", "exec-out", "run-as", "dev.operator",
                                "cat", "files/eval/evallog.jsonl"])

    def test_oracle_command_for_known_kinds(self):
        argv = driver.oracle_argv("3B1F4A2C", {"kind": "settings", "target": "global/bluetooth_on"})
        self.assertIn("settings get global bluetooth_on", " ".join(argv))
        self.assertIsNone(driver.oracle_argv("3B1F4A2C", {"kind": "answer"}))

    def test_oracle_ok(self):
        self.assertTrue(driver.oracle_ok({"kind": "content", "expects": ["op-test"]}, "op-test standup", None))
        self.assertFalse(driver.oracle_ok({"kind": "content", "expects": ["op-test"]}, "nothing", None))
        self.assertTrue(driver.oracle_ok({"kind": "answer", "expects": ["4711"]}, "", "the parcel is 4711"))
        self.assertFalse(driver.oracle_ok({"kind": "shell", "expects_absent": ["dev.operator.fixture"]},
                                          "package:dev.operator.fixture", None))


class OutputPathTest(unittest.TestCase):

    def test_repo_paths_are_refused(self):
        inside = os.path.join(driver.REPO_ROOT, "eval", "results")
        with self.assertRaises(ValueError):
            driver.assert_outside_repo(inside)
        with self.assertRaises(ValueError):
            driver.assert_outside_repo(driver.REPO_ROOT)

    def test_run_out_dir_is_named_date_sha(self):
        path = driver.run_out_dir(os.path.join(os.path.expanduser("~"), "operator-eval"),
                                  "abcdef123456", _dt.date(2026, 9, 27))
        self.assertTrue(path.endswith(os.path.join("operator-eval", "2026-09-27-abcdef123456")))
        self.assertTrue(os.path.isabs(path))

    def test_default_out_root_is_home(self):
        self.assertEqual(driver.default_out_root(), os.path.join(os.path.expanduser("~"), "operator-eval"))


if __name__ == "__main__":
    unittest.main()
