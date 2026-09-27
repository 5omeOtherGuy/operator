package dev.operator.core.loop

import dev.operator.core.api.AbstainReason
import dev.operator.core.api.Answer
import dev.operator.core.api.ApprovalToken
import dev.operator.core.api.AuditEvent
import dev.operator.core.api.AuditPort
import dev.operator.core.api.BackendId
import dev.operator.core.api.Clock
import dev.operator.core.api.DecidePolicy
import dev.operator.core.api.Decider
import dev.operator.core.api.Decision
import dev.operator.core.api.DecisionMeta
import dev.operator.core.api.DecisionState
import dev.operator.core.api.ElementKey
import dev.operator.core.api.ExecResult
import dev.operator.core.api.ExecutorPort
import dev.operator.core.api.GenerateRequest
import dev.operator.core.api.GenerationResult
import dev.operator.core.api.GlobalAction
import dev.operator.core.api.HandsPort
import dev.operator.core.api.LlmBackend
import dev.operator.core.api.LlmPort
import dev.operator.core.api.LlmStats
import dev.operator.core.api.ModelRole
import dev.operator.core.api.ModelSpec
import dev.operator.core.api.NodeAction
import dev.operator.core.api.NotificationRecord
import dev.operator.core.api.Point
import dev.operator.core.api.Question
import dev.operator.core.api.Snapshot
import dev.operator.core.api.StopReason
import dev.operator.core.api.ToolCall
import java.util.concurrent.atomic.AtomicInteger

/*
 * Scripted in-process fakes for the six loop ports (S6 tests). Nothing here touches a phone, an
 * account or a network service; the screens are the §6.2 fixtures.
 */

/** A scripted `LlmPort`. [onGenerate] is called for every generate; the counters drive failure tests. */
class FakeLlm : LlmPort {

    val generateCalls = ArrayList<GenerateCall>()
    var onGenerate: (List<String>, String?) -> String = { _, _ -> """{"a":"wait"}""" }

    /** Fail this many of the next generate calls (an inference death, §2.3). */
    var failsRemaining: Int = 0

    /** Fail every generate call. */
    var failAlways: Boolean = false

    data class GenerateCall(val role: ModelRole, val promptParts: List<String>, val gbnf: String?, val maxTokens: Int)

    override suspend fun generate(
        req: GenerateRequest,
        role: ModelRole,
        promptParts: List<String>,
        gbnf: String?,
        maxTokens: Int,
        onToken: (String) -> Unit,
    ): GenerationResult {
        generateCalls.add(GenerateCall(role, promptParts, gbnf, maxTokens))
        if (failAlways) throw IllegalStateException("the :llm binding died")
        if (failsRemaining > 0) {
            failsRemaining--
            throw IllegalStateException("the :llm binding died")
        }
        val text = onGenerate(promptParts, gbnf)
        return GenerationResult(text, emptyList(), StopReason.EOG, emptyStats())
    }

    override suspend fun load(spec: ModelSpec) = Unit

    override suspend fun unload(role: ModelRole) = Unit

    override suspend fun tokenize(role: ModelRole, text: String, addSpecial: Boolean, parseSpecial: Boolean): List<Int> = emptyList()

    override suspend fun labelLogits(
        req: GenerateRequest,
        role: ModelRole,
        promptParts: List<String>,
        labelTokenIds: List<List<Int>>,
    ): List<FloatArray> = emptyList()

    override suspend fun embedLast(req: GenerateRequest, role: ModelRole, tokens: List<Int>): FloatArray = FloatArray(0)

    override suspend fun stateSave(role: ModelRole, path: String) = Unit

    override suspend fun stateRestore(role: ModelRole, path: String) = Unit

    override suspend fun stateCheckpoint(role: ModelRole, tag: String, partialOnly: Boolean) = Unit

    override suspend fun abort(reqId: Long) = Unit

    override suspend fun pid(): Int = 0

    override suspend fun stats(): LlmStats = emptyStats()

    override suspend fun bench(pp: Int, tg: Int, threads: Int): LlmStats = emptyStats()

    private fun emptyStats() = LlmStats(0, 0, 0, 0, 0, 0.0, 0.0, LlmBackend.CPU, "R11")
}

/** A scripted `Decider`: one answer per kind, with an optional scripted `task.done` sequence. */
class FakeDecider : Decider {

    var intentOption: String? = "use_ui"
    var routeOption: String? = "ui"
    var achievedOption: String? = "achieved"
    var taskDoneOption: () -> String = { "not_yet" }
    var confidence: Double = 0.9

    val askedKinds = ArrayList<String>()

    override suspend fun <A : Answer> decide(
        s: DecisionState,
        q: Question<A>,
        p: DecidePolicy,
    ): Decision<A> {
        askedKinds.add(q.kind.id)
        val option = when (q.kind.id) {
            "task.intent" -> intentOption
            "route.api_or_ui" -> routeOption
            "effect.achieved" -> achievedOption
            "task.done" -> taskDoneOption()
            else -> null
        }
        @Suppress("UNCHECKED_CAST")
        return when {
            option == null -> Decision.Abstained<Answer>(null, AbstainReason.BELOW_THRESHOLD, meta())
            else -> Decision.Decided(Answer.Choice(mapOf(option to confidence), confidence, option), meta())
        } as Decision<A>
    }

    override suspend fun decideAll(
        s: DecisionState,
        qs: List<Question<*>>,
        p: DecidePolicy,
    ): List<Decision<*>> = qs.map { q ->
        @Suppress("UNCHECKED_CAST")
        decide(s, q, p)
    }

    private fun meta() = DecisionMeta(BackendId.RULES, null, null, null, null, 0)
}

/** A scripted `ExecutorPort`: an answer for `ask_owner`, one result for everything else. */
class FakeExecutor : ExecutorPort {

    val executed = ArrayList<ToolCall>()
    val ownerQuestions = ArrayList<String>()

    /** The owner's answers, in order; the last one is reused when the queue runs dry. */
    var ownerAnswers = ArrayDeque(listOf("no"))

    /** The result for a normal call; the default is positive evidence (§7.5 `Ok`). */
    var resultFor: (ToolCall) -> ExecResult = { ExecResult.Done("ok") }

    /** Return `NeedsApproval` the first time this call is seen, mirroring the gate handshake. */
    var pendingOnce: (ToolCall) -> String? = { null }

    private val seenPending = HashSet<ToolCall>()

    override suspend fun execute(call: ToolCall, approval: ApprovalToken?): ExecResult {
        executed.add(call)
        if (call is ToolCall.AskOwner) {
            ownerQuestions.add(call.question)
            return ExecResult.Done(ownerAnswers.first())
        }
        val pending = pendingOnce(call)
        if (pending != null && seenPending.add(call)) {
            return ExecResult.NeedsApproval(pending)
        }
        return resultFor(call)
    }
}

/** A scripted `HandsPort`. [onSnapshot] gives a fresh screen each call unless [queue] is filled. */
class FakeHands : HandsPort {

    val queue = ArrayDeque<Snapshot>()
    var onSnapshot: suspend () -> Snapshot = { dev.operator.core.grammar.TestScreens.messagesScreen() }
    var idle: Boolean = true

    override suspend fun snapshot(): Snapshot = queue.removeFirstOrNull() ?: onSnapshot()

    override suspend fun awaitIdle(quietMs: Long, maxMs: Long): Boolean = idle

    override suspend fun isConnected(): Boolean = true

    override suspend fun performNodeAction(key: ElementKey, action: NodeAction, text: String?) = Unit

    override suspend fun gestureTap(point: Point, durationMs: Long) = Unit

    override suspend fun gestureSwipe(from: Point, to: Point, durationMs: Long) = Unit

    override suspend fun globalAction(action: GlobalAction) = Unit

    override suspend fun notifications(): List<NotificationRecord> = emptyList()

    override suspend fun takeScreenshot(): ByteArray? = null
}

/** Collects the audit lines the loop writes (§9.5). */
class FakeAudit : AuditPort {

    val events = ArrayList<AuditEvent>()
    private val count = AtomicInteger()
    private var chain = "0".repeat(64)

    override suspend fun append(event: AuditEvent) {
        events.add(event)
        count.incrementAndGet()
        chain = event.hashCode().toString().padStart(64, '0')
    }

    override suspend fun head(): String = chain
}

/** A deterministic `Clock`. */
class FakeClock : Clock {
    private var now = 1_000L

    override fun wallMs(): Long = now

    override fun monotonicMs(): Long = now++

    fun advance(ms: Long) {
        now += ms
    }
}

/** Small helpers for the loop tests (kept out of the production sources). */
object LoopTestSupport {

    fun plan(vararg subgoals: String): String =
        """{"subgoals":[${subgoals.joinToString(",") { """{"s":"$it"}""" }}]}"""

    fun tap(index: Int): String = """{"a":"tap","i":$index}"""

    fun done(answer: String): String = """{"a":"done","answer":"$answer"}"""
}
