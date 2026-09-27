package dev.operator.core.osf

import dev.operator.core.api.NodeAction
import dev.operator.core.api.Role
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §6.1 rule 9: soft 800 / hard 1,500 tokens, deterministic truncation order — list children first,
 * as `… N more items (scroll [K])`; window headers, dialog buttons and `edit` fields are never
 * trimmed.
 */
class BudgetTruncationTest {

    private fun bigListScreen(): String {
        val children = (0 until 40).map { i ->
            Tree.node(
                Tree.key("item $i", role = Role.TXT),
                role = Role.TXT, label = "List item number $i " + "w".repeat(110),
                depth = 2, parent = 0, actions = emptySet(),
            )
        }
        val nodes = listOf(
            Tree.node(
                Tree.key("", role = Role.LIST, viewId = "id/list"), role = Role.LIST,
                children = (1..40).toList(), actions = setOf(NodeAction.SCROLL_FORWARD),
            ),
        ) + children + Tree.node(
            Tree.key("", role = Role.EDIT, viewId = "id/e"), role = Role.EDIT,
            label = "kept field", actions = setOf(NodeAction.SET_TEXT),
        )
        return OsfSerializer().screen(Tree.snapshot(nodes = nodes))
    }

    @Test
    fun `over the soft budget list children are trimmed with a marker`() {
        val screen = bigListScreen()
        assertTrue(Regex("""\s+… \d+ more items \(scroll \[1\]\)""").containsMatchIn(screen))
        assertTrue(screen.contains("END"))
        assertTrue(screen.contains("""edit "kept field"""")) // never trimmed
        assertFalse(screen.contains("List item number 39")) // the last child went first
        assertTrue(screen.contains("List item number 0")) // earliest children survive
    }

    @Test
    fun `truncation is deterministic`() {
        assertEquals(bigListScreen(), bigListScreen())
    }

    @Test
    fun `under the budget nothing is trimmed`() {
        val screen = OsfSerializer().screen(
            Tree.snapshot(nodes = listOf(Tree.node(Tree.key("a"), label = "A"))),
        )
        assertEquals("[1] btn \"A\"", screen.split("\n")[1])
    }

    @Test
    fun `over the hard budget plain lines go from the bottom, header and end stay`() {
        val nodes = (0 until 40).map { i ->
            Tree.node(Tree.key("t$i", role = Role.TXT), role = Role.TXT, label = "line $i " + "y".repeat(60), actions = emptySet())
        }
        val screen = OsfSerializer(softTokenBudget = 10, hardTokenBudget = 60).screen(Tree.snapshot(nodes = nodes))
        assertTrue(screen.startsWith("SCREEN "))
        assertTrue(screen.endsWith("END"))
        assertTrue(screen.contains("line 0")) // trimmed from the bottom
        assertFalse(screen.contains("line 39"))
    }

    @Test
    fun `last resort keeps only header marker and end`() {
        val nodes = (0 until 60).map { i ->
            Tree.node(Tree.key("b$i"), label = "b$i " + "z".repeat(200))
        }
        val screen = OsfSerializer(softTokenBudget = 5, hardTokenBudget = 8).screen(Tree.snapshot(nodes = nodes))
        assertEquals("… truncated", screen.split("\n")[1])
        assertTrue(screen.endsWith("END"))
    }
}
