package app.gamenative.ui.screen.wow

import android.os.SystemClock
import kotlin.math.exp

/**
 * Honest progress of "tap Play -> the game's window is on screen".
 *
 * The launch pipeline reports the milestones it has really reached; between two milestones the bar eases
 * towards the next one (a launch step has no byte count), but never past it, so the bar only reaches 100%
 * when the game's own window is mapped.
 */
internal object WowLaunchProgress {
    private class Phase(val floor: Float, val cap: Float, val tauMs: Float, val since: Long)

    @Volatile private var phase = Phase(0f, 0f, 1f, 0L)
    @Volatile var active = false
        private set

    /** Starts a run unless one is already in progress (the launcher and the view model both call it). */
    @Synchronized
    fun begin() {
        if (active) return
        active = true
        phase = Phase(0.02f, 0.08f, 2000f, SystemClock.elapsedRealtime())
    }

    /** A milestone was reached: the bar is at least [floor] and may ease up to [cap] over roughly [tauMs]. */
    @Synchronized
    fun reach(floor: Float, cap: Float, tauMs: Long) {
        if (!active) begin()
        val now = displayAt(SystemClock.elapsedRealtime())
        val f = maxOf(floor, now)
        phase = Phase(f, maxOf(cap, f + 0.01f), tauMs.toFloat(), SystemClock.elapsedRealtime())
    }

    /** The game window is on screen. */
    @Synchronized
    fun complete() {
        if (!active) return
        phase = Phase(1f, 1f, 1f, SystemClock.elapsedRealtime())
    }

    /** The splash is gone; the next launch starts from zero. */
    @Synchronized
    fun end() {
        active = false
        phase = Phase(0f, 0f, 1f, 0L)
    }

    fun display(): Float = if (!active) -1f else displayAt(SystemClock.elapsedRealtime())

    private fun displayAt(nowMs: Long): Float {
        val p = phase
        if (p.cap <= p.floor) return p.floor
        val dt = (nowMs - p.since).coerceAtLeast(0L).toFloat()
        return (p.floor + (p.cap - p.floor) * (1f - exp(-dt / p.tauMs))).coerceIn(0f, 1f)
    }
}
