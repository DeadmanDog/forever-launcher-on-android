package app.gamenative.wow.addons.provider

import android.content.Context
import app.gamenative.SecretStore
import app.gamenative.utils.Net
import app.gamenative.wow.addons.AddonCategory
import app.gamenative.wow.addons.AddonFlavor
import app.gamenative.wow.addons.ProviderIds
import app.gamenative.wow.addons.SourceRef
import kotlinx.serialization.Serializable
import okhttp3.Headers
import okhttp3.OkHttpClient

/**
 * The player's own CurseForge API key (console.curseforge.com), entered on the addon page and kept only on this phone. It is
 * never bundled: the repository is public and a shipped key could be taken and revoked.
 */
object CurseForgeKey {
    private const val PREFS = "wow_addons"
    /** Where older versions kept the key unsealed; read once for the migration, then removed (see [SecretStore]). */
    private const val KEY = "curseforge_api_key"
    private const val KEY_SEALED = "curseforge_api_key_sealed"
    @Volatile var value: String = ""
        private set

    /** Reads the key (sealed with the Android KeyStore; an unsealed key of an older version is sealed on this first read). */
    fun load(context: Context): String {
        value = SecretStore.get(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE), KEY_SEALED, KEY).orEmpty()
        return value
    }

    fun save(context: Context, key: String) {
        val clean = key.trim()
        check(SecretStore.put(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE), KEY_SEALED, KEY, clean))
        value = clean
    }
}

/**
 * CurseForge (official Core API v1, needs the player's API key in `x-api-key`). Without a key the provider is disabled and
 * makes no request.
 *
 * A project's files carry game version strings: 1.60.x is WoW Forever, another 1.x is Classic, anything else is not offered.
 * The newest file per flavor comes from `latestFiles`; a flavor that only appears in `latestFilesIndexes` is listed without a
 * download link and resolved by [get] (one `/files/{id}` request) before an install. A file whose author disabled third-party
 * distribution has no `downloadUrl`: it is listed as not installable ("open on the website").
 */
class CurseForgeProvider(
    private val http: OkHttpClient = Net.http,
    private val apiBase: String = "https://api.curseforge.com/v1",
    private val apiKey: () -> String = { CurseForgeKey.value },
) : AddonProvider {
    override val id: String = ProviderIds.CURSEFORGE
    override val enabled: Boolean get() = apiKey().isNotBlank()

    private val base get() = apiBase.trimEnd('/')
    private fun headers() = Headers.headersOf("x-api-key", apiKey(), "Accept", "application/json")

    /** How Browse lists are ordered (CurseForge sortField). */
    enum class Sort(val field: Int, val descending: Boolean) { POPULAR(2, true), UPDATED(3, true), DOWNLOADS(6, true), NAME(4, false) }

    @Volatile var sort: Sort = Sort.POPULAR
    /** The category Browse and search are narrowed to; null = all. */
    @Volatile private var categoryIds: Map<AddonCategory, List<Long>>? = null

    // Search is never narrowed here: the manager filters search results by category itself, and relinking hand-copied addons
    // must find them whatever category Browse shows.
    override suspend fun search(query: String, page: Int): List<RemoteAddon> = list(page, query.trim(), null)

    override suspend fun popular(page: Int): List<RemoteAddon> = list(page, "", null)

    override suspend fun popular(page: Int, category: AddonCategory?): List<RemoteAddon> = list(page, "", category)

    /** CurseForge's own WoW addon categories, grouped under ours by name (fetched once). */
    private suspend fun categoryIds(): Map<AddonCategory, List<Long>> {
        categoryIds?.let { return it }
        val body = http.fetchText("$base/categories?gameId=$WOW_GAME_ID&classId=$ADDONS_CLASS_ID", headers()) ?: return emptyMap()
        return ProviderJson.decodeFromString<CategoryList>(body).data
            .mapNotNull { c -> AddonCategory.fromName(c.name)?.let { it to c.id } }
            .groupBy({ it.first }, { it.second }).also { categoryIds = it }
    }

    private suspend fun list(page: Int, query: String, category: AddonCategory?): List<RemoteAddon> {
        if (!enabled) return emptyList()
        // The API takes at most 10 category ids; a category of ours with no CurseForge match is filtered by the caller instead.
        val ids = if (category == null) emptyList() else runCatching { categoryIds() }.getOrDefault(emptyMap())[category].orEmpty().take(10)
        val url = okhttp3.HttpUrl.Builder().scheme("https").host("query.invalid")
            .addQueryParameter("gameId", WOW_GAME_ID.toString())
            .addQueryParameter("classId", ADDONS_CLASS_ID.toString())
            .addQueryParameter("sortField", sort.field.toString()).addQueryParameter("sortOrder", if (sort.descending) "desc" else "asc")
            .apply { if (ids.isNotEmpty()) addQueryParameter("categoryIds", ids.joinToString(",", "[", "]")) }
            .addQueryParameter("pageSize", PAGE_SIZE.toString())
            .addQueryParameter("index", (page * PAGE_SIZE).toString())
            .apply { if (query.isNotEmpty()) addQueryParameter("searchFilter", query) }
            .build()
        val body = http.fetchText("$base/mods/search?${url.encodedQuery}", headers()) ?: return emptyList()
        return ProviderJson.decodeFromString<ModList>(body).data.mapNotNull(::toRemote)
    }

    override suspend fun get(projectId: String): RemoteAddon? {
        if (!enabled || projectId.toLongOrNull() == null) return null
        val body = http.fetchText("$base/mods/$projectId", headers()) ?: return null
        val mod = ProviderJson.decodeFromString<ModOne>(body).data ?: return null
        return resolveIndexOnly(mod, toRemote(mod) ?: return null)
    }

    /** A flavor known only from the index has no link yet: fetch that one file (one request per such flavor). */
    private suspend fun resolveIndexOnly(mod: Mod, listed: RemoteAddon): RemoteAddon = listed.copy(latest = listed.latest.mapValues { (_, file) ->
        if (file.installable || mod.latestFiles.any { it.id.toString() == file.fileId }) file
        else http.fetchText("$base/mods/${mod.id}/files/${file.fileId}", headers())
            ?.let { ProviderJson.decodeFromString<FileOne>(it).data }?.let(::toFile) ?: file
    })

    override suspend fun getMany(ids: List<String>): List<RemoteAddon> {
        val wanted = ids.mapNotNull { it.toLongOrNull() }.distinct()
        if (!enabled || wanted.isEmpty()) return emptyList()
        val body = http.fetchText("$base/mods", headers(), postJson = "{\"modIds\":[${wanted.joinToString(",")}]}") ?: return emptyList()
        // Installed addons: their update state needs the real file of an index-only flavor (else the Classic file looked newest).
        return ProviderJson.decodeFromString<ModList>(body).data.mapNotNull { mod -> toRemote(mod)?.let { resolveIndexOnly(mod, it) } }
    }

    override suspend fun description(projectId: String): String? {
        if (!enabled || projectId.toLongOrNull() == null) return null
        return http.fetchText("$base/mods/$projectId/description", headers())
            ?.let { ProviderJson.decodeFromString<Description>(it).data }
            ?.let(RichText::toPlain)?.takeIf { it.isNotBlank() }
    }

    private fun toRemote(mod: Mod): RemoteAddon? {
        // Per flavor: a release (1) before a beta (2) or alpha (3), then the newest file. A file listed in full wins over the
        // same file known only from the index.
        class Candidate(val flavor: AddonFlavor, val releaseType: Int, val fileId: Long, val full: Boolean, val file: () -> RemoteFile)
        val candidates = mod.latestFiles.flatMap { f ->
            f.gameVersions.mapNotNull(::flavorOf).toSet().map { Candidate(it, f.releaseType, f.id, true) { toFile(f) } }
        } + mod.latestFilesIndexes.mapNotNull { i ->
            flavorOf(i.gameVersion)?.let { Candidate(it, i.releaseType, i.fileId, false) {
                RemoteFile(i.fileId.toString(), i.filename.removeSuffix(".zip"), "", emptyList()) } }
        }
        val latest = candidates.groupBy { it.flavor }.mapValues { (_, list) ->
            list.sortedWith(compareBy<Candidate> { it.releaseType }.thenByDescending { it.fileId }.thenByDescending { it.full }).first().file()
        }
        if (latest.isEmpty()) return null
        return RemoteAddon(
            provider = id,
            projectId = mod.id.toString(),
            name = mod.name,
            summary = mod.summary,
            author = mod.authors.firstOrNull()?.name.orEmpty(),
            iconUrl = mod.logo?.thumbnailUrl ?: mod.logo?.url,
            downloads = mod.downloadCount.toLong(),
            latest = latest,
            websiteUrl = mod.links?.websiteUrl,
            categories = mod.categories.mapNotNull { AddonCategory.fromName(it.name) }.toSet(),
            screenshots = mod.screenshots.mapNotNull { it.url },
            sourceUrl = mod.links?.sourceUrl?.takeIf { it.startsWith("https://") },
        )
    }

    private fun toFile(file: ModFile) = RemoteFile(
        fileId = file.id.toString(),
        version = file.displayName.ifBlank { file.fileName.removeSuffix(".zip") },
        downloadUrl = file.downloadUrl.orEmpty(),
        dependencies = file.dependencies.filter { it.relationType == REQUIRED_DEPENDENCY }
            .map { SourceRef(ProviderIds.CURSEFORGE, it.modId.toString()) },
    )

    @Serializable private data class ModList(val data: List<Mod> = emptyList())
    @Serializable private data class ModOne(val data: Mod? = null)
    @Serializable private data class FileOne(val data: ModFile? = null)
    @Serializable private data class Description(val data: String? = null)
    @Serializable private data class Mod(
        val id: Long, val name: String = "", val summary: String = "", val downloadCount: Double = 0.0,
        val links: Links? = null, val logo: Asset? = null, val authors: List<Author> = emptyList(),
        val categories: List<Category> = emptyList(), val screenshots: List<Asset> = emptyList(),
        val latestFiles: List<ModFile> = emptyList(), val latestFilesIndexes: List<FileIndex> = emptyList(),
    )
    @Serializable private data class Links(val websiteUrl: String? = null, val sourceUrl: String? = null)
    @Serializable private data class Asset(val url: String? = null, val thumbnailUrl: String? = null)
    @Serializable private data class Author(val name: String = "")
    @Serializable private data class Category(val id: Long = 0, val name: String = "")
    @Serializable private data class CategoryList(val data: List<Category> = emptyList())
    @Serializable private data class ModFile(
        val id: Long, val displayName: String = "", val fileName: String = "", val releaseType: Int = 1,
        val downloadUrl: String? = null, val gameVersions: List<String> = emptyList(),
        val dependencies: List<Dependency> = emptyList(),
    )
    @Serializable private data class Dependency(val modId: Long, val relationType: Int = 0)
    @Serializable private data class FileIndex(val gameVersion: String = "", val fileId: Long, val filename: String = "", val releaseType: Int = 1)

    companion object {
        private const val WOW_GAME_ID = 1
        private const val ADDONS_CLASS_ID = 1
        private const val PAGE_SIZE = 50
        private const val REQUIRED_DEPENDENCY = 3

        /** "1.60.1" is WoW Forever, another "1.x" Classic; retail and other versions are not offered. */
        internal fun flavorOf(gameVersion: String): AddonFlavor? {
            val parts = gameVersion.trim().split('.').map { it.toIntOrNull() }
            val major = parts.getOrNull(0) ?: return null
            val minor = parts.getOrNull(1) ?: return null
            return when {
                major == 1 && minor >= 60 -> AddonFlavor.FOREVER
                major == 1 -> AddonFlavor.CLASSIC
                else -> null
            }
        }
    }
}
