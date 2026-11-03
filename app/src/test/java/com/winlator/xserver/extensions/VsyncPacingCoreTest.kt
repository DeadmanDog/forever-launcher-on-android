package com.winlator.xserver.extensions

import org.junit.Assert.*
import org.junit.Test

class VsyncPacingCoreTest {
    private val period60 = 16_666_667L

    /** Feeds one held buffer per vsync (a game that is always ready) and returns the released count per vsync. */
    private fun releasesPerVsync(limit: Int, periodNs: Long, vsyncs: Int): List<Int> {
        val core = VsyncPacingCore<Int>()
        core.setLimit(limit)
        var scheduled = 0
        var released = 0
        val out = mutableListOf<Int>()
        for (i in 0 until vsyncs) {
            while (scheduled - released < 2) core.schedule(7, scheduled++, false)
            val n = core.onVsync(1_000_000_000L + i * periodNs).size
            released += n
            out += n
        }
        return out
    }

    @Test fun sixtyOnSixtyHzReleasesExactlyOnePerVsync() {
        assertTrue(releasesPerVsync(60, period60, 120).drop(1).all { it == 1 })
    }

    @Test fun thirtyOnSixtyHzReleasesEverySecondVsync() {
        val r = releasesPerVsync(30, period60, 120).drop(2)
        assertEquals(r.size / 2, r.sum())
        assertTrue(r.zipWithNext().all { (a, b) -> a + b == 1 })
    }

    @Test fun sixtyOnOneTwentyHzReleasesEverySecondVsync() {
        val r = releasesPerVsync(60, 8_333_333L, 240).drop(20)
        assertTrue(r.zipWithNext().all { (a, b) -> a + b == 1 })
    }

    @Test fun fortyFiveOnSixtyHzReleasesThreeOfFour() {
        val r = releasesPerVsync(45, period60, 400).drop(8)
        assertEquals(r.size * 3.0 / 4.0, r.sum().toDouble(), 2.0)
        assertTrue(r.all { it <= 1 })
    }

    @Test fun limitSlightlyBelowThePanelRateLocksToEveryVsyncWithoutPeriodicSkips() {
        // 60 FPS limit on a 60.05 Hz panel: a raw ratio would skip one refresh every ~20 s.
        val r = releasesPerVsync(60, 16_652_789L, 60 * 60).drop(20)
        assertTrue(r.all { it == 1 })
        assertEquals(1.0, VsyncPacingCore.snap(0.9992), 0.0)
        assertEquals(0.5, VsyncPacingCore.snap(0.5004), 0.0)
        assertEquals(0.75, VsyncPacingCore.snap(0.75), 0.0)
    }

    @Test fun skippedVsyncCallbacksCountAsElapsedRefreshesButNeverBurst() {
        val core = VsyncPacingCore<Int>()
        core.setLimit(60)
        repeat(4) { core.schedule(1, it, false) }
        assertEquals(1, core.onVsync(1_000_000_000L).size)
        assertEquals(1, core.onVsync(1_000_000_000L + period60).size)
        // Three refreshes passed while the thread was late: still at most one buffer per window.
        assertEquals(1, core.onVsync(1_000_000_000L + 4 * period60).size)
        assertEquals(period60, core.periodNs())
    }

    @Test fun anIdleWindowDoesNotSaveUpReleasesForABurst() {
        val core = VsyncPacingCore<Int>()
        core.setLimit(60)
        for (i in 0 until 10) core.onVsync(1_000_000_000L + i * period60)
        repeat(3) { core.schedule(1, it, false) }
        assertEquals(1, core.onVsync(1_000_000_000L + 10 * period60).size)
    }

    @Test fun afterIdleThirtyFpsDoesNotReleaseOnTwoConsecutiveVsyncs() {
        val core = VsyncPacingCore<Int>()
        core.setLimit(30)
        for (i in 0 until 10) core.onVsync(1_000_000_000L + i * period60)
        repeat(4) { core.schedule(1, it, false) }
        val r = (10 until 16).map { core.onVsync(1_000_000_000L + it * period60).size }
        assertTrue(r.zipWithNext().none { (a, b) -> a == 1 && b == 1 })
        assertEquals(3, r.sum())
    }

    @Test fun noLimitReleasesImmediatelyAndChangingTheLimitReleasesEverythingHeld() {
        val core = VsyncPacingCore<Int>()
        assertEquals(listOf(5), core.schedule(1, 5, false))
        core.setLimit(60)
        repeat(3) { core.schedule(1, it, false) }
        assertEquals(listOf(0, 1, 2), core.setLimit(30))
        assertFalse(core.hasPending())
        assertTrue(core.setLimit(30).isEmpty())
    }

    @Test fun aNormalHeldScheduleAllocatesNoResultList() {
        val core = VsyncPacingCore<Int>()
        core.setLimit(60)
        val first = core.schedule(1, 0, false)
        val second = core.schedule(1, 1, false)
        assertTrue(first.isEmpty())
        assertSame("the shared empty result, no new list per present", first, second)
        assertSame(first, core.schedule(2, 7, true))
        assertSame(first, core.setLimit(60))
    }

    @Test fun eagerReleaseReturnsDisplacedBuffersOfTheSameWindowOnly() {
        val core = VsyncPacingCore<Int>()
        core.setLimit(60)
        core.schedule(1, 10, false); core.schedule(2, 20, false)
        assertEquals(listOf(10), core.schedule(1, 11, true))
        val released = core.onVsync(1_000_000_000L)
        assertEquals(setOf(11, 20), released.toSet())
    }

    /**
     * The real wrapper's protocol (VsyncFramePacer): a callback is posted only while something is pending, each callback releases what
     * the core allows and calls idle() when the queue is empty afterwards. The game renders 93 frames/s into three buffers and starts
     * the next frame as soon as a buffer comes back. Returns buffers handed back per second over the last 20 s of 30.
     */
    private fun releasesPerSecond(limit: Int, panelHz: Int, gameFps: Int = 93): Double {
        val core = VsyncPacingCore<Int>()
        core.setLimit(limit)
        val vsyncNs = 1_000_000_000L / panelHz
        val produceNs = 1_000_000_000L / gameFps
        var free = 3
        var nextProduce = 0L
        var serial = 0
        var callbackPosted = false
        var nextVsync = vsyncNs
        var released = 0
        val stepNs = 100_000L
        var t = 0L
        val total = 30_000_000_000L
        while (t < total) {
            if (free > 0 && t >= nextProduce) {
                free--; serial++; nextProduce = t + produceNs
                val now = core.schedule(1, serial, false)
                free += now.size
                if (core.hasPending() && !callbackPosted) callbackPosted = true
            }
            if (t >= nextVsync) {
                if (callbackPosted) {
                    callbackPosted = false
                    val out = core.onVsync(nextVsync)
                    free += out.size
                    if (t >= 10_000_000_000L) released += out.size
                    if (core.hasPending()) callbackPosted = true else core.idle()
                }
                nextVsync += vsyncNs
            }
            t += stepNs
        }
        return released / 20.0
    }

    @Test fun aSixtyLimitHoldsOnEveryPanelRateEvenWithTheQueueEmptyingAfterEachRelease() {
        for (hz in listOf(60, 90, 120, 144, 165)) {
            val rate = releasesPerSecond(60, hz)
            assertEquals("60 FPS limit on a $hz Hz panel", 60.0, rate, 1.5)
        }
    }

    @Test fun aThirtyLimitHoldsOnAOneTwentyHertzPanel() {
        assertEquals(30.0, releasesPerSecond(30, 120), 1.0)
    }

    @Test fun theLimitDoesNotThrottleAGameThatIsSlowerThanIt() {
        assertEquals(45.0, releasesPerSecond(60, 120, gameFps = 45), 2.0)
    }

    @Test fun thePanelPeriodIsLearnedAcrossAnEmptyQueue() {
        val core = VsyncPacingCore<Int>()
        core.setLimit(60)
        val vsync = 8_333_333L
        var t = 1_000_000_000L
        repeat(6) {
            core.schedule(1, it, false)
            core.onVsync(t)
            core.idle()                     // the queue is empty after every release: the wrapper says so each time
            t += vsync
        }
        assertEquals(vsync, core.periodNs())
    }

    @Test fun aPauseIsNotLearnedAsAPeriod() {
        val core = VsyncPacingCore<Int>()
        core.setLimit(60)
        core.schedule(1, 1, false); core.onVsync(1_000_000_000L); core.idle()
        core.schedule(1, 2, false); core.onVsync(1_000_000_000L + 5_000_000_000L)      // five seconds later
        assertEquals("the default period stays", 16_666_667L, core.periodNs())
    }
}
