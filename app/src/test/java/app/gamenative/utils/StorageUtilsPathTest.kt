package app.gamenative.utils

import android.app.Application
import app.gamenative.ui.screen.wow.GamePath
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * 1.4.8: a folder picker handed back "53FC-3FF3:WoWF" for /storage/emulated/0/WoWF (no such volume is mounted), and the launcher
 * then looked for the game in a folder that does not exist. The path is now matched onto a mounted volume; the folder name does
 * not matter, only .build.info and Data/ inside it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class StorageUtilsPathTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun anUnmountedVolumeIsMatchedOntoTheVolumeThatHasTheFolder() {
        val internal = temp.newFolder("emulated0"); val card = temp.newFolder("card")
        File(internal, "WoWF").mkdirs(); File(card, "CLSJSJ").mkdirs()
        val roots = listOf(internal.path, card.path)
        assertEquals("${internal.path}/WoWF", StorageUtils.existingPath("/storage/53FC-3FF3/WoWF", roots))
        // Any folder name works, on any volume.
        assertEquals("${card.path}/CLSJSJ", StorageUtils.existingPath("/storage/ABCD-1234/CLSJSJ", roots))
        // A path that exists is kept; one that exists nowhere gives null (the caller keeps its own guess).
        assertEquals("${internal.path}/WoWF", StorageUtils.existingPath("${internal.path}/WoWF", roots))
        assertNull(StorageUtils.existingPath("/storage/53FC-3FF3/Nothing", roots))
        assertNull(StorageUtils.existingPath("/storage/53FC-3FF3", roots))
    }

    @Test fun theGameRootIsFoundByItsFilesNotByTheFolderName() {
        val game = temp.newFolder("CLSJSJ"); File(game, ".build.info").writeText("x"); File(game, "Data").mkdirs()
        assertEquals(game, GamePath.findGameRoot(game))
        // Picking the Data folder or the parent also finds it.
        assertEquals(game, GamePath.findGameRoot(File(game, "Data")))
        // Picking the folder above it finds it by its files too.
        assertEquals(game, GamePath.findGameRoot(game.parentFile))
    }
}
