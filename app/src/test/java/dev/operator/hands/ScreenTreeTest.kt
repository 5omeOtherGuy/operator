package dev.operator.hands

import dev.operator.core.api.*
import org.junit.Assert.*
import org.junit.Test

class ScreenTreeTest {
    private data class Fake(
        override val text: String? = null,
        override val description: String? = null,
        override val hint: String? = null,
        override val viewId: String? = null,
        override val password: Boolean = false,
        override val dataSensitive: Boolean = false,
        override val clickable: Boolean = false,
        override val editable: Boolean = false,
        override val children: List<TreeNode> = emptyList(),
    ) : TreeNode {
        override val bounds = Bounds(10, 20, 110, 60)
        override val stateDescription: String? = null
        override val tooltip: String? = null
        override val uniqueId: String? = null
        override val className: String? = "Button"
        override val visible = true
        override val enabled = true
        override val checked = false
        override val selected = false
        override val focused = false
        override val longClickable = false
        override val scrollable = false
    }

    @Test fun mapsFakeTreeAndExcludesOwnOverlayAndSensitiveContent() {
        val root = Fake(children = listOf(
            Fake(text = "Continue", clickable = true, viewId = "continue"),
            Fake(text = "secret", password = true, editable = true),
            Fake(text = "gate approval", dataSensitive = true),
        ))
        val app = TreeWindow(3, WindowType.APPLICATION, 1, "other.app", "Page", true, false, root)
        val own = TreeWindow(4, WindowType.APPLICATION, 2, "dev.operator", "Gate", false, true, root)
        val overlay = own.copy(id = 5, own = false, type = WindowType.ACCESSIBILITY_OVERLAY)
        val ime = own.copy(id = 6, own = false, type = WindowType.INPUT_METHOD)
        val result = ScreenTree.build(1, 100, listOf(app, own, overlay, ime))
        assertEquals(1, result.windows.size)
        assertTrue(result.keyboardUp)
        assertEquals("other.app", result.foregroundPackage)
        assertEquals(3, result.nodes.size)
        assertEquals(listOf(1, 2), result.nodes[0].children)
        assertEquals("Continue", result.nodes[1].label)
        assertEquals(Bounds(10, 20, 110, 60), result.nodes[1].bounds)
        assertTrue(NodeAction.CLICK in result.nodes[1].actions)
        assertEquals("", result.nodes[2].label)
        assertTrue(NodeState.PASSWORD in result.nodes[2].state)
        assertFalse(NodeAction.SET_TEXT in result.nodes[2].actions)
        assertEquals(result.nodes[1].key, ScreenTree.build(2, 101, listOf(app)).nodes[1].key)
    }
}
