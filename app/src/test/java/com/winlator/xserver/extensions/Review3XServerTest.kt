package com.winlator.xserver.extensions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Third review round (1.0.8): each case failed on 1.0.7 and is fixed with it. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class Review3XServerTest {
    @Test fun x14_theMscNeverGoesBackwardsWhenTheLimitChanges() {
        val extension = PresentExtension()
        try {
            extension.setFrameRateLimit(60)
            val before = extension.mscFor(10_000_000_000L)
            extension.setFrameRateLimit(30)          // the player lowers the limit in the Quick Menu
            val after = extension.mscFor(10_000_016_000L)
            assertTrue("MSC went from $before back to $after", after >= before)
            assertTrue(extension.mscFor(10_100_000_000L) > after)   // and keeps counting at the new limit
        } finally { extension.close() }
    }

    @Test fun x15_belowTheRefreshRateABufferAfterAnIdleStretchGoesOnTheNextVsync() {
        // 30 FPS on 60 Hz and 60 FPS on 120 Hz: the queue is empty between frames, so every buffer arrives after an idle stretch.
        for ((limit, period) in listOf(30 to 16_666_667L, 60 to 8_333_333L)) {
            val core = VsyncPacingCore<Int>()
            core.setLimit(limit)
            var t = 1_000_000_000L
            for (i in 0 until 20) { core.schedule(1, i, false); core.onVsync(t); t += period }   // learn the period
            while (core.hasPending()) { core.onVsync(t); t += period }
            core.idle()
            t += 5 * period
            core.schedule(1, 99, false)
            var vsyncs = 0
            while (true) { vsyncs++; if (core.onVsync(t).isNotEmpty()) break; t += period }
            assertEquals("limit $limit: vsyncs until the buffer after an idle stretch is released", 1, vsyncs)
        }
    }

    @Test fun x16_aReleasedHoldLeavesNoTimerBehind() {
        val holds = PresentIdleHolds(2000)
        val before = PresentIdleHolds.TIMER.queue.size
        val tokens = (1..50).map { holds.hold {} }
        tokens.forEach(holds::release)
        // A cancelled timer task stayed queued for the full 2 s and woke the timer thread for nothing (~60 a second at 60 FPS).
        assertEquals(before, PresentIdleHolds.TIMER.queue.size)
    }

    @Test fun x17_theTimerPacerForgetsWindowsThatStoppedPresenting() {
        var now = 0L
        val pacer = PresentFramePacer<Int>({ }, { now }, false)
        pacer.setFrameRateLimit(60)
        for (window in 1..200) pacer.schedule(window, window, false)
        now += 10_000_000_000L
        pacer.drainDue()
        pacer.schedule(1000, 1, false)
        val deadlines = PresentFramePacer::class.java.getDeclaredField("nextDeadlines").apply { isAccessible = true }.get(pacer) as Map<*, *>
        assertTrue("deadlines kept for ${deadlines.size} windows", deadlines.size <= 2)
    }

    @Test fun x18_thePhaseDelayStaysInsideOneRefreshAfterTheRateChanges() {
        // A 12 ms delay learned at 60 Hz, then the panel runs at 120 Hz (8.3 ms) with frames already centred: the delay stayed 12 ms,
        // more than a whole refresh, so every release slipped past a vsync.
        val r120 = 8_333_333L
        val align = PresentPhaseAlign(12_000_000L)
        align.update(1, r120 / 2, 900, r120)   // the window measured before the takeover is skipped
        val offset = align.update(2, r120 / 2, 900, r120)
        assertTrue("delay $offset ns at a $r120 ns refresh", offset < r120)
    }
}

class Review4PhaseTest {
    private val r60 = 16_666_667L

    @org.junit.Test fun p1_aSmallPhaseCorrectionDoesNotWrapTheDelayByAWholeRefresh() {
        // Delay 1 ms, frames 3 ms past the middle: the 2 ms that cannot be taken off (a release before the vsync) used to wrap the
        // delay to 14.7 ms. One release then came a whole refresh late: a repeated frame (1.0.9 diag: 65 such jumps in 58 min).
        val align = PresentPhaseAlign(1_000_000L)
        align.update(1, r60 / 2, 900, r60)                       // the window measured before the takeover is skipped
        val next = align.update(2, r60 / 2 - 3_000_000L, 900, r60)
        org.junit.Assert.assertTrue("delay jumped from 1 ms to ${next / 1e6} ms", kotlin.math.abs(next - 1_000_000L) < r60 / 4)
    }

    @org.junit.Test fun p1_framesCloseToTheLatchStillMoveAcross() {
        // 6 ms short of what a release before the vsync would need: frames would sit within a quarter refresh of the latch, so the
        // delay still goes round once (one repeated frame instead of a missed latch every few frames).
        val align = PresentPhaseAlign(1_000_000L)
        align.update(1, r60 / 2, 900, r60)
        val next = align.update(2, r60 / 2 - 7_000_000L, 900, r60)
        org.junit.Assert.assertEquals(Math.floorMod(1_000_000L - 7_000_000L, r60), next)
    }
}
