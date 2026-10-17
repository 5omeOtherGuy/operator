package dev.operator.core.loop

/*
 * Loop detection over the two screen hashes (§8.4).
 *
 * The detector keeps the last [window] full hashes, the (full hash, action) pairs already taken, and
 * the banned pairs. A pair seen a second time is banned for that state; a full hash seen
 * [hashRepeats] times inside the window, or [noProgressLimit] steps with no achieved verify, is a
 * recovery signal. The banned action keys are handed back to the grammar builder, so a banned action
 * is removed from the enums of *that* state and not merely rejected after the fact (§8.3, §8.4).
 *
 * Design: §8.4 loop detection, §8.3 (banned pairs leave the grammar), ADR-0009 item 6.
 */

/** The detector's verdict for one (state, action) observation. */
data class LoopObservation(
    /** The pair had been seen before, so it is now banned. */
    val bannedNow: Boolean,
    /** The full hash has repeated enough times inside the window to call RECOVER. */
    val hashRepeated: Boolean,
)

/** One task's loop detector. */
class LoopDetector(private val config: LoopConfig) {

    private val window = ArrayDeque<Long>()
    private val seenPairs = HashSet<String>()
    private val banned = LinkedHashSet<String>()
    private var noProgress = 0

    val bans: List<String> get() = banned.toList()

    /**
     * Records the current observation. The (hash, action) pair is added; if it was already present it
     * is banned and [LoopObservation.bannedNow] is true. The hash history then decides [LoopObservation.hashRepeated].
     */
    fun observe(fullHash: Long, actionKey: String): LoopObservation {
        val pair = pairKey(fullHash, actionKey)
        val bannedNow = if (!seenPairs.add(pair)) {
            banned.add(pair)
            true
        } else {
            false
        }

        window.addLast(fullHash)
        while (window.size > config.loopWindow) window.removeFirst()
        val repeats = window.count { it == fullHash }

        return LoopObservation(bannedNow = bannedNow, hashRepeated = repeats >= config.loopHashRepeats)
    }

    fun isBanned(fullHash: Long, actionKey: String): Boolean = pairKey(fullHash, actionKey) in banned

    /** The action keys banned for [fullHash]; the per-step grammar removes them from its enums (§8.3). */
    fun bannedFor(fullHash: Long): Set<String> {
        val prefix = "$fullHash|"
        return banned.filter { it.startsWith(prefix) }.map { it.substring(prefix.length) }.toSet()
    }

    fun recordProgress() {
        noProgress = 0
    }

    fun recordNoProgress() {
        noProgress++
    }

    val noProgressSteps: Int get() = noProgress

    /** §8.4: 4 consecutive steps with no achieved verify → REPLAN. */
    fun shouldReplan(): Boolean = noProgress >= config.noProgressLimit

    fun resetNoProgress() {
        noProgress = 0
    }

    private fun pairKey(fullHash: Long, actionKey: String): String = "$fullHash|$actionKey"

    companion object {
        /** The readable form used in reports: `s14 tap:9`. */
        fun describe(fullHash: Long, actionKey: String): String = "s$fullHash $actionKey"
    }
}
