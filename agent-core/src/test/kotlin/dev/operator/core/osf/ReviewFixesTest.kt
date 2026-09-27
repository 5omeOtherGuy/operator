package dev.operator.core.osf

import dev.operator.core.api.NodeAction
import dev.operator.core.api.NodeState
import dev.operator.core.api.Role
import dev.operator.core.api.WindowType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Repair round 1 (lead, review of PR #5): one test per review item, each failing before its fix.
 *
 * 1 changes() applies the screen() exclusions; 2 password text never renders in CHANGES;
 * 3 notification exclusion removes the containing row; 4 OTP phone exemption needs a phone-shaped
 * token; 5 id-less same role+label siblings get distinct keys; 6 numbers are never reused within
 * an epoch; 7 the SCREEN header never names an excluded foreground window; Medium: an empty
 * non-blank window is a poor tree.
 */
class ReviewFixesTest {

    // ------------------------------------------------------------------ 1

    @Test
    fun `changes applies the same exclusions as screen`() {
        fun snap(label: String, id: Long) = Tree.snapshot(
            nodes = listOf(Tree.node(Tree.key(label, viewId = "id/reset"), label = label, windowId = 1, pkg = "com.android.settings")),
            windows = listOf(Tree.window(id = 1, pkg = "com.android.settings", title = "Reset options")),
            id = id,
            pkg = "com.android.settings",
        )
        val out = OsfSerializer(denylistedPackages = setOf("com.android.settings"))
            .changes(snap("Factory reset", 1), snap("Erase all data", 2), "tap", 1)
        assertFalse("denylisted labels leaked into CHANGES: $out", out.contains("Factory reset") || out.contains("Erase all data"))

        fun gateSnap(label: String, id: Long) = Tree.snapshot(
            nodes = listOf(
                Tree.node(Tree.key(label, viewId = "id/gate"), label = label, windowId = 2, pkg = Tree.OPERATOR),
                Tree.node(Tree.key("kept", viewId = "id/kept"), label = "Kept button"),
            ),
            windows = listOf(Tree.window(id = 2, pkg = Tree.OPERATOR, title = null), Tree.window(id = 1, title = "App")),
            id = id,
        )
        val out2 = OsfSerializer().changes(gateSnap("Approve?", 1), gateSnap("Approve now?", 2), "tap", 1)
        assertFalse("operator-window labels leaked into CHANGES: $out2", out2.contains("Approve"))
    }

    // ------------------------------------------------------------------ 2

    @Test
    fun `password nodes never render text in screen or changes`() {
        fun snap(label: String, id: Long) = Tree.snapshot(
            nodes = listOf(
                Tree.node(
                    Tree.key("", role = Role.EDIT, viewId = "id/pw"), role = Role.EDIT, label = label,
                    actions = setOf(NodeAction.SET_TEXT), state = setOf(NodeState.PASSWORD),
                    viewId = "id/pw",
                ),
            ),
            id = id,
        )
        val screen = OsfSerializer().screen(snap("secret1", 1))
        assertFalse("password content leaked into SCREEN: $screen", screen.contains("secret1"))
        assertTrue(screen.contains("edit password"))

        val out = OsfSerializer().changes(snap("secret1", 1), snap("hunter2!", 2), "type", 1)
        assertFalse("password content leaked into CHANGES: $out", out.contains("secret1") || out.contains("hunter2"))
        assertTrue("expected the chars-only marker: $out", out.contains("(password field, 7 chars)") && out.contains("(password field, 8 chars)"))
    }

    // ------------------------------------------------------------------ 3

    @Test
    fun `notification exclusion removes the whole containing row`() {
        val screen = OsfSerializer(
            ownNotifications = listOf(OwnNotification(appName = "Operator", title = "Update ready", text = "Tap to update now")),
        ).screen(
            Tree.snapshot(
                nodes = listOf(
                    Tree.node(Tree.key("", role = Role.LIST, viewId = "id/shade_list"), role = Role.LIST, children = listOf(1), actions = setOf(NodeAction.SCROLL_FORWARD)),
                    Tree.node(Tree.key("row", viewId = "id/row1"), label = "", role = Role.TXT, depth = 2, parent = 0, actions = setOf(NodeAction.CLICK), children = listOf(2, 3, 4)),
                    Tree.node(Tree.key("title", role = Role.TXT), role = Role.TXT, label = "Update ready", depth = 3, parent = 1, actions = emptySet()),
                    Tree.node(Tree.key("action", viewId = "id/act"), label = "Update", depth = 3, parent = 1),
                    Tree.node(Tree.key("reply", role = Role.EDIT, viewId = "id/reply"), role = Role.EDIT, label = "Reply", depth = 3, parent = 1, actions = setOf(NodeAction.SET_TEXT)),
                    Tree.node(Tree.key("other row", viewId = "id/row2"), label = "Other app · Mail arrived", depth = 2, parent = 0, actions = setOf(NodeAction.CLICK)),
                ),
                windows = listOf(Tree.window(id = 1, type = WindowType.SYSTEM, pkg = "com.android.systemui", title = "Shade")),
            ),
        )
        assertFalse("row survived: $screen", screen.contains("Update"))
        assertFalse("action button survived: $screen", screen.contains("\"Update\""))
        assertFalse("reply field survived: $screen", screen.contains("Reply"))
        assertTrue("unrelated row dropped: $screen", screen.contains("Mail arrived"))
    }

    // ------------------------------------------------------------------ 4

    @Test
    fun `a code followed by another digit group is still redacted`() {
        assertEquals("Your code is ‹code› 2", OsfText.redactOtp("Your code is 482913 2"))
        assertEquals("Your code is ‹code›", OsfText.redactOtp("Your code is 482913"))
    }

    // ------------------------------------------------------------------ 5

    @Test
    fun `id-less siblings with the same role and label get distinct keys`() {
        fun links() = listOf(
            Tree.node(Tree.key("", role = Role.WEB, viewId = "id/root"), role = Role.WEB, label = "", children = listOf(1, 2)),
            Tree.node(Tree.key("Docs", role = Role.LINK), role = Role.LINK, label = "Docs", depth = 2, parent = 0, actions = emptySet()),
            Tree.node(Tree.key("Docs", role = Role.LINK), role = Role.LINK, label = "Docs", depth = 2, parent = 0, actions = emptySet()),
        )
        val s1 = Tree.snapshot(nodes = links())
        assertEquals("keys must be unique within a snapshot", 3, s1.nodes.map { it.key }.toSet().size)

        val s2 = Tree.snapshot(nodes = links(), prev = s1)
        assertEquals("sticky numbers collapsed identical links", 3, s2.nodes.map { it.index }.toSet().size)

        // The ordinal is part of the key API for id-less nodes (web/Flutter fallback, §6.4).
        val k0 = OsfKeys.elementKey("p", WindowType.APPLICATION, null, null, Role.LINK, emptyList(), null, null, "Docs", ordinal = 0)
        val k1 = OsfKeys.elementKey("p", WindowType.APPLICATION, null, null, Role.LINK, emptyList(), null, null, "Docs", ordinal = 1)
        assertNotEquals(k0, k1)
    }

    // ------------------------------------------------------------------ 6

    @Test
    fun `numbers are never reused within an epoch after a node disappears`() {
        val s1 = Tree.snapshot(
            nodes = listOf(
                Tree.node(Tree.key("A", viewId = "id/A"), label = "A"),
                Tree.node(Tree.key("B", viewId = "id/B"), label = "B"),
                Tree.node(Tree.key("C", viewId = "id/C"), label = "C"),
                Tree.node(Tree.key("D", viewId = "id/D"), label = "D"),
                Tree.node(Tree.key("E", viewId = "id/E"), label = "E"),
            ),
        )
        assertEquals(listOf(1, 2, 3, 4, 5), s1.nodes.map { it.index })

        val s2 = Tree.snapshot(nodes = s1.nodes.take(4).map { it.copy() }, prev = s1)
        assertEquals(listOf(1, 2, 3, 4), s2.nodes.map { it.index })

        val s3 = Tree.snapshot(
            nodes = s1.nodes.take(4).map { it.copy() } + Tree.node(Tree.key("F", viewId = "id/F"), label = "F"),
            prev = s2,
        )
        // 5 was E's number; E is gone, so F must not get 5 back.
        assertEquals(listOf(1, 2, 3, 4, 6), s3.nodes.map { it.index })
    }

    // ------------------------------------------------------------------ 7

    @Test
    fun `the header names the top kept window when the foreground is excluded`() {
        val screen = OsfSerializer().screen(
            Tree.snapshot(
                nodes = listOf(Tree.node(Tree.key("kept", viewId = "id/kept"), label = "Kept button", windowId = 2)),
                windows = listOf(
                    Tree.window(id = 1, pkg = Tree.OPERATOR, title = "Gate"),      // foreground gate
                    Tree.window(id = 2, title = "App"),                            // kept app window
                ),
                pkg = Tree.OPERATOR,
            ),
        )
        val header = screen.split("\n")[0]
        assertFalse("operator window named: $header", header.contains(Tree.OPERATOR) || header.contains("Gate"))
        assertTrue("kept window not named: $header", header.contains(Tree.PKG) && header.contains("title=\"App\""))

        val onlyGate = OsfSerializer().screen(
            Tree.snapshot(
                nodes = listOf(Tree.node(Tree.key("gate", viewId = "id/g"), label = "Gate card", windowId = 1, pkg = Tree.OPERATOR)),
                windows = listOf(Tree.window(id = 1, pkg = Tree.OPERATOR, title = "Gate")),
                pkg = Tree.OPERATOR,
            ),
        )
        val header2 = onlyGate.split("\n")[0]
        assertFalse("no kept window, yet a package is named: $header2", header2.contains("pkg="))
    }

    // ------------------------------------------------------------------ Medium

    @Test
    fun `an empty non-blank window is a poor tree`() {
        assertEquals(
            PoorTreeReason.TooFewLabelledActionables(labelled = 0),
            PoorTree.detect(Tree.snapshot(nodes = emptyList())),
        )
        assertNull(PoorTree.detect(Tree.snapshot(nodes = emptyList(), windows = emptyList())))
    }
}
