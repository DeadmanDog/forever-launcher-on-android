package app.gamenative.wow.addons.provider

import app.gamenative.wow.addons.provider.Fixtures.base
import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 2026-11-12 review: when the catalog and its fallback both fail, each lookup must not refetch everything again. */
class GitHubCatalogFailureTest {
    private val server = MockWebServer().apply {
        dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = MockResponse.Builder().code(404).body("{}").build()
        }
        start()
    }

    @After fun tearDown() { server.close() }

    @Test fun aFailedCatalogIsRetriedOnlyAfterAWhile() = runBlocking {
        var now = 1_000_000L
        val provider = GitHubProvider(OkHttpClient(), apiBase = server.base(), catalogUrl = "${server.base()}/catalog.json",
            clock = { now }, fallbackRepos = listOf("a/one", "b/two"))
        assertTrue(provider.search("x", 0).isEmpty())
        val first = server.requestCount
        assertEquals(3, first)
        provider.search("y", 0)
        provider.popular(0)
        assertEquals("no new requests within the retry window", first, server.requestCount)
        now += 6 * 60 * 1000L
        provider.search("z", 0)
        assertEquals(first * 2, server.requestCount)
    }
}
