package dev.operator.eval

/**
 * The seams the `dev` evaluation surface plugs into (FOUNDATION §12; ADR-0015).
 *
 * S12 owns the harness (the receiver, the log and the replay runner); the loop (S6), the gate (S8),
 * the kill switch (S8/§9.6) and `:llm`'s bench (S1) own the behaviour behind these hooks. The
 * composition root (I1) sets them once; until then the receiver records a refusal instead of
 * pretending a task ran, so a driver run is never silently green.
 *
 * Every hook is optional and fails closed: an unset hook is a refusal, never a pass.
 */
object EvalHooks {

    /** Starts one task by id. Returns false when the loop declined (disarmed, budget, unknown id). */
    @Volatile
    var taskRunner: EvalTaskRunner? = null

    /** Automation-only gate approval; exists in the `dev` channel only (research 07 §R1). */
    @Volatile
    var gateApprover: EvalGateApprover? = null

    /** The §9.6 kill triggers K-a…K-e. */
    @Volatile
    var killer: EvalKiller? = null

    /** `:llm` `bench(pp, tg, threads)` (research 07 §R1 `BENCH`). */
    @Volatile
    var bencher: EvalBench? = null

    /** The loop entry `ReplayRunner` feeds recorded snapshots to; when unset, replay is a no-op. */
    @Volatile
    var replayConsumer: ReplayStepConsumer? = null

    fun reset() {
        taskRunner = null
        gateApprover = null
        killer = null
        bencher = null
        replayConsumer = null
    }
}

/** Starts a task by id. The owner channel is the loop's job; this is its `dev`-only entry. */
fun interface EvalTaskRunner {
    fun start(taskId: String, goal: String, seed: Long, run: Int): Boolean
}

/** Approves the pending gate card with [gateId] without a physical gesture (research 07 §R1). */
fun interface EvalGateApprover {
    fun approve(gateId: String): Boolean
}

/** Fires one of the §9.6 kill triggers. */
fun interface EvalKiller {
    fun kill(trigger: String): Boolean
}

/** Runs `:llm`'s bench entry point. */
fun interface EvalBench {
    fun bench(model: String, pp: Int, tg: Int, threads: Int): Boolean
}
