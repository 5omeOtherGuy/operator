package dev.operator.eval

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * The evaluation receiver (FOUNDATION §12; ADR-0015). It exists in the `dev` source set of `:app`
 * only, so no release or `emulatorStub` build carries it.
 *
 * It is guarded by `android.permission.DUMP`, which the adb shell holds and ordinary apps cannot get:
 * the laptop driver sends `RUN_TASK`, `APPROVE_GATE` (dev only), `KILL`, `REPLAY` and `BENCH` with
 * `am broadcast`. S12 implements the actions, `EvalLog` and `ReplayRunner`.
 */
class EvalReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {
        Log.i(TAG, "eval action ${intent?.action} (S12 implements it)")
    }

    companion object {
        const val ACTION_RUN_TASK = "dev.operator.eval.RUN_TASK"
        const val ACTION_APPROVE_GATE = "dev.operator.eval.APPROVE_GATE"
        const val ACTION_KILL = "dev.operator.eval.KILL"
        const val ACTION_REPLAY = "dev.operator.eval.REPLAY"
        const val ACTION_BENCH = "dev.operator.eval.BENCH"

        private const val TAG = "operator.eval"
    }
}
