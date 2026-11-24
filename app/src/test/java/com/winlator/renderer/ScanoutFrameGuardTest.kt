package com.winlator.renderer

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Review 3 (3): an offset or resized frame of the scanout window leaves scanout instead of freezing the picture. */
class ScanoutFrameGuardTest {
    private val window = Any()
    private val other = Any()

    @Test fun anOffsetFrameOfTheScanoutWindowTakesItOutOfScanout() {
        val guard = ScanoutFrameGuard()
        assertTrue(guard.compositorFrame(window, true, true, false))
        assertTrue(guard.blocks(window))
        assertFalse(guard.blocks(other))
        // The same again changes nothing more.
        assertFalse(guard.compositorFrame(window, true, true, false))
    }

    @Test fun itComesBackOnlyAfterEnoughFullFramesInARow() {
        val guard = ScanoutFrameGuard()
        guard.compositorFrame(window, true, true, false)
        repeat(ScanoutFrameGuard.FULL_FRAMES_TO_RETURN - 2) {
            assertFalse(guard.compositorFrame(window, false, false, true))
        }
        // One offset frame starts the count again.
        assertFalse(guard.compositorFrame(window, false, false, false))
        repeat(ScanoutFrameGuard.FULL_FRAMES_TO_RETURN - 1) {
            assertFalse(guard.compositorFrame(window, false, false, true))
        }
        assertTrue(guard.blocks(window))
        assertTrue(guard.compositorFrame(window, false, false, true))
        assertFalse(guard.blocks(window))
    }

    @Test fun fullFramesAndOtherWindowsNeverBlock() {
        val guard = ScanoutFrameGuard()
        assertFalse(guard.compositorFrame(window, true, true, true))
        assertFalse(guard.compositorFrame(other, true, false, false))
        assertFalse(guard.compositorFrame(window, false, false, false))
        assertFalse(guard.blocks(window))
        assertFalse(guard.blocks(other))
    }
}
