package app.gamenative.ui.widget

import org.junit.Assert.*
import org.junit.Test

class FrameTimeStatsTest {
    private val ms = 1_000_000L

    /** [count] frames spaced [intervalMs] apart starting at [start]; returns the timestamp of the last one. */
    private fun feed(stats: FrameTimeStats, start: Long, count: Int, intervalMs: Double): Long {
        var t = start
        stats.record(t)
        repeat(count) { t += (intervalMs * ms).toLong(); stats.record(t) }
        return t
    }

    @Test fun aSteadySixtyFpsHasEqualLowsAndAFrameTimeNearSixteenPointSeven() {
        val stats = FrameTimeStats(onDemand = false)
        val end = feed(stats, 1_000 * ms, 600, 1000.0 / 60)
        val r = stats.reading(end)!!
        assertEquals(16.7f, r.frameTimeMs, 0.1f)
        assertEquals(60f, r.low1Fps, 0.5f)
        assertEquals(60f, r.low01Fps, 0.5f)
    }

    @Test fun oneSlowFrameInAThousandIsTheZeroPointOneLowAndOnlyOneTenthOfTheOnePercentLow() {
        val stats = FrameTimeStats(onDemand = false)
        var t = feed(stats, 1_000 * ms, 998, 10.0)
        t += 100 * ms; stats.record(t)               // a 100 ms hitch = 10 FPS
        t = feed(stats, t, 1, 10.0)
        val r = stats.reading(t)!!
        assertEquals("1% low averages the slowest 10 frames: nine 10 ms and one 100 ms", 1000f / 19f, r.low1Fps, 0.5f)
        assertEquals("0.1% low is the single worst frame", 10f, r.low01Fps, 0.5f)
    }

    @Test fun tenPercentSlowFramesPullTheOnePercentLowDown() {
        val stats = FrameTimeStats(onDemand = false)
        var t = 1_000 * ms; stats.record(t)
        repeat(1000) { i -> t += (if (i % 10 == 0) 40 else 10) * ms; stats.record(t) }
        val r = stats.reading(t)!!
        assertEquals(25f, r.low1Fps, 0.5f)
        assertEquals(25f, r.low01Fps, 0.5f)
    }

    @Test fun tooFewFramesGiveNoReading() {
        val stats = FrameTimeStats(onDemand = false)
        val end = feed(stats, 1_000 * ms, 10, 16.0)
        assertNull(stats.reading(end))
    }

    @Test fun aPauseIsNotAFrame() {
        val stats = FrameTimeStats(onDemand = false)
        var t = feed(stats, 1_000 * ms, 300, 16.0)
        t += 5_000 * ms                                 // the Quick Menu was open
        t = feed(stats, t, 300, 16.0)
        val r = stats.reading(t)!!
        assertEquals("the pause does not ruin the lows", 62.5f, r.low01Fps, 1f)
    }

    @Test fun noReadingWhileTheGameStopsPresenting() {
        val stats = FrameTimeStats(onDemand = false)
        val end = feed(stats, 1_000 * ms, 300, 16.0)
        assertNotNull(stats.reading(end))
        assertNull("no frame during the last two seconds", stats.reading(end + 3_000 * ms))
    }

    @Test fun framesOlderThanTheWindowLeaveTheStatistics() {
        val stats = FrameTimeStats(windowNs = 10_000 * ms, onDemand = false)
        var t = feed(stats, 1_000 * ms, 200, 100.0)      // slow start: 10 FPS for 20 s
        t = feed(stats, t, 800, 10.0)                    // then 100 FPS for 8 s... plus the window moves on
        t = feed(stats, t, 800, 10.0)
        val r = stats.reading(t)!!
        assertEquals(100f, r.low01Fps, 1f)
    }

    @Test fun resetForgetsEverything() {
        val stats = FrameTimeStats(onDemand = false)
        val end = feed(stats, 1_000 * ms, 300, 16.0)
        stats.reset()
        assertNull(stats.reading(end))
    }

    @Test fun theSlowestShareIsAtLeastOneFrame() {
        assertEquals(10, FrameTimeStats.slowestCount(1000, 0.01))
        assertEquals(1, FrameTimeStats.slowestCount(1000, 0.001))
        assertEquals(1, FrameTimeStats.slowestCount(30, 0.001))
        assertEquals(1, FrameTimeStats.slowestCount(1, 0.01))
        assertEquals(2, FrameTimeStats.slowestCount(2000, 0.001))
    }

    @Test fun nothingIsRecordedUntilSomebodyAsksForReadings() {
        val stats = FrameTimeStats()
        var t = 1_000 * ms
        repeat(200) { stats.record(t); t += 16 * ms }
        assertNull("no reading was ever requested, so the present path recorded nothing", stats.reading(t))
        // The first request starts the recording; a window of frames later there is a reading.
        repeat(100) { stats.record(t); t += 16 * ms; stats.reading(t) }
        assertNotNull(stats.reading(t))
    }

    @Test fun recordingStopsAgainWhenTheReadingsStop() {
        val stats = FrameTimeStats()
        var t = 1_000 * ms
        stats.reading(t)
        repeat(100) { stats.record(t); t += 16 * ms }
        assertNotNull(stats.reading(t))
        t += 20_000 * ms                          // nobody reads for a long time
        repeat(100) { stats.record(t); t += 16 * ms }
        assertNull("the old frames are too old and the new ones were not recorded", stats.reading(t))
    }
}
