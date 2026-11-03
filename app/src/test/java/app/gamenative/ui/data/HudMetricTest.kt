package app.gamenative.ui.data

import org.junit.Assert.*
import org.junit.Test

class HudMetricTest {
    @Test fun orderRoundTripDropsRetiredAndUnknownIdsAndDuplicates() {
        val order = HudMetric.parseOrder("CPU_CLOCK,GPU_FREQ,CLOCK,CPU_CLOCK,bad")
        assertEquals(HudMetric.CPU_CLOCK, order[0])
        assertEquals(HudMetric.CLOCK, order[1])
        assertEquals(HudMetric.entries.size, order.size)
        assertEquals(order, HudMetric.parseOrder(order.joinToString(",") { it.name }))
    }
    @Test fun moveIsBoundedAndPreservesAllMetrics() {
        val order = HudMetric.entries.toList()
        assertEquals(order, HudMetric.move(order, HudMetric.FPS, -1))
        val moved = HudMetric.move(order, HudMetric.VRAM, -1)
        assertEquals(HudMetric.VRAM, moved[moved.lastIndex - 1])
        assertEquals(order.toSet(), moved.toSet())
    }
    @Test fun disabledEntriesDoNotLeaveSlotsAndRetainOrderWhenReEnabled() {
        val config = PerformanceHudConfig(metricOrder = listOf(HudMetric.CLOCK, HudMetric.CPU_CLOCK, HudMetric.FPS),
            showClockTime = true, showCpuClock = false, showGpuClock = false, showCpuUsage = false, showGpuUsage = false,
            showRamUsage = false, showBatteryLevel = false, showPowerDraw = false,
            showCpuTemperature = false, showGpuTemperature = false)
        // The same composition PerformanceHudView uses to lay out the rows.
        fun visible(c: PerformanceHudConfig) = HudMetric.normalizeOrder(c.metricOrder).filter { it.enabled(c) }
        assertEquals(listOf(HudMetric.CLOCK, HudMetric.FPS), visible(config))
        assertEquals(listOf(HudMetric.CLOCK, HudMetric.CPU_CLOCK, HudMetric.FPS), visible(config.copy(showCpuClock = true)))
    }
    @Test fun scaleIsCappedAndMinimumIsSmallerThanFormerSmallFont() {
        assertEquals(1f, HudMetric.scale(250), 0f)
        assertEquals(0.4f, HudMetric.scale(-10), 0f)
        assertTrue(13f * HudMetric.scale(40) < 10f)
    }
    @Test fun clockTogglePreservesOtherConfigurationAndLabelsAreExact() {
        val original = PerformanceHudConfig(style = PerformanceHudStyle.TERMINAL, sizePercent = 64)
        assertEquals(original.copy(showCpuClock = true), HudMetric.CPU_CLOCK.toggle(original))
        assertEquals("POWER", HudMetric.POWER.label)
        assertEquals("BATTERY RUNTIME", HudMetric.RUNTIME.label)
        assertTrue("every Quick Menu metric name is upper case", HudMetric.values().all { it.label == it.label.uppercase() })
        assertEquals("BAT T.", HudMetric.BATTERY_TEMP.label)
        assertEquals("CPU T.", HudMetric.CPU_TEMP.label)
        assertEquals("GPU T.", HudMetric.GPU_TEMP.label)
        assertEquals("CPU C.", HudMetric.CPU_CLOCK.label)
        assertEquals("GPU C.", HudMetric.GPU_CLOCK.label)
        assertEquals(original.copy(showGpuClock = false), HudMetric.GPU_CLOCK.toggle(original))
    }
    @Test fun afterburnerAndCapFrameXStylesRoundTripAndUnknownFallsBack() {
        assertEquals(PerformanceHudStyle.AFTERBURNER, PerformanceHudStyle.fromPrefValue("afterburner"))
        assertEquals(PerformanceHudStyle.CAPFRAMEX, PerformanceHudStyle.fromPrefValue("capframex"))
        PerformanceHudStyle.entries.forEach { assertEquals(it, PerformanceHudStyle.fromPrefValue(it.prefValue)) }
        assertEquals(PerformanceHudStyle.CLASSIC, PerformanceHudStyle.fromPrefValue("nope"))
        assertEquals(PerformanceHudStyle.entries.size, PerformanceHudStyle.entries.map { it.prefValue }.toSet().size)
    }
    @Test fun defaultHudOrderKeepsRequestedSequenceAndAllMetrics() {
        val order = HudMetric.parseOrder("FPS,FPS_GRAPH,CPU,CPU_GRAPH,GPU,GPU_GRAPH,GPU_CLOCK,GPU_TEMP,CPU_TEMP,BATTERY_TEMP,RAM,POWER,BATTERY,RUNTIME,CLOCK,CPU_CLOCK")
        assertEquals(HudMetric.entries.size, order.size)
        assertEquals(listOf("FPS", "CPU", "GPU", "GPU_CLOCK", "GPU_TEMP", "CPU_TEMP", "BATTERY_TEMP", "RAM", "POWER", "BATTERY", "RUNTIME", "CLOCK", "CPU_CLOCK", "FPS_LOW1", "FPS_LOW01", "FRAMETIME", "VRAM"),
            order.map { it.name }.filterNot { it.endsWith("_GRAPH") })
    }
}

class HudDefaultProfileTest {
    @org.junit.Test fun theFirstInstallOrderIsTheCapFrameXListOfTheScreenshot() {
        val order = HudMetric.parseOrder("FPS,FPS_LOW1,FPS_LOW01,FRAMETIME,CPU_CLOCK,CPU,CPU_TEMP,GPU_CLOCK,VRAM,GPU,GPU_TEMP,RAM,POWER,RUNTIME,BATTERY,BATTERY_TEMP,CLOCK,FPS_GRAPH,CPU_GRAPH,GPU_GRAPH")
        val visible = listOf(HudMetric.FPS, HudMetric.FPS_LOW1, HudMetric.FPS_LOW01, HudMetric.FRAMETIME, HudMetric.CPU_CLOCK, HudMetric.CPU, HudMetric.CPU_TEMP, HudMetric.GPU_CLOCK, HudMetric.VRAM,
            HudMetric.GPU, HudMetric.GPU_TEMP, HudMetric.RAM, HudMetric.POWER, HudMetric.RUNTIME, HudMetric.BATTERY, HudMetric.BATTERY_TEMP)
        org.junit.Assert.assertEquals(visible, order.take(visible.size))
        org.junit.Assert.assertEquals("every metric is still listed once", HudMetric.entries.size, order.distinct().size)
    }
}
