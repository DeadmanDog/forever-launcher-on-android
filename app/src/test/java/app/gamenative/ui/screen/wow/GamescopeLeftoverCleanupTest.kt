package app.gamenative.ui.screen.wow

import android.app.Application
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files

/** 1.2.7: the Gamescope backend is gone; an update removes what an earlier build unpacked for it (about 70 MB) and keeps the rest. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class GamescopeLeftoverCleanupTest {
    @Test fun theUnpackedPayloadItsCopiesTheCachedArchiveAndTheAliasGo() {
        val context = RuntimeEnvironment.getApplication()
        val files = context.filesDir
        val payload = File(files, "gamescope/lib").apply { mkdirs() }
        File(payload, "libgallium.so").writeBytes(ByteArray(16))
        File(files, "gamescope.previous/bin").mkdirs()
        val cached = File(context.cacheDir, "gamescope-gamenative-20261105.5-wow-dd4-cursor.tzst").apply { writeBytes(ByteArray(4)) }
        val alias = File(files.absoluteFile.parentFile, ".gsc")
        alias.delete(); Files.createSymbolicLink(alias.toPath(), File(files, "gamescope").toPath())
        val kept = File(files, "keep-me.txt").apply { writeText("x") }

        WowBundledComponents.removeGamescope(context)

        assertFalse(File(files, "gamescope").exists())
        assertFalse(File(files, "gamescope.previous").exists())
        assertFalse(cached.exists())
        assertFalse(Files.isSymbolicLink(alias.toPath()) || alias.exists())
        assertTrue(kept.isFile)
        WowBundledComponents.removeGamescope(context) // nothing left: nothing done, no error
    }
}
