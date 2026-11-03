package app.gamenative.ui.screen.wow

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** 1.4.9: no Microsoft font or Winlator helper ships in the APK, and the copies an older pattern left in a container are removed. */
class WowLegacyFilesTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun theBundledPatternCarriesNoFontAndOldCopiesAreRemoved() {
        val assets = listOf(File("src/main/assets"), File("app/src/main/assets")).first { it.isDirectory }
        val names = mutableListOf<String>()
        org.apache.commons.compress.archivers.tar.TarArchiveInputStream(org.apache.commons.compress.compressors.xz.XZCompressorInputStream(
            File(assets, "container_pattern_common.txz").inputStream().buffered())).use { tar ->
            while (true) { val e = tar.nextEntry ?: break; names += e.name }
        }
        // Only the folder skeleton is left.
        assertEquals(emptyList<String>(), names.filter { !it.endsWith("/") })
        val windows = temp.newFolder("windows"); val fonts = File(windows, "Fonts").apply { mkdirs() }
        File(windows, "system32").mkdirs(); File(windows, "system32/txt.ico").writeText("x"); File(windows, "wfm.exe").writeText("x")
        File(windows, "winhandler.exe").writeText("x")
        listOf("arial.ttf", "Arial.TTF", "webdings.ttf", "vga932.fon").forEach { File(fonts, it).writeText("x") }
        File(fonts, "tahoma.ttf").writeText("wine's own")
        assertEquals(7, WowLegacyFiles.removeFrom(windows))
        assertEquals(listOf("tahoma.ttf"), fonts.list()!!.toList())
        assertEquals(emptyList<String>(), File(windows, "system32").list()!!.toList())
        assertFalse(File(windows, "wfm.exe").exists() || File(windows, "winhandler.exe").exists())
    }
}
