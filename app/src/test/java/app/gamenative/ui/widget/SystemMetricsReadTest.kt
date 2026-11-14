package app.gamenative.ui.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** 2026-11-12 review: one-line reads reuse a buffer and match BufferedReader.readLine; a denied /proc/stat is not retried forever. */
class SystemMetricsReadTest {
    @get:Rule val temp = TemporaryFolder()

    private fun file(text: String) = temp.newFile().apply { writeText(text) }.path

    @Test fun firstLineMatchesBufferedReader() {
        val samples = listOf("", "\n", "1234", "1234\n", "52\n53\n", "cpu  1 2 3 4\r\ncpu0 1\n", "x".repeat(1023), "x".repeat(1024),
            "y".repeat(3000) + "\nz", "ünnep 5°C\n")
        for (text in samples) {
            val path = file(text)
            assertEquals(text.take(20), File(path).bufferedReader().use { it.readLine() }, SystemMetricsSources.readFirstLine(path))
        }
        assertNull(SystemMetricsSources.readFirstLine(File(temp.root, "missing").path))
    }

    @Test fun aDeniedProcStatIsLeftAfterAFewTries() {
        var procStatReads = 0
        val sampler = CpuUsageSampler({ path -> if (path == "/proc/stat") { procStatReads++; null } else null }, 2, { mapOf(1 to 1L) }, { 0L })
        repeat(20) { sampler.sample() }
        assertEquals(3, procStatReads)
    }
}
