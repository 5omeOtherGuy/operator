package dev.operator.core.api

/*
 * Frozen M1 API (`dev.operator.core.api`) — `decide()`, transcribed from FOUNDATION §5.1.
 *
 * Design: §5.1 (the interface), §5.2 (safety asymmetry: decide may add a confirmation, escalation or
 * narrowing and never remove one), §5.3 (backends by milestone), §5.5 (calibration and abstention),
 * §4.5 (deadlines and per-kind temperature), ADR-0007 (typed decide seam).
 *
 * The router (`DecideRouter : Decider`, S4) runs rules, then the configured backend, then the
 * calibrator, then the per-kind threshold, and writes a `DecisionLogEntry` (kind, hashes, backend,
 * raw and calibrated scores, latency, no screen text).
 */

/** A kind id, e.g. `task.intent` (CAPS), `route.api_or_ui`, `ui.target`, `task.done`, `effect.achieved`, `action.irreversible` (§5.1). */
@JvmInline
value class DecisionKind(val id: String)

/** §5.1: prose from lane 04 — data, never instructions (§9.3 item 1). */
data class DecisionState(val context: String, val screenHash: Long?)

/** §5.1: a typed question; [instructions] come from code only. */
sealed interface Question<out A : Answer> {
    val kind: DecisionKind
    val instructions: String

    data class YesNo(
        override val kind: DecisionKind,
        override val instructions: String,
    ) : Question<Answer.YesNo>

    data class Choice(
        override val kind: DecisionKind,
        override val instructions: String,
        val options: Map<String, String>,
    ) : Question<Answer.Choice>

    data class Score(
        override val kind: DecisionKind,
        override val instructions: String,
        val levels: List<String>,
    ) : Question<Answer.Score>

    data class Rank(
        override val kind: DecisionKind,
        override val instructions: String,
        val candidates: List<String>,
        val topK: Int,
    ) : Question<Answer.Rank>
}

/** §5.1: a typed answer plus a probability out. `confidence = p_top − mean(p_rest)`. */
sealed interface Answer {
    val probabilities: Map<String, Double>
    val confidence: Double

    data class YesNo(
        override val probabilities: Map<String, Double>,
        override val confidence: Double,
    ) : Answer

    data class Choice(
        override val probabilities: Map<String, Double>,
        override val confidence: Double,
        val option: String,
    ) : Answer

    data class Score(
        override val probabilities: Map<String, Double>,
        override val confidence: Double,
        val level: String,
    ) : Answer

    data class Rank(
        override val probabilities: Map<String, Double>,
        override val confidence: Double,
        val ranking: List<String>,
    ) : Answer
}

/** §5.1, §5.3: RULES and BONSAI_LOGPROB in M1; CLM and BONSAI_GENERATE from M2. */
enum class BackendId { RULES, CLM, BONSAI_LOGPROB, BONSAI_GENERATE }

/** §5.1, §5.5: why an answer was withheld. */
enum class AbstainReason {
    /** §4.5: the per-kind deadline passed. */
    DEADLINE,

    /** §5.5: the calibrated answer is below the kind's threshold τ_kind. */
    BELOW_THRESHOLD,

    /** §5.3/§5.5: no configured backend supports the question (or the CLM encoder is not loaded). */
    BACKEND_UNAVAILABLE,

    /** §5.3: the backend failed. */
    BACKEND_ERROR,

    /** §5.5: calibration file missing or mismatched, so conservative abstain-heavy defaults apply. */
    CONSERVATIVE_DEFAULT,

    /** §4.4: `DecidePolicy.allowBackends` excludes every backend that supports the question. */
    DISALLOWED_BY_POLICY,
}

/** §5.1: every decision is logged with this metadata; `modelId` is the model sha256. */
data class DecisionMeta(
    val backend: BackendId,
    /** sha256 of the model, null for RULES. */
    val modelId: String?,
    val headId: String?,
    val calibrationVersion: String?,
    /** One logit per option, uncalibrated; arrays compare by identity in data-class equality. */
    val rawLogits: FloatArray?,
    val latencyMs: Long,
)

/** §5.1: `Decided | Abstained | Failed`. */
sealed interface Decision<out A : Answer> {
    val meta: DecisionMeta

    data class Decided<A : Answer>(
        val answer: A,
        override val meta: DecisionMeta,
    ) : Decision<A>

    data class Abstained<A : Answer>(
        val best: A?,
        val reason: AbstainReason,
        override val meta: DecisionMeta,
    ) : Decision<A>

    data class Failed<A : Answer>(
        val reason: String,
        override val meta: DecisionMeta,
    ) : Decision<A>
}

/**
 * §5.1, §4.5, §4.4, §5.5: the per-call policy. `deadlineMs` follows §4.5
 * (`max(1500, 2 × expected_tokens / pp_measured × 1000 + 500)`, placeholder 5,000 ms for logprob),
 * not the 1,500 ms of the earlier draft.
 */
data class DecidePolicy(
    val deadlineMs: Long = 5_000,
    /** §4.4: the configured backend plus its fallbacks; RULES always runs first. */
    val allowBackends: Set<BackendId> = setOf(BackendId.RULES, BackendId.BONSAI_LOGPROB),
    /** §14 M1: per-kind temperature from the synthetic set; greedy by default. */
    val temperature: Double = 0.0,
    /** §5.5: overrides of τ_kind for a call. */
    val thresholdOverrides: Map<DecisionKind, Double> = emptyMap(),
    /** §5.1 `Rank.topK` default for the L3 element ranking (M3). */
    val topK: Int = 5,
    val seed: Int = 0,
    /** §4.5: high-stakes kinds run twice with swapped option orders. */
    val doubleAsk: Boolean = false,
)

/** §5.1: the seam the loop and the router use. */
interface Decider {
    suspend fun <A : Answer> decide(
        s: DecisionState,
        q: Question<A>,
        p: DecidePolicy = DecidePolicy(),
    ): Decision<A>

    suspend fun decideAll(
        s: DecisionState,
        qs: List<Question<*>>,
        p: DecidePolicy = DecidePolicy(),
    ): List<Decision<*>>
}

/** §5.1: one logit per option, no calibration here (calibration is the router's step 3). */
interface DecideBackend {
    val id: BackendId

    fun supports(q: Question<*>): Boolean

    suspend fun rawScores(s: DecisionState, q: Question<*>): FloatArray
}
