package app.gamenative.ui.screen.wow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [29])
class WowAppUpdaterTest {
    @Test fun versionsCompareNumerically() {
        assertTrue(WowAppUpdater.isNewer("v1.0.2", "1.0.1"))
        assertTrue(WowAppUpdater.isNewer("1.1.0", "1.0.9"))
        assertTrue(WowAppUpdater.isNewer("v1.0.10", "1.0.9"))
        assertTrue(WowAppUpdater.isNewer("1.0.1.1", "1.0.1"))
        assertFalse(WowAppUpdater.isNewer("v1.0.1", "1.0.1"))
        assertFalse(WowAppUpdater.isNewer("v1.0.0", "1.0.1"))
        assertFalse(WowAppUpdater.isNewer("latest", "1.0.1"))
        assertEquals(listOf(1, 0, 2), WowAppUpdater.parts("v1.0.2-test"))
    }

    @Test fun theReleaseJsonGivesTheApkAsset() {
        val json = """{"tag_name":"v1.0.2","html_url":"https://github.com/x/y/releases/tag/v1.0.2","body":"Fixes\n- one",
            "assets":[{"name":"notes.txt","browser_download_url":"https://e/notes.txt","size":5},
                      {"name":"Forever-Launcher-v1.0.2.apk","browser_download_url":"https://e/a.apk","size":51000000}]}"""
        val r = WowAppUpdater.parse(json)!!
        assertEquals("v1.0.2", r.version); assertEquals("https://e/a.apk", r.apkUrl); assertEquals(51000000L, r.apkSize)
        assertEquals("Fixes\n- one", r.notes)
        assertNull(WowAppUpdater.parse("""{"tag_name":"v1.0.2","assets":[]}"""))
    }

    @Test fun onlyThePublishedPackageChecks() {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        // The development package never checks; the release tree's test package is forever.launcher itself.
        assertEquals(context.packageName == "forever.launcher", WowAppUpdater.enabled(context))
        assertFalse(WowAppUpdater.enabled(context) && context.packageName != "forever.launcher")
        assertNull(WowAppUpdater.pending(context))   // nothing found yet
    }
}
