package app.gamenative.ui.screen.wow

import app.gamenative.ui.component.PerformanceHudPreset
import app.gamenative.ui.component.applyPerformanceHudPreset
import app.gamenative.ui.component.matchesPerformanceHudPreset
import app.gamenative.ui.data.PerformanceHudConfig
import app.gamenative.ui.widget.CpuUsageReading
import app.gamenative.ui.widget.CpuUsageSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PerformanceHudPresetTest {
    private val everything = PerformanceHudConfig(
        showCpuClock = true, showGpuClock = true, showVramUsage = true, showFrameTime = true, showFps1Low = true, showFps01Low = true,
    )

    @Test fun fpsOnlyAlsoSwitchesTheNewerMetricsOff() {
        val c = applyPerformanceHudPreset(everything, PerformanceHudPreset.FPS_ONLY)
        assertTrue(c.showFrameRate)
        assertFalse(c.showCpuClock || c.showGpuClock || c.showVramUsage || c.showFrameTime || c.showFps1Low || c.showFps01Low)
    }

    @Test fun essentialAndBatteryAlsoSwitchTheNewerMetricsOff() {
        for (preset in listOf(PerformanceHudPreset.ESSENTIAL, PerformanceHudPreset.BATTERY)) {
            val c = applyPerformanceHudPreset(everything, preset)
            assertFalse("$preset", c.showCpuClock || c.showGpuClock || c.showVramUsage || c.showFrameTime || c.showFps1Low || c.showFps01Low)
        }
    }

    @Test fun fullSwitchesThemOn() {
        val c = applyPerformanceHudPreset(PerformanceHudConfig(), PerformanceHudPreset.FULL)
        assertTrue(c.showCpuClock && c.showGpuClock && c.showVramUsage && c.showFrameTime && c.showFps1Low && c.showFps01Low)
    }

    @Test fun aPresetMatchesItselfAndNothingChangedByOneNewerMetric() {
        for (preset in PerformanceHudPreset.entries) {
            val applied = applyPerformanceHudPreset(everything, preset)
            assertTrue("$preset", matchesPerformanceHudPreset(applied, preset))
            val tweaked = applied.copy(showFrameTime = !applied.showFrameTime)
            assertFalse("$preset after one newer metric changed", matchesPerformanceHudPreset(tweaked, preset))
        }
    }

    @Test fun aClockRatioIsNeverShownAsCpuLoad() {
        assertNull(CpuUsageReading(50, CpuUsageSource.CPU_FREQUENCY).loadPercent)
        assertEquals(37, CpuUsageReading(37, CpuUsageSource.PROC_STAT).loadPercent)
        assertEquals(12, CpuUsageReading(12, CpuUsageSource.PROCESS_TIME).loadPercent)
    }
}
