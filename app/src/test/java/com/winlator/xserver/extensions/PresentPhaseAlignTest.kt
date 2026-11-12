package com.winlator.xserver.extensions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PresentPhaseAlignTest {
    private val r120 = 8_333_333L
    private val r60 = 16_666_667L

    /** The wait before the panel takes a frame when releases are delayed by [offset]: a later release shortens it. */
    private fun margin(base: Long, offset: Long, refresh: Long) = Math.floorMod(base - offset, refresh)

    @Test fun framesOnTheEdgeMoveToMidRefresh() {
        val align = PresentPhaseAlign()
        val offset = align.update(1, 500_000, 900, r120)
        assertEquals(r120 / 2.0, margin(500_000, offset, r120).toDouble(), 2.0)
    }

    @Test fun theWindowAfterAShiftIsSkippedAndTheSameWindowCountsOnce() {
        val align = PresentPhaseAlign()
        val first = align.update(1, 500_000, 900, r120)
        assertEquals(first, align.update(2, 7_000_000, 900, r120))
        assertEquals(first, align.update(3, 4_100_000, 900, r120))
        assertEquals(first, align.update(3, 100_000, 900, r120))
    }

    @Test fun aPacerThatTakesOverContinuesFromTheDelayInForce() {
        // Frames measured with a 3 ms delay, now mid-refresh: the new pacer keeps 3 ms instead of restarting from 0 + error.
        val align = PresentPhaseAlign(3_000_000L)
        assertEquals(3_000_000L, align.offsetNs())
        assertEquals(3_000_000L, align.update(7, 500_000, 900, r120))   // measured before the takeover: skipped
        val next = align.update(8, 500_000, 900, r120)
        // It corrects from 3 ms (not from 0). Mid-refresh would need a release before the vsync, so it stops at 0 (1.1.2: no wrap
        // for a small overshoot): frames then sit 3.5 ms from the latch, more than a quarter refresh.
        assertEquals(0L, next)
        assertTrue(margin(500_000, next - 3_000_000L, r120) in r120 / 4..r120 * 3 / 4)
        assertEquals(0L, PresentPhaseAlign(0L).offsetNs())
    }

    @Test fun scatteredOrNearlyCentredPhasesChangeNothing() {
        val align = PresentPhaseAlign()
        assertEquals(0L, align.update(1, 500_000, 300, r120))
        assertEquals(0L, align.update(2, r60 / 2 + r60 / 10, 900, r60))
        assertEquals(0L, align.update(3, 1_000_000, 900, 0))
    }

    @Test fun closedLoopSettlesMidRefreshAndStaysThere() {
        for (refresh in listOf(r60, r120)) for (base in listOf(0L, 300_000L, refresh / 3, refresh - 200_000L)) {
            val align = PresentPhaseAlign()
            var offset = 0L
            val history = ArrayList<Long>()
            for (window in 1L..40L) {
                // a little window-to-window noise on the measured mean
                val noise = if (window % 2 == 0L) 150_000L else -150_000L
                offset = align.update(window, margin(base + noise, offset, refresh), 900, refresh)
                history.add(offset)
            }
            val settled = margin(base, offset, refresh)
            // 1.1.2: frames settle mid-refresh, or, when that would need the delay to wrap round (a repeated frame), at least a
            // quarter refresh away from the latch.
            assertTrue("refresh=$refresh base=$base settled=$settled", settled in refresh / 4 - 150_000..refresh * 3 / 4 + 150_000)
            for ((a, b) in history.zipWithNext()) assertTrue("the delay wrapped: $history", Math.abs(b - a) < refresh * 3 / 4 || settledBefore(a, b))
            assertTrue("offset keeps moving: $history", history.takeLast(20).toSet().size == 1)
            assertTrue(offset in 0 until refresh)
        }
    }

    /** A wrap is allowed only out of the latch's quarter refresh (the frames were too close to it). */
    private fun settledBefore(a: Long, b: Long) = false

    @Test fun releaseAccuracyIsReportedAndReset() {
        PresentPhase.takeReleaseStats()
        PresentPhase.noteRelease(-2_000_000)
        PresentPhase.noteRelease(4_000_000)
        PresentPhase.noteSampleCost(5_000)
        assertEquals("2,3000,4000,5", PresentPhase.takeReleaseStats())
        assertEquals("0,0,0,0", PresentPhase.takeReleaseStats())
    }
}
