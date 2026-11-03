package app.gamenative.ui.widget

import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import app.gamenative.ui.data.HudMetric
import app.gamenative.ui.data.PerformanceHudConfig
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class PerformanceHudLayoutTest {
    private fun config() = PerformanceHudConfig(showCpuUsage = false, showGpuUsage = false,
        showRamUsage = false, showBatteryLevel = false, showPowerDraw = false,
        showCpuTemperature = false, showGpuTemperature = false, showCpuClock = true, showGpuClock = false,
        showClockTime = true, metricOrder = listOf(HudMetric.CLOCK, HudMetric.CPU_CLOCK, HudMetric.FPS_GRAPH, HudMetric.FPS))
    private fun snapshot() = HudSnapshot(fpsValue = 60f, cpuValue = null, gpuValue = null,
        fps = "FPS 60.0", cpu = null, gpu = null, ram = "", battery = null, power = null,
        runtime = null, batteryTemp = null, clock = "TIME 12:34", cpuTemp = null,
        gpuTemp = null, fan = null, tune = null, cpuClock = "CPU C. 4534 MHz")
    private fun render(view: PerformanceHudView) {
        PerformanceHudView::class.java.getDeclaredMethod("renderSnapshot", HudSnapshot::class.java)
            .apply { isAccessible = true }.invoke(view, snapshot())
    }
    private fun texts(view: View): List<TextView> = when {
        view.visibility != View.VISIBLE -> emptyList()
        view is TextView -> listOf(view)
        view is ViewGroup -> (0 until view.childCount).flatMap { texts(view.getChildAt(it)) }
        else -> emptyList()
    }

    @Test fun bothLayoutsFollowOrderAndDisabledEntriesOccupyNoChildren() {
        for (compact in listOf(false, true)) {
            val view = PerformanceHudView(ApplicationProvider.getApplicationContext(), { 60f }, config(), compact)
            render(view)
            assertEquals(listOf("TIME 12:34", "CPU C. 4534 MHz", "FPS 60.0"), texts(view).map { it.text.toString() })
            val active = view.getChildAt(if (compact) 1 else 0) as ViewGroup
            assertEquals(3, active.childCount)
            view.setConfig(config().copy(showCpuClock = false))
            assertEquals(2, active.childCount)
            assertEquals(listOf("TIME 12:34", "FPS 60.0"), texts(view).map { it.text.toString() })
        }
    }
    @Test fun graphsAreIndependentMovableEntriesAndScaleAffectsText() {
        val view = PerformanceHudView(ApplicationProvider.getApplicationContext(), { 60f }, config().copy(showFrameRateGraph = true))
        render(view)
        val active = view.getChildAt(0) as ViewGroup
        assertEquals(4, active.childCount)
        assertTrue(texts(active.getChildAt(2)).isEmpty())
        val maximum = texts(view).first().textSize
        view.setConfig(config().copy(sizePercent = 40))
        val minimum = texts(view).first().textSize
        view.setConfig(config().copy(sizePercent = 100))
        assertTrue(minimum < maximum)
        assertEquals(0.4f, minimum / texts(view).first().textSize, 0.01f)
    }
}
