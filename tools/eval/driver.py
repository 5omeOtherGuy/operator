#!/usr/bin/env python3
"""driver.py — the laptop-side evaluation driver (FOUNDATION §12; ADR-0015; research/07 §R1).

It runs the M1 task tier (T0, five times each) on the owner's phone over **USB adb only**, then pulls
the EvalLog into ``~/operator-eval/<date>-<sha>/`` — never into the repo (OQ-2 default (b)).

Hard rules, straight from the brief and research 07:

* **adb only, USB only, never wireless.**  A serial containing ``:`` is a TCP/IP (wireless) transport
  and is refused before any command is built; ``emulator-*`` is refused too (the harness targets the
  physical phone).  Set ``ADB_MDNS=0`` in the environment to keep mDNS out of the transport list.
* **Every adb call is wrapped in a timeout** (``timeout_s``), so a wedged device cannot hang a run.
* Oracles are host-side ``content query`` / ``dumpsys`` — never ``uiautomator dump`` (it suspends
  accessibility services, research 07 §F2).
* Results stay on the laptop; only numeric ``metrics.json`` (written by ``scorer.py``) may enter the
  repo.

This module never runs anything against a phone on its own: ``--dry-run`` prints the exact plan, and
the unit tests only exercise the pure helpers.  Python 3 stdlib + unittest.
"""

from __future__ import annotations

import argparse
import datetime as _dt
import os
import shutil
import subprocess
import sys
import time
from typing import Any, Callable, Dict, Iterable, List, Optional, Sequence, Tuple

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import suite_v0  # noqa: E402

REPO_ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

#: The operator package under test (dev channel, same applicationId as release, §12).
OPERATOR_PACKAGE = "dev.operator"
EVAL_RECEIVER = "dev.operator.eval.EvalReceiver"

#: The fixture app that serves the localhost pages and posts fixture notifications (C12).
FIXTURE_PACKAGE = "dev.operator.fixture"
FIXTURE_SERVICE = "dev.operator.fixture.FixtureService"

#: EvalLog path relative to the app's data dir.  The dev build is debuggable, so ``run-as`` reads it.
EVALLOG_RELPATH = "files/eval/evallog.jsonl"

#: The fixture page server port (127.0.0.1 only).
FIXTURE_PORT = 8099

#: Per-adb-call timeout (research 07 §R1: "every adb call wrapped in timeout").
DEFAULT_TIMEOUT_S = 30

#: Extras the receiver reads with ``getLongExtra`` (Bundle does not coerce Integer to Long).
LONG_EXTRAS = ("seed",)


# --------------------------------------------------------------------------------------------------
# adb plumbing (pure) — no device is touched here
# --------------------------------------------------------------------------------------------------

def is_wireless_serial(serial: str) -> bool:
    """True for a TCP/IP transport (``host:port``), which the harness must never use."""
    return ":" in serial


def is_emulator_serial(serial: str) -> bool:
    return serial.startswith("emulator-")


def check_serial(serial: str) -> str:
    """Reject wireless and emulator transports before any command is built."""
    if not serial or serial.strip() != serial:
        raise ValueError("empty or whitespace-padded serial %r" % serial)
    if is_wireless_serial(serial):
        raise ValueError(
            "refusing the wireless transport %r: the harness is USB-only (brief; research 07 §R1)" % serial
        )
    if is_emulator_serial(serial):
        raise ValueError("refusing the emulator %r: device runs target the physical phone" % serial)
    return serial


def adb_argv(serial: str, args: Sequence[str]) -> List[str]:
    """``adb -s <serial> <args>``; validates the serial first."""
    return ["adb", "-s", check_serial(serial), *args]


def shell_argv(serial: str, command: str) -> List[str]:
    return adb_argv(serial, ["shell", command])


def parse_adb_devices(output: str) -> List[Dict[str, str]]:
    """Parse ``adb devices -l`` into ``[{"serial", "state", "attrs"}]``; the header is skipped."""
    devices: List[Dict[str, str]] = []
    for line in output.splitlines():
        line = line.strip()
        if not line or line.startswith("List of devices"):
            continue
        parts = line.split()
        if len(parts) < 2:
            continue
        devices.append({"serial": parts[0], "state": parts[1], "attrs": line})
    return devices


def pick_usb_device(devices: Iterable[Dict[str, str]]) -> str:
    """The single authorised USB device; raises when there is none or more than one."""
    usb = [d["serial"] for d in devices
           if d.get("state") == "device" and not is_wireless_serial(d["serial"])
           and not is_emulator_serial(d["serial"])]
    if not usb:
        raise ValueError("no USB device in the `device` state; connect the phone over USB")
    if len(usb) > 1:
        raise ValueError("more than one USB device (%s); pass --serial" % ", ".join(usb))
    return usb[0]


# --------------------------------------------------------------------------------------------------
# the run plan
# --------------------------------------------------------------------------------------------------

def task_ids_for_tiers(tiers: Sequence[str]) -> List[str]:
    ids: List[str] = []
    for tier in tiers:
        ids += [task["id"] for task in suite_v0.tasks_for(tier)]
    return ids


def seed_for(task_id: str, run: int) -> int:
    """A deterministic per-run seed, so a replay of a run is reproducible."""
    digits = "".join(ch for ch in task_id if ch.isdigit()) or "0"
    return int(digits) * 1000 + int(run)


def plan_runs(task_ids: Sequence[str], runs: int = suite_v0.RUNS_PER_TASK,
              *, port: int = FIXTURE_PORT, today: Optional[_dt.date] = None) -> List[Dict[str, Any]]:
    """One entry per (task, run): the rendered goal, its seed and its oracle."""
    plan: List[Dict[str, Any]] = []
    for task_id in task_ids:
        task = suite_v0.TASKS_BY_ID[task_id]
        for run in range(1, runs + 1):
            seed = seed_for(task_id, run)
            nonce = "%s-%d-%d" % (suite_v0.DATA_PREFIX, seed, run)
            plan.append({
                "taskId": task_id,
                "tier": task["tier"],
                "run": run,
                "seed": seed,
                "nonce": nonce,
                "goal": suite_v0.render(task, nonce=nonce, port=port, today=today),
                "oracle": task["oracle"],
                "setup": task.get("setup", []),
                "cleanup": task.get("cleanup", []),
            })
    return plan


def broadcast_argv(serial: str, action: str, extras: Sequence[Tuple[str, Any]]) -> List[str]:
    """``am broadcast`` to EvalReceiver; ``--es``/``--ei``/``--el``/``--ez`` choose the extra type.

    ``seed`` is sent as ``--el`` because the receiver reads it with ``getLongExtra``; ``Bundle`` does
    not coerce an Integer extra to a Long.
    """
    argv = ["am", "broadcast", "-n", "%s/%s" % (OPERATOR_PACKAGE, EVAL_RECEIVER),
            "-a", "dev.operator.eval.%s" % action]
    for key, value in extras:
        if isinstance(value, bool):
            argv += ["--ez", key, "true" if value else "false"]
        elif key in LONG_EXTRAS:
            argv += ["--el", key, str(int(value))]
        elif isinstance(value, int):
            argv += ["--ei", key, str(value)]
        else:
            argv += ["--es", key, str(value)]
    return shell_argv(serial, _join_shell(argv))


def run_task_argv(serial: str, task_id: str, goal: str, seed: int, run: int) -> List[str]:
    return broadcast_argv(serial, "RUN_TASK",
                          [("taskId", task_id), ("goal", goal), ("seed", seed), ("run", run)])


def approve_gate_argv(serial: str, gate_id: str) -> List[str]:
    return broadcast_argv(serial, "APPROVE_GATE", [("gateId", gate_id)])


def kill_argv(serial: str, trigger: str) -> List[str]:
    return broadcast_argv(serial, "KILL", [("trigger", trigger)])


def replay_argv(serial: str, file_name: str) -> List[str]:
    return broadcast_argv(serial, "REPLAY", [("file", file_name)])


def bench_argv(serial: str, model: str, pp: int, tg: int, threads: int) -> List[str]:
    return broadcast_argv(serial, "BENCH",
                          [("model", model), ("pp", pp), ("tg", tg), ("threads", threads)])


def fixture_start_argv(serial: str, port: int = FIXTURE_PORT) -> List[str]:
    return shell_argv(serial, _join_shell([
        "am", "start-foreground-service", "-n", "%s/%s" % (FIXTURE_PACKAGE, FIXTURE_SERVICE),
        "-a", "dev.operator.fixture.START", "--ei", "port", str(port),
    ]))


def fixture_notification_argv(serial: str, title: str, text: str) -> List[str]:
    return shell_argv(serial, _join_shell([
        "am", "start-foreground-service", "-n", "%s/%s" % (FIXTURE_PACKAGE, FIXTURE_SERVICE),
        "-a", "dev.operator.fixture.POST_NOTIFICATION", "--es", "title", title, "--es", "text", text,
    ]))


def evallog_cat_argv(serial: str) -> List[str]:
    """Read EvalLog out of the app's data dir through ``run-as`` (the dev build is debuggable)."""
    return adb_argv(serial, ["exec-out", "run-as", OPERATOR_PACKAGE, "cat", EVALLOG_RELPATH])


def oracle_argv(serial: str, oracle: Dict[str, Any]) -> Optional[List[str]]:
    command = suite_v0.oracle_command(oracle)
    if command is None:
        return None
    return shell_argv(serial, _join_shell(command))


def oracle_ok(oracle: Dict[str, Any], output: str, answer: Optional[str]) -> bool:
    """Apply the oracle's expectations to the host command output / the task's answer."""
    haystack = ((answer or "") if oracle.get("kind") == "answer" else (output or "")).lower()
    expects = [str(e).lower() for e in oracle.get("expects", [])]
    absent = [str(e).lower() for e in oracle.get("expects_absent", [])]
    if any(e not in haystack for e in expects):
        return False
    if any(e in haystack for e in absent):
        return False
    return True


def _join_shell(argv: Sequence[str]) -> str:
    """Quote argv for one ``adb shell`` command string (no local shell is involved)."""
    out = []
    for token in argv:
        if token and all(ch.isalnum() or ch in "._-/:=,@+" for ch in token):
            out.append(token)
        else:
            out.append("'" + token.replace("'", "'\\''") + "'")
    return " ".join(out)


# --------------------------------------------------------------------------------------------------
# output paths (never the repo)
# --------------------------------------------------------------------------------------------------

def default_out_root() -> str:
    return os.path.join(os.path.expanduser("~"), "operator-eval")


def assert_outside_repo(path: str, repo_root: str = REPO_ROOT) -> str:
    """Refuse any destination inside the repo (OQ-2 default (b): logs never enter it)."""
    resolved = os.path.realpath(path)
    root = os.path.realpath(repo_root)
    if resolved == root or resolved.startswith(root + os.sep):
        raise ValueError("refusing to write eval data inside the repo: %s" % resolved)
    return resolved


def run_out_dir(out_root: str, sha: str, date: Optional[_dt.date] = None) -> str:
    day = (date or _dt.date.today()).isoformat()
    short = (sha or "nogit")[:12]
    return assert_outside_repo(os.path.join(out_root, "%s-%s" % (day, short)))


# --------------------------------------------------------------------------------------------------
# execution (only reached without --dry-run)
# --------------------------------------------------------------------------------------------------

def run_adb(argv: Sequence[str], *, timeout_s: int = DEFAULT_TIMEOUT_S) -> subprocess.CompletedProcess:
    env = dict(os.environ)
    env["ADB_MDNS"] = "0"
    return subprocess.run(list(argv), capture_output=True, text=True, timeout=timeout_s, env=env,
                          check=False)


def execute(plan: Sequence[Dict[str, Any]], serial: str, out_dir: str, *,
            apk: Optional[str], fixture_apk: Optional[str], timeout_s: int,
            echo: Callable[[str], None] = print) -> Dict[str, Any]:
    """Install, run the plan, pull EvalLog.  Never touches anything unless called explicitly."""
    os.makedirs(out_dir, exist_ok=True)
    check_serial(serial)

    if apk:
        echo("install %s" % apk)
        run_adb(adb_argv(serial, ["install", "-r", "-t", apk]), timeout_s=max(timeout_s, 300))
    if fixture_apk:
        echo("install fixture %s" % fixture_apk)
        run_adb(adb_argv(serial, ["install", "-r", "-t", fixture_apk]), timeout_s=max(timeout_s, 300))
        # targetSdk 36: POST_NOTIFICATIONS is a runtime permission, granted once over adb.
        run_adb(shell_argv(serial, "pm grant %s android.permission.POST_NOTIFICATIONS" % FIXTURE_PACKAGE),
                timeout_s=timeout_s)

    echo("start fixture page server on 127.0.0.1:%d" % FIXTURE_PORT)
    run_adb(fixture_start_argv(serial), timeout_s=timeout_s)

    results = []
    for entry in plan:
        task_id = entry["taskId"]
        echo("[%s run %d] %s" % (task_id, entry["run"], entry["goal"]))
        for setup in entry.get("setup", []):
            if setup.get("kind") == "fixture_notification":
                run_adb(fixture_notification_argv(serial, setup.get("title", "op-test"),
                                                  setup.get("text", "")), timeout_s=timeout_s)
        run_adb(run_task_argv(serial, task_id, entry["goal"], entry["seed"], entry["run"]),
                timeout_s=timeout_s)
        answer = _poll_until_terminal(serial, task_id, entry["run"], timeout_s, echo)
        argv = oracle_argv(serial, entry["oracle"])
        output = ""
        if argv is not None:
            proc = run_adb(argv, timeout_s=timeout_s)
            output = proc.stdout
        passed = oracle_ok(entry["oracle"], output, answer)
        results.append({"taskId": task_id, "run": entry["run"], "oracle_pass": passed,
                        "answer": answer})
        for cleanup in entry.get("cleanup", []):
            _cleanup(serial, cleanup, timeout_s)
    log_path = os.path.join(out_dir, "evallog.jsonl")
    proc = run_adb(evallog_cat_argv(serial), timeout_s=max(timeout_s, 120))
    with open(log_path, "w", encoding="utf-8") as handle:
        handle.write(proc.stdout)
    echo("EvalLog -> %s" % log_path)
    return {"results": results, "evallog": log_path}


def _poll_until_terminal(serial: str, task_id: str, run: int, timeout_s: int,
                         echo: Callable[[str], None]) -> Optional[str]:
    """Poll EvalLog for the task's terminal line; returns the answer string when present."""
    deadline = time.monotonic() + max(timeout_s, 300)
    while time.monotonic() < deadline:
        proc = run_adb(evallog_cat_argv(serial), timeout_s=timeout_s)
        for line in proc.stdout.splitlines():
            line = line.strip()
            if not line or task_id not in line:
                continue
            try:
                import json
                record = json.loads(line)
            except ValueError:
                continue
            if (record.get("type") == "task" and record.get("taskId") == task_id
                    and record.get("run") == run and record.get("status") in ("done", "aborted", "failed", "refused")):
                return record.get("answer")
        time.sleep(5)
    echo("[%s run %d] timed out waiting for a terminal EvalLog line" % (task_id, run))
    return None


def _cleanup(serial: str, cleanup: Dict[str, Any], timeout_s: int) -> None:
    kind = cleanup.get("kind")
    if kind == "content_delete":
        run_adb(shell_argv(serial, "content delete --uri %s --where \"%s\""
                           % (cleanup["uri"], cleanup.get("where", "1"))), timeout_s=timeout_s)
    elif kind == "global_action":
        run_adb(shell_argv(serial, "input keyevent KEYCODE_HOME"), timeout_s=timeout_s)
    elif kind == "fixture_clear_notifications":
        run_adb(fixture_notification_argv(serial, "", ""), timeout_s=timeout_s)
    elif kind == "settings":
        pass  # nothing to do; the alarm is replaced by the next run


def git_sha(repo_root: str = REPO_ROOT) -> str:
    git = shutil.which("git")
    if not git:
        return "nogit"
    proc = subprocess.run([git, "-C", repo_root, "rev-parse", "--short=12", "HEAD"],
                          capture_output=True, text=True, check=False)
    return proc.stdout.strip() or "nogit"


def main(argv: Optional[List[str]] = None) -> int:
    parser = argparse.ArgumentParser(description="operator eval driver (USB adb only; never wireless)")
    parser.add_argument("--serial", help="USB device serial (auto-detected when exactly one is attached)")
    parser.add_argument("--tiers", default=",".join(suite_v0.M1_TIERS), help="tiers to run (default: T0)")
    parser.add_argument("--tasks", help="explicit comma-separated task ids (overrides --tiers)")
    parser.add_argument("--runs", type=int, default=suite_v0.RUNS_PER_TASK, help="runs per task (5)")
    parser.add_argument("--apk", help="the dev APK to install (from `gh run download`)")
    parser.add_argument("--fixture-apk", help="the fixture APK to install")
    parser.add_argument("--out-root", default=default_out_root(), help="default: ~/operator-eval")
    parser.add_argument("--sha", help="build sha for the directory name (default: git HEAD)")
    parser.add_argument("--timeout", type=int, default=DEFAULT_TIMEOUT_S, help="per-adb-call timeout")
    parser.add_argument("--dry-run", action="store_true", help="print the plan and exit; touch nothing")
    args = parser.parse_args(argv)

    task_ids = ([t for t in args.tasks.split(",") if t] if args.tasks
                else task_ids_for_tiers([t for t in args.tiers.split(",") if t]))
    plan = plan_runs(task_ids, args.runs)

    try:
        if args.serial:
            check_serial(args.serial)
        out_dir = run_out_dir(args.out_root, args.sha or git_sha())
    except ValueError as exc:
        print("error: %s" % exc, file=sys.stderr)
        return 2

    if args.dry_run:
        print("out: %s" % out_dir)
        for entry in plan:
            print("%s run %d seed %d | %s" % (entry["taskId"], entry["run"], entry["seed"], entry["goal"]))
        return 0

    try:
        if not args.serial:
            proc = run_adb(["adb", "devices", "-l"], timeout_s=args.timeout)
            args.serial = pick_usb_device(parse_adb_devices(proc.stdout))
        print("device: %s" % check_serial(args.serial))
    except ValueError as exc:
        print("error: %s" % exc, file=sys.stderr)
        return 2
    execute(plan, args.serial, out_dir, apk=args.apk, fixture_apk=args.fixture_apk,
            timeout_s=args.timeout)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
