package dev.operator.core.osf

import dev.operator.core.api.NodeAction
import dev.operator.core.api.NodeState
import dev.operator.core.api.Role
import dev.operator.core.api.Snapshot
import dev.operator.core.api.UiNode
import dev.operator.core.api.WindowInfo
import dev.operator.core.api.WindowType

/*
 * The OSF v0 serializer (FOUNDATION §6.1–§6.3, ADR-0008): Snapshot/UiNode → the model's screen
 * text, the CHANGES block, and nothing else. Pure Kotlin, no Android types; the same output runs
 * on live nodes and recorded trees (ADR-0008 item 9).
 *
 * Division of labour: the reader (S7) merges leaves, assigns roles and keys, and builds the
 * frozen Snapshot; this class applies §6.1 rules 1–11 to the text: window exclusion, own
 * notification rows, OTP redaction, escaping, truncation, flags, the `?` fallback, the sensitive
 * placeholder, numbering rendering (numbers come sticky-assigned from [StickyNumbering] via
 * [OsfSnapshots]) and the token budget.
 */

/** One of operator's own notifications, matched against shade and heads-up rows (§6.1 rule 1, R3). */
data class OwnNotification(val appName: String?, val title: String?, val text: String?)

class OsfSerializer(
    /** App labels come from PackageManager on device (§6.2); pure input here. */
    private val appLabels: Map<String, String> = emptyMap(),
    /** Policy input (§6.1 rule 8): packages whose windows render as `[hidden: sensitive app]`. */
    private val denylistedPackages: Set<String> = emptySet(),
    /** §6.1 rule 1: operator's own windows never reach the model. */
    private val operatorPackage: String = "dev.operator",
    /** §6.1 rule 1: operator's active notifications; matching rows and their subtrees are dropped. */
    private val ownNotifications: List<OwnNotification> = emptyList(),
    /** §6.1 rule 9 budgets; INFERENCE, tuned by 04-M1-2 and 04-M1-4. */
    private val softTokenBudget: Int = 800,
    private val hardTokenBudget: Int = 1500,
    /** Deterministic stand-in for the model tokenizer until S1's is wired in. */
    private val tokenEstimator: (String) -> Int = OsfSerializer::defaultTokenEstimate,
) {
    companion object {
        fun defaultTokenEstimate(text: String): Int = (text.length + 3) / 4

        private val DROPPED_WINDOW_TYPES =
            setOf(WindowType.ACCESSIBILITY_OVERLAY, WindowType.INPUT_METHOD, WindowType.SPLIT_SCREEN_DIVIDER)
        private val HEADER_ROLES = setOf(Role.LIST, Role.WEB, Role.MENU)
        private val CHECKABLE_ROLES = setOf(Role.CHK, Role.RADIO, Role.SWITCH)
        private const val HIDDEN_LINE = "[hidden: sensitive app]"
        private const val MAX_DIFF_LINES = 10
    }

    // --------------------------------------------------------------------- screen

    /** The §6.2 `SCREEN … END` block for [snapshot]. */
    fun screen(snapshot: Snapshot): String {
        val view = View(snapshot)
        val lines = ArrayList<Line>(snapshot.nodes.size + 4)
        lines.add(Line(screenHeader(snapshot, view.primary), protectedFromTrim = true))

        for (w in view.keptWindows) {
            if (w.packageName in denylistedPackages) {
                // §6.1 rule 8: the window and everything identifying its subpage are omitted,
                // only the placeholder is rendered.
                lines.add(Line(HIDDEN_LINE, protectedFromTrim = true))
                continue
            }
            if (w.rootNodeIndex == null) continue
            if (view.primary != null && w.id != view.primary.id) lines.add(Line(windowHeader(w), protectedFromTrim = true))
            for ((pos, node) in view.visible) {
                if (node.windowId != w.id) continue
                lines.add(elementLine(node, snapshot.nodes, pos) ?: continue)
            }
        }
        lines.add(Line("END", protectedFromTrim = true))
        return applyBudget(lines, snapshot)
    }

    // --------------------------------------------------------------------- changes

    /**
     * The §6.1 rule 11 `CHANGES` block after an action: `+` new, `-` gone, `~` changed label or
     * flags, matched by element key, at most [MAX_DIFF_LINES] lines then `…`.
     *
     * Review fix 1: the diff sees exactly what screen() sees — the same [View] filters (kept
     * windows, denylist, own-notification rows) apply to both snapshots.
     */
    fun changes(prev: Snapshot, curr: Snapshot, verb: String, actedNumber: Int? = null): String {
        val header = StringBuilder("CHANGES s").append(prev.id).append("->s").append(curr.id)
            .append(" after ").append(verb)
        actedNumber?.let { header.append(" [").append(it).append("]") }
        header.append(":")

        val prevView = View(prev)
        val currView = View(curr)
        val prevByKey = HashMap<dev.operator.core.api.ElementKey, UiNode>(prevView.visible.size)
        for ((_, n) in prevView.visible) if (n.key !in prevByKey) prevByKey[n.key] = n
        val currKeys = currView.visible.mapTo(HashSet()) { it.second.key }

        val body = ArrayList<String>()
        for ((pos, node) in currView.visible) {
            val old = prevByKey[node.key]
            if (old == null) {
                elementLine(node, curr.nodes, pos)?.let { body.add("+" + it.text) }
                continue
            }
            val newLabel = diffLabel(node)
            val oldLabel = diffLabel(old)
            if (newLabel != oldLabel) {
                // Review fix 2: a password field's text never renders, in either state.
                val newShown =
                    if (NodeState.PASSWORD in node.state) passwordMarker(node)
                    else "\"${renderPart(newLabel, OsfText.MAX_EDIT_CHARS)}\""
                val oldShown =
                    if (NodeState.PASSWORD in old.state) passwordMarker(old)
                    else "\"${renderPart(oldLabel, OsfText.MAX_EDIT_CHARS)}\""
                body.add("~[${node.index}] ${roleToken(node.role)} $newShown (was $oldShown)")
            }
            flagDelta(old, node)?.let { delta ->
                body.add("~[${node.index}] ${roleToken(node.role)}${flagLineLabelPart(node)} $delta")
            }
        }
        for ((pos, old) in prevView.visible) {
            if (old.key !in currKeys) {
                elementLine(old, prev.nodes, pos)?.let { body.add("-" + it.text) }
            }
        }

        val shown = body.take(MAX_DIFF_LINES)
        return (listOf(header.toString()) + shown + (if (body.size > MAX_DIFF_LINES) listOf("…") else emptyList()))
            .joinToString("\n")
    }

    // --------------------------------------------------------------------- headers

    private fun screenHeader(snapshot: Snapshot, primary: WindowInfo?): String {
        // Review fix 7: the header names the top kept window only — an excluded foreground window
        // (the operator gate, an overlay) is never named, and with no kept window at all, no
        // package, app or title is emitted.
        val sb = StringBuilder("SCREEN s").append(snapshot.id)
        if (primary != null) {
            val denyPrimary = primary.packageName in denylistedPackages
            sb.append(" app=\"").append(escapeTitle(appLabels[primary.packageName] ?: primary.packageName)).append("\"")
            sb.append(" pkg=").append(primary.packageName)
            sb.append(" win=").append(windowToken(primary.type))
            if (!denyPrimary) {
                primary.title?.let { sb.append(" title=\"").append(escapeTitle(it)).append("\"") }
            }
        }
        if (snapshot.keyboardUp) sb.append(" kbd=up")
        snapshot.focusedIndex?.let { sb.append(" focus=").append(it) }
        return sb.toString()
    }

    private fun windowHeader(w: WindowInfo): String {
        val sb = StringBuilder("WINDOW")
        sb.append(" app=\"").append(escapeTitle(appLabels[w.packageName] ?: w.packageName)).append("\"")
        sb.append(" pkg=").append(w.packageName)
        sb.append(" win=").append(windowToken(w.type))
        w.title?.let { sb.append(" title=\"").append(escapeTitle(it)).append("\"") }
        return sb.toString()
    }

    private fun escapeTitle(raw: String): String =
        OsfText.escape(OsfText.truncate(OsfText.redactOtp(raw), OsfText.MAX_LABEL_CHARS))

    private fun windowToken(type: WindowType): String = when (type) {
        WindowType.APPLICATION -> "app"
        WindowType.SYSTEM -> "system"
        WindowType.OTHER -> "other"
        WindowType.INPUT_METHOD -> "ime"
        WindowType.ACCESSIBILITY_OVERLAY -> "overlay"
        WindowType.SPLIT_SCREEN_DIVIDER -> "divider"
    }

    // --------------------------------------------------------------------- element lines

    private fun elementLine(node: UiNode, nodes: List<UiNode>, pos: Int): Line? {
        val labelled = !node.label.isBlank()
        val actionable = node.actions.isNotEmpty()
        if (!labelled && !actionable && node.children.isEmpty() && node.role !in HEADER_ROLES) return null

        val sb = StringBuilder()
        if (node.depth > 1) sb.append("  ".repeat(node.depth - 1))
        sb.append('[').append(node.index).append("] ").append(roleToken(node.role))

        if (NodeState.PASSWORD in node.state) {
            // §6.1 rule 5: password nodes are emitted without content.
            sb.append(" password")
        } else when (node.role) {
            Role.EDIT -> {
                val parts = OsfText.labelParts(node.label)
                val hint = if (parts.size == 2 && parts[0].isEmpty()) parts[1] else null
                val content = if (hint != null) "" else node.label
                sb.append(" \"").append(renderPart(content, OsfText.MAX_EDIT_CHARS)).append("\"")
                if (hint != null) sb.append(" hint=\"").append(renderPart(hint, OsfText.MAX_LABEL_CHARS)).append("\"")
            }
            else -> {
                if (labelled) {
                    sb.append(" ")
                    sb.append(OsfText.labelParts(node.label).joinToString(MERGE_SEPARATOR) { "\"${renderPart(it, OsfText.MAX_LABEL_CHARS)}\"" })
                } else if (actionable && node.role !in HEADER_ROLES) {
                    // §6.1 rule 7: `btn ?`, with a hint derived from the view id. Header roles
                    // (list/web/menu) carry their state in flags, not a `?` label (§6.2 [5]).
                    val hint = OsfText.hintFromViewId(node.viewId)
                    sb.append(" \"").append(if (hint != null) "?$hint" else "?").append("\"")
                }
            }
        }
        appendFlags(sb, node)
        val header = node.role in HEADER_ROLES
        return Line(
            sb.toString(),
            protectedFromTrim = node.role == Role.EDIT || node.role == Role.BTN || header,
            nodePos = pos,
            listParentPos = node.parentIndex?.takeIf { it in nodes.indices && nodes[it].role == Role.LIST },
        )
    }

    /** §6.1 rule 6, in the order of [04§R1.6]: disabled checked/unchecked selected focused scroll more. */
    private fun appendFlags(sb: StringBuilder, node: UiNode) {
        val st = node.state
        if (NodeState.DISABLED in st) sb.append(" disabled")
        when {
            node.role in CHECKABLE_ROLES -> sb.append(if (NodeState.CHECKED in st) " checked" else " unchecked")
            NodeState.CHECKED in st -> sb.append(" checked")
        }
        if (NodeState.SELECTED in st) sb.append(" selected")
        if (NodeState.FOCUSED in st) sb.append(" focused")
        if (node.role == Role.LIST) {
            val forward = NodeAction.SCROLL_FORWARD in node.actions
            val backward = NodeAction.SCROLL_BACKWARD in node.actions
            if (forward || backward) {
                val b = node.bounds
                val vertical = (b.bottom - b.top) >= (b.right - b.left)
                sb.append(" scroll=").append(if (vertical) "v" else "h")
                sb.append(" more=").append(
                    when {
                        forward && backward -> "both"
                        backward -> "above"
                        else -> "below"
                    },
                )
            }
        }
    }

    private fun roleToken(role: Role): String = role.name.lowercase()

    private fun renderPart(raw: String, maxChars: Int): String =
        OsfText.escape(OsfText.truncate(OsfText.redactOtp(raw), maxChars))

    // --------------------------------------------------------------------- diff helpers

    /** The label a CHANGES line compares and shows: `edit` content (a hint is not a change), else the label. */
    private fun diffLabel(node: UiNode): String {
        if (node.role == Role.EDIT) {
            val parts = OsfText.labelParts(node.label)
            return if (parts.size == 2 && parts[0].isEmpty()) "" else node.label
        }
        return node.label
    }

    /** Review fix 2: only the length of a password field's content is model-visible. */
    private fun passwordMarker(n: UiNode): String = "(password field, ${n.label.length} chars)"

    private fun flagLineLabelPart(node: UiNode): String {
        if (NodeState.PASSWORD in node.state) return ""
        val label = diffLabel(node)
        if (label.isBlank()) return ""
        return " \"${renderPart(label, OsfText.MAX_LABEL_CHARS)}\""
    }

    private fun flagDelta(old: UiNode, new: UiNode): String? {
        val deltas = ArrayList<String>(2)
        fun delta(had: Boolean, has: Boolean, presentWord: String, absentWord: String) {
            if (had == has) return
            val now = if (has) presentWord else absentWord
            val was = if (had) presentWord else absentWord
            deltas.add("$now (was $was)")
        }
        delta(NodeState.DISABLED in old.state, NodeState.DISABLED in new.state, "disabled", "enabled")
        delta(NodeState.CHECKED in old.state, NodeState.CHECKED in new.state, "checked", "unchecked")
        delta(NodeState.SELECTED in old.state, NodeState.SELECTED in new.state, "selected", "deselected")
        delta(NodeState.FOCUSED in old.state, NodeState.FOCUSED in new.state, "focused", "unfocused")
        delta(NodeState.EXPANDED in old.state, NodeState.EXPANDED in new.state, "expanded", "collapsed")
        return if (deltas.isEmpty()) null else deltas.joinToString(", ")
    }

    // --------------------------------------------------------------------- shared view (review fix 1)

    /**
     * One filtered view of a snapshot, feeding both screen() and changes(): kept windows
     * (excluded window types and operator's own package dropped), denylisted windows held apart
     * for the placeholder, and own-notification rows removed from the node list.
     */
    private inner class View(val snapshot: Snapshot) {
        val keptWindows = snapshot.windows.filter {
            it.type !in DROPPED_WINDOW_TYPES && it.packageName != operatorPackage
        }
        val primary: WindowInfo? = keptWindows.firstOrNull { it.active } ?: keptWindows.firstOrNull()
        private val denylistedIds = keptWindows.filter { it.packageName in denylistedPackages }.mapTo(HashSet()) { it.id }
        private val renderedIds: Set<Int> = keptWindows.mapTo(HashSet()) { it.id } - denylistedIds
        private val dropped: Set<Int> = ownNotificationSubtrees(snapshot)

        /** (position, node) pairs the model may see, in emission order. */
        val visible: List<Pair<Int, UiNode>> = snapshot.nodes.withIndex()
            .filter { (i, n) -> n.windowId in renderedIds && i !in dropped }
            .map { it.index to it.value }
    }

    // --------------------------------------------------------------------- own notifications

    /**
     * §6.1 rule 1, review fix 3: shade and heads-up rows matching one of operator's active
     * notifications are dropped whole — a match climbs to its containing row (the highest
     * ancestor strictly below the window root or a list node), so the row's action buttons and
     * inline reply field die with it. Any match drops (ambiguous matches fail safe).
     */
    private fun ownNotificationSubtrees(snapshot: Snapshot): Set<Int> {
        if (ownNotifications.isEmpty()) return emptySet()
        val matchers = ownNotifications
            .flatMap { listOfNotNull(it.title?.trim(), it.text?.trim()) }
            .filter { it.isNotEmpty() }
            .toHashSet()
        if (matchers.isEmpty()) return emptySet()

        val nodes = snapshot.nodes
        val childrenOf = HashMap<Int, MutableList<Int>>()
        nodes.forEachIndexed { i, n -> n.parentIndex?.let { childrenOf.getOrPut(it) { ArrayList() }.add(i) } }
        val rootOf = HashMap<Int, Int?>()
        for (w in snapshot.windows) rootOf[w.id] = w.rootNodeIndex

        val dropped = HashSet<Int>()
        val stack = ArrayDeque<Int>()
        nodes.forEachIndexed { i, n ->
            if (OsfText.labelParts(n.label).any { it.trim() in matchers }) {
                stack.addLast(rowTop(nodes, i, rootOf[n.windowId]))
            }
        }
        while (stack.isNotEmpty()) {
            val i = stack.removeLast()
            if (!dropped.add(i)) continue
            childrenOf[i]?.let { stack.addAll(it) }
        }
        return dropped
    }

    /** The containing row of [pos]: the highest ancestor strictly below the window root or a list. */
    private fun rowTop(nodes: List<UiNode>, pos: Int, rootPos: Int?): Int {
        var top = pos
        var p = nodes[pos].parentIndex
        while (p != null && p in nodes.indices) {
            if (p == rootPos) break
            if (nodes[p].role == Role.LIST) break
            top = p
            p = nodes[p].parentIndex
        }
        return top
    }

    // --------------------------------------------------------------------- budget (§6.1 rule 9)

    private data class Line(
        val text: String,
        val protectedFromTrim: Boolean,
        val nodePos: Int? = null,
        val listParentPos: Int? = null,
    )

    /**
     * Deterministic truncation order: list children first (last child of the largest list),
     * replaced by `… N more items (scroll [K])`; window headers, dialog buttons and `edit` fields
     * are never trimmed (buttons and headers are protected wholesale — over-protecting ordinary
     * buttons fails toward the design). Then, only if the hard budget is still exceeded, plain
     * element lines from the bottom. Last resort: header + `… truncated` + END.
     */
    private fun applyBudget(lines: List<Line>, snapshot: Snapshot): String {
        val removed = HashSet<Int>()
        val trimmedByList = LinkedHashMap<Int, MutableList<Line>>()

        fun currentText(): String {
            val markers = HashMap<Int, String>()
            for ((parentPos, removedLines) in trimmedByList) {
                val keptIdx = lines.indices.filter { lines[it].listParentPos == parentPos && it !in removed }
                val anchor = keptIdx.maxOrNull()
                    ?: lines.indexOfFirst { it.nodePos == parentPos } // the list line itself
                val depth = ((removedLines.first().nodePos?.let { snapshot.nodes[it].depth } ?: 2) - 1).coerceAtLeast(0)
                markers[anchor] = "  ".repeat(depth) + "… " + removedLines.size + " more items (scroll [" +
                    snapshot.nodes[parentPos].index + "])"
            }
            val out = ArrayList<String>(lines.size)
            for (i in lines.indices) {
                if (i in removed) continue
                out.add(lines[i].text)
                markers[i]?.let { out.add(it) }
            }
            return out.joinToString("\n")
        }

        var text = currentText()

        while (tokenEstimator(text) > softTokenBudget) {
            val candidates = lines.indices
                .filter { it !in removed && lines[it].listParentPos != null && !lines[it].protectedFromTrim }
            if (candidates.isEmpty()) break
            val byList = candidates.groupBy { lines[it].listParentPos!! }
            val (parentPos, group) = byList.entries.sortedWith(compareBy({ -it.value.size }, { it.key })).first()
            val victimIdx = group.maxBy { lines[it].nodePos!! }
            removed.add(victimIdx)
            trimmedByList.getOrPut(parentPos) { ArrayList() }.add(lines[victimIdx])
            text = currentText()
        }

        while (tokenEstimator(text) > hardTokenBudget) {
            val idx = lines.indices.lastOrNull {
                it !in removed && !lines[it].protectedFromTrim && lines[it].listParentPos == null
            } ?: break
            removed.add(idx)
            text = currentText()
        }

        if (tokenEstimator(text) > hardTokenBudget) {
            return lines.first().text + "\n… truncated\nEND"
        }
        return text
    }
}
