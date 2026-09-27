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
    private val approvalVerifier: ApprovalVerifier,
    val taint: TaintTracker = TaintTracker(),
) : ExecutorPort {
    private val limits = RateLimits(clock)
    private val usedTokens = mutableSetOf<String>()
    private val edited = mutableMapOf<ElementKey, String>()
    private val pending = mutableMapOf<String, Pair<String, RiskClass>>()
    private val mutex = Mutex()

    /** Called at each owner task boundary, before accepting its first observation. */
    suspend fun resetTask() {
        mutex.lock()
        try {
            taint.reset()
            edited.clear()
            pending.clear()
            usedTokens.clear()
        } finally {
            mutex.unlock()
        }
    }

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
            (node != null && (node.packageName == "com.android.systemui" ||
                screen?.foregroundPackage == "com.android.systemui") &&
                context.operatorNotificationTarget(screen ?: hands.snapshot(), node) != false) ||
            (node != null && context.operatorNotificationTarget(screen ?: hands.snapshot(), node) == true) ||
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
            AppCategories.resolve(pkg, context.appCategory(pkg)) == AppCategory.SETTINGS &&
                screen?.windows?.any { w ->
                Regex("reset|factory|app info|developer|accessibility|device admin|special app|security|lock screen|account", RegexOption.IGNORE_CASE)
                    .containsMatchIn(w.title.orEmpty())
            } == true)) return refuse("package")
        // Capture only task-scoped, non-sensitive observations; never treat their text as commands.
        screen?.nodes?.forEach { taint.observe(ObservedText(it.packageName, it.label)) }
        if (call is ToolCall.ListNotifications) {
            hands.notifications().filterNot { it.isOperator }.forEach {
                taint.observe(ObservedText(it.packageName, listOfNotNull(it.title, it.text).joinToString(" ")))
            }
        }
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
        val detected = arguments(call).values.firstNotNullOfOrNull {
            taint.detect(it, context.ownerUtterance)
        }
        val carried = if (node != null && screen != null && UiRisk.submit(node)) {
            edited.keys.firstNotNullOfOrNull { taint.field(it) }
        } else null
        // 9. Limits.
        val base = ToolCatalog.base.getValue(call::class)
        val category = node?.let { AppCategories.resolve(it.packageName, context.appCategory(it.packageName)) }
        val raised = when (call) {
            is ToolCall.SetText -> detected != null
            is ToolCall.Click, is ToolCall.LongClick -> node != null && screen != null &&
                UiRisk.risk(node, screen, carried != null, category, context.iconBesideFilledEdit(screen, node))
            is ToolCall.CalendarInsert -> call.attendees.isNotEmpty()
            is ToolCall.SetPermission -> call.grantState == PermissionGrantState.GRANTED
            else -> false
        }
        risk = if (raised || context.irreversible(call))
            maxOf(base, ToolCatalog.risers[call::class] ?: RiskClass.R2) else base
        risk = maxOf(risk, pending.values.filter { it.first == call.toString() }
            .maxOfOrNull { it.second } ?: risk)
        val irreversible = risk == RiskClass.R2 || risk == RiskClass.R3
        limits.refusal(call, ui, irreversible)?.let { return refuse(it) }
        // 10. Disarm, stop, and hard ceiling.
        if (!gate.armed.value || context.halted || context.steps >= 60) return refuse("halted")
        // 11. Gate. Tokens must be authenticated by the gate, single-use and bound to the exact call.
        if (irreversible) {
            val id = call.toString()
            if (approval == null) {
                val label = if (node != null) context.appLabel(node.packageName)?.takeIf { it.isNotBlank() } else null
                val source = if (node != null && screen != null) context.screenContext(screen, node) else null
                if (context.taskId.isBlank()) return refuse("approval_binding")
                if (node != null && (label == null || source == null)) return refuse("card_context")
                val pendingId = "${clock.monotonicMs()}:${pending.size}"
                pending[pendingId] = id to risk
                val card = GateCard(
                    if (node != null) "${call.name}: ${node.label.ifBlank { "unlabelled target" }} in $label (${node.packageName})"
                    else "Confirm ${call.name}",
                    arguments(call).map { (key, value) -> "$key: $value" },
                    (source ?: emptyList()) +
                        listOfNotNull(if (number != null && !context.knownNumber(number)) "Unknown number" else null),
                    detected ?: carried, risk, if (risk == RiskClass.R3)
                        listOf(ApprovalMethod.VOLUME_HOLD, ApprovalMethod.BIOMETRIC_STRONG)
                    else listOf(ApprovalMethod.VOLUME_HOLD))
                // The gate owns the UI; never treat its response as authority without token authentication.
                val binding = ApprovalBinding(context.taskId, context.steps, call,
                    (screen ?: hands.snapshot()).screenSignature, edited.toMap())
                when (val decision = gate.request(card, binding)) {
                    is GateResult.Approved -> {
                        pending.remove(pendingId)
                        pending[decision.token.id] = id to risk
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
            if (pending[approval.id] != (id to risk) || approval.id in usedTokens ||
                context.taskId.isBlank() || approval.taskId != context.taskId ||
                approval.step != context.steps ||
                approval.call != call || approval.issuedAtMs > clock.monotonicMs() ||
                approval.expiresAtMs <= clock.monotonicMs() || approval.expiresAtMs - approval.issuedAtMs > 30_000 ||
                (risk == RiskClass.R3 && approval.method != ApprovalMethod.BIOMETRIC_STRONG) ||
                approval.screenSignature != (screen ?: hands.snapshot()).screenSignature ||
                approval.editedFieldContents != edited) return refuse("approval")
            val fresh = hands.snapshot()
            val liveFields = context.editedFieldContents(edited.keys)
            if (fresh.screenSignature != approval.screenSignature ||
                target != null && fresh.nodes.none { it.index == target.second && it.key == target.third &&
                    it.label == node?.label && action in it.actions } ||
                liveFields != edited) return refuse("toctou")
            if (!approvalVerifier.verifyAndConsume(approval,
                ApprovalBinding(context.taskId, context.steps, call, fresh.screenSignature,
                    liveFields),
                clock.monotonicMs())) return refuse("approval")
            usedTokens += approval.id
            pending.remove(approval.id)
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
        is ToolCall.InstallApk -> context.ownerPickedUri(call.uri)
        else -> true
    }

    /** Complete typed arguments for the card and taint check; never stringify a ToolCall for the owner. */
    private fun arguments(call: ToolCall): Map<String, String> = when (call) {
        is ToolCall.ReadScreen -> mapOf("snapshot" to call.snapshotId.toString())
        ToolCall.ScreenshotInternal -> emptyMap()
        ToolCall.ListNotifications -> emptyMap()
        ToolCall.NextAlarm -> emptyMap()
        is ToolCall.CalendarQuery -> mapOf("query" to call.query, "fromMs" to call.fromMs.toString(),
            "toMs" to call.toMs.toString())
        is ToolCall.AskOwner -> mapOf("question" to call.question)
        is ToolCall.Finish -> mapOf("answer" to call.answer)
        is ToolCall.Wait -> mapOf("ms" to call.ms.toString())
        is ToolCall.SendSms -> mapOf("number" to call.number, "text" to call.text)
        is ToolCall.Call -> mapOf("number" to call.number)
        is ToolCall.ReplyNotification -> mapOf("notification" to call.key, "text" to call.text)
        is ToolCall.NotificationAction -> mapOf("notification" to call.key, "action" to call.actionIndex.toString())
        is ToolCall.SetText -> mapOf("snapshot" to call.snapshotId.toString(),
            "element" to call.elementIndex.toString(), "key" to call.elementKey.hash.toString(),
            "target" to call.label, "text" to call.text)
        is ToolCall.Click -> mapOf("snapshot" to call.snapshotId.toString(),
            "element" to call.elementIndex.toString(), "key" to call.elementKey.hash.toString(), "target" to call.label)
        is ToolCall.LongClick -> mapOf("snapshot" to call.snapshotId.toString(),
            "element" to call.elementIndex.toString(), "key" to call.elementKey.hash.toString(), "target" to call.label)
        is ToolCall.Scroll -> mapOf("snapshot" to call.snapshotId.toString(),
            "element" to call.elementIndex.toString(), "key" to call.elementKey.hash.toString(),
            "direction" to call.direction.name)
        ToolCall.Back, ToolCall.Home, ToolCall.Recents, ToolCall.Notifications,
        ToolCall.QuickSettings, ToolCall.DismissShade, ToolCall.LockScreen -> emptyMap()
        is ToolCall.Media -> mapOf("action" to call.action.name)
        is ToolCall.InstallApk -> mapOf("uri" to call.uri)
        is ToolCall.Uninstall -> mapOf("package" to call.packageName)
        is ToolCall.HideApp -> mapOf("package" to call.packageName)
        is ToolCall.SuspendApp -> mapOf("package" to call.packageName)
        is ToolCall.SetPermission -> mapOf("package" to call.packageName, "permission" to call.permission,
            "state" to call.grantState.name)
        is ToolCall.CalendarInsert -> mapOf("title" to call.title, "attendees" to call.attendees.joinToString(),
            "location" to call.location.orEmpty(), "beginMs" to call.beginMs.toString(),
            "endMs" to call.endMs.toString())
        is ToolCall.CalendarDelete -> mapOf("eventId" to call.eventId.toString())
        is ToolCall.LaunchApp -> mapOf("package" to call.packageName)
        is ToolCall.SetAlarm -> mapOf("time" to "${call.hour}:${call.minute}",
            "label" to call.label.orEmpty(), "days" to call.daysOfWeek.joinToString())
        is ToolCall.SetTimer -> mapOf("durationMs" to call.durationMs.toString(), "label" to call.label.orEmpty())
        is ToolCall.DismissAlarm -> mapOf("triggerTimeMs" to call.triggerTimeMs.toString())
        is ToolCall.Torch -> mapOf("on" to call.on.toString())
        ToolCall.HeadsetHook, ToolCall.Reboot -> emptyMap()
    }
}
