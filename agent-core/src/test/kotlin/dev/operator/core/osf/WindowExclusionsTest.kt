package dev.operator.core.osf

import dev.operator.core.api.Role
import dev.operator.core.api.WindowType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §6.1 rule 1 (operator's own windows and overlays dropped, own notification rows dropped with
 * their buttons and reply fields) and rule 8 (denylisted windows render as the placeholder).
 */
class WindowExclusionsTest {

    @Test
    fun `operator windows overlays and the ime never reach the model`() {
        val screen = OsfSerializer().screen(
            Tree.snapshot(
                nodes = listOf(
                    Tree.node(Tree.key("own"), label = "Gate card", windowId = 2, pkg = Tree.OPERATOR),
                    Tree.node(Tree.key("overlay"), label = "Overlay", windowId = 3),
                    Tree.node(Tree.key("ime"), label = "Keyboard", windowId = 4),
                    Tree.node(Tree.key("app"), label = "App button"),
                ),
                windows = listOf(
                    Tree.window(id = 2, pkg = Tree.OPERATOR, title = null),
                    Tree.window(id = 3, type = WindowType.ACCESSIBILITY_OVERLAY, pkg = "com.android.systemui", title = null),
                    Tree.window(id = 4, type = WindowType.INPUT_METHOD, pkg = "com.android.systemui", title = null),
                    Tree.window(id = 1),
                ),
            ),
        )
        assertFalse(screen.contains("Gate card"))
        assertFalse(screen.contains("Overlay"))
        assertFalse(screen.contains("Keyboard"))
        assertTrue(screen.contains("App button"))
    }

    @Test
    fun `own notification rows drop with their buttons and reply fields`() {
        val shadeRows = listOf(
            Tree.node(Tree.key("mail", role = Role.TXT), role = Role.TXT, label = "Mail · New mail", depth = 1, parent = null, actions = emptySet()),
            Tree.node(
                Tree.key("operator row", role = Role.TXT), role = Role.TXT,
                label = "Operator · Update ready · Tap to update now", depth = 1, actions = emptySet(),
            ),
            Tree.node(Tree.key("update btn"), label = "Update", depth = 2, parent = 1),
            Tree.node(Tree.key("reply", role = Role.EDIT, viewId = "id/reply"), role = Role.EDIT, label = "Reply", depth = 2, parent = 1, actions = setOf(dev.operator.core.api.NodeAction.SET_TEXT)),
        )
        val screen = OsfSerializer(
            ownNotifications = listOf(OwnNotification(appName = "Operator", title = "Update ready", text = "Tap to update now")),
        ).screen(
            Tree.snapshot(
                nodes = shadeRows,
                windows = listOf(Tree.window(id = 1, type = WindowType.SYSTEM, pkg = "com.android.systemui", title = "Shade")),
            ),
        )
        assertFalse(screen.contains("Update ready"))
        assertFalse(screen.contains("Tap to update now"))
        assertFalse(screen.contains("\"Update\"")) // the row's action button died with it
        assertFalse(screen.contains("Reply")) // and its inline reply field
        assertTrue(screen.contains("New mail"))
    }

    @Test
    fun `a denylisted window renders only the placeholder`() {
        val screen = OsfSerializer(
            appLabels = mapOf("com.android.settings" to "Settings"),
            denylistedPackages = setOf("com.android.settings"),
        ).screen(
            Tree.snapshot(
                nodes = listOf(
                    Tree.node(Tree.key("reset", viewId = null), label = "Factory reset", windowId = 1, pkg = "com.android.settings"),
                ),
                windows = listOf(Tree.window(id = 1, pkg = "com.android.settings", title = "Reset options")),
                pkg = "com.android.settings",
            ),
        )
        val lines = screen.split("\n")
        assertEquals("[hidden: sensitive app]", lines[1])
        assertFalse(screen.contains("Factory reset"))
        assertFalse(screen.contains("Reset options")) // the subpage name never renders
        // the package appears only in the SCREEN header, nowhere else
        assertEquals(1, lines.count { it.contains("com.android.settings") })
        assertEquals("SCREEN s1 app=\"Settings\" pkg=com.android.settings win=app", lines[0])
        assertEquals("END", lines.last())
    }

    @Test
    fun `a second window gets a window header`() {
        val screen = OsfSerializer().screen(
            Tree.snapshot(
                nodes = listOf(
                    Tree.node(Tree.key("a"), label = "In app"),
                    Tree.node(Tree.key("d"), label = "In dialog", windowId = 2),
                ),
                windows = listOf(Tree.window(id = 1), Tree.window(id = 2, title = "Dialog", active = false)),
            ),
        )
        assertTrue(screen.contains("WINDOW app=\"com.example.app\" pkg=com.example.app win=app title=\"Dialog\""))
        assertTrue(screen.indexOf("In app") < screen.indexOf("WINDOW"))
    }
}
