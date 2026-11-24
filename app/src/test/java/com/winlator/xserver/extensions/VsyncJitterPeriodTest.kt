package com.winlator.xserver.extensions

import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/**
 * 1.2.21: the app limiter learned the panel period from the SMALLEST recent vsync delta; with jittered vsync timestamps that is
 * always too short, and with 60 FPS on a 144 Hz panel (the player's) fewer buffers went out than the limit (57.6 FPS at 200 us).
 */
class VsyncJitterPeriodTest {
    private fun releasedFps(hz: Double, limit: Int, jitterUs: Double): Double {
        val vs = (1e9 / hz).toLong()
        val rnd = Random(3)
        val core = VsyncPacingCore<Int>()
        core.setLimit(limit)
        var id = 0; var released = 0; var first = 0L; var last = 0L
        for (v in 1 until (hz * 60).toInt()) {
            val t = v * vs + ((rnd.nextDouble() * 2 - 1) * jitterUs * 1000).toLong()
            if (!core.hasPending()) core.schedule(1, id++, false)
            val out = core.onVsync(t)
            if (!core.hasPending()) core.schedule(1, id++, false)
            if (v > hz * 5) { if (first == 0L) first = t; released += out.size; last = t }
        }
        return released * 1e9 / (last - first)
    }

    @Test fun sixtyOnA144HzPanelKeepsSixtyWithJitteredVsyncs() {
        for (jitter in listOf(0.0, 50.0, 100.0, 200.0)) {
            val fps = releasedFps(144.0, 60, jitter)
            assertTrue("jitter $jitter us: $fps FPS", fps >= 59.7)
        }
    }

    @Test fun dividingRatesStaySnappedWithJitter() {
        for (hz in listOf(60.0, 120.0)) for (jitter in listOf(0.0, 100.0, 200.0)) {
            val fps = releasedFps(hz, 60, jitter)
            assertTrue("$hz Hz, jitter $jitter us: $fps FPS", fps >= 59.9)
        }
    }
}
