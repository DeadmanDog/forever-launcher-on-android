package app.gamenative.ui.widget

import app.gamenative.ui.data.PerformanceHudStyle
import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File

class HardwareClockSamplerTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun currentPolicyClockUsesKHzAndHighestPolicy() {
        val cpu = temp.newFolder("cpu")
        fun frequency(policy: Int, name: String, value: String) {
            File(cpu, "cpufreq/policy$policy/$name").apply { parentFile!!.mkdirs(); writeText(value) }
        }
        frequency(0, "scaling_cur_freq", "1824000")
        frequency(4, "scaling_cur_freq", "2221000")
        frequency(4, "cpuinfo_max_freq", "4000000")
        assertEquals(2221, HardwareClockSampler(cpu, emptyList(), emptyList()).cpuMHz())
    }

    @Test fun inaccessiblePolicyCanUsePerCoreSourceAndHardwareClockHasPriority() {
        val cpu = temp.newFolder("alternativeCpu")
        File(cpu, "cpufreq/policy0").mkdirs()
        File(cpu, "cpu0/cpufreq/scaling_cur_freq").apply { parentFile!!.mkdirs(); writeText("2221000") }
        File(cpu, "cpu0/cpufreq/cpuinfo_cur_freq").writeText("2100000")
        assertEquals(2100, HardwareClockSampler(cpu, emptyList(), emptyList()).cpuMHz())
    }

    @Test fun gpuDevfreqDiscoveryDoesNotUseUnrelatedDeviceClock() {
        val devfreq = temp.newFolder("devfreq")
        File(devfreq, "soc.gpu/cur_freq").apply { parentFile!!.mkdirs(); writeText("800000000") }
        File(devfreq, "memory/cur_freq").apply { parentFile!!.mkdirs(); writeText("1500000000") }
        assertEquals(800, HardwareClockSampler(temp.newFolder("noCpu"), listOf(devfreq), emptyList()).gpuMHz())
    }

    @Test fun gpuClockUsesHzAndMissingPathsAreUnavailable() {
        val gpu = temp.newFile("gpuclk").apply { writeText("903000000") }
        val sampler = HardwareClockSampler(temp.newFolder("empty"), emptyList(), listOf(gpu))
        assertEquals(903, sampler.gpuMHz())
        assertNull(sampler.cpuMHz())
        gpu.writeText("invalid")
        assertNull(sampler.gpuMHz())
    }

    @Test fun megahertzSourcesAreUsedWhenTheHertzNodesAreUnreadable() {
        val mhz = temp.newFile("gpu_clock").apply { writeText("680") }
        val sampler = HardwareClockSampler(temp.newFolder("emptyCpu"), emptyList(), listOf(File(temp.root, "deniedHz")), listOf(mhz))
        assertEquals(680, sampler.gpuMHz())
        val report = HardwareClockSampler.probe(listOf(mhz.path, File(temp.root, "absent").path))
        assertTrue(report.contains("=680") && report.contains("=missing"))
    }

    @Test fun invalidAndImplausibleReadingsAreRejected() {
        assertNull(HardwareClockSampler.mhz(null, 1000))
        assertNull(HardwareClockSampler.mhz(-1, 1000))
        assertNull(HardwareClockSampler.mhz(Long.MAX_VALUE, 1000))
        assertNull(HardwareClockSampler.mhz(0, 1000))
    }

    @Test fun stylesAreStableAndUnknownPreferencesUseClassic() {
        assertEquals(5, PerformanceHudStyle.entries.size)
        PerformanceHudStyle.entries.forEach { assertEquals(it, PerformanceHudStyle.fromPrefValue(it.prefValue)) }
        assertEquals(PerformanceHudStyle.CLASSIC, PerformanceHudStyle.fromPrefValue("old"))
        // The retired Glass style falls back to Classic for anyone who had saved it.
        assertEquals(PerformanceHudStyle.CLASSIC, PerformanceHudStyle.fromPrefValue("glass"))
    }
}
