package app.gamenative.ui.screen.wow

import android.app.Application
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Codex UI16-01: a reopened addon page has a new controller and manager, so only the app-wide job count can keep writes apart. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [29])
class WowAddonJobsTest {
    private val root: File = Files.createTempDirectory("addon-jobs").toFile()

    @After fun tearDown() {
        while (WowAddonJobs.running > 0) WowAddonJobs.end()
        root.deleteRecursively()
    }

    @Test fun aReopenedPageWaitsForTheWriteOfTheClosedOne() {
        // The closed page's install is still running in the app-wide scope.
        val pending = "Installing Questie" to "Questie telepítése"
        WowAddonJobs.begin(pending)

        val reopened = WowAddonsController(root, CoroutineScope(Dispatchers.Unconfined))
        assertTrue(reopened.busy)
        assertEquals(pending, reopened.working)

        reopened.updateAll()
        reopened.installFromUrl("https://github.com/owner/repo")
        assertEquals(1, WowAddonJobs.running)
        assertEquals(pending, WowAddonJobs.label)

        WowAddonJobs.end()
        assertEquals(0, WowAddonJobs.running)
        assertNull(reopened.working)
    }
}
