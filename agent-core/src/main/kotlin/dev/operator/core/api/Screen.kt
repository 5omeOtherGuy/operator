package dev.operator.core.api

/*
 * Frozen M1 API (`dev.operator.core.api`) — screen observation types.
 *
 * Design: FOUNDATION §6.1 (rules 1-11), §6.2 (worked example), §6.3 (element identity and hashes),
 * §7.4 step 3-5 (the executor validates against a snapshot), §9.2 item 5 (the approval token binds
 * the screen signature).
 *
 * These are the types the OSF v0 serializer (S2) and the hands (S7) exchange. Bounds and element
 * keys are host-side: they never reach the model (§6.1 rule 6, §7.1). This file holds data types
 * only — the reader that fills them is S7 and the serializer over them is S2.
 */

/** Screen-pixel bounds of a node. Host-side only: "no coordinates" in OSF (§6.1 rule 6). */
data class Bounds(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
)

/**
 * Stable element identity, §6.3: hash(package, window type, uniqueId or viewId, role, id-ancestor
 * path, CollectionItemInfo row/col, normalised label). The hash function itself belongs to S2/S7.
 */
@JvmInline
value class ElementKey(val hash: Long)

/**
 * OSF role tokens, §6.1 rule 4: `btn txt edit switch chk radio tab list web link img menu seek`.
 * Layout containers are never emitted (§6.1 rule 3), so there is no container role.
 */
enum class Role { BTN, TXT, EDIT, SWITCH, CHK, RADIO, TAB, LIST, WEB, LINK, IMG, MENU, SEEK }

/**
 * Actionable capabilities of a node, as the executor checks them (§7.4 step 4: "the element exists,
 * is visible and enabled, and supports the action"). `ACTION_COPY`, `ACTION_CUT` and `ACTION_PASTE`
 * are F-class and deliberately absent (§7.2, S-08).
 */
enum class NodeAction {
    CLICK, LONG_CLICK, SET_TEXT, SCROLL_FORWARD, SCROLL_BACKWARD,
    FOCUS, EXPAND, COLLAPSE, CHECK, UNCHECK, SELECT, DISMISS,
}

/** Non-default node states only (§6.1 rule 6). `PASSWORD` drives `edit password` and the `type` refusal (§7.1). */
enum class NodeState { CHECKED, EXPANDED, FOCUSED, SELECTED, DISABLED, PASSWORD }

/** Window kinds, mirroring the a11y window types the reader sees (§6.1 rule 1). */
enum class WindowType { APPLICATION, INPUT_METHOD, SYSTEM, ACCESSIBILITY_OVERLAY, SPLIT_SCREEN_DIVIDER, OTHER }

/**
 * A kept window. Operator's own windows and every `ACCESSIBILITY_OVERLAY` window are dropped before
 * the snapshot is built (§6.1 rule 1), the IME becomes [Snapshot.keyboardUp], and the status and
 * navigation bars are dropped; so [WindowType] here is normally `APPLICATION` or `SYSTEM`.
 */
data class WindowInfo(
    val id: Int,
    val type: WindowType,
    val layer: Int,
    val packageName: String,
    val title: String?,
    val active: Boolean,
    /** Index into [Snapshot.nodes] of the window root, null when the window contributed no node. */
    val rootNodeIndex: Int?,
)

/**
 * One OSF element: the fields §6.1 needs (role, label, non-default flags, indentation, key) plus the
 * host-side data the executor uses to act and re-check (§7.4, §9.2 item 5). Lists, dialogs and web
 * roots are emitted as header nodes with [children].
 */
data class UiNode(
    /** Sticky element number for the model, §6.1 rule 10. */
    val index: Int,
    val key: ElementKey,
    val windowId: Int,
    val packageName: String,
    val role: Role,
    /** Label after the §6.1 rule 5 precedence (text > contentDescription > hint > stateDescription > tooltip), escaped and truncated. */
    val label: String,
    val className: String?,
    val viewId: String?,
    val uniqueId: String?,
    val bounds: Bounds,
    val depth: Int,
    val parentIndex: Int?,
    /** Indices of emitted children (merged, actionable or header nodes), §6.1 rule 3. */
    val children: List<Int>,
    val actions: Set<NodeAction>,
    val state: Set<NodeState>,
    /** CollectionItemInfo row/col, part of the key (§6.3). */
    val row: Int?,
    val column: Int?,
    val windowTitle: String?,
)

/** `(package, window title, structural hash)` — the screen signature of §6.3, bound into the approval token (§9.2 item 5). */
data class ScreenSignature(
    val packageName: String,
    val windowTitle: String?,
    val structuralHash: Long,
)

/**
 * One observation of the screen: what the OSF serializer numbers over, and what the executor
 * compares a `ToolCall`'s `snapshotId` against (§7.4 step 3, a stale call returns
 * `Refused("stale")`). [nodes] is flat and in emission order; [UiNode.children] holds indices.
 */
data class Snapshot(
    /** Host-stamped, monotonically increasing. §6.2 `snapshot=s14`. */
    val id: Long,
    /** Capture time, `Clock.wallMs()`. */
    val capturedAtMs: Long,
    val foregroundPackage: String,
    val windows: List<WindowInfo>,
    val nodes: List<UiNode>,
    /** Structural hash: keys + roles (§6.3). */
    val structuralHash: Long,
    /** Full hash: plus labels and flags, with clock text normalised (§6.3). */
    val fullHash: Long,
    /** §6.2 header flag `kbd=up`. */
    val keyboardUp: Boolean,
    /** §6.2 header flag `focus=9`. */
    val focusedIndex: Int?,
    val screenSignature: ScreenSignature,
)
