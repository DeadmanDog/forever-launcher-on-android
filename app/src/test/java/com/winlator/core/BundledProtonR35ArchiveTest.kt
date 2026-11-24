package com.winlator.core

import app.gamenative.ui.screen.wow.WowBundledComponents
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import org.apache.commons.compress.compressors.zstandard.ZstdCompressorInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 1.2.6: Proton r35 drops the Wine modules WoW never loads (599 phone sessions) and is packed with zstd -22. The app's own zstd decoder (commons-compress over zstd-jni, default 128 MiB window limit) must still
 * read it, and r35 must keep every module the sessions loaded plus those that load as data or on rare paths.
 */
class BundledProtonR35ArchiveTest {
    private val assets = listOf(File("src/main/assets"), File("app/src/main/assets")).first { it.isDirectory }

    /** Entry name -> bytes read, with the app's decoder; [inner] reads one member fully as well. */
    private fun read(archive: File, inner: String? = null): Pair<Map<String, Long>, ByteArray?> {
        val out = linkedMapOf<String, Long>(); var member: ByteArray? = null
        TarArchiveInputStream(ZstdCompressorInputStream(archive.inputStream().buffered())).use { tar ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val e = tar.nextEntry ?: break
                val name = e.name.removePrefix("./")
                if (name == inner) member = tar.readBytes()
                else { var n = 0L; while (true) { val r = tar.read(buffer); if (r < 0) break; n += r }; out[name] = n; continue }
                out[name] = member!!.size.toLong()
            }
        }
        return out to member
    }

    @Test fun protonR35DecodesAndKeepsWhatWowLoads() {
        val bundle = File(assets, "bundled_components/${WowBundledComponents.PROTON_NAME}-r${WowBundledComponents.PROTON_VERSION_CODE}.wcp")
        assertTrue(bundle.path, bundle.isFile)
        val (entries, prefix) = read(bundle, "prefixPack.txz")
        val w = "lib/wine/aarch64-windows/"
        val modules = entries.keys.filter { it.startsWith(w) && it.length > w.length }.map { it.removePrefix(w).lowercase() }.toSet()
        assertEquals(252, modules.size)
        // Loaded in the phone sessions, or loaded as data / on rare paths (kept on purpose).
        listOf("ntdll.dll", "kernelbase.dll", "user32.dll", "winevulkan.dll", "winex11.drv", "winepulse.drv", "mmdevapi.dll",
            "dsound.dll", "mfplat.dll", "msmpeg2vdec.dll", "mf.dll", "mfreadwrite.dll", "xinput1_4.dll", "dinput8.dll", "windows.gaming.input.dll",
            "gameinput.dll", "sapi.dll", "wbemprox.dll", "winhttp.dll", "schannel.dll", "dbghelp.dll", "winedbg.exe", "explorer.exe",
            "wineboot.exe", "services.exe", "winedevice.exe", "rundll32.exe", "regedit.exe", "apisetschema.dll", "stdole2.tlb", "tzres.dll",
            "xtajit64.dll", "wow64.dll", "vcruntime140.dll", "msvcp140.dll", "ucrtbase.dll", "comctl32_v6.dll", "windowscodecs.dll")
            .forEach { assertTrue("missing $it", it in modules) }
        listOf("msxml3.dll", "oledb32.dll", "odbc32.dll", "opencl.dll", "mapi32.dll", "wldap32.dll", "cards.dll", "scrrun.dll")
            .forEach { assertFalse("still bundled: $it", it in modules) }
        assertFalse(entries.containsKey("lib/wine/aarch64-unix/opencl.so"))
        assertTrue(entries.containsKey("lib/wine/aarch64-unix/winevulkan.so"))
        // The prefix keeps the common controls side by side, not the MSXML or OLE DB/ADO files (their empty folders stay).
        val names = TarArchiveInputStream(XZCompressorInputStream(prefix!!.inputStream())).use { t ->
            generateSequence { t.nextEntry }.filter { it.isFile }.map { it.name.removePrefix("./") }.toList() }
        assertTrue(names.any { it.contains("winsxs/arm64_microsoft.windows.common-controls_") && it.endsWith("comctl32.dll") })
        assertTrue(names.contains(".wine/system.reg"))
        assertFalse(names.any { it.contains("msxml") || it.contains("OLE DB/") || it.contains("System/ADO/") })
    }
}
