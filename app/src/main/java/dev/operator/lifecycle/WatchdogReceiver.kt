package dev.operator.lifecycle

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * The watchdog's alarm receiver (FOUNDATION §2.5, §3.2 A3b; ADR-0003).
 *
 * A Keeper run is scheduled with `setExactAndAllowWhileIdle` every 15 min (`USE_EXACT_ALARM`), which
 * keeps the Keeper on time in Doze and exempts the FGS start from the background-start limit. A
 * force-stop still cancels the alarm. S10 implements the scheduling and the run; F0 declares the
 * receiver, which only our own PendingIntent can reach.
 */
class WatchdogReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {
        Log.i(TAG, "watchdog tick")
    }

    private companion object {
        const val TAG = "operator.watchdog"
    }
}
