package dev.operator.core.policy

import dev.operator.core.api.*

/** Owner-selected capabilities and host facts. Never derive these from screen text. */
interface PolicyContext {
    val allowedTools: Set<String>
    val allowedPackages: Set<String>
    val ownerUtterance: String
    val halted: Boolean
    val steps: Int
    fun installed(pkg: String): Boolean
    fun sensitive(pkg: String): Boolean = SensitivePackages.matches(pkg)
    fun highRisk(pkg: String): Boolean = listOf("messag", "sms", "mail", "social", "settings", "browser")
        .any { it in pkg.lowercase() }
    fun knownNumber(number: String): Boolean
    /** A trusted gate implementation must authenticate the token's nonce/MAC, not just its fields. */
    fun authentic(token: ApprovalToken): Boolean
    /** Host-only classifier; can only raise the class. */
    fun irreversible(call: ToolCall): Boolean = false
}

object SensitivePackages {
    private val seeds = setOf(
        "dev.operator", "com.android.permissioncontroller", "com.google.android.permissioncontroller",
        "com.google.android.packageinstaller", "com.android.packageinstaller",
        "com.android.vending", "com.google.android.apps.authenticator2",
        "com.google.android.apps.walletnfcrel", "com.paypal.android.p2pmobile",
    )
    private val keywords = Regex("bank|pay|auth|pass", RegexOption.IGNORE_CASE)
    fun matches(pkg: String): Boolean = seeds.any { pkg == it || pkg.startsWith("$it.") } ||
        keywords.containsMatchIn(pkg)
}

/** Observations are data, not a source of goals or policy changes. */
data class ObservedText(val packageName: String, val text: String)

class TaintTracker {
    private val observations = mutableListOf<ObservedText>()
    private val fields = mutableMapOf<ElementKey, Taint>()
    fun observe(source: ObservedText) { observations += source }
    fun field(key: ElementKey): Taint? = fields[key]
    fun setField(key: ElementKey, value: String, taint: Taint?) {
        if (value.isEmpty() || taint == null) fields.remove(key) else fields[key] = taint
    }
    fun detect(text: String, owner: String): Taint? {
        val normal = normalize(text)
        val owned = normalize(owner)
        val match = observations.firstNotNullOfOrNull { source ->
            val seen = normalize(source.text)
            val long = if (normal.length >= 8) (0..normal.length - 8).firstOrNull {
                val span = normal.substring(it, it + 8)
                seen.contains(span) && !owned.contains(span)
            }?.let { normal.substring(it, it + 8) } else null
            val digits = Regex("\\d{4,}").findAll(normal).firstOrNull {
                seen.contains(it.value) && !owned.contains(it.value)
            }?.value
            val code = Regex("\\b(?=[A-Z0-9]*\\d)[A-Z0-9]{4,8}\\b").findAll(text.uppercase())
                .firstOrNull { seen.contains(normalize(it.value)) && !owned.contains(normalize(it.value)) }?.value
            (long ?: digits ?: code)?.let { Taint(source.packageName, listOf(it)) }
        }
        return match
    }
    private fun normalize(value: String) = value.uppercase().filter { it.isLetterOrDigit() }
}

object UiRisk {
    private val words = Regex(
        "\\b(send|senden|pay|bezahlen|kaufen|buy|order|bestellen|delete|löschen|remove|entfernen|confirm|bestätigen|install|installieren|allow|zulassen|transfer|überweisen|post|posten|publish|veröffentlichen|submit|absenden|sign|unterschreiben|accept|annehmen|ok|yes|ja|continue|weiter|done|fertig|apply|anwenden|reply|antworten|share|teilen|forward|weiterleiten|archive|archivieren|discard|verwerfen|clear|leeren|erase|reset|zurücksetzen|block|blockieren|report|melden|unsubscribe|abbestellen|sign out|log out|abmelden|force stop|beenden erzwingen|uninstall|deinstallieren|disable|deaktivieren|leave|verlassen|end|beenden)\\b",
        RegexOption.IGNORE_CASE,
    )
    private val navigation = Regex("^(back|up|close|more options|overflow|expand|collapse|navigate up)$", RegexOption.IGNORE_CASE)
    fun submit(node: UiNode): Boolean = Regex("send|submit|search|go|enter|suggest|form|reply", RegexOption.IGNORE_CASE)
        .containsMatchIn(listOfNotNull(node.label, node.viewId).joinToString(" "))
    fun browser(snapshot: Snapshot): Boolean = snapshot.nodes.any { it.role == Role.WEB } &&
        snapshot.nodes.any { it.role == Role.EDIT } ||
        snapshot.foregroundPackage.contains("browser", true)
    fun risk(node: UiNode, snapshot: Snapshot, tainted: Boolean, highRisk: Boolean = false): Boolean {
        val text = listOfNotNull(node.label, node.viewId, node.className).joinToString(" ")
        val dialog = snapshot.windows.any { it.id == node.windowId && it.title?.contains("dialog", true) == true } ||
            node.windowTitle?.contains("dialog", true) == true
        val web = browser(snapshot)
        if (tainted && submit(node) || web && submit(node)) return true
        if (words.containsMatchIn(text) || node.viewId == "android:id/button1" ||
            text.contains("share target", true) || text.contains("reaction", true) ||
            text.contains("smart reply", true) || text.contains("send_icon", true)) return true
        if (dialog && node.role == Role.BTN) return true
        val nav = navigation.matches(node.label) || node.role == Role.TAB ||
            node.role == Role.EDIT && node.actions.contains(NodeAction.FOCUS) ||
            node.actions.any { it in setOf(NodeAction.EXPAND, NodeAction.COLLAPSE) }
        return (dialog || web || highRisk || snapshot.foregroundPackage.contains("settings", true) ||
            listOf("messag", "sms", "mail", "social").any { it in snapshot.foregroundPackage.lowercase() }) && !nav
    }
}

class RateLimits(private val clock: Clock) {
    private val events = mutableListOf<Triple<String, String, Long>>()
    fun refusal(call: ToolCall, ui: Boolean, irreversible: Boolean): String? {
        val now = clock.monotonicMs()
        val limits = buildList {
            if (ui) add(Triple("ui", 3, 1_000L))
            if (call is ToolCall.SendSms) { add(Triple("sms", 5, 3_600_000L)); add(Triple("sms", 20, 86_400_000L)) }
            if (call is ToolCall.Call) add(Triple("call", 5, 3_600_000L))
            if (call is ToolCall.InstallApk || call is ToolCall.Uninstall) add(Triple("install", 3, 86_400_000L))
            if (call is ToolCall.SetPermission || call is ToolCall.HideApp || call is ToolCall.SuspendApp)
                add(Triple("device_policy", 10, 86_400_000L))
            if (irreversible) add(Triple("same", 1, 60_000L))
        }
        return limits.firstOrNull { (scope, count, window) ->
            events.count { it.first == scope && (scope != "same" || it.second == call.toString()) &&
                now >= it.third && now - it.third < window } >= count
        }?.let { "rate_${it.first}_${it.third}" }
    }
    fun record(call: ToolCall, ui: Boolean, irreversible: Boolean) {
        val now = clock.monotonicMs()
        if (ui) events += Triple("ui", call.toString(), now)
        when (call) {
            is ToolCall.SendSms -> events += Triple("sms", call.toString(), now)
            is ToolCall.Call -> events += Triple("call", call.toString(), now)
            is ToolCall.InstallApk, is ToolCall.Uninstall -> events += Triple("install", call.toString(), now)
            is ToolCall.SetPermission, is ToolCall.HideApp, is ToolCall.SuspendApp ->
                events += Triple("device_policy", call.toString(), now)
            else -> Unit
        }
        if (irreversible) events += Triple("same", call.toString(), now)
    }
}
