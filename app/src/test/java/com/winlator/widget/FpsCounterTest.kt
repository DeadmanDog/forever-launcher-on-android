package com.winlator.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FpsCounterTest {
    /** Feeds [fps] frames per second for [seconds] seconds starting at [startMs]; returns the time after the last frame. */
    private fun run(c: FpsCounter, startMs: Long, seconds: Int, fps: Int, limit: Int = 0): Long {
        var t = startMs
        val step = 1000.0 / fps
        var exact = startMs.toDouble()
        repeat(seconds * fps) {
            exact += step
            t = exact.toLong()
            c.onFrame(t, limit)
        }
        return t
    }

    @Test fun aSteadyStreamReadsItsRate() {
        val c = FpsCounter()
        val end = run(c, 10_000, 3, 60)
        assertEquals(60f, c.fps(end), 1.5f)
    }

    @Test fun theReadingBecomesZeroWhenFramesStop() {
        val c = FpsCounter()
        val end = run(c, 10_000, 3, 60)
        assertEquals(60f, c.fps(end + 500), 1.5f)             // a short gap keeps the reading
        assertEquals(0f, c.fps(end + FpsCounter.STALE_MS + 1), 0f)   // a frozen game shows no measurement
    }

    @Test fun theFirstFrameAfterALongPauseIsNotDividedByThePause() {
        val c = FpsCounter()
        val end = run(c, 10_000, 3, 60)
        val resume = end + 10_000
        c.onFrame(resume, 0)
        // Before the fix this window was (frames since the last reading) / 10 s = ~0.1 FPS right after the resume.
        assertEquals(0f, c.fps(resume + 10), 0f)
        val end2 = run(c, resume, 2, 60)
        assertEquals(60f, c.fps(end2), 1.5f)
    }

    @Test fun aNewReadingIsReportedOncePerWindow() {
        val c = FpsCounter()
        var fresh = 0
        var t = 1000L
        repeat(120) { t += 16; if (c.onFrame(t, 0)) fresh++ }
        assertTrue("about two readings per second of frames, got $fresh", fresh in 3..5)
    }

    @Test fun aBurstJustAboveTheLimitIsShownAsTheLimit() {
        val c = FpsCounter()
        val end = run(c, 10_000, 2, 66, limit = 60)
        assertEquals(60f, c.fps(end), 0f)
    }

    @Test fun aReadingWellAboveTheLimitIsShownUnchanged() {
        val c = FpsCounter()
        val end = run(c, 10_000, 3, 93, limit = 60)
        assertEquals("a limiter that does not work must not be hidden", 93f, c.fps(end), 3f)
    }

    @Test fun resetForgetsEverything() {
        val c = FpsCounter()
        val end = run(c, 10_000, 2, 60)
        c.reset()
        assertEquals(0f, c.fps(end), 0f)
        assertFalse(c.onFrame(end + 1, 0))
    }
}
