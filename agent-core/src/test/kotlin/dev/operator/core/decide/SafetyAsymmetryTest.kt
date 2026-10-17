package dev.operator.core.decide

import dev.operator.core.api.AbstainReason
import dev.operator.core.api.Answer
import dev.operator.core.api.BackendId
import dev.operator.core.api.Decision
import dev.operator.core.api.DecisionMeta
import dev.operator.core.api.RiskClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §5.2: decide may add a confirmation or raise a UI target to R2, and never remove or lower anything.
 */
class SafetyAsymmetryTest {

    private val meta = DecisionMeta(BackendId.BONSAI_LOGPROB, "sha256-model", null, "v", null, 3L)

    private fun yes(pTrue: Double): Decision<Answer.YesNo> =
        Decision.Decided(Answer.YesNo(mapOf("true" to pTrue, "false" to 1 - pTrue), confidence = 1.0), meta)

    private fun abstained(): Decision<Answer.YesNo> =
        Decision.Abstained(null, AbstainReason.BELOW_THRESHOLD, meta)

    private fun failed(): Decision<Answer.YesNo> = Decision.Failed("timeout", meta)

    @Test
    fun `an irreversible yes raises a UI target to R2`() {
        assertEquals(RiskClass.R2, SafetyAsymmetry.riskClass(RiskClass.R0, Kinds.ACTION_IRREVERSIBLE, yes(1.0)))
        assertEquals(RiskClass.R2, SafetyAsymmetry.riskClass(RiskClass.R1, Kinds.ACTION_IRREVERSIBLE, yes(0.9)))
    }

    @Test
    fun `a no answer never lowers the deterministic class`() {
        assertEquals(RiskClass.R1, SafetyAsymmetry.riskClass(RiskClass.R1, Kinds.ACTION_IRREVERSIBLE, yes(0.0)))
        assertEquals(RiskClass.R2, SafetyAsymmetry.riskClass(RiskClass.R2, Kinds.ACTION_IRREVERSIBLE, yes(0.0)))
        assertEquals(RiskClass.R3, SafetyAsymmetry.riskClass(RiskClass.R3, Kinds.ACTION_IRREVERSIBLE, yes(0.0)))
    }

    @Test
    fun `other kinds never change the risk class`() {
        assertEquals(RiskClass.R1, SafetyAsymmetry.riskClass(RiskClass.R1, Kinds.TASK_DONE, yes(1.0)))
    }

    @Test
    fun `an abstention or a failure on the irreversible question is conservative`() {
        assertEquals(RiskClass.R2, SafetyAsymmetry.riskClass(RiskClass.R0, Kinds.ACTION_IRREVERSIBLE, abstained()))
        assertEquals(RiskClass.R2, SafetyAsymmetry.riskClass(RiskClass.R0, Kinds.ACTION_IRREVERSIBLE, failed()))
    }

    @Test
    fun `confirmations may be added but never removed`() {
        assertTrue(SafetyAsymmetry.confirmationsRequired(false, Kinds.ACTION_IRREVERSIBLE, yes(1.0)))
        assertTrue("a baseline confirmation must survive a No", SafetyAsymmetry.confirmationsRequired(true, Kinds.ACTION_IRREVERSIBLE, yes(0.0)))
        assertTrue(SafetyAsymmetry.confirmationsRequired(false, Kinds.ACTION_IRREVERSIBLE, abstained()))
        assertFalse(SafetyAsymmetry.confirmationsRequired(false, Kinds.ACTION_IRREVERSIBLE, yes(0.0)))
        assertFalse(SafetyAsymmetry.confirmationsRequired(false, Kinds.TASK_DONE, yes(1.0)))
    }
}
