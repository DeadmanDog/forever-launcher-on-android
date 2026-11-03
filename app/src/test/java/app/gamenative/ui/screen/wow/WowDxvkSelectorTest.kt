package app.gamenative.ui.screen.wow

import android.app.Application
import android.content.ContextWrapper
import com.winlator.container.Container
import com.winlator.contents.ContentProfile
import com.winlator.contents.ContentsManager
import com.winlator.core.KeyValueSet
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
class WowDxvkSelectorTest {
    @get:Rule val temp = TemporaryFolder()

    private fun profile(name: String = "2.7-arm64", code: Int = 3,
                        type: ContentProfile.ContentType = ContentProfile.ContentType.CONTENT_TYPE_DXVK) =
        ContentProfile().apply { this.type = type; verName = name; verCode = code; desc = "Installed test profile" }

    private fun container() = Container(WowContainerProfile.CONTAINER_ID).apply {
        rootDir = temp.newFolder()
        loadData(WowContainerProfile.defaultsForNewContainer("/games/wow", false, false))
        setDXWrapper("dxvk-2.4.1-wow-aarch64-test-1")
        setDXWrapperConfig("version=2.4.1-wow-aarch64-test-1,async=1,maxDeviceMemory=4096,customFlag=keep")
        saveData()
    }

    private fun apply(container: Container, profile: ContentProfile = profile()) =
        WowDxvkSelector.applyToContainer(container, WowDxvkSelector.identifier(profile), listOf(profile)) { false }

    @Test fun recognizesCurrentInstalledDxvk() {
        val p = profile("2.4.1-wow-aarch64-test", 1)
        assertSame(p, WowDxvkSelector.currentProfile("dxvk-2.4.1-wow-aarch64-test-1", "", listOf(p)))
        assertSame(p, WowDxvkSelector.currentProfile("dxvk", "version=2.4.1-wow-aarch64-test-1,async=1", listOf(p)))
        assertNull(WowDxvkSelector.currentProfile("dxvk-missing-1", "", listOf(p)))
    }

    @Test fun selectingAnotherInstalledProfileUpdatesBothIdentifiers() {
        val c = container()
        apply(c)
        assertEquals("dxvk-2.7-arm64-3", c.getDXWrapper())
        assertEquals("2.7-arm64-3", KeyValueSet(c.getDXWrapperConfig()).get("version"))
    }

    @Test fun preservesEveryOtherConfigKey() {
        val c = container()
        apply(c)
        assertEquals("version=2.7-arm64-3,async=1,maxDeviceMemory=4096,customFlag=keep", c.getDXWrapperConfig())
    }

    @Test fun insertsVersionWhenItIsMissing() {
        val c = container().apply { setDXWrapperConfig("async=1") }
        apply(c)
        assertEquals("async=1,version=2.7-arm64-3", c.getDXWrapperConfig())
    }

    @Test fun preservesWineGraphicsAndAllOtherSerializedRuntimeSettings() {
        val c = container().apply {
            wineVersion = "proton-custom-arm64ec-5"
            graphicsDriver = "Wrapper-custom"
            graphicsDriverConfig = "version=Turnip-custom,quality=low"
            graphicsDriverVersion = "Turnip-custom"
            envVars = "CUSTOM_ENV=keep WINEESYNC=1"
            cpuList = "4,5"
            audioDriver = "alsa"
            saveData()
        }
        val before = JSONObject(c.configFile.readText())
        apply(c)
        val after = JSONObject(c.configFile.readText())
        assertEquals(before.keys().asSequence().toSet(), after.keys().asSequence().toSet())
        before.keys().forEach { key ->
            if (key !in setOf("dxwrapper", "dxwrapperConfig")) {
                assertEquals("Preserve $key", before.get(key).toString(), after.get(key).toString())
            }
        }
    }

    @Test fun persistsSelectionAcrossContainerReloadAndPlayPreparation() {
        val c = container()
        apply(c)
        val loaded = Container(c.id).apply { rootDir = c.rootDir; loadData(JSONObject(c.configFile.readText())) }
        assertFalse(WowContainerProfile.prepareExistingContainer(loaded, "/games/wow"))
        assertEquals("dxvk-2.7-arm64-3", loaded.getDXWrapper())
        assertEquals("2.7-arm64-3", KeyValueSet(loaded.getDXWrapperConfig()).get("version"))
    }

    @Test fun rejectsMissingProfileWithoutSavingOrFallback() {
        val c = container()
        val before = c.configFile.readText()
        assertThrows(IllegalStateException::class.java) {
            WowDxvkSelector.applyToContainer(c, "dxvk-missing-3", listOf(profile())) { false }
        }
        assertEquals(before, c.configFile.readText())
        assertEquals("dxvk-2.4.1-wow-aarch64-test-1", c.getDXWrapper())
    }

    @Test fun rejectsNonDxvkProfile() {
        val c = container()
        val p = profile(type = ContentProfile.ContentType.CONTENT_TYPE_TURNIP)
        assertThrows(IllegalStateException::class.java) { apply(c, p) }
    }

    @Test fun rejectsMalformedVersionWithoutSaving() {
        val c = container()
        val before = c.configFile.readText()
        assertThrows(IllegalArgumentException::class.java) { apply(c, profile("bad,async=0")) }
        assertEquals(before, c.configFile.readText())
    }

    @Test fun rejectsActiveSessionAndSessionStartingBeforeSave() {
        val c = container()
        val before = c.configFile.readText()
        val p = profile()
        assertThrows(IllegalStateException::class.java) {
            WowDxvkSelector.applyToContainer(c, WowDxvkSelector.identifier(p), listOf(p)) { true }
        }
        var checks = 0
        assertThrows(IllegalStateException::class.java) {
            WowDxvkSelector.applyToContainer(c, WowDxvkSelector.identifier(p), listOf(p)) { ++checks >= 2 }
        }
        assertEquals(before, c.configFile.readText())
    }

    @Test fun listsOnlyInstalledDxvkAndLaunchResolvesExactSelectedProfile() {
        val files = temp.newFolder()
        val context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getFilesDir() = files
        }
        val p = profile()
        val dir = ContentsManager.getInstallDir(context, p).apply { mkdirs() }
        val metadata = File(dir, ContentsManager.PROFILE_NAME)
        metadata.writeText(JSONObject().apply {
            put("type", "DXVK"); put("versionName", p.verName); put("versionCode", p.verCode)
            put("description", p.desc); put("files", org.json.JSONArray())
        }.toString())
        File(dir.parentFile, "broken").apply { mkdirs() }.resolve("profile.json").writeText("invalid")
        val profiles = WowDxvkSelector.installedProfiles(context)
        assertEquals(1, profiles.size)
        assertEquals(p.verName, profiles.single().verName)
        assertEquals(p.verCode, profiles.single().verCode)
        val manager = ContentsManager(context).apply { syncContents() }
        assertEquals(p.verName, manager.getProfileByEntryName(WowDxvkSelector.identifier(profiles.single())).verName)
        metadata.delete()
        assertTrue(WowDxvkSelector.installedProfiles(context).isEmpty())
    }

    @Test fun applyDoesNotTouchPrefixFiles() {
        val c = container()
        val marker = File(c.rootDir, ".wine/system.reg").apply { parentFile!!.mkdirs(); writeText("unchanged") }
        apply(c)
        assertEquals("unchanged", marker.readText())
    }
}
