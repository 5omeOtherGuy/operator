package dev.operator.core.decide

import dev.operator.core.api.DecisionKind

/*
 * S4 decide(): the M1 decision kinds and which of them are high-stakes.
 *
 * Design: FOUNDATION §5.1 (the kinds are examples, the set is per-use), §5.3 ("high-stakes kinds are
 * averaged over two option orderings to counter position bias"), §4.5 ("high-stakes kinds run twice
 * (two option orders)"), ADR-0007 decision 1 and 7, research/03-decide.md §R3 and §F4.3.
 *
 * `:agent-core` is pure Kotlin/JVM; this package is the only S4 writer.
 */

/**
 * §5.1, ADR-0007: the decision kinds M1 names. A caller may use its own ids; calibration and
 * thresholds are keyed by id, so new ids fall back to the conservative defaults until a file names
 * them (§5.5).
 */
object Kinds {
    /** ADR-0012's `decide(TaskIntent)`; the CAPS kind of §5.1. */
    val TASK_INTENT = DecisionKind("task.intent")

    /** §5.1: API route or UI, after the rules. */
    val ROUTE_API_OR_UI = DecisionKind("route.api_or_ui")

    /** §5.1: L3 element grounding (a Rank over candidate action texts). */
    val UI_TARGET = DecisionKind("ui.target")

    /** §5.1: is the task done. */
    val TASK_DONE = DecisionKind("task.done")

    /** §5.1, §5.4: did the last action reach the expected screen (false, R2 by §5.4/OSF-D3 or unreachable)  */
    val EFFECT_ACHIEVED = DecisionKind("effect.achieved")

    /** §5.1, §5.2: is a UI target irreversible; the only kind whose answer may raise the risk class. */
    val ACTION_IRREVERSIBLE = DecisionKind("action.irreversible")

    /** Every kind this object names. */
    val ALL: Set<DecisionKind> =
        setOf(TASK_INTENT, ROUTE_API_OR_UI, UI_TARGET, TASK_DONE, EFFECT_ACHIEVED, ACTION_IRREVERSIBLE)
}

/**
 * §5.3, §4.5, research/03-decide.md §F4.3: kinds scored twice with the option order swapped and the
 * scores averaged, because a multiple-choice model has an option-position bias. These are the
 * consequential kinds: a wrong answer on them can authorise or end work.
 */
val HIGH_STAKES_KINDS: Set<DecisionKind> =
    setOf(Kinds.ACTION_IRREVERSIBLE, Kinds.EFFECT_ACHIEVED, Kinds.TASK_DONE)
