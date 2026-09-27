package dev.operator.core.osf

import dev.operator.core.api.ElementKey
import dev.operator.core.api.Role
import dev.operator.core.api.ScreenSignature
import dev.operator.core.api.Snapshot
import dev.operator.core.api.UiNode
import dev.operator.core.api.WindowType

/*
 * Element identity and loop hashes (FOUNDATION §6.3).
 *
 * - ElementKey = hash(package, window type, uniqueId or viewId, role, id-ancestor path,
 *   CollectionItemInfo row/col, normalised label). The reader (S7) computes keys with
 *   [elementKey] before it builds the frozen [UiNode]s; bounds are never part of the key
 *   (§6.3: last resort only).
 * - Structural hash = keys + roles, in emission order.
 * - Full hash = structural inputs + labels and flags, with clock text normalised
 *   ([OsfText.normaliseClock]) so a ticking clock does not change it.
 * - Screen signature = (package, window title, structural hash), bound into the approval token
 *   (§9.2 item 5).
 *
 * The hash is FNV-1a over length-prefixed UTF-8 fields with a version tag, so it is deterministic
 * across processes and runs.
 */

object OsfKeys {

    /** §6.3: normalised label part of the key. */
    fun normalisedLabel(label: String): String = OsfText.normaliseLabel(label)

    /**
     * §6.3 composite key. [idAncestorPath] is the view ids of the node's id-bearing ancestors, root first.
     *
     * Label reading (see the §6.2 host-map example `h(pkg, app, compose_message_text, edit, …)` and the
     * golden `CHANGES` for the typed-into edit field): when a stable id exists (uniqueId or viewId) it is
     * the identity and the volatile label is not hashed — otherwise the typed text of [9] would change its
     * key and the diff could not show `~`. The normalised label is hashed only for id-less nodes (web and
     * Flutter fallbacks, §6.4), where it is the identity the design gives them.
     *
     * [ordinal] disambiguates id-less siblings that share parent, role and label (review fix 5): the
     * node's 0-based position among them in tree order. Hashed only for id-less nodes, always (0 for a
     * singleton), so keys stay unique within a snapshot and stable across snapshots.
     */
    fun elementKey(
        packageName: String,
        windowType: WindowType,
        uniqueId: String?,
        viewId: String?,
        role: Role,
        idAncestorPath: List<String>,
        row: Int?,
        column: Int?,
        label: String,
        ordinal: Int = 0,
    ): ElementKey {
        val h = Hash64()
        h.putString("osf-key-v1")
        h.putString(packageName)
        h.putString(windowType.name)
        h.putBoolean(uniqueId != null)
        h.putString(uniqueId ?: viewId ?: "")
        h.putString(role.name)
        h.putLong(idAncestorPath.size.toLong())
        idAncestorPath.forEach { h.putString(it) }
        h.putLong(row?.toLong() ?: -1L)
        h.putLong(column?.toLong() ?: -1L)
        if (uniqueId == null && viewId == null) {
            h.putString(normalisedLabel(label))
            h.putLong(ordinal.toLong())
        }
        return ElementKey(h.finalise())
    }

    /** Structural hash: keys + roles (§6.3). */
    fun structuralHash(nodes: List<UiNode>): Long {
        val h = Hash64()
        h.putString("osf-struct-v1")
        h.putLong(nodes.size.toLong())
        for (n in nodes) {
            h.putLong(n.key.hash)
            h.putString(n.role.name)
        }
        return h.finalise()
    }

    /** Full hash: plus labels and flags, clock text normalised (§6.3). */
    fun fullHash(nodes: List<UiNode>): Long {
        val h = Hash64()
        h.putString("osf-full-v1")
        h.putLong(nodes.size.toLong())
        for (n in nodes) {
            h.putLong(n.key.hash)
            h.putString(n.role.name)
            h.putString(OsfText.normaliseClock(OsfText.normaliseLabel(n.label)))
            h.putLong(n.state.size.toLong())
            n.state.sortedBy { it.name }.forEach { h.putString(it.name) }
        }
        return h.finalise()
    }

    /** §6.3 screen signature, as the approval token binds it (§9.2 item 5). */
    fun screenSignature(packageName: String, windowTitle: String?, structuralHash: Long): ScreenSignature =
        ScreenSignature(packageName, windowTitle, structuralHash)
}

/**
 * §6.1 rule 10: numbering is sticky while the screen signature holds (package + title + ≥60 % key
 * overlap). A persisting key keeps its number, a new key gets max+1, and — review fix 6 — the
 * epoch keeps a monotonically increasing high-water counter, so a number is never reused within
 * an epoch even after its node disappears. When the signature changes, numbering restarts at 1
 * in a fresh epoch.
 *
 * The counter cannot be recovered from a [Snapshot] alone (a snapshot does not record retired
 * numbers), so the caller threads [Numbering] from snapshot to snapshot — [OsfSnapshots.buildBuilt]
 * is the assembly path that does it; [assign] with a bare `prev` snapshot is the lossy fallback.
 */
object StickyNumbering {

    const val MIN_KEY_OVERLAP = 0.6

    /** The numbers of one snapshot plus the epoch's high-water mark and signature. */
    data class Numbering(
        val packageName: String,
        val windowTitle: String?,
        val numbers: Map<dev.operator.core.api.ElementKey, Int>,
        val highWater: Int,
    )

    /** Numbers in [keysInOrder] order, plus the state to thread into the next snapshot. */
    data class Assignment(val numbers: List<Int>, val numbering: Numbering)

    fun numberingHolds(prev: Snapshot, curr: Snapshot): Boolean =
        numberingHolds(numberingOf(prev), curr.screenSignature.packageName, curr.screenSignature.windowTitle, curr.nodes.map { it.key })

    /**
     * Overlap is |prev ∩ curr| / max(|prev|, |curr|): the fraction of the larger key set that
     * persists (the design's "≥60 % key overlap" without a denominator; this reading keeps a
     * growing list sticky).
     */
    fun numberingHolds(prev: Numbering, currPackage: String, currWindowTitle: String?, currKeys: List<dev.operator.core.api.ElementKey>): Boolean {
        if (prev.packageName != currPackage) return false
        if (prev.windowTitle != currWindowTitle) return false
        val a = prev.numbers.keys
        val b = currKeys.toHashSet()
        if (a.isEmpty() && b.isEmpty()) return true
        val denom = maxOf(a.size, b.size)
        return a.intersect(b).size.toDouble() / denom >= MIN_KEY_OVERLAP
    }

    /** Lossy derivation from a bare snapshot: retired numbers are unknowable from it alone. */
    fun numberingOf(snapshot: Snapshot): Numbering = Numbering(
        packageName = snapshot.screenSignature.packageName,
        windowTitle = snapshot.screenSignature.windowTitle,
        numbers = snapshot.nodes.groupBy({ it.key }, { it.index }).mapValues { (_, v) -> v.first() },
        highWater = snapshot.nodes.maxOfOrNull { it.index } ?: 0,
    )

    /** Legacy convenience over a bare previous snapshot; prefer [assignNumbering]. */
    fun assign(prev: Snapshot?, currPackage: String, currWindowTitle: String?, keysInOrder: List<dev.operator.core.api.ElementKey>): List<Int> =
        assignNumbering(prev?.let { numberingOf(it) }, currPackage, currWindowTitle, keysInOrder).numbers

    /** The fixed path: threads the epoch counter, never reuses a number within an epoch. */
    fun assignNumbering(
        prev: Numbering?,
        currPackage: String,
        currWindowTitle: String?,
        keysInOrder: List<dev.operator.core.api.ElementKey>,
    ): Assignment {
        if (prev == null || !numberingHolds(prev, currPackage, currWindowTitle, keysInOrder)) {
            val fresh = List(keysInOrder.size) { it + 1 }
            return Assignment(fresh, Numbering(currPackage, currWindowTitle, keysInOrder.zip(fresh).toMap(), fresh.size))
        }
        val numbers = HashMap<dev.operator.core.api.ElementKey, Int>(prev.numbers)
        var highWater = maxOf(prev.highWater, prev.numbers.values.maxOrNull() ?: 0)
        val out = ArrayList<Int>(keysInOrder.size)
        for (k in keysInOrder) {
            val kept = prev.numbers[k]
            if (kept != null) {
                out.add(kept)
            } else {
                highWater += 1
                out.add(highWater)
                numbers[k] = highWater
            }
        }
        return Assignment(out, Numbering(currPackage, currWindowTitle, numbers, highWater))
    }
}

/** FNV-1a 64 over length-prefixed UTF-8 fields. */
internal class Hash64 {
    private var h = 0xcbf29ce484222325uL.toLong()
    private var count = 0L

    private fun byte(b: Int) {
        h = (h xor (b and 0xFF).toLong()) * 0x100000001b3L
        count++
    }

    fun putLong(v: Long) {
        for (i in 0 until 8) byte((v ushr (8 * i)).toInt())
    }

    fun putBoolean(v: Boolean) = byte(if (v) 1 else 0)

    fun putString(s: String) {
        val bytes = s.toByteArray(Charsets.UTF_8)
        putLong(bytes.size.toLong())
        for (b in bytes) byte(b.toInt())
    }

    fun finalise(): Long = h * 31L + count
}
