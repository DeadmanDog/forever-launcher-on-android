package app.gamenative.ui.screen.wow

import android.content.Context
import java.io.File

/**
 * How much of the game data is on the phone. The launcher installs only the client (about 250 MB); the game streams the rest
 * (47.1 GB for Windows ARM64 + one language on wowf 1.60.1, like a Battle.net install) from Blizzard's CDN while it runs, into Data/data/data.NNN.
 * Downloaded = the size of those data files (close to the downloaded bytes; the files carry a little bookkeeping of their own),
 * total = the download manifest of the installed build ([WowClientDownloader.gameDataSize]), cached per build and language.
 */
internal object WowGameData {
    private const val PREFS = "wow_forever"
    private val dataFile = Regex("""data\.\d{3}""")

    data class Progress(val downloaded: Long, val total: Long) {
        val fraction: Float get() = if (total <= 0) 0f else (downloaded.toDouble() / total).coerceIn(0.0, 1.0).toFloat()
        val percent: Int get() = (fraction * 100).toInt()
        val complete: Boolean get() = total > 0 && downloaded >= total * 0.995
        /** Too little data to start well: under 5 %, or under 1 GiB while the size is not known yet. */
        val lowData: Boolean get() = if (total > 0) fraction < 0.05f else downloaded < WowCasc.GIB
    }

    fun downloaded(gameRoot: File): Long =
        File(gameRoot, "Data/data").listFiles { file -> file.isFile && dataFile.matches(file.name) }?.sumOf { it.length() } ?: 0L

    private fun cacheKey(gameRoot: File, locale: String): String? =
        WowClientDownloader.readBuildInfo(File(gameRoot, ".build.info"))?.get("Build Key")?.takeIf { it.isNotBlank() }?.let { "gamedata_total2_${it}_$locale" }

    /** The language the next launch plays in: the one chosen in Settings, else Config.wtf's (the launch writes the choice there). */
    internal fun locale(context: Context, gameRoot: File): String =
        WowRuntimeTuning.gameLocale(context) ?: runCatching { WowGameConfig.current(gameRoot).locale }.getOrDefault("enUS")

    /** The cached total for the installed build, or null when it was not computed yet. */
    fun cachedTotal(context: Context, gameRoot: File): Long? {
        val key = cacheKey(gameRoot, locale(context, gameRoot)) ?: return null
        return context.getSharedPreferences(PREFS, 0).getLong(key, -1L).takeIf { it > 0 }
    }

    /** Computes and caches the total when it is missing (network, about 46 MB once per build). Call on IO. */
    fun ensureTotal(context: Context, gameRoot: File): Long? {
        cachedTotal(context, gameRoot)?.let { return it }
        val locale = locale(context, gameRoot)
        val key = cacheKey(gameRoot, locale) ?: return null
        val total = WowClientDownloader.gameDataSize(gameRoot, locale).takeIf { it > 0 } ?: return null
        context.getSharedPreferences(PREFS, 0).edit().putLong(key, total).apply()
        return total
    }

    fun progress(context: Context, gameRoot: File): Progress? = cachedTotal(context, gameRoot)?.let { Progress(downloaded(gameRoot), it) }

    /** Deletes the game's local storage (Data/data: data files, indexes, shmem) before a copy from a PC. Not while the game runs. */
    fun deleteStorage(gameRoot: File) {
        check(!app.gamenative.PluviaApp.keepAlive && !WowDataDownloadState.running) { "Stop the game and the download first." }
        val names = Regex("""data\.\d{3}|[0-9a-f]{10}\.idx(\.tmp)?|shmem(\.tmp)?""")
        File(gameRoot, "Data/data").listFiles { f -> f.isFile && names.matches(f.name) }?.forEach { it.delete() }
    }

    /**
     * When the game marked its storage for repair (Data/data/RepairMarker.psv) and the cause is ours (a data file without its
     * segment headers in the index), puts them back and removes the marker. Call on IO before the game starts; a storage that is
     * fine or that needs another kind of repair is left as it is.
     */
    fun repairIfMarked(context: Context, gameRoot: File): Boolean {
        val dir = File(gameRoot, "Data/data")
        val marker = File(dir, "RepairMarker.psv")
        if (!marker.isFile || !File(dir, "shmem").isFile) return false
        val template = context.assets.open("casc/shmem.template").use { it.readBytes() }
        val storage = WowCasc.Storage.open(dir, template)
        val repaired = storage.repaired
        storage.close()
        if (repaired) marker.delete()
        return repaired
    }

    fun gb(bytes: Long): String = String.format(java.util.Locale.ROOT, "%.1f", bytes / 1073741824.0)
}
