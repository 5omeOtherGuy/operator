package dev.operator.core.loop

import dev.operator.core.api.PauseReason

/*
 * The loop's final report (S6).
 *
 * One terminal outcome plus the counters the owner status pill and the audit log read (§11, §9.5):
 * step counts, the bans the detector recorded, the history lines and the exit reason.
 *
 * Design: §8.1 (terminal states), §8.4, §9.5, §11.
 */

/** A terminal loop outcome: the three terminal states of §8.1. */
sealed interface LoopOutcome {
    data class Completed(val answer: String) : LoopOutcome

    data class Aborted(val reason: String) : LoopOutcome

    data class Paused(val reason: PauseReason) : LoopOutcome
}

/** Everything the composition root needs after a task ends. */
data class TaskReport(
    val taskId: String,
    val request: String,
    val outcome: LoopOutcome,
    val uiSteps: Int,
    val steps: Int,
    val extensions: Int,
    val recoveries: Int,
    val replans: Int,
    val bans: List<String>,
    val history: List<String>,
    val exitReason: String,
    val elapsedMs: Long,
) {
    val answer: String? get() = (outcome as? LoopOutcome.Completed)?.answer
}
