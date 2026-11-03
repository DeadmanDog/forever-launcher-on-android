package com.winlator.renderer

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HudWindowFilterTest {
    private val second = 1_000_000_000L

    @Test fun withoutATrackedWindowEverythingCounts() {
        val f = HudWindowFilter()
        assertTrue(f.shouldCount(false, false, 5 * second))
    }

    @Test fun theTrackedWindowAndWindowsInsideItCount() {
        val f = HudWindowFilter()
        assertTrue(f.shouldCount(true, true, 5 * second))
    }

    @Test fun aSecondWindowDoesNotAddToTheGamesRate() {
        val f = HudWindowFilter()
        var counted = 0
        var t = 5 * second
        repeat(120) {                                   // the game at 60/s and a tool window at 33/s for one second
            t += 8_000_000L
            if (f.shouldCount(true, true, t)) counted++        // game
            if (it % 2 == 0 && f.shouldCount(true, false, t + 1_000)) counted++   // tool
        }
        assertTrue("only the game's requests, got $counted", counted == 120)
    }

    @Test fun trackingThatNeverMatchesWhatPresentsCountsEverythingNotZero() {
        // The 1.3.20 failure: the presenting window is not the tracked one, nothing ever matches -> the HUD read 0.0 FPS.
        val f = HudWindowFilter()
        var counted = 0
        var t = 5 * second
        repeat(100) { t += 16_000_000L; if (f.shouldCount(true, false, t)) counted++ }
        assertTrue("every request counts, got $counted", counted == 100)
    }

    @Test fun whenTheTrackedWindowGoesQuietAForeignWindowCountsAfterOneSecond() {
        val f = HudWindowFilter()
        val start = 5 * second
        assertTrue(f.shouldCount(true, true, start))
        assertFalse("within the second the foreign window is still ignored", f.shouldCount(true, false, start + second / 2))
        assertTrue("after a quiet second it is counted", f.shouldCount(true, false, start + second + 1))
    }

    @Test fun resetForgetsTheLastMatch() {
        val f = HudWindowFilter()
        f.shouldCount(true, true, 5 * second)
        f.reset()
        assertTrue(f.shouldCount(true, false, 5 * second + 1))
    }
}
