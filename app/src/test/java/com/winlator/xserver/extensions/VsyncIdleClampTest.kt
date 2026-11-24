package com.winlator.xserver.extensions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 1.2.25: the "starts over after idle" cut in VsyncPacingCore.schedule only after a real idle stretch, for a caller that ticks
 * onVsync on every refresh even with nothing pending (this test). 1.2.26 correction: VsyncFramePacer stops its callback while the
 * queue is empty and counts the skipped refreshes at the next one, so in the app the cut never removed credit; the 57.6 FPS of the
 * 1.2.25 notes came from a model that ticked every refresh.
 */
class VsyncIdleClampTest {
    private val refresh144 = 1_000_000_000L / 144

    /** One buffer: the next one is presented [renderNs] after each release. Returns the release gaps in refreshes. */
    private fun gaps(renderNs: Long, vsyncs: Int = 600): List<Int> {
        val core = VsyncPacingCore<Int>()
        core.setLimit(60)
        var ready = -1L
        var pending = false
        var last = -1
        val gaps = mutableListOf<Int>()
        for (v in 0 until vsyncs) {
            val t = v * refresh144
            if (v == 0 || (!pending && ready in 0..t)) { core.schedule(1, v, false); pending = true }
            if (core.onVsync(t).isNotEmpty()) {
                if (last >= 0 && v > 100) gaps += v - last
                last = v; pending = false; ready = t + renderNs
            }
        }
        return gaps
    }

    @Test
    fun oneBufferAt144HzKeepsTheSixtyFpsPattern() {
        for (renderMs in listOf(5L, 8L, 10L, 12L)) {
            val g = gaps(renderMs * 1_000_000L)
            val fps = 144.0 * g.size / g.sum()
            assertEquals("render $renderMs ms: ${g.take(10)}", 60.0, fps, 0.3)
            assertTrue(g.all { it == 2 || it == 3 })
        }
    }

    @Test
    fun aRealIdleStretchStillStartsOver() {
        // Nothing pending for a long time (a loading screen): the next buffer waits for the next vsync, the one after it keeps the
        // pattern - never two releases on consecutive refreshes.
        val core = VsyncPacingCore<Int>()
        core.setLimit(60)
        for (v in 0 until 20) core.onVsync(v * refresh144)
        core.schedule(1, 1, false)
        core.schedule(1, 2, false)
        val released = mutableListOf<Int>()
        for (v in 20 until 40) if (core.onVsync(v * refresh144).isNotEmpty()) released += v
        assertTrue("releases $released", released.size >= 2 && released[1] - released[0] >= 2)
    }
}
