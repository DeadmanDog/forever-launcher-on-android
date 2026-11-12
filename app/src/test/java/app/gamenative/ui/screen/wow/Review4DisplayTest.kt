package app.gamenative.ui.screen.wow

import com.winlator.core.envvars.EnvVars
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File

/** Fourth review round (1.1.2): each case failed on 1.1.1 and is fixed with it. */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [34])
class Review4DisplayTest {
    @get:Rule val temp = TemporaryFolder()
    private val header = "present_id,kind,content_gen,content_updates,content_ns,queue_ns,display_ns,margin_us"
    private fun row(i: Int) = "$i,shown,$i,1,${223579038704970L + i},${223579038936012L + i},${223579074765073L + i},7831"

    @Test fun d1_aLongCsvExportKeepsItsHeaderAndStartsOnAWholeRow() {
        val file = File(temp.root, "frames.csv")
        file.bufferedWriter().use { w -> w.write(header + "\n"); var i = 0; repeat(80_000) { w.write(row(i++) + "\n") } }
        assertTrue(file.length() > DiagnosticExport.MAX_ENTRY_BYTES)
        val out = ByteArrayOutputStream(); DiagnosticExport.writeBounded(file, out)
        val lines = out.toString(Charsets.UTF_8.name()).lines().filter { it.isNotEmpty() }
        assertEquals("the export lost the CSV header", header, lines.first())
        assertEquals("the export starts in the middle of a row", 8, lines[1].split(',').size)
    }

    @Test fun d2_aLongSessionKeepsItsNewestFrames() {
        val session = temp.newFolder("session")
        val sampler = DiagnosticSampler(session, false)
        try {
            val frameLine = DiagnosticSampler::class.java.getDeclaredMethod("frameLine", String::class.java).apply { isAccessible = true }
            var last = ""
            var i = 0
            while (i < 140_000) { last = row(i++); frameLine.invoke(sampler, last) }   // ~10 MB: a session of about 40 minutes
            val kept = File(session, "frames.csv").readText()
            assertTrue("the newest frames of a long session were never written", kept.contains(last))
            assertTrue("a rotated frame log lost its header", kept.startsWith(header))
        } finally { sampler.close() }
    }

    @Test fun x1_theGameIsToldTheResolutionForEveryDisplayModeItHas() {
        val lines = WowDisplaySettings.configLines(listOf("SET gxFullscreenResolution \"1280x720\""), "1344x608")
        assertTrue(lines.toString(), lines.contains("SET gxFullscreenResolution \"1344x608\""))
        assertTrue(lines.toString(), lines.contains("SET gxWindowedResolution \"1344x608\""))
        assertTrue("gxResolution is not a CVar of this client", lines.none { it.startsWith("SET gxResolution ") })
    }

    @Test fun dx12_lowInputLatencyAlsoLimitsTheDx12Queue() {
        val env = EnvVars()
        WowRuntimeTuning.applyFrameLatency(env, dx12 = true, enabled = true)
        assertEquals("DX12 queued up to the driver's default frames ahead", "1", env.get("VKD3D_SWAPCHAIN_LATENCY_FRAMES"))
    }
}
