package dev.operator.core.gate

import dev.operator.core.api.*
import org.junit.Assert.*
import org.junit.Test

class GateTest {
    private val binding = ApprovalBinding("task", 1, ToolCall.SendSms("123", "hello"),
        ScreenSignature("sms", "chat", 42), mapOf(ElementKey(9) to "draft"))

    @Test fun holds() {
        fun hold(ms: Long): GateKeyResult {
            val gate = KeyGate(0)
            assertEquals(GateKeyResult.WAITING, gate.event(Key.DOWN, true, 1000))
            return gate.event(Key.DOWN, false, 1000 + ms)
        }
        assertEquals(GateKeyResult.WAITING, hold(900))
        assertEquals(GateKeyResult.APPROVED, hold(1500))
        assertEquals(GateKeyResult.VOID, hold(2600))
        val gate = KeyGate(0)
        gate.event(Key.DOWN, true, 1000)
        assertEquals(GateKeyResult.VOID, gate.event(Key.UP, true, 1200))
        assertEquals(GateKeyResult.VOID, gate.event(Key.DOWN, false, 2500))
        val dark = KeyGate(0)
        dark.event(Key.DOWN, true, 1000)
        assertEquals(GateKeyResult.FINGERPRINT,
            dark.event(Key.DOWN, false, 2500, screenOn = false))
        val media = KeyGate(0)
        media.event(Key.DOWN, true, 1000, mediaPlaying = true)
        assertEquals(GateKeyResult.FINGERPRINT, media.event(Key.DOWN, false, 2500))
    }

    @Test fun tokensAreBoundAndSingleUse() {
        val tokens = ApprovalTokens()
        fun changed(b: ApprovalBinding) = tokens.mint(binding, ApprovalMethod.VOLUME_HOLD, 100)
            .let { assertFalse(tokens.verifyAndConsume(it, b, 200)) }
        changed(binding.copy(step = 2))
        changed(binding.copy(call = ToolCall.SendSms("456", "hello")))
        changed(binding.copy(screenSignature = binding.screenSignature.copy(structuralHash = 43)))
        changed(binding.copy(editedFieldContents = mapOf(ElementKey(9) to "changed")))
        val token = tokens.mint(binding, ApprovalMethod.VOLUME_HOLD, 100)
        assertFalse(tokens.verifyAndConsume(token.copy(method = ApprovalMethod.BIOMETRIC_STRONG),
            binding, 200))
        assertTrue(tokens.verifyAndConsume(token, binding, 200))
        assertFalse(tokens.verifyAndConsume(token, binding, 200))
        assertFalse(tokens.verifyAndConsume(tokens.mint(binding, ApprovalMethod.VOLUME_HOLD, 100),
            binding, 30_100))
    }

    @Test fun triplePress() {
        val fast = StopKeys()
        assertFalse(fast.down(0)); assertFalse(fast.down(400)); assertTrue(fast.down(800))
        val slow = StopKeys()
        assertFalse(slow.down(0)); assertFalse(slow.down(650)); assertFalse(slow.down(1300))
    }
}
