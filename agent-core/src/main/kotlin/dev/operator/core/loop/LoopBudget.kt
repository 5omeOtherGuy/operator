package dev.operator.core.loop

/*
 * Step accounting for the loop (§8.4, C7).
 *
 * UI steps are the budget unit. 25 UI steps per task is the owner checkpoint: the loop asks
 * "continue for 10 more?" and each approval adds 10, up to the hard ceiling of 60, which the executor
 * enforces for real (C7). A subgoal may spend 8 UI steps without progress before the loop recovers.
 *
 * Design: §8.4 budgets, C7, ADR-0009 item 5.
 */

/** Per-task step counters; one instance per task run. */
class LoopBudget(private val config: LoopConfig) {

    /** UI steps taken (a step that reached the executor on the UI path). */
    var uiSteps: Int = 0
        private set

    /** Every model step, UI or API. */
    var steps: Int = 0
        private set

    /** UI steps the owner has authorised so far; starts at the 25-step checkpoint. */
    var allowance: Int = config.taskStepCheckpoint
        private set

    /** How often the owner has granted "10 more?". */
    var extensions: Int = 0
        private set

    /** Consecutive UI steps on the current subgoal with no achieved verify. */
    var stepsWithoutProgress: Int = 0
        private set

    fun recordStep() {
        steps++
    }

    /** A UI step that reached the executor; a recover action (scroll, back) counts here too. */
    fun recordUiStep() {
        steps++
        uiSteps++
        stepsWithoutProgress++
    }

    /** A verify reported `achieved`: the subgoal made progress, so its 8-step allowance resets. */
    fun recordProgress() {
        stepsWithoutProgress = 0
    }

    fun subgoalExhausted(): Boolean = stepsWithoutProgress >= config.uiStepsPerSubgoal

    fun atCeiling(): Boolean = uiSteps >= config.hardCeiling

    /** True when the next UI step would cross the owner's current allowance (the 26th, 36th, …). */
    fun needsExtension(): Boolean = uiSteps >= allowance && allowance < config.hardCeiling

    /** §8.4: +10 per approval, never past the ceiling. Returns false when the ceiling is already hit. */
    fun grantExtension(): Boolean {
        if (allowance >= config.hardCeiling) return false
        allowance = minOf(allowance + config.extensionSize, config.hardCeiling)
        extensions++
        return true
    }
}
