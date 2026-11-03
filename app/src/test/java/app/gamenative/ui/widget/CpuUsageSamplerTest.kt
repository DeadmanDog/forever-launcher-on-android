package app.gamenative.ui.widget

import org.junit.Assert.*
import org.junit.Test

class CpuUsageSamplerTest {
    @Test fun staticMaximumIsReadOnceWhileEveryCurrentSampleRemainsFresh() {
        val reads = mutableMapOf<String, Int>()
        var current = 1000L
        val sampler = CpuUsageSampler({ path ->
            reads[path] = (reads[path] ?: 0) + 1
            when {
                path.endsWith("scaling_cur_freq") -> current.toString()
                path.endsWith("cpuinfo_max_freq") -> "4000"
                else -> null
            }
        }, 8)
        assertEquals(25, sampler.sample()!!.percent)
        current = 3000
        repeat(100) { assertEquals(75, sampler.sample()!!.percent) }
        assertEquals(8, reads.filterKeys { it.endsWith("cpuinfo_max_freq") }.values.sum())
        assertEquals(808, reads.filterKeys { it.endsWith("scaling_cur_freq") }.values.sum())
        sampler.reset()
        current = 2000
        assertEquals(50, sampler.sample()!!.percent)
        assertEquals(8, reads.filterKeys { it.endsWith("cpuinfo_max_freq") }.values.sum())
    }

    @Test fun unavailableMaximumIsRetriedAndOfflineCoreDoesNotDiluteReading() {
        var online = false
        var maximumAvailable = false
        val sampler = CpuUsageSampler({ path ->
            when {
                path.contains("cpu1/") && !online -> null
                path.endsWith("scaling_cur_freq") -> "1000"
                path.endsWith("cpuinfo_max_freq") && maximumAvailable -> "2000"
                else -> null
            }
        }, 2)
        assertNull(sampler.sample())
        maximumAvailable = true
        assertEquals(50, sampler.sample()!!.percent)
        online = true
        assertEquals(50, sampler.sample()!!.percent)
    }

    @Test fun procStatKeepsPriorityAndRequiresNoFrequencyReadsForItsDelta() {
        var line = "cpu 100 0 50 1000 0 0 0"
        var frequencyReads = 0
        val sampler = CpuUsageSampler({ path ->
            if (path == "/proc/stat") line else { frequencyReads++; null }
        }, 2)
        sampler.sample()
        frequencyReads = 0
        line = "cpu 120 0 60 1070 0 0 0"
        assertEquals(CpuUsageReading(30, CpuUsageSource.PROC_STAT), sampler.sample())
        assertEquals(0, frequencyReads)
    }

    @Test fun unreadableProcStatUsesTheProcessCpuTimeAsAPercentage() {
        var now = 0L
        var ticks = mapOf(10 to 500L, 11 to 40L)
        val sampler = CpuUsageSampler({ null }, 2, { ticks }, { now })
        assertNull("the first sample has no interval", sampler.sample())
        now = 1000
        // 100 ticks = one CPU second inside one wall second on two cores.
        ticks = mapOf(10 to 560L, 11 to 80L)
        assertEquals(CpuUsageReading(50, CpuUsageSource.PROCESS_TIME), sampler.sample())
        now = 2000
        ticks = mapOf(10 to 560L, 11 to 80L)
        assertEquals(CpuUsageReading(0, CpuUsageSource.PROCESS_TIME), sampler.sample())
    }

    @Test fun processesThatStartOrExitBetweenSamplesDoNotBreakTheReading() {
        var now = 0L
        var ticks = mapOf(10 to 1000L, 11 to 900L)
        val sampler = CpuUsageSampler({ null }, 1, { ticks }, { now })
        sampler.sample()
        now = 1000
        // pid 11 exited, pid 12 is new with 30 ticks: only 30 + 20 ticks were spent.
        ticks = mapOf(10 to 1020L, 12 to 30L)
        assertEquals(50, sampler.sample()!!.percent)
    }

    @Test fun procStatStillWinsOverTheProcessTime() {
        var line = "cpu 100 0 50 1000 0 0 0"
        val sampler = CpuUsageSampler({ path -> if (path == "/proc/stat") line else null }, 2, { mapOf(1 to 1L) }, { 0L })
        sampler.sample()
        line = "cpu 120 0 60 1070 0 0 0"
        assertEquals(CpuUsageSource.PROC_STAT, sampler.sample()!!.source)
    }
}
