package dev.operator.core.policy

import dev.operator.core.api.*

enum class AppCategory { MESSAGING, EMAIL, SOCIAL, BROWSER, SETTINGS, DIALOG_CAPABLE, OTHER }

object AppCategories {
    private val seeds = mapOf(
        "com.google.android.apps.messaging" to AppCategory.MESSAGING,
        "com.android.mms" to AppCategory.MESSAGING,
        "com.whatsapp" to AppCategory.MESSAGING,
        "org.telegram.messenger" to AppCategory.MESSAGING,
        "com.test.messaging" to AppCategory.MESSAGING,
        "com.google.android.gm" to AppCategory.EMAIL,
        "com.microsoft.office.outlook" to AppCategory.EMAIL,
        "com.test.email" to AppCategory.EMAIL,
        "com.instagram.android" to AppCategory.SOCIAL,
        "com.facebook.katana" to AppCategory.SOCIAL,
        "com.test.social" to AppCategory.SOCIAL,
        "com.android.chrome" to AppCategory.BROWSER,
        "org.mozilla.firefox" to AppCategory.BROWSER,
        "com.test.browser" to AppCategory.BROWSER,
        "com.android.settings" to AppCategory.SETTINGS,
    )
    fun resolve(pkg: String, host: AppCategory?): AppCategory? =
        seeds[pkg] ?: host
}

/** Owner-selected capabilities and host facts. Never derive these from screen text. */
interface PolicyContext {
    val allowedTools: Set<String>
    val allowedPackages: Set<String>
    val ownerUtterance: String
    val halted: Boolean
    val steps: Int
    fun installed(pkg: String): Boolean
    fun sensitive(pkg: String): Boolean = SensitivePackages.matches(pkg)
    /** I1 supplies the host's ApplicationInfo.category mapping; null is unknown (R2). */
    fun appCategory(pkg: String): AppCategory? = null
    /** S7 supplies authoritative target metadata, not text inferred from a SystemUI node. */
    fun operatorNotificationTarget(snapshot: Snapshot, node: UiNode): Boolean? = null
    /** S7/S2 supply adjacency when OSF's flat nodes cannot show a filled sibling edit. */
    fun iconBesideFilledEdit(snapshot: Snapshot, node: UiNode): Boolean? = null
    /** S7 reads the *live* contents of all edited fields; null means unavailable, not empty. */
    fun editedFieldContents(keys: Set<ElementKey>): Map<ElementKey, String>? = null
    /** Owner-only picker registry from S7/I1; model-provided URIs never satisfy this by default. */
    fun ownerPickedUri(uri: String): Boolean = false
    fun appLabel(pkg: String): String? = null
    fun screenContext(snapshot: Snapshot, node: UiNode): List<String>? = null
    fun knownNumber(number: String): Boolean
    /** Verify gate-issued MAC over nonce, task, step, canonical typed arguments, screen and edited fields. */
    fun authentic(token: ApprovalToken, canonicalArguments: Map<String, String>): Boolean
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
    fun reset() { observations.clear(); fields.clear() }
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
    fun browser(snapshot: Snapshot, category: AppCategory?): Boolean =
        category == AppCategory.BROWSER ||
            snapshot.nodes.any { it.role == Role.WEB } && snapshot.nodes.any { it.role == Role.EDIT }
    fun risk(node: UiNode, snapshot: Snapshot, tainted: Boolean, category: AppCategory?,
             besideFilledEdit: Boolean?): Boolean {
        if (category == null) return true
        val ancestors = generateSequence(node.parentIndex) { index ->
            snapshot.nodes.firstOrNull { it.index == index }?.parentIndex
        }.take(snapshot.nodes.size).mapNotNull { index -> snapshot.nodes.firstOrNull { it.index == index } }
        val text = (sequenceOf(node) + ancestors).flatMap {
            sequenceOf(it.label, it.viewId.orEmpty(), it.className.orEmpty())
        }.joinToString(" ")
        val dialog = snapshot.windows.any { it.id == node.windowId && it.title?.contains("dialog", true) == true } ||
            node.windowTitle?.contains("dialog", true) == true
        val web = browser(snapshot, category)
        if (tainted && submit(node) || web && submit(node)) return true
        if (words.containsMatchIn(text) || node.viewId == "android:id/button1" ||
            text.contains("share target", true) || text.contains("reaction", true) ||
            text.contains("smart reply", true) || text.contains("send_icon", true) ||
            node.label.isBlank() && besideFilledEdit != false) return true
        if (dialog && node.role == Role.BTN) return true
        val nav = navigation.matches(node.label) || node.role == Role.TAB ||
            node.role == Role.EDIT && node.actions.contains(NodeAction.FOCUS) ||
            node.actions.any { it in setOf(NodeAction.EXPAND, NodeAction.COLLAPSE) }
        return (dialog || web || category != AppCategory.OTHER) && !nav
    }
}

class RateLimits(private val clock: Clock) {
    private val events = mutableListOf<Triple<String, String, Long>>()
    val storedEvents: Int get() = events.size
    private fun prune(now: Long) { events.removeAll { now < it.third || now - it.third >= 86_400_000L } }
    fun refusal(call: ToolCall, ui: Boolean, irreversible: Boolean): String? {
        val now = clock.monotonicMs()
        prune(now)
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
        prune(now)
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
