package app.gamenative.wow.addons

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** jaredgei 0489788: installing addons from a .zip picked on the phone, and addon folders named after their .toc. */
class AddonZipImportTest {
    private lateinit var root: File
    private lateinit var addonsDir: File
    private lateinit var db: AddonDb

    @Before fun setUp() {
        root = Files.createTempDirectory("zipimport").toFile()
        addonsDir = File(root, "_forever_/Interface/AddOns").apply { mkdirs() }
        db = AddonDb.forAddonsDir(addonsDir)
    }

    @After fun tearDown() { root.deleteRecursively() }

    private fun manager() = AddonManager(addonsDir, db, emptyList(), OkHttpClient())
    private fun import(bytes: ByteArray) = runBlocking { manager().installFromZip(bytes.inputStream()) }
    private fun visible() = addonsDir.list()!!.filter { !it.startsWith(".") }.sorted()

    @Test fun aPickedZipInstallsAndIsRecordedWithoutASource() {
        val record = import(TestZips.addon("Questie"))
        assertEquals(listOf("Questie"), visible())
        assertEquals(listOf("Questie"), record.folders)
        assertEquals("Questie", record.name)
        assertNull(record.source)
        assertEquals(listOf("Questie"), db.load().flatMap { it.folders })
        // Nothing left over next to the AddOns folder.
        assertTrue(addonsDir.parentFile!!.list()!!.none { it.startsWith(".foreverywhere-import") })
    }

    @Test fun aGitHubStyleFolderIsRenamedToItsToc() {
        import(TestZips.text("Questie-main/Questie.toc" to "## Title: Questie\n", "Questie-main/Libs/x.lua" to "--"))
        assertEquals(listOf("Questie"), visible())
        assertTrue(File(addonsDir, "Questie/Libs/x.lua").isFile)
    }

    @Test fun perFlavourTocsGiveOneName() {
        import(TestZips.text("Foo-1.2/Foo_Mainline.toc" to "## Title: Foo\n", "Foo-1.2/Foo-Classic.toc" to "## Title: Foo\n"))
        assertEquals(listOf("Foo"), visible())
    }

    @Test fun aForeverTocNamesTheFolderToo() {
        import(TestZips.text("Baz-main/Baz_Forever.toc" to "## Title: Baz\n", "Baz-main/Baz-Classic.toc" to "## Title: Baz\n"))
        assertEquals(listOf("Baz"), visible())
    }

    @Test fun aTocAtTheZipRootGetsItsOwnFolder() {
        import(TestZips.text("Bar.toc" to "## Title: Bar\n", "core.lua" to "--", "Libs/lib.lua" to "--", "__MACOSX/._core.lua" to "x"))
        assertEquals(listOf("Bar"), visible())
        assertTrue(File(addonsDir, "Bar/core.lua").isFile)
        assertTrue(File(addonsDir, "Bar/Libs/lib.lua").isFile)
        assertFalse(File(addonsDir, "Bar/__MACOSX").exists())
    }

    @Test fun correctlyNamedAndSeveralAddonsStayAsTheyAre() {
        import(TestZips.text("A/A.toc" to "## Title: A\n", "B/B_Mainline.toc" to "## Title: B\n", "B/B.toc" to "## Title: B\n"))
        assertEquals(listOf("A", "B"), visible())
    }

    @Test fun aZipWithoutAddonsAndAnUnsafeZipAreRefused() {
        assertThrows(java.io.IOException::class.java) { import(TestZips.text("readme.txt" to "hi")) }
        assertThrows(java.io.IOException::class.java) { import(TestZips.text("../evil/evil.toc" to "x")) }
        assertThrows(java.io.IOException::class.java) { import("not a zip at all".toByteArray()) }
        assertEquals(emptyList<String>(), visible())
    }
}
