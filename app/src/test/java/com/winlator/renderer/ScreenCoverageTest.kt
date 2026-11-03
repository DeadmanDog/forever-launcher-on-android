package com.winlator.renderer

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenCoverageTest {
    @Test fun aWindowExactlyOverTheScreenHidesWhatIsBelow() = assertTrue(ScreenCoverage.covers(0, 0, 100, 100, 100, 100))
    @Test fun aLargerWindowThatStillCoversTheScreenHidesWhatIsBelow() {
        assertTrue(ScreenCoverage.covers(-10, -10, 200, 200, 100, 100))
        assertTrue(ScreenCoverage.covers(0, 0, 1344, 700, 1344, 608))
    }
    // The review's table: 100x100 window on a 100x100 screen, moved aside, leaves part of the lower window visible.
    @Test fun aScreenSizedWindowMovedAsideDoesNotHideWhatIsBelow() {
        assertFalse(ScreenCoverage.covers(50, 0, 100, 100, 100, 100))
        assertFalse(ScreenCoverage.covers(-50, 0, 100, 100, 100, 100))
        assertFalse(ScreenCoverage.covers(50, 50, 100, 100, 100, 100))
    }
    @Test fun aOnePixelGapIsNotCovered() {
        assertFalse(ScreenCoverage.covers(1, 0, 100, 100, 100, 100))
        assertFalse(ScreenCoverage.covers(0, 0, 99, 100, 100, 100))
        assertFalse(ScreenCoverage.covers(0, 0, 100, 99, 100, 100))
    }
    @Test fun aSmallerWindowIsNeverACover() = assertFalse(ScreenCoverage.covers(0, 0, 50, 50, 100, 100))
    @Test fun hugeValuesDoNotOverflow() = assertTrue(ScreenCoverage.covers(-2_000_000_000L, -2_000_000_000L, 4_000_000_000L, 4_000_000_000L, 100, 100))
}
