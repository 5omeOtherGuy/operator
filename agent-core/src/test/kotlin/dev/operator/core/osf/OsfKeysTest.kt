package dev.operator.core.osf

import dev.operator.core.api.Role
import dev.operator.core.api.WindowType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** §6.3: element keys, structural hash (keys + roles) and full hash (plus labels and flags). */
class OsfKeysTest {

    @Test
    fun `an id-bearing key ignores the label, an id-less key uses it`() {
        val a = OsfKeys.elementKey("p", WindowType.APPLICATION, null, "id/e", Role.EDIT, emptyList(), null, null, "old text")
        val b = OsfKeys.elementKey("p", WindowType.APPLICATION, null, "id/e", Role.EDIT, emptyList(), null, null, "new text")
        assertEquals(a, b)

        val c = OsfKeys.elementKey("p", WindowType.APPLICATION, null, null, Role.TXT, emptyList(), null, null, "old text")
        val d = OsfKeys.elementKey("p", WindowType.APPLICATION, null, null, Role.TXT, emptyList(), null, null, "new text")
        assertNotEquals(c, d)
    }

    @Test
    fun `keys distinguish package window role row and column`() {
        val base = OsfKeys.elementKey("p", WindowType.APPLICATION, null, "id/e", Role.BTN, emptyList(), null, null, "Go")
        assertNotEquals(base, OsfKeys.elementKey("q", WindowType.APPLICATION, null, "id/e", Role.BTN, emptyList(), null, null, "Go"))
        assertNotEquals(base, OsfKeys.elementKey("p", WindowType.SYSTEM, null, "id/e", Role.BTN, emptyList(), null, null, "Go"))
        assertNotEquals(base, OsfKeys.elementKey("p", WindowType.APPLICATION, null, "id/e", Role.TXT, emptyList(), null, null, "Go"))
        assertNotEquals(base, OsfKeys.elementKey("p", WindowType.APPLICATION, null, "id/e", Role.BTN, emptyList(), 1, null, "Go"))
        assertNotEquals(base, OsfKeys.elementKey("p", WindowType.APPLICATION, null, "id/e", Role.BTN, emptyList(), null, 2, "Go"))
        assertNotEquals(base, OsfKeys.elementKey("p", WindowType.APPLICATION, null, "id/e", Role.BTN, listOf("id/parent"), null, null, "Go"))
    }

    @Test
    fun `structural hash follows keys and roles not labels`() {
        val n1 = Tree.node(Tree.key("A", role = Role.TXT, viewId = null), role = Role.TXT, label = "A", actions = emptySet())
        val n2 = Tree.node(Tree.key("A", role = Role.TXT, viewId = null), role = Role.TXT, label = "A changed", actions = emptySet())
        assertEquals(OsfKeys.structuralHash(listOf(n1)), OsfKeys.structuralHash(listOf(n2)))
        val n3 = Tree.node(Tree.key("A", role = Role.BTN, viewId = null), role = Role.BTN, label = "A", actions = emptySet())
        assertNotEquals(OsfKeys.structuralHash(listOf(n1)), OsfKeys.structuralHash(listOf(n3)))
    }

    @Test
    fun `full hash follows labels and flags but not the clock`() {
        val a = Tree.node(Tree.key("m", role = Role.TXT), role = Role.TXT, label = "Yes, see you there · 19:05", depth = 2, actions = emptySet())
        val b = Tree.node(Tree.key("m", role = Role.TXT), role = Role.TXT, label = "Yes, see you there · 19:06", depth = 2, actions = emptySet())
        assertEquals(OsfKeys.fullHash(listOf(a)), OsfKeys.fullHash(listOf(b)))

        val c = Tree.node(Tree.key("m", role = Role.TXT), role = Role.TXT, label = "No, see you there · 19:05", depth = 2, actions = emptySet())
        assertNotEquals(OsfKeys.fullHash(listOf(a)), OsfKeys.fullHash(listOf(c)))

        val d = a.copy(state = setOf(dev.operator.core.api.NodeState.SELECTED))
        assertNotEquals(OsfKeys.fullHash(listOf(a)), OsfKeys.fullHash(listOf(d)))
    }

    @Test
    fun `the screen signature binds package title and structural hash`() {
        val nodes = listOf(Tree.node(Tree.key("a"), label = "A"))
        val sig = OsfKeys.screenSignature("pkg", "Title", 42L)
        assertEquals("pkg", sig.packageName)
        assertEquals("Title", sig.windowTitle)
        assertEquals(42L, sig.structuralHash)
        assertTrue(OsfKeys.structuralHash(nodes) != 0L)
    }
}
