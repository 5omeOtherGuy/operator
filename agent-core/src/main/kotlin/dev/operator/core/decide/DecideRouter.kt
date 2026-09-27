package dev.operator.core.decide

import dev.operator.core.api.AbstainReason
import dev.operator.core.api.Answer
import dev.operator.core.api.BackendId
import dev.operator.core.api.Clock
import dev.operator.core.api.DecideBackend
import dev.operator.core.api.DecidePolicy
import dev.operator.core.api.Decider
import dev.operator.core.api.Decision
import dev.operator.core.api.DecisionMeta
import dev.operator.core.api.DecisionState
import dev.operator.core.api.LlmPort
import dev.operator.core.api.ModelRole
import dev.operator.core.api.Question
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

/*
 * S4 decide(): `DecideRouter : Decider`, the seam the loop calls.
 *
 * Design: FOUNDATION §5.1 ("`DecideRouter : Decider` runs, in order: 1. rules; 2. the configured
 * backend; 3. `Calibrator.apply(kind, logits)`; 4. the per-kind threshold τ_kind; 5. `Decided` or
 * `Abstained`. It then writes a `DecisionLogEntry`"), §5.2 (safety asymmetry, see `SafetyAsymmetry`),
 * §5.3 (M1 backends RULES + BONSAI_LOGPROB), §5.5 (calibration and abstention), §4.4
 * (`DecidePolicy.allowBackends` chooses the backend per call), §4.5 (the per-kind deadline and
 * double-ask), ADR-0007 decisions 3-4 and 7, research/03-decide.md §R1 and §R3.
 *
 * The router never throws a backend failure or a timeout: a deadline overrun is `Failed("timeout")`
 * (§4.5) and any other backend failure is `Abstained(BACKEND_ERROR)`. `CancellationException` that is
 * not a timeout (the task was killed, §9.6) propagates unchanged.
 */

/** §4.5: the wall/monotonic clock `:agent-core` runs on when the composition root does not inject one. */
object SystemClock : Clock {
    override fun wallMs(): Long = System.currentTimeMillis()

    override fun monotonicMs(): Long = System.nanoTime() / 1_000_000
}

class DecideRouter(
    private val rules: RulesBackend = RulesBackend(),
    /** The learned backends, in preference order; the first that is allowed and supports the question acts. */
    private val backends: List<DecideBackend> = emptyList(),
    /** §5.5: one calibrator per backend id, from the versioned calibration files. */
    private val calibrators: Map<BackendId, Calibrator> = emptyMap(),
    private val log: DecisionLog = InMemoryDecisionLog(),
    private val clock: Clock = SystemClock,
) : Decider {

    override suspend fun <A : Answer> decide(
        s: DecisionState,
        q: Question<A>,
        p: DecidePolicy,
    ): Decision<A> {
        val start = clock.monotonicMs()
        val keys = Questions.optionKeys(q)
        val contextSha = Hashes.sha256Hex(s.context)
        val labels = Questions.labels(q, keys.size)

        // ---- 1. rules (exact, code-supplied; §5.1 step 1) ----
        if (BackendId.RULES in p.allowBackends) {
            when (val result = rules.evaluate(s, q)) {
                is RuleResult.Answer -> {
                    val index = keys.indexOf(result.option)
                    if (index < 0) {
                        return emit(
                            failed<A>("rule '${result.option}' is not an option of kind ${q.kind.id}", BackendId.RULES, null, start),
                            s, q, contextSha, labels, deadlineMs = p.deadlineMs,
                        )
                    }
                    val probabilities = Scoring.oneHot(keys.size, index)
                    val confidence = Scoring.confidence(probabilities)
                    val answer = answerOf(q, probabilities, confidence)
                    val meta = DecisionMeta(
                        backend = BackendId.RULES,
                        modelId = null,
                        headId = null,
                        calibrationVersion = null,
                        rawLogits = FloatArray(probabilities.size) { probabilities[it].toFloat() },
                        latencyMs = clock.monotonicMs() - start,
                    )
                    val decision = Decision.Decided(answer, meta)
                    return emit(
                        decision, s, q, contextSha, labels,
                        raw = probabilities.toList(), calibrated = probabilities.toList(),
                        confidence = confidence, threshold = 0.0, deadlineMs = p.deadlineMs,
                    )
                }

                is RuleResult.Abstain -> {
                    val meta = DecisionMeta(BackendId.RULES, null, null, null, null, clock.monotonicMs() - start)
                    return emit(
                        Decision.Abstained<A>(null, result.reason, meta),
                        s, q, contextSha, labels, deadlineMs = p.deadlineMs,
                    )
                }

                RuleResult.NoMatch -> Unit
            }
        }

        // ---- 2. the configured backend (§4.4 allowBackends, in preference order) ----
        val chosen = backends.firstOrNull { it.id in p.allowBackends && it.supports(q) }
        if (chosen == null) {
            val supporting = backends.firstOrNull { it.supports(q) }
            val reason = if (supporting != null) AbstainReason.DISALLOWED_BY_POLICY else AbstainReason.BACKEND_UNAVAILABLE
            val reportBackend = supporting?.id ?: backends.firstOrNull()?.id ?: BackendId.RULES
            val identity = (supporting ?: backends.firstOrNull()) as? BackendIdentity
            val meta = DecisionMeta(
                reportBackend, identity?.modelId, identity?.headId, null, null, clock.monotonicMs() - start,
            )
            return emit(
                Decision.Abstained<A>(null, reason, meta),
                s, q, contextSha, labels, deadlineMs = p.deadlineMs,
            )
        }

        val identity = chosen as? BackendIdentity
        val raw: FloatArray
        try {
            raw = withDeadline(p.deadlineMs) {
                if (chosen is DoubleAskBackend) chosen.rawScores(s, q, p.doubleAsk) else chosen.rawScores(s, q)
            }
        } catch (timeout: TimeoutCancellationException) {
            return emit(
                failed<A>("timeout", chosen.id, identity, start),
                s, q, contextSha, labels, deadlineMs = p.deadlineMs,
            )
        } catch (cancelled: CancellationException) {
            // §9.6: the task was killed; propagate, do not turn it into a decision.
            throw cancelled
        } catch (error: Exception) {
            val meta = DecisionMeta(
                chosen.id, identity?.modelId, identity?.headId, null, null, clock.monotonicMs() - start,
            )
            return emit(
                Decision.Abstained<A>(null, AbstainReason.BACKEND_ERROR, meta),
                s, q, contextSha, labels, deadlineMs = p.deadlineMs,
            )
        }

        // ---- 3. calibrate, 4. threshold (τ_kind, overridable per call), 5. decide or abstain ----
        val calibrator = calibrators[chosen.id] ?: TemperatureCalibrator(file = null)
        val applied = calibrator.apply(q.kind, raw)
        val threshold = p.thresholdOverrides[q.kind] ?: applied.threshold
        val confidence = Scoring.confidence(applied.probabilities)
        val meta = DecisionMeta(
            backend = chosen.id,
            modelId = identity?.modelId,
            headId = identity?.headId,
            calibrationVersion = applied.version,
            rawLogits = raw.copyOf(),
            latencyMs = clock.monotonicMs() - start,
        )
        val answer = answerOf(q, applied.probabilities, confidence)
        val decision: Decision<A> = if (confidence >= threshold) {
            Decision.Decided(answer, meta)
        } else {
            val reason = if (applied.usedDefault) AbstainReason.CONSERVATIVE_DEFAULT else AbstainReason.BELOW_THRESHOLD
            Decision.Abstained(answer, reason, meta)
        }
        return emit(
            decision, s, q, contextSha, labels,
            raw = raw.map { it.toDouble() }, calibrated = applied.probabilities.toList(),
            confidence = confidence, threshold = threshold, deadlineMs = p.deadlineMs,
        )
    }

    override suspend fun decideAll(
        s: DecisionState,
        qs: List<Question<*>>,
        p: DecidePolicy,
    ): List<Decision<*>> {
        val out = ArrayList<Decision<*>>(qs.size)
        for (q in qs) out += decideAny(s, q, p)
        return out
    }

    private suspend fun decideAny(s: DecisionState, q: Question<*>, p: DecidePolicy): Decision<*> =
        decide(s, q, p)

    @Suppress("UNCHECKED_CAST")
    private fun <A : Answer> answerOf(q: Question<A>, probabilities: DoubleArray, confidence: Double): A =
        Questions.answer(q, probabilitiesByKey(Questions.optionKeys(q), probabilities), confidence) as A

    private fun <A : Answer> failed(
        reason: String,
        backend: BackendId,
        identity: BackendIdentity?,
        start: Long,
    ): Decision<A> {
        val meta = DecisionMeta(
            backend, identity?.modelId, identity?.headId, null, null, clock.monotonicMs() - start,
        )
        return Decision.Failed(reason, meta)
    }

    /** Append the log line (§5.1, no screen text) and return the decision unchanged. */
    private suspend fun <A : Answer> emit(
        decision: Decision<A>,
        s: DecisionState,
        q: Question<A>,
        contextSha256: String,
        optionLabels: List<String>,
        raw: List<Double> = emptyList(),
        calibrated: List<Double> = emptyList(),
        confidence: Double = 0.0,
        threshold: Double = 0.0,
        deadlineMs: Long,
    ): Decision<A> {
        val meta = decision.meta
        log.append(
            DecisionLogEntry(
                ts = clock.wallMs(),
                kind = q.kind.id,
                backend = meta.backend,
                modelId = meta.modelId,
                headId = meta.headId,
                calibrationVersion = meta.calibrationVersion,
                recipeVersion = identityOf(meta.backend)?.recipeVersion,
                screenHash = s.screenHash,
                contextSha256 = contextSha256,
                optionLabels = optionLabels,
                rawScores = raw,
                calibrated = calibrated,
                confidence = confidence,
                threshold = threshold,
                outcome = when (decision) {
                    is Decision.Decided -> DecisionOutcome.DECIDED
                    is Decision.Abstained -> DecisionOutcome.ABSTAINED
                    is Decision.Failed -> DecisionOutcome.FAILED
                },
                abstainReason = (decision as? Decision.Abstained)?.reason,
                failureReason = (decision as? Decision.Failed)?.reason,
                latencyMs = meta.latencyMs,
                deadlineMs = deadlineMs,
            ),
        )
        return decision
    }

    private fun identityOf(id: BackendId): BackendIdentity? =
        backends.firstOrNull { it.id == id } as? BackendIdentity

    private suspend fun <T> withDeadline(deadlineMs: Long, block: suspend () -> T): T =
        if (deadlineMs > 0) withTimeout(deadlineMs) { block() } else block()

    companion object {
        /**
         * The M1 wiring: RULES + BONSAI_LOGPROB over [llm], with the BONSAI_LOGPROB calibration file
         * resolved for (BONSAI_LOGPROB, [modelSha256], null, [recipeVersion]) (§5.5). The composition
         * root (I1) injects the real [LlmPort], [DecisionLog] and [Clock].
         */
        fun of(
            llm: LlmPort,
            modelSha256: String,
            rules: List<Rule> = emptyList(),
            calibration: CalibrationStore = CalibrationStore(),
            log: DecisionLog = InMemoryDecisionLog(),
            clock: Clock = SystemClock,
            role: ModelRole = ModelRole.PLANNER,
            recipeVersion: String = BONSAI_LOGPROB_RECIPE_VERSION,
        ): DecideRouter {
            val bonsai = BonsaiLogprobBackend(llm, modelSha256, role, recipeVersion)
            return DecideRouter(
                rules = RulesBackend(rules),
                backends = listOf(bonsai),
                calibrators = mapOf(bonsai.id to calibration.calibrator(bonsai.calibrationKey)),
                log = log,
                clock = clock,
            )
        }
    }
}
