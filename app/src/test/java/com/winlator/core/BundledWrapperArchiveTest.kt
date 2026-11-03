package com.winlator.core

import android.app.Application
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.security.MessageDigest

/**
 * 1.3.53: the bundled Vulkan wrapper is the same e03c3486fcda build without its debug info. The app's own extractor must unpack
 * every file, the helper libraries unchanged, and the wrapper must keep its symbols but carry no DWARF section.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [29])
class BundledWrapperArchiveTest {
    @get:Rule val temp = TemporaryFolder()

    private val expected = mapOf(
        "usr/lib/libadrenotools.so" to "7412c757d22aaa6c9ee94e567a0637a203c852de3d095e75397e85264108858c",
        "usr/lib/libfile_redirect_hook.so" to "081809c19edc4d6ef44667f16bacdc3ff534be5669a87db015d24ca50ee60953",
        "usr/lib/libgsl_alloc_hook.so" to "80969e481472084ce5f565b716a4707c91ff1f9fe99b9ec70eebebff0ab54cc7",
        "usr/lib/libhook_impl.so" to "63d5960e97119a93a00c0d5b7fa1c4e4a84ec47f7dd7c15024142f1943487632",
        "usr/lib/libmain_hook.so" to "de9418db319242ad08e2c0d52ecfefb1eeede5341989ef1aa20cba8dca8fb926",
        "usr/lib/libvulkan_wrapper.so" to "6dc43b8909d9ce3ea7568bcf353a2f2f6294908ac34530071fa9b411995b1852",
        "usr/share/vulkan/icd.d/wrapper_icd.aarch64.json" to "adb27bf7cad76917424a9eaee918c5f0ec755adc98c0921f78434d67f89de304",
    )

    private fun sha256(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }

    @Test fun theAppExtractorUnpacksTheStrippedWrapperAndUnchangedHelpers() {
        val assets = listOf(File("src/main/assets"), File("app/src/main/assets")).first { it.isDirectory }
        val root = temp.newFolder("imagefs")
        assertTrue(TarCompressorUtils.extract(TarCompressorUtils.Type.XZ, File(assets, "graphics_driver/wrapper.txz"), root))
        val unpacked = root.walkTopDown().filter { it.isFile }.map { it.relativeTo(root).path.replace('\\', '/') }.toSet()
        assertEquals(expected.keys, unpacked)
        expected.forEach { (path, hash) -> assertEquals(path, hash, sha256(File(root, path))) }
        val wrapper = String(File(root, "usr/lib/libvulkan_wrapper.so").readBytes(), Charsets.ISO_8859_1)
        assertFalse(wrapper.contains(".debug_info"))
        assertTrue(wrapper.contains("wrapper_GetPhysicalDeviceProperties2"))
        assertTrue(wrapper.contains("WRAPPER_LOG_LEVEL"))
    }
}
