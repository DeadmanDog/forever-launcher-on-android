package app.gamenative.display

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
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [29])
class WowDisplayBackendTest {
    @get:Rule val temp = TemporaryFolder()
    private val files by lazy { temp.newFolder("files") }
    private val context by lazy { object : ContextWrapper(RuntimeEnvironment.getApplication()) { override fun getFilesDir() = files } }
    private var previous: Any? = null
    @Before fun before() {
        val field = ImageFs::class.java.getDeclaredField("INSTANCE").apply { isAccessible = true }
        previous = field.get(null); field.set(null, ImageFs.find(File(files, "imagefs")))
    }
    @After fun after() { ImageFs::class.java.getDeclaredField("INSTANCE").apply { isAccessible = true }.set(null, previous) }
    private fun container() = Container("wow_forever").apply {
        rootDir = File(ImageFs.find(context).rootDir,"home/xuser-wow_forever").apply { mkdirs() }
        wineVersion = "custom-proton"; dxWrapper = "custom-dxvk"; graphicsDriver = "custom-driver"
        saveData()
    }
    @Test fun absentSettingKeepsXserver() { assertFalse(WowDisplayBackend.isGamescope(container())) }
    @Test fun gamescopeChoicePersistsAndChangingBackPreservesRuntime() {
        val c = container()
        // Already validated payload simulation: no native display is loaded in policy tests.
        val dir = WowGamescopePayload.directory(context).apply { mkdirs() }
        for (name in listOf("bin/gamescope","bin/gamescopereaper","bin/Xwayland","lib/libgndrm_shim.so","lib/libadrenotools_gamescope.so"))
            File(dir,name).apply { parentFile.mkdirs(); writeText("test") }
        File(dir,".wow-payload-sha256").writeText(WowGamescopePayload.SHA256)
        val before = JSONObject(c.configFile.readText())
        WowDisplayBackend.apply(context,WowDisplayBackend.GAMESCOPE, prepare = {}) { false }
        assertTrue(WowDisplayBackend.isGamescope(ContainerManager(context).getContainerById(c.id)))
        WowDisplayBackend.apply(context,WowDisplayBackend.XSERVER) { false }
        val saved = ContainerManager(context).getContainerById(c.id)!!
        assertFalse(WowDisplayBackend.isGamescope(saved))
        val after = JSONObject(saved.configFile.readText())
        for (key in before.keys()) if (key != "extraData") assertEquals(key,before.get(key).toString(),after.get(key).toString())
    }
    @Test fun activeSessionAndRaceCannotSaveBackend() {
        val c = container(); val before = c.configFile.readBytes()
        assertThrows(IllegalStateException::class.java) { WowDisplayBackend.apply(context,WowDisplayBackend.XSERVER) { true } }
        var checks = 0
        assertThrows(IllegalStateException::class.java) { WowDisplayBackend.apply(context,WowDisplayBackend.XSERVER) { ++checks > 1 } }
        assertArrayEquals(before,c.configFile.readBytes())
    }
    @Test fun missingContainerIsNeverCreated() {
        assertThrows(IllegalStateException::class.java) { WowDisplayBackend.apply(context,WowDisplayBackend.XSERVER) { false } }
        assertNull(ContainerManager(context).getContainerById("wow_forever"))
    }
    @Test fun failedPreparationDoesNotChangeTheSavedBackend() {
        val c = container(); val before = c.configFile.readBytes()
        assertThrows(IllegalStateException::class.java) {
            WowDisplayBackend.apply(context,WowDisplayBackend.GAMESCOPE,prepare = { error("broken payload") }) { false }
        }
        assertArrayEquals(before,c.configFile.readBytes())
    }
    @Test fun invalidChoiceIsRejected() { assertThrows(IllegalArgumentException::class.java) { WowDisplayBackend.apply(context,"invalid") { false } } }
    @Test fun guestGeometryIsExactAndInvalidGeometryRejected() {
        assertEquals(1344 to 608,WowGamescopeRuntime.geometry("1344x608"))
        assertEquals(1792 to 810,WowGamescopeRuntime.geometry("1792x810"))
        for (v in listOf("","0x0","10x720","720x99999","720x810 -- bad"))
            assertThrows(IllegalStateException::class.java) { try { WowGamescopeRuntime.geometry(v) } catch (e: IllegalArgumentException) { throw IllegalStateException(e) } }
    }
}
