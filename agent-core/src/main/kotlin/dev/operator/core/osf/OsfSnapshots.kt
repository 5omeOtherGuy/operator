package dev.operator.core.osf

import dev.operator.core.api.ScreenSignature
import dev.operator.core.api.Snapshot
import dev.operator.core.api.UiNode
import dev.operator.core.api.WindowInfo

/*
 * Snapshot assembly over the frozen types, for the reader (S7), replay and tests: sticky numbers
 * (§6.1 rule 10), the two loop hashes and the screen signature (§6.3) in one place, so every
 * Snapshot is built with the same rules the serializer renders.
 */
object OsfSnapshots {

    /**
     * Builds [Snapshot] from reader-supplied [UiNode]s (keys already assigned via [OsfKeys]).
     * [focusedNumber] is the model number of the focused element (§6.2 `focus=9`); pass null when
     * nothing is focused. [prev] is the previous snapshot of the same observation stream, or null
     * for a fresh numbering epoch.
     */
    fun build(
        id: Long,
        capturedAtMs: Long,
        foregroundPackage: String,
        windows: List<WindowInfo>,
        nodes: List<UiNode>,
        keyboardUp: Boolean = false,
        focusedNumber: Int? = null,
        prev: Snapshot? = null,
    ): Snapshot {
        val title = (windows.firstOrNull { it.active } ?: windows.firstOrNull())?.title
        val numbers = StickyNumbering.assign(prev, foregroundPackage, title, nodes.map { it.key })
        val renumbered = nodes.mapIndexed { i, n -> if (n.index == numbers[i]) n else n.copy(index = numbers[i]) }
        val structural = OsfKeys.structuralHash(renumbered)
        val full = OsfKeys.fullHash(renumbered)
        return Snapshot(
            id = id,
            capturedAtMs = capturedAtMs,
            foregroundPackage = foregroundPackage,
            windows = windows,
            nodes = renumbered,
            structuralHash = structural,
            fullHash = full,
            keyboardUp = keyboardUp,
            focusedIndex = focusedNumber,
            screenSignature = ScreenSignature(foregroundPackage, title, structural),
        )
    }
}
