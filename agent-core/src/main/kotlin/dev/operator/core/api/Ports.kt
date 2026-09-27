package dev.operator.core.api

import kotlinx.coroutines.flow.StateFlow

/*
 * Frozen M1 API (`dev.operator.core.api`) — the six ports the loop is written against.
 *
 * Design: FOUNDATION §2.1 (components), §2.2 (module rule: the only path from the loop to an effect
 * is `Executor.execute(call, approval)`), §2.3 (`:llm` AIDL surface), §2.4 (threading: hands
 * dispatcher, task scope), §6 (observation), §7.4/§7.5 (validation and verification), §9.2 (gate),
 * §9.5 (audit), §9.6 (kill switch and `armed`), ADR-0002/0003.
 *
 * The loop (S6) sees these interfaces and nothing else; the implementations are S1, S3, S7, S8, S10.
 */

/**
 * §2.3, suspend mirror of every row of the `:llm` AIDL surface. A call returns when its result is
 * complete; tokens stream through [onToken]. Payloads stay under 256 KB (§2.3); images go through
 * `SharedMemory` from M2.
 */
interface LlmPort {
    /** §2.3 `load`: type allowlist plus sha256 before the model is opened (§4.8). */
    suspend fun load(spec: ModelSpec)

    /** §2.3 `unload`. */
    suspend fun unload(role: ModelRole)

    /** §2.3 `tokenize`: token parity and prefix computation. */
    suspend fun tokenize(role: ModelRole, text: String, addSpecial: Boolean, parseSpecial: Boolean): List<Int>

    /** §2.3 `generate`: plan, action and slot filling with prefix reuse. */
    suspend fun generate(
        req: GenerateRequest,
        role: ModelRole,
        promptParts: List<String>,
        gbnf: String?,
        maxTokens: Int,
        onToken: (String) -> Unit,
    ): GenerationResult

    /** §2.3 `labelLogits`: one logit row per label on the request's branch sequence (§4.5). */
    suspend fun labelLogits(
        req: GenerateRequest,
        role: ModelRole,
        promptParts: List<String>,
        labelTokenIds: List<List<Int>>,
    ): List<FloatArray>

    /** §2.3 `embedLast` (M2): CLM-8B encoder state on the recipe-exact text, `pooling=NONE`, last row. */
    suspend fun embedLast(req: GenerateRequest, role: ModelRole, tokens: List<Int>): FloatArray

    /** §2.3 `stateSave`/`stateRestore`: static-prefix cache (§4.5). */
    suspend fun stateSave(role: ModelRole, path: String)

    suspend fun stateRestore(role: ModelRole, path: String)

    /** §2.3 `stateCheckpoint` (`LLAMA_STATE_SEQ_FLAGS_PARTIAL_ONLY`, 27B). */
    suspend fun stateCheckpoint(role: ModelRole, tag: String, partialOnly: Boolean)

    /** §2.3 `abort`: cooperative stop, per-token flag plus the CPU abort callback (§9.6). */
    suspend fun abort(reqId: Long)

    /** §2.3 `pid`: lets the main process kill `:llm` by PID for the hard stop (§9.6, ADR-0002). */
    suspend fun pid(): Int

    /** §2.3 `stats`. */
    suspend fun stats(): LlmStats

    /** §2.3 `bench(pp, tg, threads)`. */
    suspend fun bench(pp: Int, tg: Int, threads: Int): LlmStats
}

/** §3.2 A1: the a11y global actions. */
enum class GlobalAction { BACK, HOME, RECENTS, NOTIFICATIONS, QUICK_SETTINGS, DISMISS_SHADE, LOCK_SCREEN }

/** Host-side gesture endpoints. Coordinates never reach the model (§7.1, C10). */
data class Point(val x: Int, val y: Int)

/** One row of the notification shade, as the NotificationListener sees it (§3.2 A12). */
data class NotificationRecord(
    val key: String,
    val packageName: String,
    val title: String?,
    val text: String?,
    val postedAtMs: Long,
    /** Operator's own rows are excluded from OSF and are never a target (§6.1 rule 1, §7.4 step 5, S-10). */
    val isOperator: Boolean,
    val actions: List<NotificationActionRecord>,
)

/** One action button of a [NotificationRecord]; [isReply] marks an inline reply field (§6.1 rule 1). */
data class NotificationActionRecord(val index: Int, val title: String?, val isReply: Boolean)

/**
 * §2.4, §6: the eyes and hands. Reads are free of effects; [performNodeAction] and the gestures are
 * host-only primitives (C10) that only the executor calls (§2.2). OTP spans are redacted before any
 * of this reaches the model (§6.1 rule 1b).
 */
interface HandsPort {
    /** §7.5 pre-snapshot: settle, then read, then build the [Snapshot]. */
    suspend fun snapshot(): Snapshot

    /** §7.5: no content, state or scroll events from the foreground window for `quietMs`, bounded by `maxMs`. */
    suspend fun awaitIdle(quietMs: Long, maxMs: Long): Boolean

    /** §2.5, §9.6: the service is bound and armed. */
    suspend fun isConnected(): Boolean

    /** §7.4 step 4: the node action (click, set text, scroll, …) on a node the executor re-resolved by key. */
    suspend fun performNodeAction(key: ElementKey, action: NodeAction, text: String? = null)

    /** §7.1 fallback: a host-computed tap at a point inside a node's bounds, capped at 1000 ms (§9.6). */
    suspend fun gestureTap(point: Point, durationMs: Long)

    /** §7.1 fallback: a host-computed swipe, capped at 1000 ms (§9.6). */
    suspend fun gestureSwipe(from: Point, to: Point, durationMs: Long)

    /** §3.2 A1: a global action. */
    suspend fun globalAction(action: GlobalAction)

    /** §3.2 A12, §7.2 `list_notifications`: operator's own rows excluded, OTP spans redacted (§6.1 rules 1 and 1b). */
    suspend fun notifications(): List<NotificationRecord>

    /** §14 M2: `takeScreenshot`, at least 333 ms apart; null when a window is `FLAG_SECURE` (§6.4). */
    suspend fun takeScreenshot(): ByteArray?
}

/** §2.2, ADR-0002: the only path from the loop to an effect. */
interface ExecutorPort {
    /**
     * §7.4 validation order, §7.3 risk class, §9.2 gate, §7.5 verification.
     * [approval] is the token the gate minted for this call, if any.
     */
    suspend fun execute(call: ToolCall, approval: ApprovalToken?): ExecResult
}

/** §9.3 item 3: tainted spans and the package they came from, rendered on the card. */
data class Taint(val sourcePackage: String, val spans: List<String>)

/**
 * §9.2 item 2: the card text is rendered by the executor, never model prose. [title] states what the
 * executor knows (for a UI tap: *Tap "&lt;label&gt;" in &lt;app label&gt;*); [fromScreen] lists the
 * values the app or a web page controls.
 */
data class GateCard(
    val title: String,
    val arguments: List<String>,
    val fromScreen: List<String>,
    val taint: Taint?,
    val riskClass: RiskClass,
    val methods: List<ApprovalMethod>,
)

/** §9.2 items 3-4, 6: the outcome of a card. Denied covers a Reject tap, a 60 s timeout, a volume-up press and an over-long hold. */
sealed interface GateResult {
    data class Approved(val token: ApprovalToken) : GateResult

    data class Denied(val reason: String) : GateResult

    data class Expired(val timeoutMs: Long) : GateResult

    data object Cancelled : GateResult
}

/** §9.2, §9.6: the gate the agent cannot press, and the Disarm state it obeys. */
interface GatePort {
    /** §9.6: false after Disarm; the executor refuses everything while disarmed. */
    val armed: StateFlow<Boolean>

    /** §9.2 item 1: block on the owner's decision for an R2/R3 call. */
    suspend fun request(card: GateCard): GateResult

    /** §9.6: Stop, kill or Disarm voids every pending approval. */
    suspend fun cancelPending()
}

/** §9.5: the audit decision field, `auto / gated / refused / confirmed-hold / confirmed-biometric / voided / cancelled`. */
enum class AuditDecision { AUTO, GATED, REFUSED, CONFIRMED_HOLD, CONFIRMED_BIOMETRIC, VOIDED, CANCELLED }

/** §9.5: the line kinds agents, the gate, the Keeper and the owner's own actions write. */
enum class AuditType {
    TASK, STEP, DECISION, EXEC, GATE, KILL, KEEPER, WSS_WRITE, DO_POLICY, MODEL_IMPORT, SETTINGS, AUDIT,
}

/** §9.5: one JSONL line; `prev` is added by the writer, which owns the chain. */
data class AuditEvent(
    val ts: Long,
    val type: AuditType,
    val taskId: String?,
    val step: Int?,
    val tool: String?,
    /** Canonical arguments, full for R2/R3 (OQ-3). */
    val arguments: Map<String, String>,
    val riskClass: RiskClass?,
    val decision: AuditDecision?,
    val result: String?,
    val observationSha256: String?,
    val modelOutputSha256: String?,
    val exitReason: String?,
)

/**
 * §9.5: append-only hash-chained JSONL in credential-encrypted storage in the main process. An intent
 * line is written before each act and a result line after, so unfinished R2/R3 steps are reported on
 * restart and never auto-retried. S10 owns the writer and the rotation.
 */
interface AuditPort {
    suspend fun append(event: AuditEvent)

    /** §9.5 anchor: the chain head hash, shown to the owner as an 8-character fingerprint. */
    suspend fun head(): String
}

/** §2.4, §4.5, §9.2 and §12 need both a wall clock (log timestamps) and a monotonic one (deadlines). */
interface Clock {
    fun wallMs(): Long

    fun monotonicMs(): Long
}
