package app.gamenative.ui.widget

import java.io.File
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** cpufreq exposes each shared policy under multiple per-core symlinks on Android. */
class HardwareClockAliasRegressionTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun eightCoreAliasesReadOnlyThreePoliciesAndKeepTheLiveMaximum() {
        val cpu = temp.newFolder("cpu")
        val policies = listOf(0, 2, 6).map { n -> File(cpu, "cpufreq/policy$n").apply { mkdirs() } }
        val speeds = mutableMapOf(policies[0].canonicalPath to 1000000L,
            policies[1].canonicalPath to 2000000L, policies[2].canonicalPath to 3000000L)
        repeat(8) { n ->
            val core = File(cpu, "cpu$n").apply { mkdirs() }
            Files.createSymbolicLink(File(core, "cpufreq").toPath(), policies[if (n < 2) 0 else if (n < 6) 1 else 2].toPath())
        }
        val reads = mutableListOf<String>()
        val sampler = HardwareClockSampler(cpu, emptyList(), emptyList(), emptyList())
        sampler.readLong = { path ->
            val f = File(path)
            val key = f.parentFile.canonicalPath
            // Ignore guessed core nodes not present in this fixture.
            if (key in speeds) reads += path
            if (f.name == "scaling_cur_freq") speeds[key] else null
        }
        assertEquals(3000, sampler.cpuMHz())
        assertEquals("one hardware and one fallback read per unique policy", 6, reads.size)
        speeds[policies[2].canonicalPath] = 800000L
        reads.clear()
        assertEquals("values stay live; only path discovery is cached", 2000, sampler.cpuMHz())
        assertEquals(6, reads.size)
    }

    @Test fun distinctPerCoreDirectoriesRemainAvailableWithoutPolicies() {
        val cpu = temp.newFolder("separate")
        File(cpu, "cpu0/cpufreq").mkdirs()
        File(cpu, "cpu1/cpufreq").mkdirs()
        val sampler = HardwareClockSampler(cpu, emptyList(), emptyList(), emptyList())
        sampler.readLong = { path -> when {
            path.endsWith("cpu0/cpufreq/cpuinfo_cur_freq") -> 1400000L
            path.endsWith("cpu1/cpufreq/scaling_cur_freq") -> 2300000L
            else -> null
        } }
        assertEquals(2300, sampler.cpuMHz())
    }
}
