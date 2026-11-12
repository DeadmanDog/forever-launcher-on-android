package app.gamenative.ui.screen.wow

/**
 * Label the event source; neither callback is a GPU or game-internal timestamp.
 * Intervals are measured per window: several mapped windows present on their own timelines, and mixing their timestamps
 * would show intervals the game never had. The reading reports the window with the most intervals (the game's own).
 */
internal class DiagnosticFrames {
    enum class Source { NONE, RENDERER_CALLBACK, X_PRESENT_REQUEST, GAMESCOPE_PRODUCED }
    private class Window { var last = 0L; var count = 0; var sum = 0L; var max = 0L; var over33 = 0; var idleTakes = 0 }
    private companion object { const val IDLE_TAKES_BEFORE_FORGET = 5 }
    private var source = Source.NONE
    private val windows = HashMap<Int, Window>()
    @Synchronized fun frame(now: Long, signal: Source = Source.RENDERER_CALLBACK, window: Int = 0) {
        if (source != signal) {
            source = signal
            windows.clear()
        }
        val w = windows.getOrPut(window) { Window() }
        if (w.last != 0L && now > w.last) {
            val delta = now - w.last
            w.count++; w.sum += delta; w.max = maxOf(w.max, delta)
            if (delta > 33_333_333L) w.over33++
        }
        w.last = now
        w.idleTakes = 0
    }
    data class Reading(val intervals: Int, val meanMs: Double, val maxMs: Double, val over33Ms: Int, val source: Source,
        val windowId: Int = 0, val windows: Int = 0)
    @Synchronized fun take(): Reading {
        val busiest = windows.entries.maxByOrNull { it.value.count }
        val w = busiest?.value
        val value = if (w == null || w.count == 0) Reading(0, 0.0, 0.0, 0, source, busiest?.key ?: 0, windows.size)
            else Reading(w.count, w.sum.toDouble() / w.count / 1_000_000, w.max / 1_000_000.0, w.over33, source, busiest.key, windows.size)
        // A window that stays silent for several readings is forgotten (bounded map); the others keep their last timestamp.
        windows.values.forEach {
            it.idleTakes = if (it.count == 0) it.idleTakes + 1 else 0
            it.count = 0; it.sum = 0; it.max = 0; it.over33 = 0
        }
        windows.values.removeAll { it.idleTakes >= IDLE_TAKES_BEFORE_FORGET }
        return value
    }
}
