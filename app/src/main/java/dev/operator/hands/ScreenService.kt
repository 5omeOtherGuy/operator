package dev.operator.hands

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import android.view.WindowManager
import android.view.View
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import dev.operator.core.api.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import java.util.concurrent.Executors
import kotlin.coroutines.resume

/** The only owner of live accessibility nodes and injected gestures. */
class ScreenService : AccessibilityService(), HandsPort {
    private val dispatcher = Executors.newSingleThreadExecutor { r -> Thread(r, "hands") }.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val keys = MutableSharedFlow<KeyEvent>(extraBufferCapacity = 32)
    val volumeKeys: SharedFlow<KeyEvent> = keys
    /** Wired by the composition root; disarming must not depend on a coroutine surviving unbind. */
    var onDisarm: (() -> Unit)? = null
    @Volatile private var connected = false
    @Volatile private var lastChange = SystemClock.uptimeMillis()
    @Volatile private var foregroundWindowId = -1
    private var serial = 0L
    private val live = mutableMapOf<ElementKey, AccessibilityNodeInfo>()
    private val overlays = mutableSetOf<View>()

    override fun onServiceConnected() { connected = true }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event != null && event.windowId == foregroundWindowId &&
            event.eventType and (AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or AccessibilityEvent.TYPE_VIEW_SCROLLED or
                AccessibilityEvent.TYPE_WINDOWS_CHANGED) != 0) lastChange = SystemClock.uptimeMillis()
    }
    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || event.keyCode == KeyEvent.KEYCODE_VOLUME_UP)
            keys.tryEmit(KeyEvent(event))
        return false
    }
    override fun onInterrupt() = Unit
    override fun onUnbind(intent: android.content.Intent?): Boolean {
        connected = false
        onDisarm?.invoke()
        scope.launch { clearNodes() }
        return super.onUnbind(intent)
    }
    override fun onDestroy() {
        connected = false
        onDisarm?.invoke()
        overlays.toList().forEach { removeOverlay(it) }
        scope.cancel()
        dispatcher.close()
        super.onDestroy()
    }

    /** Gate cards are owned here so they are TYPE_ACCESSIBILITY_OVERLAY, never application UI. */
    fun addOverlay(view: View, params: WindowManager.LayoutParams) {
        params.type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        (getSystemService(WINDOW_SERVICE) as WindowManager).addView(view, params)
        overlays += view
    }
    fun removeOverlay(view: View) {
        if (overlays.remove(view)) (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(view)
    }

    override suspend fun snapshot(): Snapshot = withContext(dispatcher) {
        check(connected)
        clearNodes()
        val windows = windows.orEmpty().mapNotNull { window ->
            val root = window.root ?: return@mapNotNull null
            val pkg = root.packageName?.toString().orEmpty()
            val type = when (window.type) {
                AccessibilityWindowInfo.TYPE_APPLICATION -> WindowType.APPLICATION
                AccessibilityWindowInfo.TYPE_INPUT_METHOD -> WindowType.INPUT_METHOD
                AccessibilityWindowInfo.TYPE_SYSTEM -> WindowType.SYSTEM
                AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY -> WindowType.ACCESSIBILITY_OVERLAY
                AccessibilityWindowInfo.TYPE_SPLIT_SCREEN_DIVIDER -> WindowType.SPLIT_SCREEN_DIVIDER
                else -> WindowType.OTHER
            }
            TreeWindow(window.id, type, window.layer, pkg, window.title?.toString(),
                window.isActive, pkg == packageName, AndroidNode(root))
        }
        val result = ScreenTree.build(++serial, System.currentTimeMillis(), windows)
        foregroundWindowId = result.windows.firstOrNull { it.active }?.id ?: -1
        // Resolve only kept nodes by their structural path, not by stale model-provided coordinates.
        for (window in windows) {
            if (window.own || window.type == WindowType.ACCESSIBILITY_OVERLAY ||
                window.type == WindowType.INPUT_METHOD) continue
            val root = (window.root as? AndroidNode)?.node ?: continue
            fun walk(node: AccessibilityNodeInfo, path: String) {
                val match = result.nodes.firstOrNull {
                    it.windowId == window.id &&
                        it.key == ScreenTree.key(window, AndroidNode(node), path)
                }
                if (match != null && !node.isAccessibilityDataSensitive) live[match.key] = node
                for (i in 0 until node.childCount) node.getChild(i)?.let { walk(it, "$path/$i") }
            }
            walk(root, "${window.id}")
        }
        result
    }
    private fun clearNodes() { live.clear() }

    override suspend fun awaitIdle(quietMs: Long, maxMs: Long): Boolean {
        require(quietMs >= 0 && maxMs >= 0)
        val deadline = SystemClock.uptimeMillis() + maxMs
        while (true) {
            val now = SystemClock.uptimeMillis()
            if (now - lastChange >= quietMs) return true
            if (now >= deadline) return false
            delay(minOf(deadline - now, quietMs - (now - lastChange)).coerceAtLeast(1))
        }
    }
    override suspend fun isConnected() = connected
    override suspend fun performNodeAction(key: ElementKey, action: NodeAction, text: String?) =
        withContext(dispatcher) {
            check(connected)
            val node = live[key] ?: error("stale node")
            check(node.isVisibleToUser && node.isEnabled && !node.isAccessibilityDataSensitive)
            val code = when (action) {
                NodeAction.CLICK -> AccessibilityNodeInfo.ACTION_CLICK
                NodeAction.LONG_CLICK -> AccessibilityNodeInfo.ACTION_LONG_CLICK
                NodeAction.SET_TEXT -> AccessibilityNodeInfo.ACTION_SET_TEXT
                NodeAction.SCROLL_FORWARD -> AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                NodeAction.SCROLL_BACKWARD -> AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                NodeAction.FOCUS -> AccessibilityNodeInfo.ACTION_FOCUS
                NodeAction.EXPAND -> AccessibilityNodeInfo.ACTION_EXPAND
                NodeAction.COLLAPSE -> AccessibilityNodeInfo.ACTION_COLLAPSE
                NodeAction.SELECT -> AccessibilityNodeInfo.ACTION_SELECT
                NodeAction.DISMISS -> AccessibilityNodeInfo.ACTION_DISMISS
                else -> error("unsupported action: $action")
            }
            if (action == NodeAction.SET_TEXT) check(!node.isPassword && text != null)
            val args = if (action == NodeAction.SET_TEXT) Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            } else null
            check(node.performAction(code, args)) { "node action rejected" }
        }

    override suspend fun gestureTap(point: Point, durationMs: Long) =
        gesture(point, point, durationMs)
    override suspend fun gestureSwipe(from: Point, to: Point, durationMs: Long) =
        gesture(from, to, durationMs)
    suspend fun gestureLongPress(point: Point) = gesture(point, point, 800)
    suspend fun gestureScroll(from: Point, to: Point) = gesture(from, to, 400)
    suspend fun tapNodeCentre(key: ElementKey) {
        val point = withContext(dispatcher) {
            val node = live[key] ?: error("stale node")
            check(node.isVisibleToUser && !node.isAccessibilityDataSensitive)
            Rect().also(node::getBoundsInScreen).let { Point(it.centerX(), it.centerY()) }
        }
        gestureTap(point, 80)
    }
    private suspend fun gesture(from: Point, to: Point, durationMs: Long) = withContext(dispatcher) {
        check(connected)
        require(durationMs in 1..1000)
        val path = Path().apply { moveTo(from.x.toFloat(), from.y.toFloat()); lineTo(to.x.toFloat(), to.y.toFloat()) }
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        val accepted = suspendCancellableCoroutine<Boolean> { continuation ->
            val ok = dispatchGesture(gesture, object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription) {
                    if (continuation.isActive) continuation.resume(true)
                }
                override fun onCancelled(gestureDescription: GestureDescription) {
                    if (continuation.isActive) continuation.resume(false)
                }
            }, null)
            if (!ok && continuation.isActive) continuation.resume(false)
        }
        check(accepted) { "gesture rejected" }
    }
    override suspend fun globalAction(action: GlobalAction) = withContext(dispatcher) {
        check(connected)
        val code = when (action) {
            GlobalAction.BACK -> GLOBAL_ACTION_BACK
            GlobalAction.HOME -> GLOBAL_ACTION_HOME
            GlobalAction.RECENTS -> GLOBAL_ACTION_RECENTS
            GlobalAction.NOTIFICATIONS -> GLOBAL_ACTION_NOTIFICATIONS
            GlobalAction.QUICK_SETTINGS -> GLOBAL_ACTION_QUICK_SETTINGS
            GlobalAction.DISMISS_SHADE -> GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE
            GlobalAction.LOCK_SCREEN -> GLOBAL_ACTION_LOCK_SCREEN
        }
        check(performGlobalAction(code)) { "global action rejected" }
    }
    // S9 owns NotificationListener; this port never exposes unredacted notification content.
    override suspend fun notifications(): List<NotificationRecord> = emptyList()
    // M2 screenshot path; do not return a potentially FLAG_SECURE-protected image.
    override suspend fun takeScreenshot(): ByteArray? = null
}

private class AndroidNode(val node: AccessibilityNodeInfo) : TreeNode {
    override val bounds get() = Rect().also(node::getBoundsInScreen).let { Bounds(it.left, it.top, it.right, it.bottom) }
    override val text get() = node.text?.toString()
    override val description get() = node.contentDescription?.toString()
    override val hint get() = node.hintText?.toString()
    override val stateDescription get() = node.stateDescription?.toString()
    override val tooltip get() = node.tooltipText?.toString()
    override val viewId get() = node.viewIdResourceName
    override val uniqueId get() = node.uniqueId
    override val className get() = node.className?.toString()
    override val visible get() = node.isVisibleToUser
    override val enabled get() = node.isEnabled
    override val password get() = node.isPassword
    override val dataSensitive get() = node.isAccessibilityDataSensitive
    override val checked get() = node.isChecked
    override val selected get() = node.isSelected
    override val focused get() = node.isFocused
    override val clickable get() = node.isClickable
    override val longClickable get() = node.isLongClickable
    override val editable get() = node.isEditable
    override val scrollable get() = node.isScrollable
    override val children get() = (0 until node.childCount).mapNotNull { node.getChild(it)?.let(::AndroidNode) }
}
