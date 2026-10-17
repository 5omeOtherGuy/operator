package dev.operator.fixture

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log

/**
 * The fixture foreground service (FOUNDATION §12; ADR-0015; research 07 §R2).
 *
 * It owns the localhost page server and the fixture notifications. The driver drives it with
 * `am start-foreground-service`:
 *
 * ```
 * am start-foreground-service -n dev.operator.fixture/.FixtureService -a dev.operator.fixture.START --ei port 8099
 * am start-foreground-service -n dev.operator.fixture/.FixtureService -a dev.operator.fixture.POST_NOTIFICATION \
 *     --es title "op-test" --es text "op-test parcel 4711"
 * am start-foreground-service -n dev.operator.fixture/.FixtureService -a dev.operator.fixture.CLEAR_NOTIFICATIONS
 * ```
 *
 * The service is exported but requires `android.permission.DUMP`, so only the adb shell (or a
 * signature holder) can drive it. All fixture data uses the `op-test` prefix (OQ-1).
 */
class FixtureService : Service() {

    private var pageServer: PageServer? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "op-test fixture", NotificationManager.IMPORTANCE_DEFAULT)
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, foregroundNotification())
        when (intent?.action) {
            ACTION_START -> startServer(intent.getIntExtra(EXTRA_PORT, Pages.DEFAULT_PORT))
            ACTION_POST_NOTIFICATION -> postFixtureNotification(
                intent.getStringExtra(EXTRA_TITLE).orEmpty(),
                intent.getStringExtra(EXTRA_TEXT).orEmpty(),
            )
            ACTION_CLEAR_NOTIFICATIONS -> getSystemService(NotificationManager::class.java)
                .cancelAll()
            ACTION_STOP -> {
                pageServer?.stop()
                pageServer = null
                stopSelf()
            }
            else -> Log.i(TAG, "ignored action ${intent?.action}")
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        pageServer?.stop()
        pageServer = null
        super.onDestroy()
    }

    private fun startServer(port: Int) {
        val server = pageServer
        if (server != null) {
            Log.i(TAG, "page server already running")
            return
        }
        pageServer = PageServer(port).also { it.start() }
    }

    private fun postFixtureNotification(title: String, text: String) {
        if (title.isEmpty() && text.isEmpty()) {
            getSystemService(NotificationManager::class.java).cancelAll()
            return
        }
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setAutoCancel(false)
            .addAction(Notification.Action.Builder(null, "Thanks!", contentIntent()).build())
            .build()
        getSystemService(NotificationManager::class.java).notify(FIXTURE_NOTIFICATION_ID, notification)
    }

    private fun contentIntent(): PendingIntent = PendingIntent.getActivity(
        this, 0, Intent(this, FixtureActivity::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun foregroundNotification(): Notification = Notification.Builder(this, CHANNEL_ID)
        .setSmallIcon(android.R.drawable.ic_dialog_info)
        .setContentTitle("op-test fixture")
        .setContentText("localhost page server and fixture notifications")
        .setContentIntent(contentIntent())
        .build()

    companion object {
        const val ACTION_START = "dev.operator.fixture.START"
        const val ACTION_POST_NOTIFICATION = "dev.operator.fixture.POST_NOTIFICATION"
        const val ACTION_CLEAR_NOTIFICATIONS = "dev.operator.fixture.CLEAR_NOTIFICATIONS"
        const val ACTION_STOP = "dev.operator.fixture.STOP"

        const val EXTRA_PORT = "port"
        const val EXTRA_TITLE = "title"
        const val EXTRA_TEXT = "text"

        /** T0-10's "op-test parcel 4711" and the S-02/S-11 injection rows reuse this id. */
        const val FIXTURE_NOTIFICATION_ID = 4711
        const val NOTIFICATION_ID = 4712
        const val CHANNEL_ID = "op-test"

        private const val TAG = "operator.fixture"
    }
}
