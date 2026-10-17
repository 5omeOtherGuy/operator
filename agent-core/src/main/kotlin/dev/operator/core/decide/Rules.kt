package dev.operator.core.decide

import dev.operator.core.api.AbstainReason
import dev.operator.core.api.DecisionState
import dev.operator.core.api.Question

/*
 * S4 decide(): the deterministic RULES backend (router step 1).
 *
 * Design: FOUNDATION §5.1 ("`DecideRouter` runs, in order: 1. rules; 2. the configured backend; ..."),
 * §5.2 (rules first, instructions only from code), §5.3 (RULES is one of the two M1 backends),
 * research/03-decide.md §R4 and the options table O1 ("Exact, instant answers for structural
 * questions: package allowlists, node flags, known dialogs, irreversible-action classes"), ADR-0007.
 *
 * A rule is code, not model output: it answers from [DecisionState] prose and the typed question, and
 * may also decline (forced abstention) when the deterministic policy says the step must go to the
 * owner. Rules run before the learned backend and their answer is authoritative, so they bypass
 * temperature scaling and τ_kind: an exact, code-supplied answer has no calibration to apply.
 *
 * `RulesBackend` is deliberately not a [dev.operator.core.api.DecideBackend]: the frozen
 * `supports(q)` carries no [DecisionState] and rules are state-dependent, so the router consults the
 * rules with the state in hand (see `DecideRouter`).
 */

/** §5.1 step 1: what a [Rule] did with a question. */
sealed interface RuleResult {
    /** A deterministic answer for [option], one of [optionKeys] of the question. */
    data class Answer(val option: String) : RuleResult

    /** Code policy says the router must abstain with this reason (never a model answer). */
    data class Abstain(val reason: AbstainReason, val detail: String? = null) : RuleResult

    /** This rule does not apply; the next rule, then the learned backend, is consulted. */
    data object NoMatch : RuleResult
}

/** §5.1 step 1: one deterministic decision rule. */
fun interface Rule {
    fun evaluate(s: DecisionState, q: Question<*>): RuleResult
}

/**
 * §5.1 step 1, §5.3: the ordered rule list. The first rule that does not return [RuleResult.NoMatch]
 * decides; if none matches, the router falls through to the configured learned backend.
 */
class RulesBackend(rules: List<Rule> = emptyList()) {
    val rules: List<Rule> = rules.toList()

    /** True when some rule would decide or decline this question. */
    fun matches(s: DecisionState, q: Question<*>): Boolean = evaluate(s, q) !is RuleResult.NoMatch

    /** The first matching rule's result, or [RuleResult.NoMatch]. */
    fun evaluate(s: DecisionState, q: Question<*>): RuleResult {
        for (rule in rules) {
            val result = rule.evaluate(s, q)
            if (result !is RuleResult.NoMatch) return result
        }
        return RuleResult.NoMatch
    }
}
