package app.gamenative.ui.screen.wow

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
class WowDisplaySettingsTest {
    @get:Rule val temp = TemporaryFolder()
    private val files by lazy { temp.newFolder("files") }
    private val context by lazy { object : ContextWrapper(RuntimeEnvironment.getApplication()) { override fun getFilesDir() = files } }
    private var previous: Any? = null
    @Before fun before() {
        val field = ImageFs::class.java.getDeclaredField("INSTANCE").apply { isAccessible = true }
        previous = field.get(null); field.set(null, ImageFs.find(File(files, "imagefs")))
    }
    @After fun after() { ImageFs::class.java.getDeclaredField("INSTANCE").apply { isAccessible = true }.set(null, previous) }
    private fun container(id: String = "wow_forever") = Container(id).apply {
        rootDir = File(ImageFs.find(context).rootDir, "home/xuser-$id").apply { mkdirs() }
        loadData(WowContainerProfile.defaultsForNewContainer("/games/wow", true, false))
        saveData()
    }
    @Test fun selectorIncludesNativeModeAndPreservesCustomSavedResolution() {
        val options = WowDisplaySettings.options("1366x768")
        assertEquals("1344×608", options.single { it.value == "1344x608" }.label)
        assertEquals("Full screen (1344×608)", WowDisplaySettings.options("1366x768", "1344x608").single { it.value == "1344x608" }.label)
        assertTrue(options.any { it.value == "1366x768" }); assertTrue(options.any { it.value == "1280x720" })
    }
    @Test fun applyPersistsOnlyDisplayOwnershipAndPlayDoesNotResetIt() {
        val c = container()
        val before = JSONObject(c.configFile.readText())
        val other = container("other"); val otherBytes = other.configFile.readBytes()
        WowDisplaySettings.apply(context, "1344x608") { false }
        val saved = ContainerManager(context).getContainerById(c.id)!!
        assertEquals("1344x608", saved.screenSize); assertEquals("1344x608", WowDisplaySettings.gameResolution(saved))
        val after = JSONObject(saved.configFile.readText())
        for (key in before.keys()) if (key !in listOf("screenSize", "extraData"))
            assertEquals("Unrelated field $key changed", before.get(key).toString(), after.get(key).toString())
        WowContainerProfile.prepareExistingContainer(saved, "/games/new-wow"); saved.saveData()
        assertEquals("1344x608", ContainerManager(context).getContainerById(c.id)!!.screenSize)
        assertArrayEquals(otherBytes, other.configFile.readBytes())
    }
    @Test fun theOldRedMagicPresetMovesToTheHalfResolutionOnce() {
        val c = container()
        c.putExtra("redmagicHalfMigration", null)   // a container made before this migration existed
        c.screenSize = WowDisplaySettings.LEGACY_REDMAGIC; c.putExtra(WowDisplaySettings.MANAGED, "1")
        assertTrue(WowContainerProfile.prepareExistingContainer(c, "/games/wow"))
        assertEquals("1344x608", c.screenSize); assertEquals(WowDisplaySettings.REDMAGIC, c.screenSize)
        assertEquals("1344x608", WowDisplaySettings.gameResolution(c))
        // The migration is one-time: a resolution chosen afterwards is never moved again.
        c.screenSize = "1920x1080"
        WowContainerProfile.prepareExistingContainer(c, "/games/wow")
        assertEquals("1920x1080", c.screenSize)
    }
    @Test fun otherResolutionsAndUnmanagedContainersAreLeftAloneByTheMigration() {
        val custom = container("custom"); custom.putExtra("redmagicHalfMigration", null); custom.screenSize = "1600x900"; custom.putExtra(WowDisplaySettings.MANAGED, "1")
        WowContainerProfile.prepareExistingContainer(custom, "/games/wow"); assertEquals("1600x900", custom.screenSize)
        val unmanaged = container("unmanaged"); unmanaged.putExtra("redmagicHalfMigration", null); unmanaged.screenSize = WowDisplaySettings.LEGACY_REDMAGIC
        WowContainerProfile.prepareExistingContainer(unmanaged, "/games/wow")
        assertEquals("a resolution the launcher does not own is not touched", WowDisplaySettings.LEGACY_REDMAGIC, unmanaged.screenSize)
    }
    @Test fun aNewRedMagicContainerStartsAtHalfTheDisplay() {
        val json = WowContainerProfile.defaultsForNewContainer("/games/wow", true, false, fitsRedMagic = true)
        assertEquals("1344x608", json.getString("screenSize"))
        assertTrue(WowDisplaySettings.options("").any { it.value == "1344x608" } && WowDisplaySettings.options("").none { it.value == "1792x810" })
        assertTrue(WowHardwareScale.fitsDisplay(2688, 1216, WowDisplaySettings.REDMAGIC) && WowHardwareScale.fitsDisplay(1216, 2688, WowDisplaySettings.REDMAGIC))
    }
    @Test fun activeSessionAndLaunchRaceAreRejectedBeforeSaving() {
        val c = container(); val before = c.configFile.readBytes()
        assertThrows(IllegalStateException::class.java) { WowDisplaySettings.apply(context, "1344x608") { true } }
        var checks = 0
        assertThrows(IllegalStateException::class.java) { WowDisplaySettings.apply(context, "1344x608") { ++checks > 1 } }
        assertArrayEquals(before, c.configFile.readBytes())
    }
    @Test fun missingContainerIsNotCreatedAndInvalidModeIsRejected() {
        assertThrows(IllegalStateException::class.java) { WowDisplaySettings.apply(context, "1344x608") { false } }
        assertNull(ContainerManager(context).getContainerById("wow_forever"))
        val c = container(); val before = c.configFile.readBytes()
        for (value in listOf("0x810", "1792x0", "garbage", "99999x99999"))
            assertThrows(IllegalArgumentException::class.java) { WowDisplaySettings.apply(context, value) { false } }
        assertArrayEquals(before, c.configFile.readBytes())
    }
    @Test fun existingContainerIsNotAutomaticallyOptedIntoGameConfigManagement() {
        assertNull(WowDisplaySettings.gameResolution(container()))
    }
    @Test fun gameConfigAlignsOutputPixelSizeWithoutChangingRenderScaleOrOtherGraphics() {
        val source = listOf("SET gxWindowedResolution \"1920x1080\"", "set GXRESOLUTION \"1280x720\"",
            "SET gxResolution \"800x600\"", "SET RenderScale \"1.5\"", "SET uiScale \"0.65\"", "SET farclip \"1200\"", "SET gxWindow \"1\"")
        val result = WowDisplaySettings.configLines(source, "1344x608")
        assertTrue(result.contains("SET gxWindowedResolution \"1344x608\""))
        // gxResolution is not a CVar of this client: dropped; the full-screen size is set alongside the windowed one.
        assertEquals(0, result.count { it.startsWith("SET gxResolution ", ignoreCase = true) })
        assertTrue(result.contains("SET gxFullscreenResolution \"1344x608\"")); assertTrue(result.contains("SET RenderScale \"1.5\""))
        for (line in source.takeLast(3)) assertTrue(result.contains(line))
        assertEquals(result, WowDisplaySettings.configLines(result, "1344x608"))
    }
    @Test fun returningToAnotherResolutionUsesItWithoutTouchingUnmanagedConfig() {
        val file = temp.newFile("Config.wtf"); file.writeText("SET custom \"keep\"\n")
        WowDisplaySettings.alignGameConfig(file, null); assertEquals("SET custom \"keep\"\n", file.readText())
        WowDisplaySettings.alignGameConfig(file, "1344x608")
        WowDisplaySettings.alignGameConfig(file, "1280x720")
        assertTrue(file.readText().contains("SET gxFullscreenResolution \"1280x720\"")); assertFalse(file.readText().contains("1344x608"))
    }
    @Test fun cutoutPolicyAppliesOnlyToNativeLandscapeModeAndSupportsOldAndroid() {
        assertEquals(3, WowDisplaySettings.cutoutMode("1344x608", false, 30))
        assertEquals(1, WowDisplaySettings.cutoutMode("1344x608", false, 28))
        assertNull(WowDisplaySettings.cutoutMode("1280x720", false, 35))
        assertNull(WowDisplaySettings.cutoutMode("1344x608", true, 35))
        assertNull(WowDisplaySettings.cutoutMode("1344x608", false, 27))
    }
    @Test fun geometryRecordsActualDimensionsOnlyOnChange() {
        val directory = temp.newFolder("geometry")
        val record = WowDisplayGeometry(directory) { it() }
        assertTrue(record.presented(5,1792,810) { "World of Warcraft" })
        repeat(100) { assertFalse(record.presented(5,1792,810) { "World of Warcraft" }) }
        assertTrue(record.presented(5,1280,720))
        assertFalse(record.presented(5,0,0))
        // Two windows presenting in turn: the label is built only for each window's first record, not on every Present.
        var labels = 0
        repeat(60) { record.presented(7, 640, 480) { labels++; "A / a" }; record.presented(8, 320, 240) { labels++; "B / b" } }
        assertEquals(2, labels)
        val text = File(directory,"display-geometry.log").readText()
        assertEquals(4, text.lines().count { it.contains("presented_pixmap") })
        assertTrue(text.contains("width=1792 height=810")); assertTrue(text.contains("not internal 3D target"))
    }

    @Test fun fullScreenIsHalfTheDisplayOr720HighInItsOwnAspect() {
        assertEquals("1344x608", WowDisplaySettings.fullscreenFor(2688, 1216))   // RedMagic 11 Pro: exactly half
        assertEquals("1344x608", WowDisplaySettings.fullscreenFor(1216, 2688))   // portrait sizes give the landscape value
        assertEquals(WowDisplaySettings.REDMAGIC, WowDisplaySettings.fullscreenFor(2688, 1216))
        assertEquals("1590x720", WowDisplaySettings.fullscreen720For(2688, 1216)) // 1.0.0's value, the migration source
        assertEquals("1600x720", WowDisplaySettings.fullscreenFor(2400, 1080))
        assertEquals("1600x720", WowDisplaySettings.fullscreenFor(3200, 1440))
        assertEquals("1560x720", WowDisplaySettings.fullscreenFor(2340, 1080))
        assertEquals("1024x600", WowDisplaySettings.fullscreenFor(1024, 600))    // a lower display keeps its own height
        // Each fills its display without stretching (at most one guest pixel cut per edge), so hardware scaling applies too.
        listOf(2688 to 1216, 2400 to 1080, 3200 to 1440, 2340 to 1080).forEach { (w, h) ->
            assertTrue("$w x $h", WowHardwareScale.fitsDisplay(w, h, WowDisplaySettings.fullscreenFor(w, h)!!))
        }
        val redMagic = WowDisplaySettings.options("1590x720", "1344x608")
        assertEquals("1344x608", redMagic.first().value); assertTrue(redMagic.first().fullscreen)
        assertEquals(1, redMagic.count { it.value == "1344x608" })                 // Full screen is the RedMagic entry, listed once
        assertTrue(redMagic.any { it.value == "1590x720" && !it.fullscreen })      // a saved 1.0.0 value stays selectable
        val options = WowDisplaySettings.options("1344x608", "1590x720")
        assertEquals("1590x720", options.first().value); assertTrue(options.first().fullscreen)
        assertTrue(options.single { it.value == "1344x608" }.let { !it.fullscreen })   // the saved value stays selectable
        // Full screen first, then 960x544 (as in Winlator), 1280x720 and the old RedMagic 1344x608 under it.
        assertEquals(listOf("1590x720", "960x544", "1280x720", "1344x608", "1600x900"), options.take(5).map { it.value })
        val json = WowContainerProfile.defaultsForNewContainer("/games/wow", true, false, fullscreen = "1590x720")
        assertEquals("1590x720", json.getString("screenSize")); assertEquals("1", json.getJSONObject("extraData").getString(WowDisplaySettings.MANAGED))
    }

    @Test fun aManagedRedMagicValueMovesOnceToFullScreenButAHandPickedOneStays() {
        val c = container("fs"); c.screenSize = WowDisplaySettings.REDMAGIC; c.putExtra(WowDisplaySettings.MANAGED, "1"); c.putExtra("fullscreen720Migration", null)
        assertTrue(WowContainerProfile.moveToFullscreen(c, "1590x720")); assertEquals("1590x720", c.screenSize)
        c.screenSize = "1344x608"   // picked again by hand later: the one-time move does not repeat
        assertFalse(WowContainerProfile.moveToFullscreen(c, "1590x720")); assertEquals("1344x608", c.screenSize)
        val custom = container("fs2"); custom.screenSize = "1920x1080"; custom.putExtra(WowDisplaySettings.MANAGED, "1"); custom.putExtra("fullscreen720Migration", null)
        WowContainerProfile.moveToFullscreen(custom, "1590x720"); assertEquals("1920x1080", custom.screenSize)
    }

    @Test fun aManaged100FullScreenValueMovesOnceToTheExactHalf() {
        val c = container("half"); c.screenSize = "1590x720"; c.putExtra(WowDisplaySettings.MANAGED, "1"); c.putExtra("fullscreenHalfMigration", null)
        assertTrue(WowContainerProfile.moveToHalfFullscreen(c, "1344x608", "1590x720")); assertEquals("1344x608", c.screenSize)
        c.screenSize = "1590x720"   // picked again by hand later: the one-time move does not repeat
        assertFalse(WowContainerProfile.moveToHalfFullscreen(c, "1344x608", "1590x720")); assertEquals("1590x720", c.screenSize)
        val custom = container("half2"); custom.screenSize = "1920x1080"; custom.putExtra(WowDisplaySettings.MANAGED, "1"); custom.putExtra("fullscreenHalfMigration", null)
        WowContainerProfile.moveToHalfFullscreen(custom, "1344x608", "1590x720"); assertEquals("1920x1080", custom.screenSize)
        val unmanaged = container("half3"); unmanaged.screenSize = "1590x720"; unmanaged.putExtra(WowDisplaySettings.MANAGED, null); unmanaged.putExtra("fullscreenHalfMigration", null)
        WowContainerProfile.moveToHalfFullscreen(unmanaged, "1344x608", "1590x720"); assertEquals("1590x720", unmanaged.screenSize)
        // A phone whose Full screen did not change (2400x1080 -> 1600x720 both ways) keeps its value.
        val other = container("half4"); other.screenSize = "1600x720"; other.putExtra(WowDisplaySettings.MANAGED, "1"); other.putExtra("fullscreenHalfMigration", null)
        WowContainerProfile.moveToHalfFullscreen(other, "1600x720", "1600x720"); assertEquals("1600x720", other.screenSize)
    }
}
