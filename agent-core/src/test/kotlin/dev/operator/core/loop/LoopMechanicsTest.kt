package dev.operator.core.loop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The §8.4 budgets and loop detector and the recovery ladder, in isolation. */
class LoopMechanicsTest {

    private val config = LoopConfig()

    // ---- budget (§8.4, C7) ---------------------------------------------------------------------

    @Test
    fun `the owner checkpoint fires after 25 UI steps and adds 10 per approval`() {
        val budget = LoopBudget(config)
        repeat(24) { budget.recordUiStep() }
        assertFalse(budget.needsExtension())
        budget.recordUiStep() // the 25th
        assertTrue("the 26th UI step asks the owner", budget.needsExtension())

        assertTrue(budget.grantExtension())
        assertEquals(35, budget.allowance)
        repeat(10) { budget.recordUiStep() }
        assertTrue("the 36th asks again", budget.needsExtension())
    }

    @Test
    fun `the allowance stops at the hard ceiling of 60`() {
        val budget = LoopBudget(config)
        repeat(25) { budget.recordUiStep() }
        while (budget.grantExtension()) { /* drain the ladder of approvals */ }
        assertEquals(60, budget.allowance)
        repeat(35) { budget.recordUiStep() }
        assertTrue(budget.atCeiling())
        assertFalse(budget.needsExtension())
    }

    @Test
    fun `eight UI steps without progress exhaust a subgoal`() {
        val budget = LoopBudget(config)
        repeat(7) { budget.recordUiStep() }
        assertFalse(budget.subgoalExhausted())
        budget.recordUiStep()
        assertTrue(budget.subgoalExhausted())
        budget.recordProgress()
        assertFalse("an achieved verify resets the subgoal allowance", budget.subgoalExhausted())
    }

    // ---- loop detection (§8.4) -----------------------------------------------------------------

    @Test
    fun `a pair seen twice is banned and a hash seen three times recovers`() {
        val detector = LoopDetector(config)
        val first = detector.observe(7L, "tap:1")
        assertFalse(first.bannedNow)
        assertFalse(first.hashRepeated)

        val second = detector.observe(7L, "tap:1")
        assertTrue("the (state, action) pair seen twice is banned", second.bannedNow)
        assertTrue(detector.isBanned(7L, "tap:1"))
        assertFalse("a different action on the same state is not banned", detector.isBanned(7L, "tap:2"))
        assertEquals(setOf("tap:1"), detector.bannedFor(7L))

        val third = detector.observe(7L, "tap:3")
        assertTrue("a full hash seen three times in the window recovers", third.hashRepeated)
    }

    @Test
    fun `four steps with no achieved verify ask for a replan`() {
        val detector = LoopDetector(config)
        repeat(3) { detector.recordNoProgress() }
        assertFalse(detector.shouldReplan())
        detector.recordNoProgress()
        assertTrue(detector.shouldReplan())
        detector.recordProgress()
        assertFalse(detector.shouldReplan())
    }

    // ---- recovery ladder (§8.4) ----------------------------------------------------------------

    @Test
    fun `the ladder runs in order and then asks the owner`() {
        val ladder = RecoveryLadder(config)
        val context = RecoveryContext(canScroll = true, canGoBack = true, appPackage = "com.example")
        assertEquals(RecoveryRung.RE_OBSERVE, ladder.next(context))
        repeat(config.maxScrollRecoveries) { assertEquals(RecoveryRung.SCROLL, ladder.next(context)) }
        assertEquals(RecoveryRung.BACK, ladder.next(context))
        assertEquals(RecoveryRung.RELAUNCH, ladder.next(context))
        repeat(config.maxReplans) { assertEquals(RecoveryRung.REPLAN, ladder.next(context)) }
        assertEquals(RecoveryRung.ASK_OWNER, ladder.next(context))
        assertEquals("the ladder terminates instead of cycling", RecoveryRung.ABORT, ladder.next(context))
    }

    @Test
    fun `an inapplicable rung is skipped`() {
        val ladder = RecoveryLadder(config)
        val context = RecoveryContext(canScroll = false, canGoBack = false, appPackage = null)
        assertEquals(RecoveryRung.RE_OBSERVE, ladder.next(context))
        assertEquals(RecoveryRung.REPLAN, ladder.next(context)) // no scroll, no back, no app
    }

    // ---- plan grammar (§8.2) -------------------------------------------------------------------

    @Test
    fun `the plan form bounds the subgoal count and length`() {
        val grammar = dev.operator.core.grammar.GbnfMatcher.parse(
            dev.operator.core.grammar.PlanForm.grammar().render(),
        )
        assertTrue(grammar.accepts("""{"subgoals":[{"s":"send the message"}]}"""))
        assertTrue(grammar.accepts("""{"subgoals":[{"s":"a","app":"Messages"},{"s":"b"}]}"""))
        assertFalse(grammar.accepts("""{"subgoals":[]}"""))
        assertFalse(grammar.accepts("""{"subgoals":[${List(7) { """{"s":"x"}""" }.joinToString(",")}]}"""))
    }
}
