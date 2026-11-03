package app.gamenative.ui.widget

/** The HUD's frame-time readings: the average frame time of the last second and the 1% / 0.1% low FPS of the window. */
data class FrameTimeReading(val frames: Int, val frameTimeMs: Float, val low1Fps: Float, val low01Fps: Float)

/**
 * Rolling frame-interval statistics. The lows follow the usual overlay definition (Afterburner / CapFrameX "low average"): the 1% low
 * FPS is 1000 / the average frame time of the slowest 1% of the frames in the window, the 0.1% low FPS the same for the slowest
 * 0.1% (at least one frame). A gap longer than [PAUSE_NS] is a pause (menu, loading, app in the background), not a frame, and is
 * left out so it cannot ruin the lows.
 */
class FrameTimeStats(
    private val windowNs: Long = WINDOW_NS,
    private val capacity: Int = 8192,
    /** True (production): frames are only recorded while somebody asks for readings; false: always (tests). */
    private val onDemand: Boolean = true,
) {
    private val times = LongArray(capacity)
    private val intervalsUs = IntArray(capacity)
    private var head = 0
    private var size = 0
    private var last = 0L
    @Volatile private var interestUntilNs = 0L

    @Synchronized fun record(nowNs: Long) {
        // Nobody read the statistics lately (the HUD rows are off or the HUD is hidden): the present path does no work at all.
        if (onDemand && nowNs > interestUntilNs) return
        val previous = last
        last = nowNs
        if (previous == 0L || nowNs <= previous) return
        val delta = nowNs - previous
        if (delta > PAUSE_NS) return
        times[head] = nowNs
        intervalsUs[head] = (delta / 1_000L).toInt()
        head = (head + 1) % capacity
        if (size < capacity) size++
    }

    @Synchronized fun reset() {
        head = 0; size = 0; last = 0L
    }

    /** Null until [MIN_FRAMES] frames of the window exist, or while no frame arrived during the last [PAUSE_NS]. */
    fun reading(nowNs: Long): FrameTimeReading? {
        interestUntilNs = nowNs + INTEREST_NS
        // Only the copy of the window happens under the lock: record() runs on the present path and must never wait for a sort.
        val sorted: IntArray
        var count = 0
        var recentSum = 0L
        var recentCount = 0
        synchronized(this) {
            if (size == 0) return null
            sorted = IntArray(size)
            for (i in 0 until size) {
                val index = (head - size + i + capacity) % capacity
                val time = times[index]
                if (time >= nowNs - windowNs) sorted[count++] = intervalsUs[index]
                if (time >= nowNs - RECENT_NS) { recentSum += intervalsUs[index]; recentCount++ }
            }
        }
        if (count < MIN_FRAMES || recentCount == 0) return null
        java.util.Arrays.sort(sorted, 0, count)
        return FrameTimeReading(
            frames = count,
            frameTimeMs = recentSum.toFloat() / recentCount / 1_000f,
            low1Fps = lowFps(sorted, count, 0.01),
            low01Fps = lowFps(sorted, count, 0.001),
        )
    }

    companion object {
        const val WINDOW_NS = 30_000_000_000L
        const val RECENT_NS = 1_000_000_000L
        const val PAUSE_NS = 2_000_000_000L
        const val MIN_FRAMES = 30
        /** Recording continues this long after the last reading request. */
        const val INTEREST_NS = 5_000_000_000L

        /** How many of [count] frames make up the slowest [fraction] (at least one). */
        internal fun slowestCount(count: Int, fraction: Double): Int =
            kotlin.math.ceil(count * fraction).toInt().coerceIn(1, count)

        /** FPS of the average frame time of the slowest [fraction] of the first [count] ascending [intervalsUs]. */
        internal fun lowFps(intervalsUs: IntArray, count: Int, fraction: Double): Float {
            val k = slowestCount(count, fraction)
            var sum = 0L
            for (i in count - k until count) sum += intervalsUs[i]
            val average = sum.toDouble() / k
            return (1_000_000.0 / average.coerceAtLeast(1.0)).toFloat()
        }
    }
}
