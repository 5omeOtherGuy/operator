package dev.operator.lifecycle

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Boot and self-heal entry point (FOUNDATION §2.5; ADR-0003).
 *
 * `BOOT_COMPLETED` (after the first unlock, since the Keeper's state and the models live in
 * credential-encrypted storage) and `MY_PACKAGE_REPLACED` both re-run the Keeper: the FGS is started
 * again, the enabled a11y setting is checked, and every action is audited (§9.5). S10 implements it;
 * F0 only declares the receiver.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {
        Log.i(TAG, "received ${intent?.action}")
    }

    private companion object {
        const val TAG = "operator.boot"
    }
}
