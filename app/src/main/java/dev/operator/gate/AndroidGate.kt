package dev.operator.gate

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioManager
import android.os.PowerManager
import android.os.SystemClock
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import dev.operator.core.api.*
import dev.operator.core.gate.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withTimeoutOrNull

/** Constructed with the accessibility service context; S7 forwards physical keys here. */
class AndroidGate(private val context: Context, private val tokens: ApprovalTokens = ApprovalTokens()) :
    GatePort, ApprovalVerifier by tokens {
    override val armed = MutableStateFlow(true)
    private val windows = context.getSystemService(WindowManager::class.java)
    private val power = context.getSystemService(PowerManager::class.java)
    private val audio = context.getSystemService(AudioManager::class.java)
    private val sensors = context.getSystemService(SensorManager::class.java)
    private var pending: CompletableDeferred<GateResult>? = null
    private var cardView: View? = null
    private var keys: KeyGate? = null
    private var currentBinding: ApprovalBinding? = null
    private var currentRisk: RiskClass? = null
    private var proximityFar = true
    private var listener: SensorEventListener? = null
    /** Updated by executor whenever it plays media; independent of AudioManager.isMusicActive. */
    var lastMediaPlayMs: Long = Long.MIN_VALUE

    override suspend fun request(card: GateCard, binding: ApprovalBinding): GateResult {
        check(pending == null) { "only one gate card at a time" }
        if (!armed.value) return GateResult.Cancelled
        val wait = CompletableDeferred<GateResult>()
        pending = wait
        currentBinding = binding
        currentRisk = card.riskClass
        keys = KeyGate(SystemClock.elapsedRealtime())
        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            setPadding(28, 28, 28, 28)
            setAccessibilityDataSensitive(View.ACCESSIBILITY_DATA_SENSITIVE_YES)
        }
        // GateCard is executor-rendered. Never use a model-provided approval label or action.
        (listOf(card.title) + card.arguments + card.fromScreen.map { "From screen: $it" } +
            listOfNotNull(card.taint?.let { "From screen of ${it.sourcePackage}" })).forEach {
            layout.addView(TextView(context).apply { text = it; setTextColor(Color.BLACK) })
        }
        layout.addView(Button(context).apply {
            text = "Reject"
            setAccessibilityDataSensitive(View.ACCESSIBILITY_DATA_SENSITIVE_YES)
            setFilterTouchesWhenObscured(true)
            setOnClickListener { wait.complete(GateResult.Denied("rejected")) }
        })
        cardView = layout
        val sensor = sensors.getDefaultSensor(Sensor.TYPE_PROXIMITY)
        if (sensor != null) {
            proximityFar = false // unknown is never treated as far
            listener = object : SensorEventListener {
                override fun onSensorChanged(event: SensorEvent) {
                    proximityFar = event.values[0] >= sensor.maximumRange
                }
                override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
            }.also { sensors.registerListener(it, sensor, SensorManager.SENSOR_DELAY_NORMAL) }
        } else proximityFar = true
        return try {
            windows.addView(layout, WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                android.graphics.PixelFormat.TRANSLUCENT).apply { gravity = Gravity.BOTTOM })
            withTimeoutOrNull(60_000) { wait.await() } ?: GateResult.Expired(60_000)
        } finally {
            listener?.let(sensors::unregisterListener)
            listener = null
            BiometricGate.pending = null
            cardView?.let(windows::removeView)
            cardView = null
            pending = null
            keys = null
            currentBinding = null
            currentRisk = null
        }
    }

    /** Returns true if consumed. Invoke for every key while the card is visible. */
    fun onKeyEvent(event: KeyEvent): Boolean {
        val wait = pending ?: return false
        val binding = currentBinding ?: return false
        val risk = currentRisk ?: return false
        val key = when (event.keyCode) {
            KeyEvent.KEYCODE_VOLUME_DOWN -> Key.DOWN
            KeyEvent.KEYCODE_VOLUME_UP -> Key.UP
            else -> Key.OTHER
        }
        val result = keys?.event(key, event.action == KeyEvent.ACTION_DOWN, event.eventTime,
            power.isInteractive, proximityFar, audio.isMusicActive,
            SystemClock.elapsedRealtime() - lastMediaPlayMs < 60_000,
            event.flags and 0x800 != 0) ?: return false
        when (result) {
            GateKeyResult.APPROVED -> if (risk == RiskClass.R3) fingerprint(wait, binding)
                else wait.complete(GateResult.Approved(tokens.mint(binding, ApprovalMethod.VOLUME_HOLD,
                    SystemClock.elapsedRealtime())))
            GateKeyResult.FINGERPRINT -> fingerprint(wait, binding)
            GateKeyResult.VOID -> wait.complete(GateResult.Denied("key void"))
            GateKeyResult.WAITING -> Unit
        }
        return key != Key.OTHER || pending != null
    }

    /** Owner-only pending approval fallback, also used when keys are not delivered. */
    fun fingerprint(wait: CompletableDeferred<GateResult>, binding: ApprovalBinding) {
        if (wait.isCompleted) return
        BiometricGate.pending = callback@{ ok ->
            if (wait.isCompleted) return@callback
            if (ok) wait.complete(GateResult.Approved(tokens.mint(binding,
                ApprovalMethod.BIOMETRIC_STRONG, SystemClock.elapsedRealtime())))
            else wait.complete(GateResult.Denied("biometric failed"))
        }
        context.startActivity(Intent(context, GateActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    override suspend fun cancelPending() {
        tokens.voidAll()
        BiometricGate.pending = null
        pending?.complete(GateResult.Cancelled)
    }

    /** Called synchronously by S7 on service unbind/destroy; no suspend/race with executor. */
    fun disarm() {
        armed.value = false
        tokens.voidAll()
        BiometricGate.pending = null
        pending?.complete(GateResult.Cancelled)
    }
}

internal object BiometricGate {
    var pending: ((Boolean) -> Unit)? = null
}
