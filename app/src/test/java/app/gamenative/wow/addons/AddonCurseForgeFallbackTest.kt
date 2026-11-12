package app.gamenative.wow.addons

import app.gamenative.wow.addons.provider.AddonProvider
import app.gamenative.wow.addons.provider.Fixtures
import app.gamenative.wow.addons.provider.Fixtures.base
import app.gamenative.wow.addons.provider.RemoteAddon
import app.gamenative.wow.addons.provider.RemoteFile
import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 1.3.5: CurseForge files whose author keeps them off third-party apps (DBM, Details, Auctionator) install and update from the
 * author's own release elsewhere; addons already on disk get a source so they show up and update.
 */
class AddonCurseForgeFallbackTest {
    private class Fake(override val id: String, var addons: List<RemoteAddon> = emptyList()) : AddonProvider {
        override val enabled = true
        val searches = mutableListOf<String>()
        override suspend fun search(query: String, page: Int) = addons.filter { it.name.contains(query, true) }.also { searches += query }
        override suspend fun popular(page: Int) = addons
        override suspend fun get(projectId: String) = addons.firstOrNull { it.projectId == projectId }
        override suspend fun getMany(ids: List<String>) = addons.filter { it.projectId in ids }
    }

    private val server = MockWebServer()
    private val zips = mutableMapOf<String, ByteArray>()
    private lateinit var root: File
    private lateinit var addonsDir: File
    private lateinit var db: AddonDb
    private val curse = Fake(ProviderIds.CURSEFORGE)
    private val github = Fake(ProviderIds.GITHUB)
    private val wowi = Fake(ProviderIds.WOWINTERFACE)

    @Before fun setUp() {
        server.dispatcher = Fixtures.dispatcher { req -> zips[req.url.encodedPath]?.let { MockResponse.Builder().body(Buffer().write(it)).build() } }
        server.start()
        root = Files.createTempDirectory("fallback").toFile()
        addonsDir = File(root, "_forever_/Interface/AddOns").apply { mkdirs() }
        db = AddonDb.forAddonsDir(addonsDir)
    }

    @After fun tearDown() { server.close(); root.deleteRecursively() }

    private fun manager() = AddonManager(addonsDir, db, listOf(curse, wowi, github), OkHttpClient(), browseWith = setOf(ProviderIds.CURSEFORGE))

    private fun serve(folder: String): String {
        zips["/zips/$folder.zip"] = TestZips.addon(folder, "## Interface: 16001\n## Title: $folder\n")
        return "${server.base()}/zips/$folder.zip"
    }

    private fun blocked(id: String, name: String, sourceUrl: String? = null) = RemoteAddon(ProviderIds.CURSEFORGE, id, name, "", "a", null, 1,
        mapOf(AddonFlavor.FOREVER to RemoteFile("cf$id", "v1", "", emptyList())), "https://www.curseforge.com/wow/addons/$id", sourceUrl = sourceUrl)

    private fun open(provider: String, id: String, name: String, url: String) = RemoteAddon(provider, id, name, "", "a", null, 1,
        mapOf(AddonFlavor.FOREVER to RemoteFile("f-$id", "v2", url, emptyList())), null)

    @Test fun aBlockedFileInstallsFromTheGitHubRepoTheProjectLists() = runBlocking {
        github.addons = listOf(open(ProviderIds.GITHUB, "DeadlyBossMods/DeadlyBossMods", "DeadlyBossMods", serve("DBM-Core")))
        val record = manager().install(blocked("3358", "Deadly Boss Mods (DBM)", "https://github.com/DeadlyBossMods/DeadlyBossMods"))
        assertEquals(SourceRef(ProviderIds.GITHUB, "DeadlyBossMods/DeadlyBossMods", "f-DeadlyBossMods/DeadlyBossMods"), record.source)
        assertTrue(File(addonsDir, "DBM-Core").isDirectory)
    }

    @Test fun withoutASourceRepoTheSameNameOnWowInterfaceIsUsed() = runBlocking {
        wowi.addons = listOf(open(ProviderIds.WOWINTERFACE, "5700", "Auctionator", serve("Auctionator")),
            open(ProviderIds.WOWINTERFACE, "5701", "Auctionator Plus", serve("AuctionatorPlus")))
        val record = manager().install(blocked("1001", "Auctionator"))
        assertEquals(ProviderIds.WOWINTERFACE, record.source?.provider); assertEquals("5700", record.source?.projectId)
    }

    // 1.3.6: with no other release the CurseForge download page is handed to the in-app browser; its file installs with the
    // CurseForge file id, so the next version is an update again.
    @Test fun withNoOtherReleaseTheWebsiteDownloadPageIsHandedOverAndItsFileInstalls() = runBlocking {
        val error = assertThrows(NeedsWebsiteDownload::class.java) { runBlocking { manager().install(blocked("61284", "Details! Damage Meter")) } }
        assertEquals("https://www.curseforge.com/wow/addons/61284/download/cf61284", error.pageUrl)
        val record = manager().installFromBrowser(error.remote, error.file, serve("Details"), mapOf("User-Agent" to "test"))
        assertEquals(SourceRef(ProviderIds.CURSEFORGE, "61284", "cf61284"), record.source)
        assertTrue(File(addonsDir, "Details").isDirectory)
        assertThrows(IllegalArgumentException::class.java) { runBlocking { manager().installFromBrowser(error.remote, error.file, "http://x/a.zip", emptyMap()) } }
        Unit
    }

    @Test fun updateAllNamesWhatItCouldNotUpdate() = runBlocking {
        File(addonsDir, "Details").mkdirs()
        File(addonsDir, "Details/Details.toc").writeText("## Interface: 16001\n## Title: Details\n## Version: v0.9\n## X-Curse-Project-ID: 61284\n")
        curse.addons = listOf(blocked("61284", "Details! Damage Meter", "https://github.com/Tercioo/Details-Damage-Meter"))
        val m = manager()
        assertTrue(m.updateAll().isEmpty())
        val failure = m.lastUpdateFailures.single()
        assertTrue(failure.second is NeedsWebsiteDownload)
        Unit
    }

    @Test fun addonsAlreadyOnDiskGetASourceAndShowTheirUpdate() = runBlocking {
        // A CurseForge project id in the TOC (what the packager writes) ...
        File(addonsDir, "Details").mkdirs()
        File(addonsDir, "Details/Details.toc").writeText("## Interface: 16001\n## Title: Details\n## Version: v0.9\n## X-Curse-Project-ID: 61284\n")
        // ... or, without one, exactly one CurseForge project of that very name.
        File(addonsDir, "Bagnon").mkdirs()
        File(addonsDir, "Bagnon/Bagnon.toc").writeText("## Interface: 16001\n## Title: Bagnon\n## Version: 1.0\n")
        curse.addons = listOf(blocked("61284", "Details! Damage Meter", "https://github.com/Tercioo/Details-Damage-Meter"),
            open(ProviderIds.CURSEFORGE, "1592", "Bagnon", serve("Bagnon")), open(ProviderIds.CURSEFORGE, "1593", "Bagnon Plus", ""))
        val listed = manager().installed().associateBy { it.installed!!.folders.first() }
        assertEquals(SourceRef(ProviderIds.CURSEFORGE, "61284"), listed.getValue("Details").installed!!.source)
        assertEquals(SourceRef(ProviderIds.CURSEFORGE, "1592"), listed.getValue("Bagnon").installed!!.source)
        // Details' CurseForge file is blocked, but the project names its GitHub repo: the update is offered.
        assertTrue(listed.getValue("Details").updateAvailable)
        assertTrue(listed.getValue("Bagnon").updateAvailable)
        // The name search runs once per addon, not on every refresh.
        val before = curse.searches.size; manager().installed(); assertEquals(before, curse.searches.size)
    }
}
