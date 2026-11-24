package app.gamenative.ui.screen.wow

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** 1.2.20: TU_FPS_FILE follows the panel's current rate, the limit and the background state together. */
class DriverFpsFileTest {
    private class Recorder(expected: Int) {
        val writes = java.util.Collections.synchronizedList(mutableListOf<String>())
        val latch = CountDownLatch(expected)
        val write: (java.io.File, Int, Int) -> Unit = { _, fps, hz -> writes += "$fps $hz"; latch.countDown() }
        fun await(): List<String> { latch.await(5, TimeUnit.SECONDS); return writes.toList() }
    }
    private val file = java.io.File("unused")

    @Test fun aPanelSwitchAfterTheLimitReachesTheDriver() {
        val r = Recorder(2)
        val f = WowRuntimeTuning.DriverFpsFile(file, r.write)
        f.setLimit(60, 60)          // read before setFrameRate switched the panel
        f.setPanelHz(120)           // Android switched the display mode
        assertEquals(listOf("60 60", "60 120"), r.await())
    }

    @Test fun aLimitAboveTheStaleRateIsNotCutOnceThePanelIsFaster() {
        val r = Recorder(2)
        val f = WowRuntimeTuning.DriverFpsFile(file, r.write)
        f.setLimit(120, 60)         // 120 asked while the panel still ran at 60: the driver is told 60 for now
        f.setPanelHz(120)
        assertEquals(listOf("60 60", "120 120"), r.await())
    }

    @Test fun backgroundAndPanelChangesKeepEachOther() {
        val r = Recorder(4)
        val f = WowRuntimeTuning.DriverFpsFile(file, r.write)
        f.setLimit(60, 120)
        f.setBackground(true)
        f.setPanelHz(60)
        f.setBackground(false)
        assertEquals(listOf("60 120", "15 120", "15 60", "60 60"), r.await())
    }

    @Test fun noLimitSteersToThePanelRate() {
        assertEquals(120, WowRuntimeTuning.driverTarget(0, 120))
        assertEquals(60, WowRuntimeTuning.driverTarget(0, 0).let { if (it == 0) 60 else it })   // refresh unknown: "0", the driver's 60
        assertEquals(60, WowRuntimeTuning.driverTarget(60, 120))
        assertEquals(120, WowRuntimeTuning.driverTarget(144, 120))
        val r = Recorder(2)
        val f = WowRuntimeTuning.DriverFpsFile(file, r.write)
        f.setLimit(0, 120)
        f.setPanelHz(144)
        assertEquals(listOf("120 120", "144 144"), r.await())
    }

    @Test fun anUnchangedRateWritesNothing() {
        val r = Recorder(1)
        val f = WowRuntimeTuning.DriverFpsFile(file, r.write)
        f.setLimit(60, 120)
        f.setPanelHz(120)
        f.setPanelHz(0)
        Thread.sleep(200)
        assertEquals(listOf("60 120"), r.await())
    }
}
