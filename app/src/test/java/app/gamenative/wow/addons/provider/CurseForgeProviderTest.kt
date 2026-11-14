package app.gamenative.wow.addons.provider

import app.gamenative.wow.addons.AddonCategory
import app.gamenative.wow.addons.AddonFlavor
import app.gamenative.wow.addons.provider.Fixtures.base
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 1.3.4: CurseForge as the browse source, with the player's own API key. */
class CurseForgeProviderTest {
    private val server = MockWebServer()
    private val seen = mutableListOf<Pair<String, String?>>()
    private var key = "player-key"

    private fun start(): CurseForgeProvider {
        server.dispatcher = Fixtures.dispatcher { req ->
            seen += (req.url.encodedPath + (req.url.encodedQuery?.let { "?$it" } ?: "")) to req.headers["x-api-key"]
            when (req.url.encodedPath) {
                "/mods/search" -> Fixtures.json(Fixtures.load("curseforge-search.json", server.base()))
                "/mods" -> Fixtures.json(Fixtures.load("curseforge-search.json", server.base()))
                "/mods/1003" -> Fixtures.json("""{"data":{"id":1003,"name":"IndexOnly","latestFiles":[],
                    "latestFilesIndexes":[{"gameVersion":"1.60.1","fileId":9002,"filename":"IndexOnly-2.0-forever.zip","releaseType":1}]}}""")
                "/mods/1003/files/9002" -> Fixtures.json("""{"data":{"id":9002,"displayName":"2.0-forever","fileName":"IndexOnly-2.0-forever.zip",
                    "releaseType":1,"downloadUrl":"${server.base()}/files/9002/IndexOnly-2.0-forever.zip","gameVersions":["1.60.1"]}}""")
                "/mods/1001/description" -> Fixtures.json("""{"data":"<p>Shows quests</p><ul><li>on the map</li></ul>"}""")
                else -> null
            }
        }
        server.start()
        return CurseForgeProvider(OkHttpClient(), apiBase = server.base(), apiKey = { key })
    }

    @After fun tearDown() { server.close() }

    @Test fun withoutAKeyItIsOffAndAsksNothing() = runBlocking {
        val provider = start(); key = ""
        assertFalse(provider.enabled)
        assertTrue(provider.search("questie", 0).isEmpty()); assertTrue(provider.popular(0).isEmpty())
        assertNull(provider.get("1001")); assertTrue(provider.getMany(listOf("1001")).isEmpty())
        assertTrue(seen.isEmpty())
    }

    @Test fun searchSendsTheKeyAndMapsForeverAndClassicFiles() = runBlocking {
        val provider = start()
        val results = provider.search("questie", 0)
        val (path, sent) = seen.single()
        assertEquals("player-key", sent)
        assertTrue(path, path.startsWith("/mods/search?") && "gameId=1" in path && "classId=1" in path && "searchFilter=questie" in path && "index=0" in path)
        assertEquals(listOf("Questie", "NoShare", "IndexOnly"), results.map { it.name }) // the retail-only project is not offered

        val questie = results.first()
        assertEquals("curseforge", questie.provider); assertEquals("1001", questie.projectId)
        assertEquals("Aero", questie.author); assertEquals(5_000_000L, questie.downloads)
        assertEquals(setOf(AddonCategory.QUESTS), questie.categories)
        assertEquals("https://www.curseforge.com/wow/addons/questie", questie.pageUrl())
        // The release wins over the newer alpha; retail files are ignored; only the required dependency is kept.
        val forever = questie.latest.getValue(AddonFlavor.FOREVER)
        assertEquals("7001", forever.fileId); assertEquals("v11.0-forever", forever.version); assertTrue(forever.installable)
        assertEquals(listOf("2002"), forever.dependencies.map { it.projectId })
        assertEquals("6990", questie.latest.getValue(AddonFlavor.CLASSIC).fileId)
    }

    @Test fun aFileTheAuthorKeepsOffThirdPartyAppsIsListedButNotInstallable() = runBlocking {
        val noShare = start().popular(0).single { it.name == "NoShare" }
        assertFalse(noShare.latest.getValue(AddonFlavor.FOREVER).installable)
        assertEquals("https://www.curseforge.com/wow/addons/noshare", noShare.pageUrl())
    }

    @Test fun aForeverFileKnownOnlyFromTheIndexGetsItsLinkOnGet() = runBlocking {
        val provider = start()
        val listed = provider.popular(0).single { it.name == "IndexOnly" }
        assertEquals("9002", listed.latest.getValue(AddonFlavor.FOREVER).fileId)
        assertFalse(listed.latest.getValue(AddonFlavor.FOREVER).installable)
        val full = provider.get("1003")!!.latest.getValue(AddonFlavor.FOREVER)
        assertTrue(full.installable); assertTrue(full.downloadUrl.endsWith("/files/9002/IndexOnly-2.0-forever.zip"))
        assertNull(provider.get("not-a-number"))
    }

    @Test fun getManyPostsTheIdsAndDescriptionIsPlainText() = runBlocking {
        val provider = start()
        val many = provider.getMany(listOf("1001", "1002", "x", "1003"))
        assertEquals(3, many.size)
        assertTrue(seen.any { it.first == "/mods" })
        // 2026-11-12 review: getMany (installed-addon updates) also resolves a Forever file known only from the index.
        assertEquals("/mods/1003/files/9002", seen.last().first)
        assertTrue(many.single { it.projectId == "1003" }.latest.getValue(AddonFlavor.FOREVER).installable)
        assertEquals("Shows quests\n• on the map", provider.description("1001"))
    }

    @Test fun gameVersionsDecideTheFlavor() {
        assertEquals(AddonFlavor.FOREVER, CurseForgeProvider.flavorOf("1.60.1"))
        assertEquals(AddonFlavor.FOREVER, CurseForgeProvider.flavorOf("1.61.0"))
        assertEquals(AddonFlavor.CLASSIC, CurseForgeProvider.flavorOf("1.15.7"))
        for (other in listOf("11.2.5", "4.4.2", "", "Forever", "1")) assertNull(other, CurseForgeProvider.flavorOf(other))
    }
}
