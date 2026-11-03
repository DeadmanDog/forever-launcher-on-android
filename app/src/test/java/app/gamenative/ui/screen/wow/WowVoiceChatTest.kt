package app.gamenative.ui.screen.wow

import android.Manifest
import android.app.Application
import android.content.ContextWrapper
import com.winlator.container.Container
import com.winlator.container.ContainerManager
import com.winlator.xenvironment.ImageFs
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [29])
class WowVoiceChatTest {
    @get:Rule val temp = TemporaryFolder()
    private val files by lazy { temp.newFolder("files") }
    private val context by lazy { object : ContextWrapper(RuntimeEnvironment.getApplication()) { override fun getFilesDir() = files } }
    private var previous: Any? = null
    @Before fun before() {
        val field = ImageFs::class.java.getDeclaredField("INSTANCE").apply { isAccessible = true }
        previous = field.get(null); field.set(null, ImageFs.find(File(files, "imagefs")))
        shadowOf(RuntimeEnvironment.getApplication()).denyPermissions(Manifest.permission.RECORD_AUDIO)
    }
    @After fun after() { ImageFs::class.java.getDeclaredField("INSTANCE").apply { isAccessible = true }.set(null, previous) }
    private fun container() = Container("wow_forever").apply {
        rootDir = File(ImageFs.find(context).rootDir, "home/xuser-wow_forever").apply { mkdirs() }
        loadData(WowContainerProfile.defaultsForNewContainer("/games/wow", true, false)); saveData()
    }
    @Test fun permissionIsRequiredAndDenialDoesNotChangeContainer() {
        val c = container(); val before = c.configFile.readBytes()
        assertThrows(IllegalStateException::class.java) { WowVoiceChat.saveMicrophone(context, true) { false } }
        assertArrayEquals(before, c.configFile.readBytes())
    }
    @Test fun microphoneOptInPersistsOnlyItsOwnFieldAndPlayKeepsIt() {
        val c = container(); val before = JSONObject(c.configFile.readText())
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.RECORD_AUDIO)
        WowVoiceChat.saveMicrophone(context, true) { false }
        val saved = ContainerManager(context).getContainerById(c.id)!!
        assertTrue(saved.getMicEnabled())
        val after = JSONObject(saved.configFile.readText())
        for (key in before.keys()) if (key != "micEnabled") assertEquals(key, before.get(key).toString(), after.get(key).toString())
        WowContainerProfile.prepareExistingContainer(saved, "/games/wow"); saved.saveData()
        assertTrue(ContainerManager(context).getContainerById(c.id)!!.getMicEnabled())
        shadowOf(RuntimeEnvironment.getApplication()).denyPermissions(Manifest.permission.RECORD_AUDIO)
        WowVoiceChat.saveMicrophone(context, false) { false }
        assertFalse(ContainerManager(context).getContainerById(c.id)!!.getMicEnabled())
    }
    @Test fun activeSessionAndRaceAreRejectedAndNoContainerIsCreated() {
        assertThrows(IllegalStateException::class.java) { WowVoiceChat.saveMicrophone(context, false) { false } }
        assertNull(ContainerManager(context).getContainerById("wow_forever"))
        val c = container(); val before = c.configFile.readBytes()
        assertThrows(IllegalStateException::class.java) { WowVoiceChat.saveMicrophone(context, false) { true } }
        var checks = 0
        assertThrows(IllegalStateException::class.java) { WowVoiceChat.saveMicrophone(context, false) { ++checks >= 2 } }
        assertArrayEquals(before, c.configFile.readBytes())
    }
}
