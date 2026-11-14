package app.gamenative.ui.widget

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** 2026-11-12 review: the smaps-based fallback runs at most every 10 s, not on every 1 s HUD tick. */
class GpuMemorySamplerThrottleTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun theFallbackIsReadAtMostEveryTenSeconds() {
        var now = 0L
        var reads = 0
        val sampler = GpuMemorySampler(File(temp.root, "missing"), { now }) { reads++; 1000L + reads }
        assertEquals(1001L, sampler.bytes())
        now = 9_999L
        assertEquals("cached value, no new read", 1001L, sampler.bytes())
        assertEquals(1, reads)
        now = 10_000L
        assertEquals(1002L, sampler.bytes())
        assertEquals(2, reads)
    }

    @Test fun readableDriverStatsAreReadEveryTime() {
        val pages = File(temp.root, "page_alloc").apply { writeText("100") }
        val sampler = GpuMemorySampler(temp.root, { 0L }) { error("no fallback") }
        assertEquals(100L, sampler.bytes())
        pages.writeText("200")
        assertEquals(200L, sampler.bytes())
    }
}
