package dev.operator.core.api

/*
 * Frozen M1 API (`dev.operator.core.api`) — the agent loop states of FOUNDATION §8.1.
 *
 * owner request (the only source of goals)
 *   INTAKE → CAPS → ROUTE ─api─► API_ARGS ─► GATE ─► EXEC_API ─► VERIFY_API ─► DONE
 *              │ui                                    │fail → UI fallback
 *              ▼
 *            PLAN ─► OBSERVE ─► PROPOSE ─► GATE ─(R2/R3)─► CONFIRM ─approve─► ACT
 *                     ─► SETTLE ─► VERIFY ─► PROGRESS ─┬─ continue → OBSERVE
 *                                                      ├─ done & p ≥ τ_done → DONE
 *                                                      └─ stuck/fail → RECOVER → REPLAN / ASK_OWNER / ABORT
 * Any state: owner Stop → ABORTED; inference died → PAUSED (retry once, then notify).
 *
 * Design: §8.1 (state machine), §8.2 (generate versus decide per step), §8.4 (budgets, loop
 * detection, recovery), §9.3 item 1 (goals only from the owner channel), §2.3 (a dead `:llm` process
 * pauses the task). S6 owns the machine that runs these states; this file is only their shape.
 */

/** §8.1: M1 pauses for one reason, a dead `:llm` process (§2.3). S6 extends this with a lead decision. */
enum class PauseReason { INFERENCE_DIED }

/** §8.1: one state of the loop. Terminal states are [Done], [Aborted] and [Paused]. */
sealed interface LoopState {

    /** §8.1, §9.3 item 2: the owner's request, the only source of goals. */
    data class Intake(val request: String) : LoopState

    /** §8.1, §9.3 item 2: the capability set is fixed from the owner's words before any screen text. */
    data class Caps(val capabilities: Set<String>, val appSet: Set<String>) : LoopState

    /** §8.1: `route.api_or_ui` decides the API or the UI branch. */
    data class Route(val decisionKind: DecisionKind) : LoopState

    /** §8.1, §8.2: a generate call for ≤ 6 subgoals of ≤ 80 characters. */
    data object Plan : LoopState

    /** §8.1: the API branch fills the capability's arguments under its schema grammar. */
    data class ApiArgs(val capability: String) : LoopState

    /** §8.1: R2/R3 calls wait on the gate (§9.2). */
    data class Gate(val pendingId: String) : LoopState

    /** §8.1: the API call runs through the executor (§2.2). */
    data class ExecApi(val call: ToolCall) : LoopState

    /** §8.1, §7.5: API evidence is checked; a failure falls back to the UI branch. */
    data class VerifyApi(val call: ToolCall) : LoopState

    /** §8.1, §6: read, settle, OSF; a poor tree asks the owner in M1 (§6.4). */
    data object Observe : LoopState

    /** §8.1, §8.3: the model picks a verb and an index under the per-step grammar. */
    data object Propose : LoopState

    /** §8.1, §9.2: the owner approves the card. */
    data class Confirm(val pendingId: String) : LoopState

    /** §8.1, §7.4 step 11: the executor re-checks the token, then acts (TOCTOU). */
    data class Act(val call: ToolCall) : LoopState

    /** §8.1, §7.5: idle detection and a snapshot comparison. */
    data object Settle : LoopState

    /** §8.1, §8.2: rules on `CHANGES` plus `effect.achieved` and `task.done`. */
    data object Verify : LoopState

    /** §8.1: step and budget accounting, then continue, finish or recover. */
    data class Progress(val step: Int) : LoopState

    /** §8.1, §8.4: the recovery ladder. */
    data class Recover(val reason: String) : LoopState

    /** §8.1: back to [Plan] with the recovery's result. */
    data class Replan(val reason: String) : LoopState

    /** §8.1, §6.4: a question to the owner, answered in an operator activity (§9.3 item 1). */
    data class AskOwner(val question: String) : LoopState

    /** §8.1: the goal is met with `p ≥ τ_done`. */
    data class Done(val answer: String) : LoopState

    /** §8.1: owner Stop, a budget stop or a kill (§9.6). */
    data class Aborted(val reason: String) : LoopState

    /** §8.1, §2.3: inference died; one automatic retry, then the owner is notified. */
    data class Paused(val reason: PauseReason) : LoopState
}
