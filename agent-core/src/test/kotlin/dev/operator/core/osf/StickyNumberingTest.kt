package dev.operator.core.osf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §6.1 rule 10: numbering is sticky while the screen signature holds (package + title + ≥60 %
 * key overlap); a persisting key keeps its number, a new key gets max+1, a removed key's number
 * is retired, and a signature change restarts at 1.
 */
class StickyNumberingTest {

    private fun btn(label: String) = Tree.node(Tree.key(label, viewId = "id/$label"), label = label)

    @Test
    fun `numbers persist across two snapshots of the same screen`() {
        val s1 = Tree.snapshot(listOf(btn("A"), btn("B"), btn("C"), btn("D"), btn("E")))
        assertEquals(listOf(1, 2, 3, 4, 5), s1.nodes.map { it.index })

        val s2 = Tree.snapshot(listOf(btn("A"), btn("B"), btn("C"), btn("D")), prev = s1)
        assertEquals(listOf(1, 2, 3, 4), s2.nodes.map { it.index })
        assertTrue(StickyNumbering.numberingHolds(s1, s2))
    }

    @Test
    fun `a new key gets max plus one and retired numbers are not reused`() {
        val s1 = Tree.snapshot(listOf(btn("A"), btn("B"), btn("C"), btn("D"), btn("E")))
        // D and E gone, F and G new: overlap 3/5 = 60 % — exactly the threshold, so it holds.
        val s2 = Tree.snapshot(listOf(btn("A"), btn("B"), btn("C"), btn("F"), btn("G")), prev = s1)
        assertEquals(listOf(1, 2, 3, 6, 7), s2.nodes.map { it.index })
    }

    @Test
    fun `below the overlap threshold numbering restarts`() {
        val s1 = Tree.snapshot(listOf(btn("A"), btn("B"), btn("C"), btn("D"), btn("E")))
        val s2 = Tree.snapshot(listOf(btn("A"), btn("F"), btn("G"), btn("H"), btn("I")), prev = s1)
        // 1/5 = 20 % < 60 %: new epoch.
        assertEquals(listOf(1, 2, 3, 4, 5), s2.nodes.map { it.index })
        assertFalse(StickyNumbering.numberingHolds(s1, s2))
    }

    @Test
    fun `a package or title change restarts numbering`() {
        val s1 = Tree.snapshot(listOf(btn("A"), btn("B")))
        val otherApp = Tree.snapshot(
            listOf(btn("A"), btn("B")),
            windows = listOf(Tree.window(pkg = "com.other.app", title = "Other")),
            pkg = "com.other.app",
            prev = s1,
        )
        assertEquals(listOf(1, 2), otherApp.nodes.map { it.index })
        assertFalse(StickyNumbering.numberingHolds(s1, otherApp))

        val s3 = Tree.snapshot(
            listOf(btn("A"), btn("B")),
            windows = listOf(Tree.window(title = "Other title")),
            prev = s1,
        )
        assertEquals(listOf(1, 2), s3.nodes.map { it.index })
    }
}
