package com.winlator.contents

import android.app.Application
import android.content.ContextWrapper
import com.winlator.core.WineInfo
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
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [29])
class ComponentCatalogAuditTest {
    @get:Rule val temp = TemporaryFolder()
    private val files by lazy { temp.newFolder() }
    private val context by lazy { object : ContextWrapper(RuntimeEnvironment.getApplication()) {
        override fun getFilesDir() = files
    } }
    private class CountingManager(context: android.content.Context) : ContentsManager(context) {
        var parses = 0
        override fun readProfile(file: File): ContentProfile? { parses++; return super.readProfile(file) }
    }
    private fun installRuntime(name: String = "proton-12.0-arm64ec", code: Int = 123): File {
        val dir = File(ContentsManager.getContentTypeDir(context, ContentProfile.ContentType.CONTENT_TYPE_PROTON), name).apply { mkdirs() }
        File(dir, "profile.json").writeText(JSONObject().put("type", "Proton").put("versionName", name)
            .put("versionCode", code).put("description", "audit").put("files", org.json.JSONArray())
            .put("proton", JSONObject().put("binPath", "bin").put("libPath", "lib").put("prefixPack", "prefix.txz")).toString())
        return dir
    }
    @Test fun sharedRuntimeCatalogParsesEachProfileOnceAndRefreshSeesChanges() {
        val dir = installRuntime(); val manager = CountingManager(context); manager.syncContents()
        assertEquals(1, manager.parses)
        assertEquals(1, manager.getProfiles(ContentProfile.ContentType.CONTENT_TYPE_WINE).size)
        assertEquals(1, manager.getProfiles(ContentProfile.ContentType.CONTENT_TYPE_PROTON).size)
        val json = File(dir, "profile.json"); json.writeText(JSONObject(json.readText()).put("versionCode", 124).toString())
        manager.syncContents(); assertEquals(2, manager.parses)
        assertEquals(124, manager.getProfiles(ContentProfile.ContentType.CONTENT_TYPE_PROTON).single().verCode)
    }
    @Test fun multiDigitVersionCodeKeepsRuntimeArchitectureAndInstallPath() {
        val dir = installRuntime(); val manager = ContentsManager(context); manager.syncContents()
        val wine = WineInfo.fromIdentifier(context, manager, "proton-12.0-arm64ec-123")
        assertEquals("arm64ec", wine.arch); assertEquals("12.0", wine.version); assertEquals(dir.absolutePath, wine.path)
    }
    @Test fun removalUnpublishesProfileAndNextSyncCannotListIt() {
        val dir = installRuntime(); val manager = ContentsManager(context); manager.syncContents()
        ComponentRemoval.remove(dir); assertFalse(dir.exists()); manager.syncContents()
        assertTrue(manager.getProfiles(ContentProfile.ContentType.CONTENT_TYPE_WINE).isEmpty())
    }
    @Test fun retiredPartialPayloadIsNeverAnInstalledProfile() {
        val dir = installRuntime(); Files.move(dir.toPath(), File(dir.parentFile, ".removed-test").toPath())
        val manager = ContentsManager(context); manager.syncContents()
        assertTrue(manager.getProfiles(ContentProfile.ContentType.CONTENT_TYPE_WINE).isEmpty())
    }
    @Test fun failedRemovalLeavesInstalledPayloadIntact() {
        val dir = installRuntime(); val link = File(dir.parentFile, "link")
        Files.createSymbolicLink(link.toPath(), dir.toPath())
        assertThrows(Exception::class.java) { ComponentRemoval.remove(link) }
        assertTrue(File(dir, "profile.json").isFile)
    }
}
