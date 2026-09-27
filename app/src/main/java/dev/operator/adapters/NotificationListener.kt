package dev.operator.adapters

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

/**
 * Notification read and reply (FOUNDATION §3.2 A12, §6.1 rule 1, §7.2 `list_notifications`,
 * `reply_notification`, `notification_action`; ADR-0010).
 *
 * The listener keeps the same match list the executor uses: operator's own rows, their action buttons
 * and any inline reply field are excluded from OSF and refused as targets (§7.4 step 5, S-10), and
 * OTP spans are redacted in every source (§6.1 rule 1b). S9 implements it; F0 declares the service.
 */
class NotificationListener : NotificationListenerService() {

    override fun onListenerConnected() {
        Log.i(TAG, "connected")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        Log.i(TAG, "posted ${sbn?.packageName}")
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        Log.i(TAG, "removed ${sbn?.packageName}")
    }

    private companion object {
        const val TAG = "operator.notifications"
    }
}
