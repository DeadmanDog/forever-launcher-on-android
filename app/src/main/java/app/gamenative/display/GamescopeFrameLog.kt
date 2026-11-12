package app.gamenative.display

/**
 * The per-frame log of a Gamescope session for frames.csv: the time of every frame the session produced (the daemon's producer
 * reports, i.e. the game's own acquires). Kept apart from GameNativeDrm so the diagnostics can drain it without loading the
 * Gamescope bridge library in XServer sessions. Filled only while FrameTrace is on (the diagnostics read it).
 */
object GamescopeFrameLog {
    private const val CAPACITY = 4096
    private val numbers = LongArray(CAPACITY)
    private val times = LongArray(CAPACITY)
    private var head = 0
    private var count = 0
    private var sequence = 0L

    @JvmStatic fun record(nanos: Long) {
        if (!com.winlator.renderer.FrameTrace.enabled()) return
        synchronized(this) {
            // A full ring drops its oldest frame; every kept frame keeps its own number (the numbers used to be recomputed from the
            // count, so after an overflow each time was labelled with the next frame's number).
            numbers[head] = ++sequence
            times[head] = nanos
            head = (head + 1) % CAPACITY
            if (count < CAPACITY) count++
        }
    }

    /** Pairs of (sequence number, elapsedRealtime nanos), oldest first; empties the log. */
    @JvmStatic @Synchronized fun take(): LongArray {
        if (count == 0) return LongArray(0)
        val out = LongArray(count * 2)
        val first = (head - count + CAPACITY) % CAPACITY
        for (i in 0 until count) {
            val index = (first + i) % CAPACITY
            out[i * 2] = numbers[index]; out[i * 2 + 1] = times[index]
        }
        count = 0
        return out
    }

    @JvmStatic @Synchronized fun reset() { count = 0; head = 0; sequence = 0 }
}
