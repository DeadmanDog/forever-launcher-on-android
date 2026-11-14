package app.gamenative.ui.screen.wow

import android.app.Application
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.RobolectricTestRunner

/** 2026-11-12 deep review: game data download fixes. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class Review9FixesTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @After fun clear() { context.getSharedPreferences(WowLaunchPolicy.PREFS, 0).edit().clear().commit() }

    private fun gameRootWithLocale(locale: String): File {
        val root = Files.createTempDirectory("game").toFile()
        File(root, "${WowClientDownloader.FLAVOR_DIR}/WTF").apply { mkdirs() }
            .resolve("Config.wtf").writeText("SET textLocale \"$locale\"\n")
        return root
    }

    @Test fun gameDataFollowsTheLanguageChosenInSettingsElseConfigWtf() {
        val root = gameRootWithLocale("deDE")
        assertEquals("deDE", WowGameData.locale(context, root))
        check(context.getSharedPreferences(WowLaunchPolicy.PREFS, 0).edit().putString(WowRuntimeTuning.GAME_LOCALE, "frFR").commit())
        // The next launch writes frFR into Config.wtf, so the download must fetch the French files, not the German ones.
        assertEquals("frFR", WowGameData.locale(context, root))
    }

    @Test fun onlyTheEntryBlocksOfAnArchiveIndexAreScanned() {
        // 3 blocks: 3 * 4096 entries + 3 * 24 table + 28 footer.
        val size = 3 * 4096 + 3 * 24 + 28
        assertEquals(3, WowGameDataDownloader.indexBlocks(size))
        assertTrue(WowGameDataDownloader.wholeIndex(size))
        // The old loop stopped at size - 28 and so also read the table (which repeats real keys) as entries.
        assertTrue((3 * 4096 + 3 * 24) / 4096 == 3 && WowGameDataDownloader.indexBlocks(size) * 4096 < size - 28)
        assertEquals(0, WowGameDataDownloader.indexBlocks(10))
    }

    @Test fun aHalfWrittenIndexIsNotTrustedAndWritesAreAtomic() {
        assertFalse(WowGameDataDownloader.wholeIndex(4096 + 5))
        assertFalse(WowGameDataDownloader.wholeIndex(0))
        val dir = Files.createTempDirectory("indices").toFile()
        val target = File(dir, "abc.index").apply { writeBytes(byteArrayOf(1)) }
        val data = ByteArray(4096 + 24 + 28) { it.toByte() }
        WowGameDataDownloader.writeAtomically(target, data)
        assertArrayEquals(data, target.readBytes())
        assertEquals(listOf("abc.index"), dir.list()!!.toList())
    }
}
