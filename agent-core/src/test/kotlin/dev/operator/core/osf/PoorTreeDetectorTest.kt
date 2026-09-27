package dev.operator.core.osf

import dev.operator.core.api.Bounds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §6.4 poor-tree detector; each threshold returns its own typed reason. Bounds in these trees are
 * distinct tiles of the screen (the default test bounds all coincide, which would make every node
 * cover the whole hull).
 */
class PoorTreeDetectorTest {

    private fun btn(label: String, bounds: Bounds) =
        Tree.node(Tree.key(label, viewId = "id/$label"), label = label, bounds = bounds)

    @Test
    fun `fewer than three labelled actionables is poor with the count`() {
        val reason = PoorTree.detect(
            Tree.snapshot(
                nodes = listOf(
                    btn("A", Bounds(0, 0, 100, 34)),
                    btn("B", Bounds(0, 34, 100, 33)),
                    Tree.node(Tree.key("t"), role = dev.operator.core.api.Role.TXT, label = "t", actions = emptySet(), bounds = Bounds(0, 67, 100, 33)),
                ),
            ),
        )
        assertEquals(PoorTreeReason.TooFewLabelledActionables(labelled = 2), reason)
    }

    @Test
    fun `a blank snapshot is not poor`() {
        assertNull(PoorTree.detect(Tree.snapshot(nodes = emptyList(), windows = emptyList())))
    }

    @Test
    fun `a dominant childless node is poor with coverage`() {
        val nodes = listOf(
            btn("A", Bounds(1100, 0, 1200, 100)),
            btn("B", Bounds(1100, 100, 1200, 200)),
            btn("C", Bounds(1100, 200, 1200, 300)),
            Tree.node(
                Tree.key("canvas", viewId = "id/canvas"), label = "", className = "android.view.SurfaceView",
                bounds = Bounds(0, 0, 1000, 900), // hull is 1200x900 → 900000/1080000 = 0.833…
                actions = emptySet(),
            ),
        )
        val reason = PoorTree.detect(Tree.snapshot(nodes = nodes))
        assertTrue(reason is PoorTreeReason.DominantNode)
        reason as PoorTreeReason.DominantNode
        assertEquals("android.view.SurfaceView", reason.className)
        assertEquals(0.8333, reason.coverage, 0.001)
    }

    @Test
    fun `more than forty percent unlabelled actionables is poor with the fraction`() {
        val tiles = listOf(
            Bounds(0, 0, 100, 50), Bounds(100, 0, 200, 50), Bounds(0, 50, 100, 100),
            Bounds(100, 50, 200, 100), Bounds(0, 100, 100, 150), Bounds(100, 100, 200, 150),
        )
        val nodes = listOf(btn("A", tiles[0]), btn("B", tiles[1]), btn("C", tiles[2])) +
            (0 until 3).map { Tree.node(Tree.key("", viewId = "id/u$it"), label = "", bounds = tiles[3 + it]) }
        val reason = PoorTree.detect(Tree.snapshot(nodes = nodes))
        assertTrue(reason is PoorTreeReason.TooManyUnlabelledActionables)
        reason as PoorTreeReason.TooManyUnlabelledActionables
        assertEquals(0.5, reason.unlabelledFraction, 0.0001)
    }

    @Test
    fun `a healthy tree is not poor`() {
        assertNull(
            PoorTree.detect(
                Tree.snapshot(
                    nodes = listOf(
                        btn("A", Bounds(0, 0, 100, 100)),
                        btn("B", Bounds(100, 0, 200, 100)),
                        btn("C", Bounds(0, 100, 100, 200)),
                        btn("D", Bounds(100, 100, 200, 200)),
                    ),
                ),
            ),
        )
    }

    @Test
    fun `a read failure is its own typed reason`() {
        assertEquals(PoorTreeReason.ReadFailure, PoorTreeReason.ReadFailure)
    }
}
