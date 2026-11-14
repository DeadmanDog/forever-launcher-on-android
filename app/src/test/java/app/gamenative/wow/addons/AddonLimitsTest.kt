package app.gamenative.wow.addons

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Addon downloads and archives have size and entry limits, with a reason in both languages. */
class AddonLimitsTest {
    private lateinit var root: File
    private lateinit var addons: File

    @Before fun setUp() {
        root = Files.createTempDirectory("limits").toFile()
        addons = File(root, "Interface/AddOns").apply { mkdirs() }
    }

    @After fun tearDown() { root.deleteRecursively() }

    private fun zip(bytes: ByteArray) = TestZips.write(File(root, "in.zip"), bytes)

    @Test fun theLimitsAreTheAgreedOnes() {
        assertEquals(200L * 1024 * 1024, AddonArchive.MAX_DOWNLOAD_BYTES)
        assertEquals(1024L * 1024 * 1024, AddonArchive.MAX_EXTRACTED_BYTES)
        assertEquals(20_000, AddonArchive.MAX_ENTRIES)
    }

    @Test fun copyLimitedStopsPastTheLimit() {
        val out = ByteArrayOutputStream()
        assertEquals(10L, AddonArchive.copyLimited(ByteArrayInputStream(ByteArray(10)), out, 10, AddonArchive::downloadTooLarge))
        val error = assertThrows(AddonLimitException::class.java) {
            AddonArchive.copyLimited(ByteArrayInputStream(ByteArray(11)), ByteArrayOutputStream(), 10, AddonArchive::downloadTooLarge)
        }
        assertTrue(error.en.contains("200 MB") && error.hu.contains("200 MB"))
    }

    @Test fun anArchiveThatUnpacksTooLargeIsRejectedAndLeavesNothing() {
        val file = zip(TestZips.bytes(mapOf("Big/Big.toc" to "## Title: Big\n".toByteArray(), "Big/data.bin" to ByteArray(5000))))
        val error = assertThrows(AddonLimitException::class.java) { AddonArchive.install(file, addons, 4000, 100) }
        assertTrue(error.en.contains("unpacks to more than"))
        assertTrue(addons.listFiles()!!.isEmpty())
        assertEquals(listOf("Big"), AddonArchive.install(file, addons, 10_000, 100))
    }

    @Test fun anArchiveWithTooManyEntriesIsRejected() {
        val entries = (1..30).associate { "Many/f$it.lua" to "x".toByteArray() } + ("Many/Many.toc" to "## Title: Many\n".toByteArray())
        val file = zip(TestZips.bytes(entries))
        val error = assertThrows(AddonLimitException::class.java) { AddonArchive.install(file, addons, 1_000_000, 20) }
        assertTrue(error.hu.contains("fájl"))
        assertTrue(addons.listFiles()!!.isEmpty())
        assertEquals(listOf("Many"), AddonArchive.install(file, addons, 1_000_000, 31))
    }
}
