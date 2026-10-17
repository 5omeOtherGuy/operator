package dev.operator.hands

import dev.operator.core.api.*

/** Platform-independent boundary around AccessibilityNodeInfo; fakeable in local JVM tests. */
internal interface TreeNode {
    val bounds: Bounds
    val text: String?
    val description: String?
    val hint: String?
    val stateDescription: String?
    val tooltip: String?
    val viewId: String?
    val uniqueId: String?
    val className: String?
    val visible: Boolean
    val enabled: Boolean
    val password: Boolean
    val dataSensitive: Boolean
    val checked: Boolean
    val selected: Boolean
    val focused: Boolean
    val clickable: Boolean
    val longClickable: Boolean
    val editable: Boolean
    val scrollable: Boolean
    val children: List<TreeNode>
}

internal data class TreeWindow(
    val id: Int, val type: WindowType, val layer: Int, val packageName: String,
    val title: String?, val active: Boolean, val own: Boolean, val root: TreeNode?,
)

internal object ScreenTree {
    fun build(id: Long, time: Long, windows: List<TreeWindow>): Snapshot {
        val nodes = mutableListOf<UiNode>()
        val kept = mutableListOf<WindowInfo>()
        var keyboard = false
        var focused: Int? = null
        for (window in windows.sortedByDescending { it.layer }) {
            if (window.type == WindowType.INPUT_METHOD) { keyboard = true; continue }
            if (window.own || window.type == WindowType.ACCESSIBILITY_OVERLAY) continue
            // System bars contribute no task content.
            if (window.title == "Status bar" || window.title == "Navigation bar") continue
            fun visit(node: TreeNode, parent: Int?, depth: Int, path: String): Int? {
                val b = node.bounds
                if (!node.visible || b.right <= b.left || b.bottom <= b.top || node.dataSensitive) return null
                val role = when {
                    node.password || node.editable -> Role.EDIT
                    node.className?.contains("Switch") == true -> Role.SWITCH
                    node.className?.contains("CheckBox") == true -> Role.CHK
                    node.className?.contains("RadioButton") == true -> Role.RADIO
                    node.scrollable -> Role.LIST
                    node.className?.contains("WebView") == true -> Role.WEB
                    node.clickable -> Role.BTN
                    else -> Role.TXT
                }
                val actions = buildSet {
                    if (node.clickable) add(NodeAction.CLICK)
                    if (node.longClickable) add(NodeAction.LONG_CLICK)
                    if (node.editable && !node.password) add(NodeAction.SET_TEXT)
                    if (node.scrollable) { add(NodeAction.SCROLL_FORWARD); add(NodeAction.SCROLL_BACKWARD) }
                }
                val state = buildSet {
                    if (!node.enabled) add(NodeState.DISABLED)
                    if (node.password) add(NodeState.PASSWORD)
                    if (node.checked) add(NodeState.CHECKED)
                    if (node.selected) add(NodeState.SELECTED)
                    if (node.focused) add(NodeState.FOCUSED)
                }
                val label = if (node.password) "" else
                    listOf(node.text, node.description, node.hint, node.stateDescription, node.tooltip)
                        .firstOrNull { !it.isNullOrBlank() }?.take(if (role == Role.EDIT) 200 else 80)
                        ?: if (actions.isNotEmpty()) "?" else ""
                val index = nodes.size
                nodes += UiNode(index, key(window, node, path), window.id, window.packageName, role,
                    label, node.className, node.viewId, node.uniqueId, b, depth, parent, emptyList(),
                    actions, state, null, null, window.title)
                if (node.focused) focused = index
                val children = node.children.mapIndexedNotNull { i, child ->
                    visit(child, index, depth + 1, "$path/$i")
                }
                nodes[index] = nodes[index].copy(children = children)
                return index
            }
            val root = window.root?.let { visit(it, null, 0, "${window.id}") }
            kept += WindowInfo(window.id, window.type, window.layer, window.packageName,
                window.title, window.active, root)
        }
        val foreground = kept.firstOrNull { it.active } ?: kept.firstOrNull()
        val structural = hash(nodes.joinToString("|") { "${it.key.hash}:${it.role}" })
        val full = hash(nodes.joinToString("|") { "${it.key.hash}:${it.label}:${it.state}" })
        val pkg = foreground?.packageName.orEmpty()
        return Snapshot(id, time, pkg, kept, nodes, structural, full, keyboard, focused,
            ScreenSignature(pkg, foreground?.title, structural))
    }

    private fun hash(value: String): Long =
        value.fold(1125899906842597L) { acc, char -> acc * 31 + char.code }

    fun key(window: TreeWindow, node: TreeNode, path: String): ElementKey {
        val role = when {
            node.password || node.editable -> Role.EDIT
            node.className?.contains("Switch") == true -> Role.SWITCH
            node.className?.contains("CheckBox") == true -> Role.CHK
            node.className?.contains("RadioButton") == true -> Role.RADIO
            node.scrollable -> Role.LIST
            node.className?.contains("WebView") == true -> Role.WEB
            node.clickable -> Role.BTN
            else -> Role.TXT
        }
        val label = if (node.password) "" else listOf(node.text, node.description, node.hint,
            node.stateDescription, node.tooltip).firstOrNull { !it.isNullOrBlank() }
            ?.take(if (role == Role.EDIT) 200 else 80) ?: if (node.clickable || node.editable || node.scrollable || node.longClickable) "?" else ""
        return ElementKey(hash("${window.packageName}|${window.type}|${node.uniqueId ?: node.viewId ?: path}|$role|$label"))
    }
}
