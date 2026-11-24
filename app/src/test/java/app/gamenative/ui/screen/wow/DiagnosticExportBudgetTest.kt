package app.gamenative.ui.screen.wow

import java.io.File
import java.nio.file.Files
import java.util.zip.ZipFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Review 3 (9): the diagnostics archive has a total budget (newest logs first), and a fresh export no longer deletes the last one. */
class DiagnosticExportBudgetTest {
    private fun dir() = Files.createTempDirectory("diag").toFile()

    @Test fun theNewestFilesFillTheBudget() {
        val root = dir()
        val old = File(root, "old.log").apply { writeBytes(ByteArray(600)); setLastModified(1_000_000) }
        val mid = File(root, "mid.log").apply { writeBytes(ByteArray(600)); setLastModified(2_000_000) }
        val new = File(root, "new.log").apply { writeBytes(ByteArray(300)); setLastModified(3_000_000) }
        assertEquals(listOf(mid, new), DiagnosticExport.withinBudget(listOf(old, mid, new), total = 1000).sortedBy { it.name })
        assertEquals(3, DiagnosticExport.withinBudget(listOf(old, mid, new), total = 10_000).size)
    }

    @Test fun aRecentExportStaysAnOldOneGoes() {
        val root = dir().apply { File(this, "session/stderr.log").apply { parentFile.mkdirs(); writeText("log") } }
        val cache = dir()
        val recent = File(cache, "wow-diagnostics-recent.zip").apply { writeText("x") }
        val stale = File(cache, "wow-diagnostics-stale.zip").apply {
            writeText("x"); setLastModified(System.currentTimeMillis() - DiagnosticExport.KEEP_PREVIOUS_MS - 60_000)
        }
        val made = DiagnosticExport.create(root, cache)
        assertTrue(recent.isFile)
        assertFalse(stale.exists())
        ZipFile(made).use { assertEquals(listOf("session/stderr.log"), it.entries().toList().map { e -> e.name }) }
    }
}
