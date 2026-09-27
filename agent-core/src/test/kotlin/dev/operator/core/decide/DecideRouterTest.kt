package dev.operator.core.decide

import dev.operator.core.api.AbstainReason
import dev.operator.core.api.BackendId
import dev.operator.core.api.Clock
import dev.operator.core.api.DecidePolicy
import dev.operator.core.api.Decision
import dev.operator.core.api.DecisionKind
import dev.operator.core.api.DecisionState
import dev.operator.core.api.LlmPort
import dev.operator.core.api.Question
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §5.1: `DecideRouter` runs rules → backend → calibration → τ_kind → Decided/Abstained and logs a
 * `DecisionLogEntry`. §4.5: a deadline overrun is `Failed("timeout")`. §5.3: high-stakes kinds are
 * averaged over two option orderings. §5.5: a missing calibration file abstains conservatively.
 */
class DecideRouterTest {

    private val state = DecisionState(context = "Some screen.", screenHash = 7L)
    private val modelSha = "sha256-model"

    private fun routingQuestion(kind: DecisionKind = Kinds.ROUTE_API_OR_UI) =
        Question.Choice(kind, "Route the step.", linkedMapOf("api" to "Use the API", "ui" to "Drive the UI"))

    private fun calibrationFile(version: String, vararg kinds: Pair<DecisionKind, KindCalibration>) =
        CalibrationFile(
            key = CalibrationKey(BackendId.BONSAI_LOGPROB, modelSha, null, BONSAI_LOGPROB_RECIPE_VERSION),
            version = version,
            kinds = kinds.associate { (kind, calibration) -> kind.id to calibration },
        )

    private fun router(
        llm: LlmPort,
        calibration: List<CalibrationFile> = emptyList(),
        rules: List<Rule> = emptyList(),
        log: DecisionLog = InMemoryDecisionLog(),
        clock: Clock = FakeClock(),
    ): DecideRouter {
        val backend = BonsaiLogprobBackend(llm, modelId = modelSha)
        return DecideRouter(
            rules = RulesBackend(rules),
            backends = listOf(backend),
            calibrators = mapOf(backend.id to CalibrationStore(calibration).calibrator(backend.calibrationKey)),
            log = log,
            clock = clock,
        )
    }

    @Test
    fun `calibration changes the decision at a threshold`() = runBlocking {
        // logits [2,1]: raw confidence 0.4622; at temperature 0.5 it rises to 0.7616.
        val llm = FakeLlmPort(rowsFor = { _, _, _ -> List(2) { floatArrayOf(2f, 1f) } })

        val raw = router(llm, listOf(calibrationFile("raw", Kinds.ROUTE_API_OR_UI to KindCalibration(1.0, 0.5))))
        val sharp = router(llm, listOf(calibrationFile("sharp", Kinds.ROUTE_API_OR_UI to KindCalibration(0.5, 0.5))))

        val low = raw.decide(state, routingQuestion())
        val high = sharp.decide(state, routingQuestion())

        assertTrue("0.4622 < τ 0.5 must abstain", low is Decision.Abstained)
        assertEquals(AbstainReason.BELOW_THRESHOLD, (low as Decision.Abstained).reason)
        assertEquals(0.4621, low.best!!.confidence, 1e-3)

        assertTrue("0.7616 ≥ τ 0.5 must decide", high is Decision.Decided)
        val decided = high as Decision.Decided
        assertEquals(0.7616, decided.answer.confidence, 1e-3)
        assertEquals("api", decided.answer.option)
    }

    @Test
    fun `a deadline overrun yields Failed timeout`() = runBlocking {
        val llm = FakeLlmPort(
            rowsFor = { _, _, _ -> List(2) { floatArrayOf(2f, 1f) } },
            labelDelayMs = 5_000,
        )
        val log = InMemoryDecisionLog()
        val decide = router(llm, listOf(calibrationFile("v", Kinds.ROUTE_API_OR_UI to KindCalibration(1.0, 0.5))), log = log)

        val decision = decide.decide(state, routingQuestion(), DecidePolicy(deadlineMs = 50))

        assertTrue("a slow backend must fail on the deadline", decision is Decision.Failed)
        assertEquals("timeout", (decision as Decision.Failed).reason)
        assertEquals(DecisionOutcome.FAILED, log.last()!!.outcome)
        assertEquals("timeout", log.last()!!.failureReason)
    }

    @Test
    fun `a high-stakes kind is averaged over two option orderings`() = runBlocking {
        val llm = FakeLlmPort(
            rowsFor = { _, _, call ->
                if (call == 0) List(2) { floatArrayOf(3f, 1f) } else List(2) { floatArrayOf(0f, 3f) }
            },
        )
        val log = InMemoryDecisionLog()
        val decide = router(
            llm,
            listOf(calibrationFile("v", Kinds.ACTION_IRREVERSIBLE to KindCalibration(1.0, 0.5))),
            log = log,
        )
        val question = Question.Choice(
            Kinds.ACTION_IRREVERSIBLE,
            "Is this step irreversible?",
            linkedMapOf("delete" to "Delete for everyone", "cancel" to "Cancel"),
        )

        val decision = decide.decide(state, question)

        // Both option orderings are scored and averaged: [3, 1] and (un-reversed) [3, 0] → [3, 0.5].
        assertArrayEquals(floatArrayOf(3f, 0.5f), decision.meta.rawLogits!!, 1e-6f)
        assertEquals(2, llm.labelCalls.size)
        assertEquals(listOf(3.0, 0.5), log.last()!!.rawScores)
        assertTrue(decision is Decision.Decided)
    }

    @Test
    fun `the decision log carries hashes and scores but no screen text`() = runBlocking {
        val context = "SECRET_SCREEN_TEXT_XYZZY"
        val optionText = "SCREEN_OPTION_TEXT_QWERTY"
        val llm = FakeLlmPort(rowsFor = { _, _, _ -> List(2) { floatArrayOf(2f, 1f) } })
        val log = InMemoryDecisionLog()
        val decide = router(
            llm,
            listOf(calibrationFile("v", Kinds.ROUTE_API_OR_UI to KindCalibration(1.0, 0.5))),
            log = log,
        )
        val question = Question.Choice(
            Kinds.ROUTE_API_OR_UI,
            "Route the step.",
            linkedMapOf("api" to optionText, "ui" to "Use the UI"),
        )

        decide.decide(DecisionState(context, screenHash = 42L), question)
        val entry = log.last()!!

        assertFalse("the log must not carry the context", entry.toJsonLine().contains(context))
        assertFalse("the log must not carry an option description", entry.toJsonLine().contains(optionText))
        assertFalse(entry.toString().contains(context))
        assertFalse(entry.toString().contains(optionText))
        assertEquals(Hashes.sha256Hex(context), entry.contextSha256)
        assertEquals(42L, entry.screenHash)
        assertEquals(listOf("A", "B"), entry.optionLabels)
        assertEquals(listOf(2.0, 1.0), entry.rawScores)
        assertTrue(entry.toJsonLine().contains("\"contextSha256\""))
        assertTrue(entry.toJsonLine().contains("\"rawScores\":[2.0,1.0]"))
    }

    @Test
    fun `rules run first and skip the learned backend`() = runBlocking {
        val llm = FakeLlmPort(rowsFor = { _, _, _ -> error("the backend must not be reached when a rule answers") })
        val rule = Rule { _, q ->
            if (q.kind == Kinds.TASK_DONE) RuleResult.Answer("false") else RuleResult.NoMatch
        }
        val decide = router(llm, rules = listOf(rule))

        val decision = decide.decide(state, Question.YesNo(Kinds.TASK_DONE, "Is the task done?"))

        assertTrue(decision is Decision.Decided)
        val decided = decision as Decision.Decided
        assertEquals(BackendId.RULES, decided.meta.backend)
        assertEquals(0, llm.labelCalls.size)
        assertEquals(1.0, decided.answer.confidence, 1e-12)
    }

    @Test
    fun `a missing calibration file abstains conservatively`() = runBlocking {
        val llm = FakeLlmPort(rowsFor = { _, _, _ -> List(2) { floatArrayOf(2f, 1f) } })
        val decide = router(llm)

        val decision = decide.decide(state, routingQuestion())

        assertTrue(decision is Decision.Abstained)
        assertEquals(AbstainReason.CONSERVATIVE_DEFAULT, (decision as Decision.Abstained).reason)
        assertEquals(null, decision.meta.calibrationVersion)
    }

    @Test
    fun `a policy threshold override is honoured`() = runBlocking {
        val llm = FakeLlmPort(rowsFor = { _, _, _ -> List(2) { floatArrayOf(2f, 1f) } })
        val decide = router(llm, listOf(calibrationFile("v", Kinds.ROUTE_API_OR_UI to KindCalibration(1.0, 0.9))))
        val question = routingQuestion()

        val conservative = decide.decide(state, question)
        val overridden = decide.decide(
            state,
            question,
            DecidePolicy(thresholdOverrides = mapOf(Kinds.ROUTE_API_OR_UI to 0.3)),
        )

        assertTrue(conservative is Decision.Abstained)
        assertTrue(overridden is Decision.Decided)
    }

    @Test
    fun `a disallowed backend is never called`() = runBlocking {
        val llm = FakeLlmPort(rowsFor = { _, _, _ -> error("a disallowed backend must not run") })
        val decide = router(llm)

        val decision = decide.decide(state, routingQuestion(), DecidePolicy(allowBackends = setOf(BackendId.RULES)))

        assertEquals(0, llm.labelCalls.size)
        assertTrue(decision is Decision.Abstained)
        assertEquals(AbstainReason.DISALLOWED_BY_POLICY, (decision as Decision.Abstained).reason)
    }

    @Test
    fun `a backend failure abstains with BACKEND_ERROR`() = runBlocking {
        val llm = FakeLlmPort(rowsFor = { _, _, _ -> throw IllegalStateException("native died") })
        val decide = router(llm, listOf(calibrationFile("v", Kinds.ROUTE_API_OR_UI to KindCalibration(1.0, 0.5))))

        val decision = decide.decide(state, routingQuestion())

        assertTrue(decision is Decision.Abstained)
        assertEquals(AbstainReason.BACKEND_ERROR, (decision as Decision.Abstained).reason)
    }

    @Test
    fun `decideAll returns one logged decision per question`() = runBlocking {
        val llm = FakeLlmPort(rowsFor = { _, _, _ -> List(2) { floatArrayOf(2f, 1f) } })
        val log = InMemoryDecisionLog()
        val decide = router(
            llm,
            listOf(calibrationFile("v", Kinds.ROUTE_API_OR_UI to KindCalibration(1.0, 0.5))),
            log = log,
        )

        val decisions = decide.decideAll(state, listOf(routingQuestion(), routingQuestion()))

        assertEquals(2, decisions.size)
        assertEquals(2, log.entries().size)
        assertEquals(listOf("route.api_or_ui", "route.api_or_ui"), log.entries().map { it.kind })
    }
}
