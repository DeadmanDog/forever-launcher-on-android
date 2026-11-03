package app.gamenative.wow.addons

import app.gamenative.wow.addons.provider.fetchText
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Codex OPT13-04: closing the addon page cancels its coroutines. A request whose body is still arriving, and a download, must
 * stop then, and a cancelled download must not install anything. The server sends 1 KiB every 100 ms, so a body that kept being
 * read after the cancel would take tens of seconds.
 */
class AddonCancellationTest {
    private lateinit var server: MockWebServer
    private lateinit var root: File

    @Before fun setUp() {
        server = MockWebServer().apply { start() }
        root = Files.createTempDirectory("addon-cancel").toFile()
    }

    @After fun tearDown() {
        server.close()
        root.deleteRecursively()
    }

    private fun slow(bytes: ByteArray) = MockResponse.Builder()
        .body(Buffer().write(bytes))
        .throttleBody(1024, 100, TimeUnit.MILLISECONDS)
        .build()

    @Test fun cancellingASearchRequestStopsTheBodyRead() = runBlocking {
        server.enqueue(slow(ByteArray(256 * 1024) { 'x'.code.toByte() }))
        val http = OkHttpClient()
        var finished = false
        val job = launch(Dispatchers.IO) { http.fetchText(server.url("/filelist.json").toString()); finished = true }
        delay(400)
        val start = System.nanoTime()
        job.cancelAndJoin()
        val ms = (System.nanoTime() - start) / 1_000_000
        assertTrue("cancel took $ms ms: the body was still being read", ms < 2_000)
        assertFalse(finished)
        delay(200)
        assertEquals(0, http.dispatcher.runningCallsCount())
    }

    @Test fun aCancelledDownloadInstallsNothing() = runBlocking {
        // A 200 KiB data file keeps the throttled transfer going long enough to cancel it mid-way.
        val big = TestZips.bytes(mapOf("Probe/Probe.toc" to "## Interface: 16001\n## Title: Probe\n".toByteArray(),
            "Probe/data.bin" to ByteArray(200 * 1024).also { java.util.Random(1).nextBytes(it) }))
        server.enqueue(slow(big))
        val addons = File(root, "Interface/AddOns").apply { mkdirs() }
        val manager = AddonManager(addons, AddonDb.forAddonsDir(addons), emptyList(), OkHttpClient())
        val job = launch(Dispatchers.IO) { manager.installFromUrl(server.url("/Probe.zip").toString()) }
        delay(400)
        val start = System.nanoTime()
        job.cancelAndJoin()
        val ms = (System.nanoTime() - start) / 1_000_000
        assertTrue("cancel took $ms ms", ms < 2_000)
        delay(300)
        assertFalse("a cancelled download installed the addon", File(addons, "Probe").exists())
        assertTrue(AddonDb.forAddonsDir(addons).load().isEmpty())
    }
}
