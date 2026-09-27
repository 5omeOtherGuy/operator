package dev.operator.core.decide

import dev.operator.core.api.DecideBackend
import dev.operator.core.api.DecisionState
import dev.operator.core.api.Question

/*
 * S4 decide(): the optional backend capabilities the frozen `DecideBackend` cannot express.
 *
 * The frozen API (F0) fixes `DecideBackend` to `id` + `supports(q)` + `rawScores(s, q)`. Two things
 * S4 needs are not there: the identity a backend reports for `DecisionMeta`/the log (§5.1), and the
 * per-call double-ask flag of `DecidePolicy.doubleAsk` (§4.5). Both are added as optional interfaces
 * so a backend that has them is used fully and one that does not still works.
 */

/** §5.1 `DecisionMeta`, §5.5: the identity a backend reports, so the router can log hashes and pick a calibration. */
interface BackendIdentity {
    /** sha256 of the model (§4.8), null for RULES. */
    val modelId: String?
    val headId: String?

    /** The prompt/rendering recipe version; part of the calibration key (§F6.5). */
    val recipeVersion: String?
}

/**
 * §4.5: a backend that can score one option ordering ([rawScores]) or, when [doubleAsk] is set,
 * average two orderings to counter option-position bias (§5.3, §F4.3). The frozen
 * `DecideBackend.rawScores` cannot carry the flag, so the router prefers this overload when present.
 */
interface DoubleAskBackend : DecideBackend {
    suspend fun rawScores(s: DecisionState, q: Question<*>, doubleAsk: Boolean): FloatArray
}
