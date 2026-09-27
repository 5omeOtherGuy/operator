package dev.operator.core.loop

import dev.operator.core.api.ExecResult
import dev.operator.core.api.LoopState
import dev.operator.core.api.ToolCall
import dev.operator.core.grammar.TestScreens
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The S6 acceptance tests, driven by scripted fakes over the six ports:
 *  - a three-step UI task reaches DONE;
 *  - a repeated screen bans the repeated action and the loop recovers;
 *  - the 26th UI step asks the owner for "10 more?";
 *  - an inference death pauses, retries once and continues;
 *  - the API route and the gate handshake reach their states.
 */
class AgentLoopTest {

    private fun newLoop(
        llm: FakeLlm,
        decider: FakeDecider,
        executor: FakeExecutor,
        hands: FakeHands,
        config: LoopConfig = LoopConfig(),
        onState: (LoopState) -> Unit = {},
    ): AgentLoop = AgentLoop(
        llm = llm,
        decider = decider,
        executor = executor,
        hands = hands,
        audit = FakeAudit(),
        clock = FakeClock(),
        config = config,
        onState = onState,
    )

    private fun uiDecider(): FakeDecider = FakeDecider().apply {
        intentOption = "use_ui"
        routeOption = "ui"
        achievedOption = "achieved"
    }

    private fun scriptedLlm(vararg actions: String, plan: String = LoopTestSupport.plan("do the thing")): FakeLlm {
        val queue = ArrayDeque(actions.toList())
        return FakeLlm().apply {
            onGenerate = { parts, _ ->
                when {
                    parts.last().contains("(plan)") -> plan
                    parts.last().contains("(answer)") -> "done."
                    else -> queue.removeFirst()
                }
            }
        }
    }

    @Test
    fun `a three-step UI task reaches Done`() = runBlocking {
        val hands = FakeHands()
        var n = 0
        hands.onSnapshot = { n++; TestScreens.messagesScreen(id = 20L + n, fullHash = 200L + n) }
        val llm = scriptedLlm(LoopTestSupport.tap(4), LoopTestSupport.tap(3), LoopTestSupport.done("sent"))
        val decider = uiDecider()
        var doneCalls = 0
        decider.taskDoneOption = { doneCalls++; if (doneCalls >= 3) "done" else "not_yet" }
        val executor = FakeExecutor()

        val states = ArrayList<LoopState>()
        val report = newLoop(llm, decider, executor, hands) { states.add(it) }.run("t1", "tell Anna I am late")

        assertEquals("sent", report.answer)
        assertEquals(3, report.uiSteps)
        assertTrue(states.any { it is LoopState.Intake })
        assertTrue(states.any { it is LoopState.Caps })
        assertTrue(states.any { it is LoopState.Route })
        assertTrue(states.any { it is LoopState.Plan })
        assertTrue(states.any { it is LoopState.Observe })
        assertTrue(states.any { it is LoopState.Propose })
        assertTrue(states.any { it is LoopState.Act })
        assertTrue(states.any { it is LoopState.Settle })
        assertTrue(states.any { it is LoopState.Verify })
        assertTrue(states.any { it is LoopState.Progress })
        assertTrue(states.any { it is LoopState.Done })
        assertFalse("no owner question on the happy path", states.any { it is LoopState.AskOwner })
        assertEquals(0, executor.executed.count { it is ToolCall.AskOwner })
    }

    @Test
    fun `a repeated screen bans the action and recovers`() = runBlocking {
        val hands = FakeHands()
        val fixed = TestScreens.messagesScreen(id = 30, fullHash = 300)
        hands.onSnapshot = { fixed }
        val llm = FakeLlm().apply {
            onGenerate = { parts, _ ->
                if (parts.last().contains("(plan)")) LoopTestSupport.plan("tap the thing") else LoopTestSupport.tap(4)
            }
        }
        val decider = uiDecider()
        val executor = FakeExecutor()

        val states = ArrayList<LoopState>()
        val report = newLoop(llm, decider, executor, hands) { states.add(it) }.run("t2", "tap it")

        assertTrue("the (state, action) pair seen twice is banned", report.bans.contains("tap:4"))
        assertTrue("the loop recovered", report.recoveries >= 1)
        assertTrue(states.any { it is LoopState.Recover })
        assertTrue("recovery ends with an owner question or an abort", report.outcome !is LoopOutcome.Completed)
    }

    @Test
    fun `the twenty-sixth UI step asks the owner for ten more`() = runBlocking {
        val hands = FakeHands()
        var n = 0
        hands.onSnapshot = { n++; TestScreens.messagesScreen(id = 100L + n, fullHash = 1_000L + n) }
        val llm = scriptedLlm(*Array(30) { LoopTestSupport.tap(4) }, plan = LoopTestSupport.plan("keep tapping"))
        val decider = uiDecider()
        val executor = FakeExecutor().apply { ownerAnswers = ArrayDeque(listOf("no")) }

        val states = ArrayList<LoopState>()
        val report = newLoop(llm, decider, executor, hands) { states.add(it) }.run("t3", "keep tapping")

        val asks = states.filterIsInstance<LoopState.AskOwner>()
        assertTrue("the 25-step checkpoint asks the owner", asks.isNotEmpty())
        assertTrue("the question offers 10 more steps", asks.first().question.contains("10 more"))
        assertEquals("the owner is asked before the 26th UI step", 25, executor.executed.count { it is ToolCall.Click })
        assertEquals(25, report.uiSteps)
        assertTrue(report.outcome is LoopOutcome.Aborted)
    }

    @Test
    fun `an inference death pauses, retries once and continues`() = runBlocking {
        val hands = FakeHands()
        var n = 0
        hands.onSnapshot = { n++; TestScreens.messagesScreen(id = 40L + n, fullHash = 400L + n) }
        val llm = scriptedLlm(LoopTestSupport.tap(4), LoopTestSupport.done("sent"))
        llm.failsRemaining = 1 // the plan call dies once
        val decider = uiDecider()
        var doneCalls = 0
        decider.taskDoneOption = { doneCalls++; if (doneCalls >= 2) "done" else "not_yet" }

        val states = ArrayList<LoopState>()
        val report = newLoop(llm, decider, FakeExecutor(), hands) { states.add(it) }.run("t4", "do it")

        assertTrue(states.any { it is LoopState.Paused })
        assertTrue("the retry succeeds and the task completes", report.outcome is LoopOutcome.Completed)
        assertEquals("sent", report.answer)
    }

    @Test
    fun `a persistent inference death notifies the owner`() = runBlocking {
        val hands = FakeHands()
        val llm = FakeLlm().apply { failAlways = true }
        val executor = FakeExecutor().apply { ownerAnswers = ArrayDeque(listOf("no")) }

        val states = ArrayList<LoopState>()
        val report = newLoop(llm, uiDecider(), executor, hands) { states.add(it) }.run("t5", "do it")

        assertTrue(states.any { it is LoopState.Paused })
        assertTrue("after the retry the owner is notified", states.any { it is LoopState.AskOwner })
        assertTrue(report.outcome is LoopOutcome.Aborted)
    }

    @Test
    fun `the API route fills a capability and reaches Done`() = runBlocking {
        val decider = FakeDecider().apply {
            intentOption = "alarm.set"
            routeOption = "api"
            confidence = 0.95
        }
        decider.taskDoneOption = { "done" }
        val llm = FakeLlm().apply {
            onGenerate = { parts, _ ->
                when {
                    parts.last().contains("(api)") -> """{"hour":7,"minute":0,"label":"wake"}"""
                    parts.last().contains("(answer)") -> "Alarm set for 07:00."
                    else -> "{}"
                }
            }
        }
        val executor = FakeExecutor().apply { resultFor = { ExecResult.Done("alarm is set") } }

        val states = ArrayList<LoopState>()
        val report = newLoop(llm, decider, executor, FakeHands()) { states.add(it) }.run("t6", "wake me at 7")

        assertTrue(states.any { it is LoopState.ApiArgs })
        assertTrue(states.any { it is LoopState.ExecApi })
        assertTrue(states.any { it is LoopState.VerifyApi })
        assertTrue(states.any { it is LoopState.Done })
        assertTrue(report.outcome is LoopOutcome.Completed)
        assertEquals("Alarm set for 07:00.", report.answer)
        assertTrue(executor.executed.any { it is ToolCall.SetAlarm })
    }

    @Test
    fun `an R2 call goes through the gate handshake`() = runBlocking {
        val hands = FakeHands()
        var n = 0
        hands.onSnapshot = { n++; TestScreens.messagesScreen(id = 50L + n, fullHash = 500L + n) }
        val llm = scriptedLlm(LoopTestSupport.tap(4), LoopTestSupport.done("sent"))
        val decider = uiDecider()
        var doneCalls = 0
        decider.taskDoneOption = { doneCalls++; if (doneCalls >= 2) "done" else "not_yet" }
        val executor = FakeExecutor().apply {
            pendingOnce = { call -> if (call is ToolCall.Click) "p1" else null }
        }

        val states = ArrayList<LoopState>()
        val report = newLoop(llm, decider, executor, hands) { states.add(it) }.run("t7", "tap it")

        assertTrue(states.any { it is LoopState.Gate })
        assertTrue(states.any { it is LoopState.Confirm })
        assertTrue(report.outcome is LoopOutcome.Completed)
        // The gated click is executed once (NeedsApproval) and again after the owner approves.
        assertEquals(2, executor.executed.count { it is ToolCall.Click })
    }

    @Test
    fun `granting ten more lets the task continue past the checkpoint`() = runBlocking {
        val hands = FakeHands()
        var n = 0
        hands.onSnapshot = { n++; TestScreens.messagesScreen(id = 200L + n, fullHash = 2_000L + n) }
        val llm = scriptedLlm(*Array(60) { LoopTestSupport.tap(4) }, plan = LoopTestSupport.plan("keep tapping"))
        val decider = uiDecider()
        val executor = FakeExecutor().apply { ownerAnswers = ArrayDeque(listOf("yes")) }

        val states = ArrayList<LoopState>()
        val report = newLoop(llm, decider, executor, hands) { states.add(it) }.run("t9", "keep tapping")

        assertTrue("the owner was asked at the checkpoint", states.any { it is LoopState.AskOwner })
        assertTrue("an approval adds 10 steps", report.extensions >= 1)
        assertTrue(report.uiSteps > 25)
        assertTrue("the hard 60-step ceiling stops the loop (§8.4, C7)", report.outcome is LoopOutcome.Aborted)
        assertEquals(60, report.uiSteps)
    }

    @Test
    fun `a task runs on its own SupervisorJob and can be cancelled`() = runBlocking {
        val hands = FakeHands()
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        hands.onSnapshot = {
            started.complete(Unit)
            release.await()
            TestScreens.messagesScreen()
        }
        val loop = newLoop(scriptedLlm(LoopTestSupport.tap(4), LoopTestSupport.done("sent")), uiDecider(), FakeExecutor(), hands)

        val handle = loop.start(this, "t10", "do it")
        started.await()
        assertTrue("the task is running on its own job", handle.job.isActive)
        handle.cancel()
        assertTrue("cancelling the task's SupervisorJob stops it", handle.job.isCancelled)
    }

    @Test
    fun `a poor tree asks the owner`() = runBlocking {
        val hands = FakeHands()
        val oneNode = TestScreens.snapshot(id = 60, nodes = listOf(TestScreens.node(1, dev.operator.core.api.Role.TXT, "hello")))
        hands.onSnapshot = { oneNode }
        val llm = scriptedLlm(plan = LoopTestSupport.plan("do"))
        val executor = FakeExecutor().apply { ownerAnswers = ArrayDeque(listOf("no")) }

        val states = ArrayList<LoopState>()
        val report = newLoop(llm, uiDecider(), executor, hands) { states.add(it) }.run("t8", "do something")

        val ask = states.filterIsInstance<LoopState.AskOwner>().firstOrNull()
        assertTrue("a poor tree stops the UI path and asks the owner", ask != null && ask.question.contains("cannot read"))
        assertTrue(report.outcome is LoopOutcome.Aborted)
    }
}
