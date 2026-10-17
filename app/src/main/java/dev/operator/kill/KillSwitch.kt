package dev.operator.kill

import android.os.Process
import dev.operator.core.api.*
import dev.operator.core.gate.StopKeys
import dev.operator.gate.AndroidGate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * Single main-process stop entry for K-a (key filter), K-b (notification), K-c (tile),
 * K-d (status chip). K-e is OS-owned: ScreenService calls [disarm] on unbind/destroy.
 */
class KillSwitch(
    private val scope: CoroutineScope,
    private val gate: AndroidGate,
    private val llm: LlmPort,
    private val audit: AuditPort,
    private val unbindLlm: () -> Unit,
    private val taskJob: () -> Job?,
    private val activeRequestId: () -> Long?,
    /** PID cached at successful bind time, never fetched through a wedged AIDL call. */
    private val boundPid: () -> Int?,
    private val llmIdle: () -> Boolean,
) {
    val halted = MutableStateFlow(false)
    private val presses = StopKeys()

    /** Called on physical volume-down key-down (ignore repeat events). */
    fun volumeDown(atMs: Long) {
        if (presses.down(atMs)) stop("volume-down triple press")
    }

    fun stop(source: String) {
        if (halted.value) return
        halted.value = true
        taskJob()?.cancel()
        scope.launch {
            gate.cancelPending()
            val reqId = activeRequestId()
            if (reqId != null) launch { runCatching { llm.abort(reqId) } }
            // The timer runs independently of abort(), which can itself hang.
            delay(2_000)
            if (!llmIdle()) {
                boundPid()?.takeIf { it > 0 && it != Process.myPid() }?.let(Process::killProcess)
                unbindLlm()
            }
            audit.append(AuditEvent(System.currentTimeMillis(), AuditType.KILL, null, null, null,
                emptyMap(), null, AuditDecision.CANCELLED, source, null, null, "stop"))
        }
    }

    /** Synchronously prevents effects; OS itself disables the service for the shortcut. */
    fun disarm() {
        gate.disarm()
        stop("disarm")
    }
}
