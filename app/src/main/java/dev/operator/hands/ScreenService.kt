package dev.operator.hands

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.accessibility.AccessibilityEvent

/**
 * The hands' accessibility service (FOUNDATION §3.2 A1, §6; ADR-0001, ADR-0004).
 *
 * Moved here from the scaffold's `dev.operator` package (the PLAN.md slice table gives the hands the
 * `hands` package). F0 declares the service, its config XML and its lifecycle hooks only; S7
 * implements the tree reader over `UiNode`, the gestures, the key filter and the overlay host, and
 * S8 wiring the Disarm path of §9.6 in `onUnbind`.
 */
class ScreenService : AccessibilityService() {

    override fun onServiceConnected() {
        Log.i(TAG, "connected")
    }

    /** §2.4: on the main looper, events only stamp change times for settle detection (S7). */
    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        Log.i(TAG, "destroyed")
        super.onDestroy()
    }

    private companion object {
        const val TAG = "operator.screen"
    }
}
