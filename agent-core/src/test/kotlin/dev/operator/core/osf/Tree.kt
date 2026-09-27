package dev.operator.core.osf

import dev.operator.core.api.Bounds
import dev.operator.core.api.ElementKey
import dev.operator.core.api.NodeAction
import dev.operator.core.api.Role
import dev.operator.core.api.Snapshot
import dev.operator.core.api.UiNode
import dev.operator.core.api.WindowInfo
import dev.operator.core.api.WindowType

/** Node/window builders shared by the osf tests; every snapshot is built through [OsfSnapshots]. */
object Tree {

    const val PKG = "com.example.app"
    const val OPERATOR = "dev.operator"

    fun key(
        label: String,
        role: Role = Role.BTN,
        viewId: String? = null,
        pkg: String = PKG,
        windowType: WindowType = WindowType.APPLICATION,
        row: Int? = null,
        column: Int? = null,
    ): ElementKey = OsfKeys.elementKey(pkg, windowType, null, viewId, role, emptyList(), row, column, label)

    fun node(
        key: ElementKey,
        label: String = "",
        role: Role = Role.BTN,
        depth: Int = 1,
        parent: Int? = null,
        children: List<Int> = emptyList(),
        actions: Set<NodeAction> = setOf(NodeAction.CLICK),
        bounds: Bounds = Bounds(0, 0, 100, 100),
        windowId: Int = 1,
        pkg: String = PKG,
        viewId: String? = null,
        className: String? = null,
        state: Set<dev.operator.core.api.NodeState> = emptySet(),
    ): UiNode = UiNode(
        index = 0,
        key = key,
        windowId = windowId,
        packageName = pkg,
        role = role,
        label = label,
        className = className,
        viewId = viewId,
        uniqueId = null,
        bounds = bounds,
        depth = depth,
        parentIndex = parent,
        children = children,
        actions = actions,
        state = state,
        row = null,
        column = null,
        windowTitle = null,
    )

    fun window(
        id: Int = 1,
        type: WindowType = WindowType.APPLICATION,
        pkg: String = PKG,
        title: String? = "App",
        active: Boolean = true,
        layer: Int = 0,
        rootNodeIndex: Int? = 0,
    ): WindowInfo = WindowInfo(id, type, layer, pkg, title, active, rootNodeIndex)

    fun snapshot(
        nodes: List<UiNode>,
        windows: List<WindowInfo> = listOf(window()),
        id: Long = 1,
        pkg: String = PKG,
        keyboardUp: Boolean = false,
        focusedNumber: Int? = null,
        prev: Snapshot? = null,
    ): Snapshot = OsfSnapshots.build(
        id = id,
        capturedAtMs = id * 1_000L,
        foregroundPackage = pkg,
        windows = windows,
        nodes = nodes,
        keyboardUp = keyboardUp,
        focusedNumber = focusedNumber,
        prev = prev,
    )
}
