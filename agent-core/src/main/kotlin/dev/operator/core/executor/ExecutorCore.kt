package dev.operator.core.executor

import dev.operator.core.api.*
import dev.operator.core.policy.*
import kotlinx.coroutines.sync.Mutex

/** API-specific effect and read-back implementations live behind this boundary. */
interface EffectAdapter {
    suspend fun act(call: ToolCall): Unit
}

/** Positive evidence is mandatory; no delivery acknowledgement alone can produce Done. */
interface EffectVerifier {
    suspend fun pre(call: ToolCall, snapshot: Snapshot?): Any?
    suspend fun post(call: ToolCall, before: Any?, snapshot: Snapshot?): Verification
}

sealed interface Verification {
    data class Confirmed(val evidence: String) : Verification
    data class Missing(val reason: String) : Verification
    data class Inconclusive(val reason: String) : Verification
}

class ExecutorCore(
    private val hands: HandsPort,
    private val adapter: EffectAdapter,
    private val verifier: EffectVerifier,
    private val gate: GatePort,
    private val audit: AuditPort,
    private val clock: Clock,
    private val context: PolicyContext,
    val taint: TaintTracker = TaintTracker(),
) : ExecutorPort {
    private val limits = RateLimits(clock)
    private val usedTokens = mutableSetOf<String>()
    private val edited = mutableMapOf<ElementKey, String>()
    private val pending = mutableMapOf<String, String>()
    private val mutex = Mutex()

    /** The loop supplies only owner-authorised observations, never instructions from them. */
    fun observe(source: ObservedText) = taint.observe(source)

    override suspend fun execute(call: ToolCall, approval: ApprovalToken?): ExecResult {
        mutex.lock()
        try {
            return executeLocked(call, approval)
        } finally {
            mutex.unlock()
        }
    }

    private suspend fun executeLocked(call: ToolCall, approval: ApprovalToken?): ExecResult {
        var risk: RiskClass? = null
        suspend fun result(value: ExecResult): ExecResult {
            audit.append(AuditEvent(clock.wallMs(), AuditType.DECISION, null, context.steps, call.name,
                mapOf("call" to call.toString()), risk,
                when (value) {
                    is ExecResult.Refused -> AuditDecision.REFUSED
                    is ExecResult.NeedsApproval -> AuditDecision.GATED
                    is ExecResult.Cancelled -> AuditDecision.CANCELLED
                    else -> if (risk == RiskClass.R2 || risk == RiskClass.R3) AuditDecision.GATED else AuditDecision.AUTO
                }, value.toString(), null, null, null))
            return value
        }
        suspend fun refuse(reason: String) = result(ExecResult.Refused(reason))

        // 1. Strict parse (sealed typed calls; reject fabricated names).
        if (ToolCatalog.base[call::class] == null || call.name in ToolCatalog.forbiddenNames) return refuse("parse")
        // 2. Full schema, including limits the grammar cannot express.
        if (!schema(call) || call.name !in context.allowedTools) return refuse("schema")
        val ui = call is ToolCall.Click || call is ToolCall.LongClick || call is ToolCall.SetText ||
            call is ToolCall.Scroll || call in setOf(ToolCall.Back, ToolCall.Home, ToolCall.Recents,
                ToolCall.Notifications, ToolCall.QuickSettings, ToolCall.DismissShade, ToolCall.LockScreen)
        val target = when (call) {
            is ToolCall.Click -> Triple(call.snapshotId, call.elementIndex, call.elementKey)
            is ToolCall.LongClick -> Triple(call.snapshotId, call.elementIndex, call.elementKey)
            is ToolCall.SetText -> Triple(call.snapshotId, call.elementIndex, call.elementKey)
            is ToolCall.Scroll -> Triple(call.snapshotId, call.elementIndex, call.elementKey)
            else -> null
        }
        val screen = if (target != null || call is ToolCall.ReadScreen || approval != null) hands.snapshot() else null
        // 3. Snapshot generation.
        if (target != null && target.first != screen?.id ||
            call is ToolCall.ReadScreen && call.snapshotId != screen?.id) return refuse("stale")
        // 4. Element, visibility, capability; clipboard actions do not exist in the frozen enum.
        val node = target?.let { t -> screen?.nodes?.firstOrNull { it.index == t.second && it.key == t.third } }
        val suppliedLabel = when (call) {
            is ToolCall.Click -> call.label
            is ToolCall.LongClick -> call.label
            is ToolCall.SetText -> call.label
            else -> null
        }
        val action = when (call) {
            is ToolCall.Click -> NodeAction.CLICK
            is ToolCall.LongClick -> NodeAction.LONG_CLICK
            is ToolCall.SetText -> NodeAction.SET_TEXT
            is ToolCall.Scroll -> if (call.direction == ScrollDirection.DOWN || call.direction == ScrollDirection.RIGHT)
                NodeAction.SCROLL_FORWARD else NodeAction.SCROLL_BACKWARD
            else -> null
        }
        if (target != null && (node == null || suppliedLabel != null && suppliedLabel != node.label ||
            action !in node.actions || NodeState.DISABLED in node.state ||
            node.bounds.left >= node.bounds.right || node.bounds.top >= node.bounds.bottom)) return refuse("element")
        // 5. Owner channel is never accessible through UI, notifications or foreground activities.
        if (screen?.foregroundPackage?.startsWith("dev.operator") == true ||
            node?.packageName?.startsWith("dev.operator") == true ||
            (call is ToolCall.ReplyNotification && call.key.startsWith("dev.operator")) ||
            (call is ToolCall.NotificationAction && call.key.startsWith("dev.operator")) ||
            (call is ToolCall.ReplyNotification && hands.notifications().none { it.key == call.key && !it.isOperator }) ||
            (call is ToolCall.NotificationAction && hands.notifications().none { it.key == call.key && !it.isOperator }))
            return refuse("owner_channel")
        // 6. Installed, denylisted, task-scoped packages; sensitive Settings subpages.
        val pkg = node?.packageName ?: when (call) {
            is ToolCall.LaunchApp -> call.packageName
            is ToolCall.Uninstall -> call.packageName
            is ToolCall.HideApp -> call.packageName
            is ToolCall.SuspendApp -> call.packageName
            is ToolCall.SetPermission -> call.packageName
            else -> screen?.foregroundPackage
        }
        if (pkg != null && (!context.installed(pkg) || context.sensitive(pkg) ||
            pkg !in context.allowedPackages ||
            pkg.contains("settings", true) && screen?.windows?.any { w ->
                Regex("reset|factory|app info|developer|accessibility|device admin|special app|security|lock screen|account", RegexOption.IGNORE_CASE)
                    .containsMatchIn(w.title.orEmpty())
            } == true)) return refuse("package")
        // 7. Emergency and short numbers never enter telephony.
        val number = when (call) { is ToolCall.SendSms -> call.number; is ToolCall.Call -> call.number; else -> null }
        if (number != null && (number.filter(Char::isDigit).length < 7 ||
            number.filter(Char::isDigit) in setOf("112", "911", "999", "110", "1100"))) return refuse("number")
        // 8. Text lengths, password fields, observation taint.
        val text = when (call) {
            is ToolCall.SetText -> call.text
            is ToolCall.SendSms -> call.text
            is ToolCall.ReplyNotification -> call.text
            else -> null
        }
        if (text != null && text.length > 4096 || node != null && NodeState.PASSWORD in node.state &&
            call is ToolCall.SetText) return refuse("text")
        val detected = text?.let { taint.detect(it, context.ownerUtterance) }
        val carried = if (node != null && screen != null && UiRisk.submit(node)) {
            edited.keys.firstNotNullOfOrNull { taint.field(it) }
        } else null
        // 9. Limits.
        val base = ToolCatalog.base.getValue(call::class)
        val raised = when (call) {
            is ToolCall.SetText -> detected != null
            is ToolCall.Click, is ToolCall.LongClick -> node != null && screen != null &&
                UiRisk.risk(node, screen, carried != null, context.highRisk(node.packageName))
            is ToolCall.CalendarInsert -> call.attendees.isNotEmpty()
            is ToolCall.SetPermission -> call.grantState == PermissionGrantState.GRANTED
            else -> false
        }
        risk = if (raised || context.irreversible(call)) ToolCatalog.risers[call::class] ?: base else base
        val irreversible = risk == RiskClass.R2 || risk == RiskClass.R3
        limits.refusal(call, ui, irreversible)?.let { return refuse(it) }
        // 10. Disarm, stop, and hard ceiling.
        if (!gate.armed.value || context.halted || context.steps >= 60) return refuse("halted")
        // 11. Gate. Tokens must be authenticated by the gate, single-use and bound to the exact call.
        if (irreversible) {
            val id = call.toString()
            if (approval == null) {
                val pendingId = "${clock.monotonicMs()}:${pending.size}"
                pending[pendingId] = id
                val card = GateCard("Confirm ${call.name}", listOf(id),
                    listOfNotNull(node?.label, if (number != null && !context.knownNumber(number)) "Unknown number" else null),
                    detected ?: carried, risk, if (risk == RiskClass.R3)
                        listOf(ApprovalMethod.VOLUME_HOLD, ApprovalMethod.BIOMETRIC_STRONG)
                    else listOf(ApprovalMethod.VOLUME_HOLD))
                // The gate owns the UI; never treat its response as authority without token authentication.
                when (val decision = gate.request(card)) {
                    is GateResult.Approved -> {
                        pending.remove(pendingId)
                        pending[decision.token.id] = id
                        return result(ExecResult.NeedsApproval(decision.token.id))
                    }
                    is GateResult.Denied -> {
                        pending.remove(pendingId)
                        return result(ExecResult.Cancelled(decision.reason))
                    }
                    is GateResult.Expired, GateResult.Cancelled -> {
                        pending.remove(pendingId)
                        return result(ExecResult.Cancelled("gate"))
                    }
                }
            }
            if (pending[approval.id] != id || approval.id in usedTokens || !context.authentic(approval) ||
                approval.call != call || approval.issuedAtMs > clock.monotonicMs() ||
                approval.expiresAtMs <= clock.monotonicMs() || approval.expiresAtMs - approval.issuedAtMs > 30_000 ||
                (risk == RiskClass.R3 && approval.method != ApprovalMethod.BIOMETRIC_STRONG) ||
                approval.screenSignature != (screen ?: hands.snapshot()).screenSignature ||
                approval.editedFieldContents != edited) return refuse("approval")
            usedTokens += approval.id
            pending.remove(approval.id)
            val fresh = hands.snapshot()
            if (fresh.screenSignature != approval.screenSignature ||
                target != null && fresh.nodes.none { it.index == target.second && it.key == target.third &&
                    it.label == node?.label && action in it.actions }) return refuse("toctou")
        }
        val before = verifier.pre(call, screen)
        audit.append(AuditEvent(clock.wallMs(), AuditType.EXEC, null, context.steps, call.name,
            mapOf("call" to call.toString()), risk,
            if (irreversible) AuditDecision.GATED else AuditDecision.AUTO,
            "intent", null, null, null))
        try {
            if (node != null && action != null) hands.performNodeAction(node.key, action, text)
            else when (call) {
                ToolCall.Back -> hands.globalAction(GlobalAction.BACK)
                ToolCall.Home -> hands.globalAction(GlobalAction.HOME)
                ToolCall.Recents -> hands.globalAction(GlobalAction.RECENTS)
                ToolCall.Notifications -> hands.globalAction(GlobalAction.NOTIFICATIONS)
                ToolCall.QuickSettings -> hands.globalAction(GlobalAction.QUICK_SETTINGS)
                ToolCall.DismissShade -> hands.globalAction(GlobalAction.DISMISS_SHADE)
                ToolCall.LockScreen -> hands.globalAction(GlobalAction.LOCK_SCREEN)
                else -> adapter.act(call)
            }
        } catch (e: Exception) { return result(ExecResult.Failed("act: ${e::class.simpleName}")) }
        limits.record(call, ui, irreversible)
        if (call is ToolCall.SetText) {
            taint.setField(call.elementKey, call.text, detected)
            edited[call.elementKey] = call.text
        }
        hands.awaitIdle(400, 5_000)
        val post = hands.snapshot()
        return result(when (val check = verifier.post(call, before, post)) {
            is Verification.Confirmed -> ExecResult.Done(check.evidence)
            is Verification.Missing -> ExecResult.Failed(check.reason)
            is Verification.Inconclusive -> ExecResult.Unverified("", check.reason)
        })
    }

    private fun schema(call: ToolCall): Boolean = when (call) {
        is ToolCall.SetAlarm -> call.hour in 0..23 && call.minute in 0..59 && call.daysOfWeek.all { it in 1..7 }
        is ToolCall.SetTimer -> call.durationMs > 0
        is ToolCall.Wait -> call.ms in 0..5_000
        is ToolCall.CalendarInsert -> call.beginMs < call.endMs
        is ToolCall.CalendarQuery -> call.fromMs <= call.toMs
        is ToolCall.SendSms -> call.number.isNotBlank() && call.text.isNotBlank()
        is ToolCall.Call -> call.number.isNotBlank()
        else -> true
    }
}
