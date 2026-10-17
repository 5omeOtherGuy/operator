package dev.operator.eval

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Process
import android.util.Log
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import java.io.File

/**
 * The evaluation receiver (FOUNDATION §12; ADR-0015).
 *
 * It lives in the `dev` source set of `:app` only, so no `prod` or `emulatorStub` build carries it (the
 * `checks` CI job asserts that on the merged manifests). Actions, sent by the laptop driver
 * (`tools/eval/driver.py`) with `am broadcast`:
 *
 * * `RUN_TASK <taskId> <goal> <seed> <run>` — start a task by id;
 * * `APPROVE_GATE <gateId>` — automation-only approval, `dev` only;
 * * `KILL <trigger>` — a §9.6 kill trigger;
 * * `REPLAY <file>` — replay an `op-replay-v0` file; performs no actions;
 * * `BENCH <model> <pp> <tg> <threads>` — `:llm` bench.
 *
 * **It is exported, so it is guarded twice.** The manifest requires `android.permission.DUMP`
 * (`signature|privileged|development`, which the adb shell holds and an ordinary app cannot obtain),
 * and [isTrustedCaller] re-checks the Binder calling uid: only the shell (uid 2000), root (uid 0) or
 * our own uid may drive the harness. Everything else is dropped and logged.
 *
 * The behaviour behind each action is an [EvalHooks] seam owned by S1/S6/S8; until I1 wires them, an
 * unset hook records a refusal rather than pretending the task ran.
 */
class EvalReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) return
        val uid = Binder.getCallingUid()
        if (!isTrustedCaller(uid)) {
            Log.w(TAG, "eval broadcast ${intent.action} from untrusted uid $uid: dropped")
            return
        }
        val app = context.applicationContext
        val log = EvalLog(app)
        when (intent.action) {
            ACTION_RUN_TASK -> onRunTask(log, intent)
            ACTION_APPROVE_GATE -> onApproveGate(log, intent)
            ACTION_KILL -> onKill(log, intent)
            ACTION_REPLAY -> onReplay(log, intent)
            ACTION_BENCH -> onBench(log, intent)
            else -> Log.w(TAG, "unknown eval action ${intent.action}")
        }
    }

    private fun onRunTask(log: EvalLog, intent: Intent) {
        val taskId = intent.getStringExtra(EXTRA_TASK_ID)
        if (taskId.isNullOrBlank()) {
            Log.w(TAG, "RUN_TASK without $EXTRA_TASK_ID")
            return
        }
        val goal = intent.getStringExtra(EXTRA_GOAL).orEmpty()
        val seed = intent.getLongExtra(EXTRA_SEED, 0L)
        val run = intent.getIntExtra(EXTRA_RUN, 1)
        log.appendTask(taskId, run, seed, goal, "started")
        val runner = EvalHooks.taskRunner
        if (runner == null) {
            log.appendTask(taskId, run, seed, goal, "refused", reason = "no task runner wired (S6/I1)")
            return
        }
        val started = runCatching { runner.start(taskId, goal, seed, run) }
            .getOrElse { failure ->
                log.appendTask(taskId, run, seed, goal, "failed", reason = failure.message ?: "runner threw")
                return
            }
        if (!started) {
            log.appendTask(taskId, run, seed, goal, "refused", reason = "the task runner declined $taskId")
        }
    }

    private fun onApproveGate(log: EvalLog, intent: Intent) {
        val gateId = intent.getStringExtra(EXTRA_GATE_ID) ?: return
        val approver = EvalHooks.gateApprover
        val approved = approver != null && runCatching { approver.approve(gateId) }.getOrDefault(false)
        log.appendGate(taskId = intent.getStringExtra(EXTRA_TASK_ID).orEmpty(), gateId = gateId,
            decision = if (approved) "approved" else "refused",
            method = if (approved) METHOD_AUTOMATION else null)
    }

    private fun onKill(log: EvalLog, intent: Intent) {
        val trigger = intent.getStringExtra(EXTRA_TRIGGER).orEmpty()
        val killer = EvalHooks.killer
        val killed = killer != null && runCatching { killer.kill(trigger) }.getOrDefault(false)
        log.appendEvent("kill", JSONObject().put("trigger", trigger).put("fired", killed))
    }

    private fun onReplay(log: EvalLog, intent: Intent) {
        val name = intent.getStringExtra(EXTRA_FILE)
        if (name.isNullOrBlank()) {
            Log.w(TAG, "REPLAY without $EXTRA_FILE")
            return
        }
        val file = File(log.replayDir, name)
        if (!file.isFile) {
            log.appendEvent("replay", JSONObject().put("file", name).put("error", "not found"))
            return
        }
        // A replay is CPU work over a recorded file; keep it off the main thread.
        Thread {
            val summary: ReplayRunner.Summary = runBlocking { ReplayRunner(log).run(file) }
            Log.i(TAG, "replay ${summary.file}: ${summary.steps} steps, ${summary.violations} violations")
        }.start()
    }

    private fun onBench(log: EvalLog, intent: Intent) {
        val model = intent.getStringExtra(EXTRA_MODEL).orEmpty()
        val pp = intent.getIntExtra(EXTRA_PP, 512)
        val tg = intent.getIntExtra(EXTRA_TG, 128)
        val threads = intent.getIntExtra(EXTRA_THREADS, 4)
        val bench = EvalHooks.bencher
        val ran = bench != null && runCatching { bench.bench(model, pp, tg, threads) }.getOrDefault(false)
        log.appendEvent("bench", JSONObject().put("model", model).put("pp", pp).put("tg", tg)
            .put("threads", threads).put("ran", ran))
    }

    companion object {
        const val ACTION_RUN_TASK = "dev.operator.eval.RUN_TASK"
        const val ACTION_APPROVE_GATE = "dev.operator.eval.APPROVE_GATE"
        const val ACTION_KILL = "dev.operator.eval.KILL"
        const val ACTION_REPLAY = "dev.operator.eval.REPLAY"
        const val ACTION_BENCH = "dev.operator.eval.BENCH"

        const val EXTRA_TASK_ID = "taskId"
        const val EXTRA_GOAL = "goal"
        const val EXTRA_SEED = "seed"
        const val EXTRA_RUN = "run"
        const val EXTRA_GATE_ID = "gateId"
        const val EXTRA_TRIGGER = "trigger"
        const val EXTRA_FILE = "file"
        const val EXTRA_MODEL = "model"
        const val EXTRA_PP = "pp"
        const val EXTRA_TG = "tg"
        const val EXTRA_THREADS = "threads"

        /** The adb shell uid (§F2: `adb shell` is uid 2000) and root. */
        const val SHELL_UID = 2000
        const val ROOT_UID = 0

        const val METHOD_AUTOMATION = "automation"

        private const val TAG = "operator.eval"

        /**
         * Only the shell, root or our own process may drive the harness. The manifest's DUMP guard is
         * the first line; this is the second, so a future manifest mistake does not open the harness.
         */
        fun isTrustedCaller(uid: Int): Boolean = uid == ROOT_UID || uid == SHELL_UID || uid == Process.myUid()
    }
}
