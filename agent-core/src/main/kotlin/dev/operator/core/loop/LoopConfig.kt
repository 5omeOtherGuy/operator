package dev.operator.core.loop

import dev.operator.core.api.Clock
import dev.operator.core.api.DecidePolicy
import dev.operator.core.api.DecisionKind
import dev.operator.core.api.Snapshot

/*
 * The agent loop's tunables and the seams it is wired with (S6).
 *
 * Everything numeric here is either a design constant (§8.4 budgets, §8.3 grammar bounds, τ_done from
 * §8.4) or a placeholder the measurements own; nothing is invented policy. [screenRenderer] is the
 * one seam that is not a port: the OSF serializer belongs to S2 (§6), and the composition root (I1)
 * injects it, so the loop carries a compact fallback for M1 and tests.
 *
 * Design: §8.1-§8.4, §2.3, ADR-0009.
 */

/** The decision kinds the loop asks about (FOUNDATION §5.1, §8.2). */
object Kinds {
    val TASK_INTENT = DecisionKind("task.intent")
    val ROUTE_API_OR_UI = DecisionKind("route.api_or_ui")
    val UI_TARGET = DecisionKind("ui.target")
    val EFFECT_ACHIEVED = DecisionKind("effect.achieved")
    val TASK_DONE = DecisionKind("task.done")
}

/** Renders a snapshot into the prompt's screen block; S2's OSF v0 serializer is injected here. */
fun interface ScreenRenderer {
    fun render(snapshot: Snapshot, previous: Snapshot?): String
}

/** The loop's tunables (design constants first, then measurement-owned placeholders). */
data class LoopConfig(
    // §8.4 / C7 budgets.
    val uiStepsPerSubgoal: Int = 8,
    val taskStepCheckpoint: Int = 25,
    val extensionSize: Int = 10,
    val hardCeiling: Int = 60,

    // §8.2 / §8.4 thresholds (INFERENCE defaults; M2 calibrates).
    val tauRoute: Double = 0.5,
    val tauDone: Double = 0.8,

    // §7.5 / §8.4 settle times (05-M1-V1 placeholders).
    val quietMs: Long = 400,
    val maxSettleMs: Long = 5_000,
    val reobserveQuietMs: Long = 1_200,

    // §8.2 / §8.3 generate bounds.
    val maxSubgoals: Int = 6,
    val maxSubgoalChars: Int = 80,
    val planMaxTokens: Int = 256,
    val actionMaxTokens: Int = 24,
    val answerMaxTokens: Int = 64,

    // §8.4 recovery ladder limits, per task.
    val maxReobserves: Int = 1,
    val maxScrollRecoveries: Int = 3,
    val maxBackRecoveries: Int = 1,
    val maxRelaunchRecoveries: Int = 1,
    val maxReplans: Int = 2,

    // §8.4 loop detection.
    val loopWindow: Int = 8,
    val loopHashRepeats: Int = 3,
    val noProgressLimit: Int = 4,

    // §2.3 inference death: one automatic retry.
    val inferenceRetries: Int = 1,

    // §8.4 working memory.
    val historyLimit: Int = 24,
    val historyCompactEvery: Int = 10,

    // §6.4 poor-tree detector thresholds (`04-M1-5` placeholders).
    val poorTreeMinActionables: Int = 3,
    val poorTreeUnlabelledFraction: Double = 0.4,
    val poorTreeSingleNodeFraction: Double = 0.5,

    // §5.1 decide policy the loop asks with.
    val decidePolicy: DecidePolicy = DecidePolicy(),

    /** Display name → package, for the `open` verb's enum (§8.3). */
    val appAliases: Map<String, String> = emptyMap(),

    /** The screen block seam; S2's OSF serializer is injected here. */
    val screenRenderer: ScreenRenderer = ScreenRenderer { snapshot, _ -> MinimalScreenRenderer.render(snapshot) },
)

/**
 * The M1 fallback screen block. It is deliberately small: the real OSF v0 text is S2's, and this
 * exists so the loop and its tests can run without it. The screen text is data, never instructions
 * (§9.3 item 1); labels are quoted and escaped.
 */
object MinimalScreenRenderer {

    fun render(snapshot: Snapshot): String = buildString {
        append("SCREEN s").append(snapshot.id)
        append(" app=\"").append(escape(snapshot.foregroundPackage)).append('"')
        if (snapshot.keyboardUp) append(" kbd=up")
        snapshot.focusedIndex?.let { append(" focus=").append(it) }
        append('\n')
        for (node in snapshot.nodes) {
            append('[').append(node.index).append("] ").append(node.role.name.lowercase())
            append(" \"").append(escape(node.label)).append('"')
            if (node.state.isNotEmpty()) append(' ').append(node.state.joinToString(",") { it.name.lowercase() })
            append('\n')
        }
        append("END")
    }

    private fun escape(s: String): String = buildString {
        for (c in s) when (c) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\n' -> append("\\n")
            else -> append(c)
        }
    }
}

/** Small helpers the loop shares with its tests. */
object LoopSupport {

    /** A stable, clock-and-progress-normalised fingerprint for the history line (§8.4). */
    fun historyLine(step: Int, action: String, effect: String): String = "h$step $action → $effect"

    /** §8.4: elapsed time is shown, never a limit; this is only the report's wall time. */
    fun elapsed(clock: Clock, startMonotonicMs: Long): Long = clock.monotonicMs() - startMonotonicMs
}
