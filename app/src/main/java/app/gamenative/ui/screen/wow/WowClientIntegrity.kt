package app.gamenative.ui.screen.wow

import android.content.Context
import java.io.File
import java.security.MessageDigest
import java.util.Locale

/**
 * Review 2026-11-13 (security): the game program runs from shared storage (/sdcard/World of Warcraft), where any app with All files
 * access can write. Before a launch the program (`_forever_/Wow-ARM64.exe`) and the DLLs next to it (`_forever_/<name>.dll`, the
 * ones Windows loads from the program's own folder first) are checked against the MD5 content keys of Blizzard's install manifest.
 *
 * Where the keys come from, most trusted first:
 *  - the app's private storage: the client file keys the downloader read from Blizzard's install manifest when it installed or
 *    updated the game ([remember]); kept per build key, so a normal launch needs no network;
 *  - otherwise, once per build, Blizzard's own CDN ([WowClientDownloader.officialClientKeys]: the build config is checked against its
 *    MD5 name and the manifest against the config; the CDN hosts written in .build.info are not used for this);
 *  - nothing reliable (offline, no build key): the check is skipped rather than keeping the player out of the game.
 *
 * Cost: a file that passed is remembered with its size, modification and change times and inode (all from stat, no reading), so
 * an unchanged program is not hashed again; only a new or changed file is.
 */
internal object WowClientIntegrity {
    const val PROGRAM = "Wow-ARM64.exe"
    private const val KEYS_DIR = "wow-client-keys"
    private const val VERIFIED_FILE = "verified.txt"

    @Volatile private var directory: File? = null

    fun init(context: Context) {
        directory = File(context.filesDir, KEYS_DIR)
    }

    /** Tests: the private folder the keys and the verified-file list live in. */
    internal fun useDirectory(dir: File?) {
        directory = dir
    }

    sealed class Result {
        object Ok : Result()
        data class Skipped(val reason: String) : Result()
        data class Mismatch(val files: List<String>) : Result()
    }

    /** A launch stopped because [files] differ from Blizzard's original (the launcher offers the repair). */
    class MismatchException(val files: List<String>) : IllegalStateException("Game files changed: ${files.joinToString(", ")}")

    /** At most this long on a launch (once per build the app has no keys for); a slower or offline network skips the check. */
    private const val FETCH_TIMEOUT_MS = 6_000L

    /**
     * The keys of [buildKey] from Blizzard's own CDN, bounded by [FETCH_TIMEOUT_MS]; null when offline, slow or refused. The request
     * thread is left to end by itself after a timeout.
     */
    fun fetchOfficialKeys(context: Context, gameRoot: File, buildKey: String): List<Pair<String, String>>? {
        val connectivity = context.getSystemService(android.net.ConnectivityManager::class.java)
        if (connectivity?.activeNetwork == null) return null
        val task = java.util.concurrent.FutureTask {
            WowClientDownloader.officialClientKeys(buildKey, runCatching { WowClientDownloader.region(gameRoot) }.getOrNull())
        }
        Thread(task, "WowClientKeys").apply { isDaemon = true; start() }
        return try {
            task.get(FETCH_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            null
        } catch (e: Exception) {
            null
        }
    }

    /** The files this check covers: the program and the DLLs in its own folder (not Utils/ and other sub-folders). */
    internal fun checked(entries: List<Pair<String, String>>): List<Pair<String, String>> =
        entries.filter { (path, key) ->
            WowClientDownloader.isKey(key) && '/' !in path && '\\' !in path && path != "." && path != ".." &&
                (path.equals(PROGRAM, ignoreCase = true) || path.lowercase(Locale.ROOT).endsWith(".dll"))
        }.map { (path, key) -> path to key.lowercase(Locale.ROOT) }

    /** The downloader read [entries] (client file path to content key) for [buildKey] from Blizzard's install manifest. */
    fun remember(buildKey: String, entries: List<Pair<String, String>>) {
        val dir = directory ?: return
        if (!WowClientDownloader.isKey(buildKey)) return
        val kept = checked(entries)
        if (kept.none { it.first.equals(PROGRAM, ignoreCase = true) }) return
        runCatching {
            dir.mkdirs()
            writeAtomically(File(dir, "${buildKey.lowercase(Locale.ROOT)}.keys"), kept.joinToString("") { "${it.first}\t${it.second}\n" })
        }
    }

    internal fun load(buildKey: String): List<Pair<String, String>>? {
        val dir = directory ?: return null
        val file = File(dir, "${buildKey.lowercase(Locale.ROOT)}.keys")
        val entries = runCatching { file.readLines() }.getOrNull()
            ?.mapNotNull { line -> line.split('\t').takeIf { it.size == 2 }?.let { it[0] to it[1] } }
            ?.let(::checked) ?: return null
        return entries.takeIf { list -> list.any { it.first.equals(PROGRAM, ignoreCase = true) } }
    }

    /**
     * Checks the client files of the build .build.info names. [fetchKeys] supplies the keys of a build the app has none for (null:
     * not available now). Call on IO.
     */
    fun verify(gameRoot: File, fetchKeys: (String) -> List<Pair<String, String>>?): Result {
        val dir = directory ?: return Result.Skipped("no private storage")
        val buildKey = runCatching { WowClientDownloader.activeBuild(File(gameRoot, ".build.info"))["Build Key"] }.getOrNull()
            ?.trim()?.lowercase(Locale.ROOT)?.takeIf { WowClientDownloader.isKey(it) }
            ?: return Result.Skipped("no build key")
        val keys = load(buildKey) ?: runCatching { fetchKeys(buildKey) }.getOrNull()?.let { fetched ->
            remember(buildKey, fetched)
            checked(fetched).takeIf { list -> list.any { it.first.equals(PROGRAM, ignoreCase = true) } }
        } ?: return Result.Skipped("no content keys for $buildKey")

        val clientDir = File(gameRoot, WowClientDownloader.FLAVOR_DIR)
        val verifiedFile = File(dir, VERIFIED_FILE)
        val previously = runCatching { verifiedFile.readLines().toHashSet() }.getOrDefault(HashSet())
        val passed = ArrayList<String>()
        val bad = ArrayList<String>()
        for ((path, key) in keys) {
            val file = File(clientDir, path)
            val isProgram = path.equals(PROGRAM, ignoreCase = true)
            if (!file.isFile) {
                // A missing program cannot start (and a download replaces it); a DLL the build lists but the folder lacks is not
                // something another app planted, and the game reports it itself.
                if (isProgram) bad += path
                continue
            }
            val line = "${file.absolutePath}\t$key\t${stamp(file)}"
            if (line in previously) {
                passed += line
                continue
            }
            if (md5(file) == key) passed += line else bad += path
        }
        runCatching {
            dir.mkdirs()
            writeAtomically(verifiedFile, passed.joinToString("") { "$it\n" })
        }
        return if (bad.isEmpty()) Result.Ok else Result.Mismatch(bad)
    }

    /** What changes when a file is replaced: size, modification time, and (where stat is available) inode and change time. */
    internal fun stamp(file: File): String {
        val stat = runCatching { android.system.Os.stat(file.absolutePath) }.getOrNull()
        val extra = if (stat != null) "${stat.st_ino}:${stat.st_ctime}:${stat.st_mtime}" else "-"
        return "${file.length()}:${file.lastModified()}:$extra"
    }

    internal fun md5(file: File): String? = runCatching {
        val digest = MessageDigest.getInstance("MD5")
        file.inputStream().use { input ->
            val buffer = ByteArray(1024 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }.getOrNull()

    private fun writeAtomically(dest: File, text: String) {
        val temp = File(dest.parentFile, "${dest.name}.tmp")
        temp.writeText(text)
        if (!temp.renameTo(dest)) {
            dest.delete()
            check(temp.renameTo(dest)) { "Cannot write ${dest.name}" }
        }
    }
}
