package dev.operator.core.gate

/** Hardware events only; callers must exclude accessibility-injected (0x800) events. */
enum class Key { DOWN, UP, OTHER }
enum class GateKeyResult { WAITING, APPROVED, FINGERPRINT, VOID }

class KeyGate(private val appearedAtMs: Long) {
    private var downAt: Long? = null
    private var eligible = true
    private var voided = false

    fun event(key: Key, pressed: Boolean, atMs: Long, screenOn: Boolean = true,
              proximityFar: Boolean = true, mediaPlaying: Boolean = false,
              recentMediaPlay: Boolean = false, injected: Boolean = false): GateKeyResult {
        if (voided) return GateKeyResult.VOID
        if (key == Key.UP && pressed) return void()
        if (injected || !screenOn || !proximityFar || mediaPlaying || recentMediaPlay) eligible = false
        if (key == Key.OTHER && pressed && downAt != null) return void()
        if (key != Key.DOWN) return GateKeyResult.WAITING
        if (pressed) {
            if (downAt != null) return GateKeyResult.WAITING // repeated key-down is not a fresh press
            downAt = atMs
            if (atMs - appearedAtMs < 1_000) eligible = false
            return GateKeyResult.WAITING
        }
        val start = downAt ?: return GateKeyResult.WAITING
        downAt = null
        val duration = atMs - start
        if (duration > 2_500) return void()
        return if (eligible && duration >= 1_000) GateKeyResult.APPROVED else GateKeyResult.FINGERPRINT
    }

    private fun void(): GateKeyResult { voided = true; return GateKeyResult.VOID }
}

/** Sliding-window triple press; timestamps are monotonic. */
class StopKeys {
    private val presses = ArrayDeque<Long>()
    fun down(atMs: Long): Boolean {
        while (presses.isNotEmpty() && atMs - presses.first() > 1_000) presses.removeFirst()
        presses.addLast(atMs)
        if (presses.size < 3) return false
        presses.clear()
        return true
    }
}
