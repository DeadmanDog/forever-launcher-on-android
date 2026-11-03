package com.winlator.core

import android.app.Application
import com.winlator.xenvironment.ImageFsInstaller
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * 1.4.4: the bundled image carries no command-line programs (the app never runs one from it; the GPL-3.0 tools were the strictest
 * licence items) but still every library Wine, the Vulkan wrapper and PulseAudio load from it. Image version 32 makes updated
 * phones re-extract it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [29])
class BundledImageFsArchiveTest {
    @get:Rule val temp = TemporaryFolder()

    // Loaded by winex11/winealsa/crypt32/bcrypt (Proton r31), libvulkan_wrapper.so, libpulseaudio.so and libevshim.so.
    private val needed = listOf("libX11.so.6", "libX11-xcb.so.1", "libXext.so.6", "libXcursor.so.1", "libXi.so.6", "libXrandr.so.2",
        "libXrender.so.1", "libxcb.so.1", "libxcb-dri3.so.0", "libxcb-present.so.0", "libxcb-randr.so.0", "libxcb-sync.so.1",
        "libxcb-shm.so.0", "libdrm.so", "libvulkan.so.1", "libandroid-sysvshm.so", "libasound.so.2", "libgnutls.so", "libfreetype.so.6",
        "libfontconfig.so.1", "libSDL2-2.0.so.0", "libpulse.so", "libpulsecore-13.0.so", "libsndfile.so", "libltdl.so", "libz.so.1",
        "libc++_shared.so", "libadrenotools.so", "libhook_impl.so", "libmain_hook.so", "libgsl_alloc_hook.so", "libfile_redirect_hook.so")

    /** Path -> symlink target ("" for files and folders), read straight from the archive the app ships. */
    private fun entries(archive: File): Map<String, String> {
        val out = linkedMapOf<String, String>()
        org.apache.commons.compress.archivers.tar.TarArchiveInputStream(
            org.apache.commons.compress.compressors.xz.XZCompressorInputStream(archive.inputStream().buffered())).use { tar ->
            while (true) {
                val e = tar.nextEntry ?: break
                out[e.name.removePrefix("./").trimEnd('/')] = if (e.isSymbolicLink) e.linkName else ""
            }
        }
        return out
    }

    @Test fun theImageHasTheRuntimeLibrariesButNoPrograms() {
        val assets = listOf(File("src/main/assets"), File("app/src/main/assets")).first { it.isDirectory }
        val archive = File(assets, "imagefs_bionic.txz")
        val all = entries(archive)
        fun resolves(path: String, depth: Int = 0): Boolean {
            val link = all[path] ?: return false
            if (link.isEmpty()) return true
            return depth < 8 && resolves(path.substringBeforeLast('/') + "/" + link, depth + 1)
        }
        needed.forEach { assertTrue("missing $it", resolves("usr/lib/$it")) }
        all.filter { it.value.isNotEmpty() && it.key.startsWith("usr/lib/") }.keys.forEach { assertTrue("dangling $it", resolves(it)) }
        assertEquals(emptyList<String>(), all.keys.filter { it.startsWith("usr/bin/") })
        listOf("usr/libexec", "usr/sbin", "opt/winetricks", "usr/lib/libcrypto.so.3", "usr/lib/libreadline.so.8", "usr/lib/libcurl.so.4")
            .forEach { gone -> assertFalse("still bundled: $gone", all.keys.any { it == gone || it.startsWith("$gone/") }) }
        // The app's own extractor still unpacks it.
        val root = temp.newFolder("imagefs")
        assertTrue(TarCompressorUtils.extract(TarCompressorUtils.Type.XZ, archive, root))
        assertTrue(File(root, "usr/lib/libX11.so.6.4.0").isFile)
        assertEquals(32, ImageFsInstaller.LATEST_VERSION.toInt())
    }
}
