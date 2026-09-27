package dev.operator.core.loop

/*
 * The §8.4 recovery ladder, first applicable rung wins.
 *
 *   1. re-observe with a longer settle;
 *   2. scroll the relevant container (at most 3 times);
 *   3. `back` once, unless the form has unsaved input;
 *   4. relaunch the app;
 *   5. REPLAN (at most 2 per task);
 *   6. ASK_OWNER;
 *   7. ABORT with a report.
 *
 * The per-task limits make the ladder monotonic: once a rung is spent it is never offered again, so
 * a stuck task always reaches ASK_OWNER and then ABORT rather than cycling. [RecoveryContext] carries
 * the applicability facts the loop already knows (whether a container can be scrolled, whether the
 * app can be relaunched, whether `back` is safe).
 *
 * Design: §8.4 recovery ladder, ADR-0009 item 7.
 */

/** The next recovery action. */
enum class RecoveryRung {
    RE_OBSERVE,
    SCROLL,
    BACK,
    RELAUNCH,
    REPLAN,
    ASK_OWNER,
    ABORT,
}

/** The applicability facts the ladder needs. */
data class RecoveryContext(
    /** A scroll container exists on the current screen. */
    val canScroll: Boolean,
    /** `back` is safe: not a form with unsaved input (§8.4 rung 3). */
    val canGoBack: Boolean,
    /** The foreground app's package, for the launcher intent; null when unknown. */
    val appPackage: String?,
)

/** One task's recovery ladder. */
class RecoveryLadder(private val config: LoopConfig) {

    private var reobserves = 0
    private var scrolls = 0
    private var backs = 0
    private var relaunches = 0
    private var replans = 0
    private var asked = false

    val replanCount: Int get() = replans

    /** The first applicable rung, consuming its allowance. */
    fun next(context: RecoveryContext): RecoveryRung = when {
        reobserves < config.maxReobserves -> {
            reobserves++
            RecoveryRung.RE_OBSERVE
        }

        context.canScroll && scrolls < config.maxScrollRecoveries -> {
            scrolls++
            RecoveryRung.SCROLL
        }

        context.canGoBack && backs < config.maxBackRecoveries -> {
            backs++
            RecoveryRung.BACK
        }

        context.appPackage != null && relaunches < config.maxRelaunchRecoveries -> {
            relaunches++
            RecoveryRung.RELAUNCH
        }

        replans < config.maxReplans -> {
            replans++
            RecoveryRung.REPLAN
        }

        !asked -> {
            asked = true
            RecoveryRung.ASK_OWNER
        }

        else -> RecoveryRung.ABORT
    }

    /** Consumes a REPLAN allowance for a plan-generation failure; false when the limit is spent. */
    fun tryReplan(): Boolean {
        if (replans >= config.maxReplans) return false
        replans++
        return true
    }

    /** A REPLAN was requested but the plan generator failed; spend the rung without looping. */
    fun markReplanExhausted() {
        replans = config.maxReplans
    }
}
