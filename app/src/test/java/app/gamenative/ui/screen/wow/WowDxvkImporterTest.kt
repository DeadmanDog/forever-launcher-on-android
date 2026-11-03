package app.gamenative.ui.screen.wow

import android.app.Application
import android.content.ContextWrapper
import android.net.Uri
import com.winlator.container.Container
import com.winlator.contents.ContentProfile
import com.winlator.contents.ContentsManager
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.archivers.tar.TarConstants
import org.apache.commons.compress.compressors.xz.XZCompressorOutputStream
import org.apache.commons.compress.compressors.zstandard.ZstdCompressorOutputStream
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [29])
class WowDxvkImporterTest {
    @get:Rule val temp = TemporaryFolder()
    private val files by lazy { temp.newFolder("files") }
    private val context by lazy {
        object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getFilesDir() = files
        }
    }

    private fun metadata(type: String = "DXVK", name: String = "Vegas-arm64", code: Int = 1) = JSONObject().apply {
        put("type", type); put("versionName", name); put("versionCode", code); put("description", "Vegas test")
        put("files", JSONArray().put(JSONObject().apply {
            put("source", "aarch64/d3d11.dll"); put("target", "\${system32}/d3d11.dll")
        }))
    }

    private fun archive(profile: JSONObject? = metadata(), zstd: Boolean = false,
                        extraName: String? = null, symlink: Boolean = false): Uri {
        val file = temp.newFile("${System.nanoTime()}.wcp")
        val compressed = if (zstd) ZstdCompressorOutputStream(file.outputStream())
            else XZCompressorOutputStream(file.outputStream())
        TarArchiveOutputStream(compressed).use { tar ->
            fun add(name: String, value: String) {
                val data = value.toByteArray()
                val entry = TarArchiveEntry(name, true).apply { size = data.size.toLong() }
                tar.putArchiveEntry(entry); tar.write(data); tar.closeArchiveEntry()
            }
            if (profile != null) add("profile.json", profile.toString())
            add("aarch64/d3d11.dll", "DXVK test payload")
            if (extraName != null) {
                if (symlink) {
                    val entry = TarArchiveEntry("unsafe-link", TarConstants.LF_SYMLINK).apply { linkName = extraName }
                    tar.putArchiveEntry(entry); tar.closeArchiveEntry()
                } else add(extraName, "must never be written")
            }
            tar.finish()
        }
        return Uri.fromFile(file)
    }

    private fun importWcp(uri: Uri) = WowDxvkImporter.importWcp(context, uri) { false }
    private fun assertRejected(uri: Uri): Exception {
        val error = assertThrows(Exception::class.java) { importWcp(uri) }
        assertTrue(WowDxvkSelector.installedProfiles(context).isEmpty())
        assertTrue(ContentsManager.getTmpDir(context).listFiles().orEmpty().isEmpty())
        return error
    }

    @Test fun duplicateArchiveEntryIsRejectedWithoutPublishing() {
        assertRejected(archive(extraName = "aarch64/d3d11.dll"))
    }
    @Test fun duplicateProfileDestinationIsRejectedWithoutPublishing() {
        val profile = metadata()
        profile.getJSONArray("files").put(JSONObject().put("source", "aarch64/d3d11.dll")
            .put("target", "\${system32}/d3d11.dll"))
        assertRejected(archive(profile))
    }

    @Test fun acceptsValidDxvkWcpAndImmediatelyListsIt() {
        val p = importWcp(archive())
        assertEquals(ContentProfile.ContentType.CONTENT_TYPE_DXVK, p.type)
        assertEquals("Vegas-arm64", p.verName)
        assertEquals(1, p.verCode)
        val installed = WowDxvkSelector.installedProfiles(context).single()
        assertEquals("dxvk-Vegas-arm64-1", WowDxvkSelector.identifier(installed))
        assertEquals("DXVK test payload", File(ContentsManager.getInstallDir(context, p), "aarch64/d3d11.dll").readText())
        assertTrue(ContentsManager.getTmpDir(context).listFiles().orEmpty().isEmpty())
    }

    @Test fun acceptsZstdWcpUsingExistingBackendFallback() {
        assertEquals("Vegas-arm64", importWcp(archive(zstd = true)).verName)
    }

    @Test fun rejectsTurnipAndProtonWcp() {
        assertTrue(assertRejected(archive(metadata("Turnip"))).message!!.contains("Only DXVK"))
        assertTrue(assertRejected(archive(metadata("Proton"))).message!!.contains("Only DXVK"))
    }

    @Test fun rejectsMissingProfileJson() {
        assertTrue(assertRejected(archive(profile = null)).message!!.contains("profile.json"))
    }

    @Test fun rejectsInvalidRequiredMetadata() {
        for (field in listOf("type", "versionName", "versionCode", "files")) {
            assertRejected(archive(metadata().apply { remove(field) }))
        }
        assertRejected(archive(metadata().apply { put("files", "not a list") }))
        assertRejected(archive(metadata(name = "")))
        assertRejected(archive(metadata(code = -1)))
        assertRejected(archive(metadata().apply { put("files", JSONArray()) }))
    }

    @Test fun rejectsInvalidArchiveAndWrongExtension() {
        assertRejected(Uri.fromFile(temp.newFile("broken.wcp").apply { writeText("not an archive") }))
        val wcp = File(archive().path!!)
        val txt = File(wcp.parentFile, "wrong.txt").also { wcp.copyTo(it) }
        assertTrue(assertRejected(Uri.fromFile(txt)).message!!.contains(".wcp"))
    }

    @Test fun rejectsDuplicateWithoutOverwritingExistingPayloadOrMetadata() {
        val p = importWcp(archive())
        val dir = ContentsManager.getInstallDir(context, p)
        val json = File(dir, "profile.json").readText()
        File(dir, "aarch64/d3d11.dll").writeText("existing customized payload")
        assertThrows(IllegalStateException::class.java) { importWcp(archive()) }
        assertEquals(json, File(dir, "profile.json").readText())
        assertEquals("existing customized payload", File(dir, "aarch64/d3d11.dll").readText())
        // Storage is keyed by verName, so another versionCode must also be explicitly rejected.
        assertThrows(IllegalStateException::class.java) { importWcp(archive(metadata(code = 2))) }
        assertEquals(1, WowDxvkSelector.installedProfiles(context).size)
    }

    @Test fun importLeavesContainerUnchangedAndApplyRemainsSeparate() {
        val c = Container(WowContainerProfile.CONTAINER_ID).apply {
            rootDir = temp.newFolder("container")
            loadData(WowContainerProfile.defaultsForNewContainer("/games/wow", false, false))
            setDXWrapper("dxvk-2.4.1-wow-aarch64-test-1")
            setDXWrapperConfig("version=2.4.1-wow-aarch64-test-1")
            saveData()
        }
        val before = c.configFile.readText()
        val p = importWcp(archive())
        assertEquals(before, c.configFile.readText())
        assertEquals("dxvk-2.4.1-wow-aarch64-test-1", c.getDXWrapper())
        val profiles = WowDxvkSelector.installedProfiles(context)
        assertNull(WowDxvkSelector.currentProfile(c.getDXWrapper(), c.getDXWrapperConfig(), profiles))
        WowDxvkSelector.applyToContainer(c, WowDxvkSelector.identifier(p), profiles) { false }
        assertEquals("dxvk-Vegas-arm64-1", c.getDXWrapper())
        assertEquals("Vegas-arm64-1", com.winlator.core.KeyValueSet(c.getDXWrapperConfig()).get("version"))
    }

    @Test fun rejectsArchiveTraversalBeforeWritingOutsideStaging() {
        val outside = File(ContentsManager.getTmpDir(context).parentFile, "escaped.dll")
        assertRejected(archive(extraName = "../escaped.dll"))
        assertFalse(outside.exists())
        val absolute = File(files, "absolute.dll")
        assertRejected(archive(extraName = absolute.absolutePath))
        assertFalse(absolute.exists())
    }

    @Test fun rejectsArchiveSymlinksAndUnsafeProfileTargets() {
        assertRejected(archive(extraName = "../../outside", symlink = true))
        val badTarget = metadata().apply {
            getJSONArray("files").getJSONObject(0).put("target", "\${bindir}/unexpected")
        }
        assertRejected(archive(badTarget))
        assertRejected(archive(metadata(name = "../escaped")))
    }

    @Test fun missingPayloadAndFailedInstallDoNotLeaveInstalledProfile() {
        assertRejected(archive(metadata().apply {
            getJSONArray("files").getJSONObject(0).put("source", "missing.dll")
        }))
        val typeDir = ContentsManager.getContentTypeDir(context, ContentProfile.ContentType.CONTENT_TYPE_DXVK)
        typeDir.parentFile!!.mkdirs()
        typeDir.writeText("block installation")
        assertRejected(archive())
        assertEquals("block installation", typeDir.readText())
    }

    @Test fun activeSessionCannotImport() {
        val uri = archive()
        assertThrows(IllegalStateException::class.java) {
            WowDxvkImporter.importWcp(context, uri) { true }
        }
        assertTrue(WowDxvkSelector.installedProfiles(context).isEmpty())
    }
}
