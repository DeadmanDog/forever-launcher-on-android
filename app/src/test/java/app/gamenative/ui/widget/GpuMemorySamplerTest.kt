package app.gamenative.ui.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class GpuMemorySamplerTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun kgslPagesPlusCoherentAreSummed() {
        File(temp.root, "page_alloc").writeText("2147483648\n")
        File(temp.root, "coherent").writeText("536870912\n")
        val sampler = GpuMemorySampler(temp.root) { 5L }
        assertEquals(2684354560L, sampler.bytes())
        assertEquals("2.5GB", sampler.text())
    }

    @Test fun smallValuesUseMegabytes() {
        File(temp.root, "page_alloc").writeText("314572800")
        assertEquals("300MB", GpuMemorySampler(temp.root) { null }.text())
    }

    @Test fun fallsBackToTheAppAndThenToNotAvailable() {
        assertEquals(10_000L, GpuMemorySampler(File(temp.root, "missing")) { 10_000L }.bytes())
        assertNull(GpuMemorySampler(File(temp.root, "missing")) { null }.bytes())
        assertEquals("N/A", GpuMemorySampler(File(temp.root, "missing")) { 0L }.text())
    }
}
