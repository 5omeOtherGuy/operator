package dev.operator.core.decide

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * §5.1 `confidence = p_top − mean(p_rest)` and §5.5 temperature scaling, with the worked example of
 * the brief.
 */
class ScoringTest {

    @Test
    fun `confidence matches the worked example`() {
        // §5.1: {A:0.7, B:0.2, C:0.1} → 0.7 − (0.2 + 0.1)/2 = 0.55.
        assertEquals(0.55, Scoring.confidence(doubleArrayOf(0.7, 0.2, 0.1)), 1e-12)
    }

    @Test
    fun `confidence is one for a single option and for a certain top`() {
        assertEquals(1.0, Scoring.confidence(doubleArrayOf(1.0)), 1e-12)
        assertEquals(1.0, Scoring.confidence(doubleArrayOf(1.0, 0.0)), 1e-12)
    }

    @Test
    fun `confidence is zero for a uniform two-way split`() {
        assertEquals(0.0, Scoring.confidence(doubleArrayOf(0.5, 0.5)), 1e-12)
    }

    @Test
    fun `temperature sharpens a logit row`() {
        val logits = floatArrayOf(2f, 1f)
        val plain = Scoring.softmax(logits, temperature = 1.0)
        val sharp = Scoring.softmax(logits, temperature = 0.5)

        assertEquals(0.7310586, plain[0], 1e-6)
        assertEquals(0.8807971, sharp[0], 1e-6)
        assertEquals(0.4621172, Scoring.confidence(plain), 1e-6)
        assertEquals(0.7615942, Scoring.confidence(sharp), 1e-6)
    }

    @Test
    fun `softmax over one logit is a point mass`() {
        assertArrayEquals(doubleArrayOf(1.0), Scoring.softmax(floatArrayOf(7f), 1.0), 1e-12)
    }
}
