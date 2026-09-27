package dev.operator.core.decide

import dev.operator.core.api.Answer
import dev.operator.core.api.Decision
import dev.operator.core.api.DecisionKind
import dev.operator.core.api.RiskClass

/*
 * S4 decide(): the safety asymmetry of §5.2.
 *
 * Design: FOUNDATION §5.2 ("`decide()` may **add** a confirmation, an escalation or a narrowing, and
 * never remove one. The gate for R2/R3 is deterministic and never consults `decide()` to skip itself.
 * `decide(action.irreversible)` can only raise a UI target to R2 [03§R2][05§R2]"), §4.5 ("An Abstained
 * answer on a consequential step raises the class or asks the owner, never lowers it"), §5.3, ADR-0007
 * decision 4 and consequence "no backend switch can weaken the gate", research/03-decide.md §R2.
 *
 * These are pure functions over a [Decision]: the loop applies them to the risk class and the
 * confirmation state it already derived deterministically, and it can only ever move them up.
 */

object SafetyAsymmetry {

    /**
     * §5.2: fold an `action.irreversible` decision into a baseline risk class. The result is never
     * below [baseline]; a `true` answer or an [Decision.Abstained]/[Decision.Failed] (conservative)
     * raises a UI target to R2, and only to R2 — decide can never make a target R3. Other kinds leave
     * the class untouched.
     */
    fun riskClass(baseline: RiskClass, kind: DecisionKind, decision: Decision<*>): RiskClass {
        if (kind != Kinds.ACTION_IRREVERSIBLE) return baseline
        if (baseline.ordinal >= RiskClass.R2.ordinal) return baseline
        return if (irreversible(decision)) RiskClass.R2 else baseline
    }

    /**
     * §5.2: whether a confirmation is required. [baseline] is the deterministic gate's own verdict;
     * decide may add one but never remove one. Only `action.irreversible` can add here: a Yes, an
     * abstention or a failure all require the confirmation a step's deterministic class already asked
     * for.
     */
    fun confirmationsRequired(baseline: Boolean, kind: DecisionKind, decision: Decision<*>): Boolean {
        if (baseline) return true
        if (kind != Kinds.ACTION_IRREVERSIBLE) return false
        return irreversible(decision)
    }

    /**
     * §5.2, §5.3: the conservative reading of a decision for the irreversible question. A `Decided`
     * Yes (P(true) ≥ 0.5) or an unreadable YesNo is irreversible; an abstention or a failure is
     * treated as irreversible, because not knowing must not lower the guard.
     */
    private fun irreversible(decision: Decision<*>): Boolean = when (decision) {
        is Decision.Decided -> {
            val answer = decision.answer
            if (answer is Answer.YesNo) (answer.probabilities["true"] ?: 1.0) >= 0.5 else true
        }
        else -> true
    }
}
