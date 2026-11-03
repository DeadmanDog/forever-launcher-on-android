package app.gamenative.ui.screen.wow

import org.junit.Test
import org.junit.Assert.*

class DiagnosticFramesTest {
    @Test fun recordsIntervalsAndStallsWithoutCombiningOtherSignals() {
        val c=DiagnosticFrames();c.frame(1_000_000_000);c.frame(1_016_000_000);c.frame(1_066_000_000)
        val r=c.take();assertEquals(2,r.intervals);assertEquals(33.0,r.meanMs,0.01);assertEquals(50.0,r.maxMs,0.01);assertEquals(1,r.over33Ms)
        assertEquals(0,c.take().intervals)
        c.frame(1_082_000_000);assertEquals(16.0,c.take().meanMs,0.01)
    }
    @Test fun presentRequestsAreLabeledAndDoNotMixWithRendererIntervals() {
        val c = DiagnosticFrames()
        c.frame(1_000_000_000)
        c.frame(1_016_000_000)
        c.frame(2_000_000_000, DiagnosticFrames.Source.X_PRESENT_REQUEST)
        var r = c.take()
        assertEquals(DiagnosticFrames.Source.X_PRESENT_REQUEST, r.source)
        assertEquals(0, r.intervals)
        c.frame(2_020_000_000, DiagnosticFrames.Source.X_PRESENT_REQUEST)
        r = c.take()
        assertEquals(1, r.intervals)
        assertEquals(20.0, r.meanMs, 0.01)
        assertEquals(0, r.over33Ms)
    }
    @Test fun interleavedWindowsAreMeasuredEachOnTheirOwnTimeline() {
        val c = DiagnosticFrames()
        val p = DiagnosticFrames.Source.X_PRESENT_REQUEST
        // The game presents at 0/16/32 ms, a helper window at 8/24 ms: no 8 ms intervals may appear.
        val t = 1_000_000_000L
        c.frame(t, p, 1); c.frame(t + 8_000_000, p, 2); c.frame(t + 16_000_000, p, 1); c.frame(t + 24_000_000, p, 2); c.frame(t + 32_000_000, p, 1)
        val r = c.take()
        assertEquals(2, r.intervals)
        assertEquals(16.0, r.meanMs, 0.01)
        assertEquals(1, r.windowId)
        assertEquals(2, r.windows)
    }
}
