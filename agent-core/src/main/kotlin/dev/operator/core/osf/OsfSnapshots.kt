package dev.operator.core.osf

import dev.operator.core.api.ElementKey
import dev.operator.core.api.Role
import dev.operator.core.api.ScreenSignature
import dev.operator.core.api.Snapshot
import dev.operator.core.api.UiNode
import dev.operator.core.api.WindowInfo
import dev.operator.core.api.WindowType

/*
 * Snapshot assembly over the frozen types, for the reader (S7), replay and tests: sticky numbers
 * (§6.1 rule 10) with the epoch counter threaded across snapshots (review fix 6), unique keys via
 * the id-less sibling ordinal (review fix 5), the two loop hashes and the screen signature (§6.3)
 * from the kept (non-excluded) windows (review fix 7), in one place, so every Snapshot is built
 * with the same rules the serializer renders.
 */
object OsfSnapshots {

    /** A built snapshot plus the numbering state to thread into the next snapshot of the stream. */
    data class Built(val snapshot: Snapshot, val numbering: StickyNumbering.Numbering)

    /** See [buildBuilt]; drops the numbering state (lossy for retired numbers). */
    fun build(
        id: Long,
        capturedAtMs: Long,
        foregroundPackage: String,
        windows: List<WindowInfo>,
        nodes: List<UiNode>,
        keyboardUp: Boolean = false,
        focusedNumber: Int? = null,
        prev: Snapshot? = null,
        prevNumbering: StickyNumbering.Numbering? = null,
        operatorPackage: String = "dev.operator",
    ): Snapshot = buildBuilt(
        id, capturedAtMs, foregroundPackage, windows, nodes, keyboardUp, focusedNumber, prev, prevNumbering, operatorPackage,
    ).snapshot

    /**
     * Builds [Snapshot] from reader-supplied [UiNode]s. [focusedNumber] is the model number of the
     * focused element (§6.2 `focus=9`). [prevNumbering] threads the sticky-numbering epoch (review
     * fix 6); when only a bare [prev] snapshot is given, the numbering is derived from it lossily.
     * The screen signature (and the numbering epoch) is taken from the first active kept window —
     * operator windows and overlays never define the screen (review fix 7).
     */
    fun buildBuilt(
        id: Long,
        capturedAtMs: Long,
        foregroundPackage: String,
        windows: List<WindowInfo>,
        nodes: List<UiNode>,
        keyboardUp: Boolean = false,
        focusedNumber: Int? = null,
        prev: Snapshot? = null,
        prevNumbering: StickyNumbering.Numbering? = null,
        operatorPackage: String = "dev.operator",
    ): Built {
        val keyed = disambiguateIdLessKeys(nodes, windows)
        val kept = windows.filter {
            it.type !in setOf(WindowType.ACCESSIBILITY_OVERLAY, WindowType.INPUT_METHOD, WindowType.SPLIT_SCREEN_DIVIDER) &&
                it.packageName != operatorPackage
        }
        val primary = kept.firstOrNull { it.active } ?: kept.firstOrNull()
        val signaturePackage = primary?.packageName ?: foregroundPackage
        val signatureTitle = primary?.title

        val assignment = StickyNumbering.assignNumbering(
            prevNumbering ?: prev?.let { StickyNumbering.numberingOf(it) },
            signaturePackage,
            signatureTitle,
            keyed.map { it.key },
        )
        val renumbered = keyed.mapIndexed { i, n -> if (n.index == assignment.numbers[i]) n else n.copy(index = assignment.numbers[i]) }
        val structural = OsfKeys.structuralHash(renumbered)
        val full = OsfKeys.fullHash(renumbered)
        return Built(
            Snapshot(
                id = id,
                capturedAtMs = capturedAtMs,
                foregroundPackage = foregroundPackage,
                windows = windows,
                nodes = renumbered,
                structuralHash = structural,
                fullHash = full,
                keyboardUp = keyboardUp,
                focusedIndex = focusedNumber,
                screenSignature = ScreenSignature(signaturePackage, signatureTitle, structural),
            ),
            assignment.numbering,
        )
    }

    /**
     * Review fix 5: id-less nodes sharing parent, role and normalised label (two identical web
     * links) get the deterministic ordinal of §6.4's label+ordinal fallback — their 0-based
     * position among those siblings in tree order. Id-bearing keys are untouched.
     */
    internal fun disambiguateIdLessKeys(nodes: List<UiNode>, windows: List<WindowInfo>): List<UiNode> {
        val hasIdLess = nodes.any { it.uniqueId == null && it.viewId == null }
        if (!hasIdLess) return nodes
        val typeOf = windows.associate { it.id to it.type }
        val counters = HashMap<Triple<Int?, Role, String>, Int>()
        return nodes.mapIndexed { pos, n ->
            if (n.uniqueId != null || n.viewId != null) {
                n
            } else {
                val group = Triple(n.parentIndex, n.role, OsfText.normaliseLabel(n.label))
                val ordinal = counters.getOrDefault(group, 0)
                counters[group] = ordinal + 1
                n.copy(
                    key = OsfKeys.elementKey(
                        n.packageName,
                        typeOf[n.windowId] ?: WindowType.OTHER,
                        null,
                        null,
                        n.role,
                        idAncestorPath(nodes, n.parentIndex),
                        n.row,
                        n.column,
                        n.label,
                        ordinal,
                    ),
                )
            }
        }
    }

    /** View ids of the id-bearing ancestors of [parentIndex], root first. */
    private fun idAncestorPath(nodes: List<UiNode>, parentIndex: Int?): List<String> {
        val path = ArrayList<String>()
        var i = parentIndex
        while (i != null && i in nodes.indices) {
            nodes[i].viewId?.let { path.add(it) }
            i = nodes[i].parentIndex
        }
        path.reverse()
        return path
    }
}
