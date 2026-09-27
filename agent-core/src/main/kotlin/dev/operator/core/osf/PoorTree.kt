package dev.operator.core.osf

import dev.operator.core.api.Snapshot
import dev.operator.core.api.UiNode

/*
 * Poor-tree detector (FOUNDATION §6.4, [04§R5]); thresholds are INFERENCE, from 04-M1-5.
 *
 * A screen is poor when any of:
 *  - fewer than 3 labelled actionables on a non-blank window (a snapshot with no nodes at all is
 *    blank, not poor);
 *  - a single node covers more than 50 % of the screen with no children (SurfaceView and
 *    friends; screen extent is approximated by the bounding hull of the node bounds, the frozen
 *    types carry no screen size);
 *  - more than 40 % of the actionables are unlabelled (§6.1 rule 7 counts them);
 *  - the read itself failed ([PoorTreeReason.ReadFailure], constructed by the caller; there is no
 *    snapshot to inspect then).
 *
 * M1 reaction: stop the UI path and ASK_OWNER ("I cannot read <app>'s screen"), §6.4.
 */

/** Why the tree is poor; the caller renders the ASK_OWNER text from it. */
sealed interface PoorTreeReason {
    /** Fewer than 3 labelled actionables on a non-blank window; [labelled] is the count found. */
    data class TooFewLabelledActionables(val labelled: Int, val required: Int = 3) : PoorTreeReason

    /** A childless node covering more than half the screen; [coverage] is the fraction (> 0.5). */
    data class DominantNode(val className: String?, val coverage: Double) : PoorTreeReason

    /** More than 40 % of the actionables are unlabelled; [unlabelledFraction] is the fraction. */
    data class TooManyUnlabelledActionables(val unlabelledFraction: Double) : PoorTreeReason

    /** §6.4: a window-content read (or screenshot) failure; no snapshot exists for it. */
    data object ReadFailure : PoorTreeReason
}

object PoorTree {

    const val MIN_LABELLED_ACTIONABLES = 3
    const val MAX_DOMINANT_COVERAGE = 0.5
    const val MAX_UNLABELLED_FRACTION = 0.4

    /** Null when the tree is not poor. Checks run in the order [04§R5] lists them. */
    fun detect(snapshot: Snapshot): PoorTreeReason? {
        val nodes = snapshot.nodes
        if (nodes.isEmpty()) {
            // Review Medium: a visible window that emitted no nodes is non-blank but empty —
            // unreadable for the model, so poor. A snapshot with no windows at all is blank.
            return if (snapshot.windows.isEmpty()) null
            else PoorTreeReason.TooFewLabelledActionables(labelled = 0)
        }

        val actionables = nodes.filter { it.actions.isNotEmpty() }
        val labelled = actionables.count { !it.label.isBlank() }
        if (labelled < MIN_LABELLED_ACTIONABLES) return PoorTreeReason.TooFewLabelledActionables(labelled)

        screenHullArea(nodes)?.let { hull ->
            for (n in nodes) {
                if (n.children.isNotEmpty()) continue
                val coverage = nodeArea(n) / hull
                if (coverage > MAX_DOMINANT_COVERAGE) return PoorTreeReason.DominantNode(n.className, coverage)
            }
        }

        if (actionables.isNotEmpty()) {
            val unlabelled = actionables.size - labelled
            val fraction = unlabelled.toDouble() / actionables.size
            if (fraction > MAX_UNLABELLED_FRACTION) return PoorTreeReason.TooManyUnlabelledActionables(fraction)
        }
        return null
    }

    /** Bounding hull of all node bounds, as a Double area; null when it degenerates to zero. */
    private fun screenHullArea(nodes: List<UiNode>): Double? {
        var minLeft = Int.MAX_VALUE; var minTop = Int.MAX_VALUE
        var maxRight = Int.MIN_VALUE; var maxBottom = Int.MIN_VALUE
        for (n in nodes) {
            val b = n.bounds
            minLeft = minOf(minLeft, b.left); minTop = minOf(minTop, b.top)
            maxRight = maxOf(maxRight, b.right); maxBottom = maxOf(maxBottom, b.bottom)
        }
        val w = (maxRight - minLeft).coerceAtLeast(0)
        val h = (maxBottom - minTop).coerceAtLeast(0)
        val area = w.toDouble() * h.toDouble()
        return if (area > 0.0) area else null
    }

    private fun nodeArea(n: UiNode): Double {
        val b = n.bounds
        return ((b.right - b.left).coerceAtLeast(0)).toDouble() * ((b.bottom - b.top).coerceAtLeast(0)).toDouble()
    }
}
