package dev.operator.lifecycle

import android.app.Service
import android.content.Intent
import android.os.IBinder

/**
 * The agent's foreground service (FOUNDATION §2.5, §3.2 A3; ADR-0003).
 *
 * Declared in the manifest with `foregroundServiceType="specialUse"` and the
 * `android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE` property. It has no timeout, is started from boot,
 * and returns `START_STICKY` so the system restarts it after a kill (not after a force-stop).
 * S10 starts the Keeper here and owns the notification channel and the status pill.
 */
class AgentService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY
}
