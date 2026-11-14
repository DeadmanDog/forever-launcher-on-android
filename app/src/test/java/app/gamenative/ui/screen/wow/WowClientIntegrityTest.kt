package app.gamenative.ui.screen.wow

import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Review 2026-11-13 (security): the program and its DLLs are checked against Blizzard's content keys before a launch. */
class WowClientIntegrityTest {
    private lateinit var root: File
    private lateinit var keysDir: File
    private val buildKey = "0123456789abcdef0123456789abcdef"

    @Before fun setUp() {
        root = Files.createTempDirectory("wow-root").toFile()
        keysDir = Files.createTempDirectory("wow-keys").toFile()
        WowClientIntegrity.useDirectory(keysDir)
        File(root, ".build.info").writeText(
            "Branch!STRING:0|Active!DEC:1|Build Key!HEX:16|CDN Key!HEX:16|Version!STRING:0|Product!STRING:0\n" +
                "eu|1|$buildKey|fedcba9876543210fedcba9876543210|1.15.8.70630|wowf\n")
        File(root, "_forever_/Utils").mkdirs()
        write("Wow-ARM64.exe", "program")
        write("game.dll", "library")
        write("Utils/voice.dll", "voice")
    }

    @After fun tearDown() {
        WowClientIntegrity.useDirectory(null)
        root.deleteRecursively()
        keysDir.deleteRecursively()
    }

    private fun write(path: String, text: String) = File(root, "_forever_/$path").apply { parentFile.mkdirs(); writeText(text) }
    private fun md5(text: String) = MessageDigest.getInstance("MD5").digest(text.toByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun manifest() = listOf("Wow-ARM64.exe" to md5("program"), "game.dll" to md5("library"), "Utils/voice.dll" to md5("voice"))
    private val noFetch: (String) -> List<Pair<String, String>>? = { null }

    @Test fun originalFilesPass() {
        WowClientIntegrity.remember(buildKey, manifest())
        assertEquals(WowClientIntegrity.Result.Ok, WowClientIntegrity.verify(root, noFetch))
    }

    @Test fun aReplacedProgramOrDllStopsTheLaunch() {
        WowClientIntegrity.remember(buildKey, manifest())
        write("Wow-ARM64.exe", "evil program")
        write("game.dll", "evil dll")
        val result = WowClientIntegrity.verify(root, noFetch)
        assertEquals(WowClientIntegrity.Result.Mismatch(listOf("Wow-ARM64.exe", "game.dll")), result)
    }

    @Test fun aMissingProgramIsAMismatchButAMissingDllIsNot() {
        WowClientIntegrity.remember(buildKey, manifest())
        File(root, "_forever_/game.dll").delete()
        assertEquals(WowClientIntegrity.Result.Ok, WowClientIntegrity.verify(root, noFetch))
        File(root, "_forever_/Wow-ARM64.exe").delete()
        assertEquals(WowClientIntegrity.Result.Mismatch(listOf("Wow-ARM64.exe")), WowClientIntegrity.verify(root, noFetch))
    }

    @Test fun onlyTheProgramFolderIsChecked() {
        WowClientIntegrity.remember(buildKey, manifest())
        write("Utils/voice.dll", "changed voice library")
        assertEquals(WowClientIntegrity.Result.Ok, WowClientIntegrity.verify(root, noFetch))
        assertEquals(listOf("Wow-ARM64.exe", "game.dll"), WowClientIntegrity.load(buildKey)!!.map { it.first })
    }

    @Test fun withoutAnyKeysTheCheckIsSkippedNotBlocking() {
        assertTrue(WowClientIntegrity.verify(root, noFetch) is WowClientIntegrity.Result.Skipped)
        assertTrue(WowClientIntegrity.verify(root) { throw java.io.IOException("offline") } is WowClientIntegrity.Result.Skipped)
    }

    @Test fun fetchedKeysAreUsedAndKeptForTheNextLaunch() {
        var fetches = 0
        val fetch: (String) -> List<Pair<String, String>>? = { key -> assertEquals(buildKey, key); fetches++; manifest() }
        assertEquals(WowClientIntegrity.Result.Ok, WowClientIntegrity.verify(root, fetch))
        assertEquals(WowClientIntegrity.Result.Ok, WowClientIntegrity.verify(root, fetch))
        assertEquals("fetched once per build", 1, fetches)
    }

    @Test fun keysWithoutTheProgramAreNotTrusted() {
        WowClientIntegrity.remember(buildKey, listOf("game.dll" to md5("library")))
        assertTrue(WowClientIntegrity.verify(root, noFetch) is WowClientIntegrity.Result.Skipped)
    }

    @Test fun anUnchangedFileIsNotHashedAgain() {
        WowClientIntegrity.remember(buildKey, manifest())
        assertEquals(WowClientIntegrity.Result.Ok, WowClientIntegrity.verify(root, noFetch))
        val verified = File(keysDir, "verified.txt").readLines()
        assertEquals(2, verified.size)
        assertTrue(verified.all { it.contains(File(root, "_forever_").absolutePath) })
        // A second check of the same files only compares the stat stamps and keeps them.
        assertEquals(WowClientIntegrity.Result.Ok, WowClientIntegrity.verify(root, noFetch))
        assertEquals(verified, File(keysDir, "verified.txt").readLines())
    }

    @Test fun noBuildInfoSkips() {
        File(root, ".build.info").delete()
        WowClientIntegrity.remember(buildKey, manifest())
        assertTrue(WowClientIntegrity.verify(root, noFetch) is WowClientIntegrity.Result.Skipped)
    }

    @Test fun pathsThatLeaveTheProgramFolderAreIgnored() {
        val kept = WowClientIntegrity.checked(listOf("../evil.dll" to md5("x"), "a/b.dll" to md5("x"), "Wow-ARM64.exe" to "nothex",
            "x.DLL" to md5("x").uppercase()))
        assertEquals(listOf("x.DLL" to md5("x")), kept)
    }

    @Test fun onlyBlizzardHostsAreTrustedForRememberedKeys() {
        assertTrue(WowClientDownloader.blizzardHostsOnly(listOf("level3.blizzard.com", "eu.cdn.blizzard.com")))
        assertFalse(WowClientDownloader.blizzardHostsOnly(listOf("level3.blizzard.com", "evil.example.com")))
        assertFalse(WowClientDownloader.blizzardHostsOnly(listOf("blizzard.com.evil.net")))
        assertFalse(WowClientDownloader.blizzardHostsOnly(emptyList()))
    }
}
