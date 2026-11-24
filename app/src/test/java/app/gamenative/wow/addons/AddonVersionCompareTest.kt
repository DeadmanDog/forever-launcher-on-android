package app.gamenative.wow.addons

import java.nio.file.Files
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Review 3 (9): hand-copied addons without a usable TOC version were offered an update forever; "v1.2" equals "1.2". */
class AddonVersionCompareTest {
    private val manager = Files.createTempDirectory("addons").toFile().let { dir ->
        AddonManager(dir, AddonDb.forAddonsDir(dir), emptyList(), OkHttpClient())
    }

    @Test fun unknownVersionsSayNothing() {
        assertNull(manager.knownVersion(null))
        assertNull(manager.knownVersion("  "))
        assertNull(manager.knownVersion("@project-version@"))
        assertNull(manager.knownVersion("release"))
    }

    @Test fun aLeadingVIsIgnored() {
        assertEquals("1.2.3", manager.knownVersion("v1.2.3"))
        assertEquals("12.11.1", manager.knownVersion(" V12.11.1 "))
        assertTrue(manager.sameVersion("Addon-1.2.3", manager.knownVersion("v1.2.3")!!))
        assertFalse(manager.sameVersion("Addon-1.2.30", manager.knownVersion("v1.2.3")!!))
    }
}
