package app.gamenative.wow.addons

import java.io.File
import java.io.IOException
import java.util.zip.ZipInputStream

/**
 * An addon download or archive over one of the size limits ([AddonArchive.MAX_DOWNLOAD_BYTES], [AddonArchive.MAX_EXTRACTED_BYTES],
 * [AddonArchive.MAX_ENTRIES]); carries the reason in English and Hungarian for the addon page.
 */
class AddonLimitException(val en: String, val hu: String) : IOException(en)

/** Safely installs an addon zip into `Interface/AddOns`. */
object AddonArchive {
    private const val TEMP_PREFIX = ".foreverywhere-extract-"
    /** The largest addon download accepted (the biggest real addons are a few tens of MB). */
    const val MAX_DOWNLOAD_BYTES = 200L * 1024 * 1024
    /** The most an addon archive may unpack to, all files together. */
    const val MAX_EXTRACTED_BYTES = 1024L * 1024 * 1024
    /** The most files and folders an addon archive may hold. */
    const val MAX_ENTRIES = 20_000

    fun downloadTooLarge() = AddonLimitException(
        "the download is larger than ${MAX_DOWNLOAD_BYTES / (1024 * 1024)} MB",
        "a letöltés nagyobb ${MAX_DOWNLOAD_BYTES / (1024 * 1024)} MB-nál")

    fun extractedTooLarge() = AddonLimitException(
        "the archive unpacks to more than ${MAX_EXTRACTED_BYTES / (1024 * 1024)} MB",
        "az archívum kicsomagolva több mint ${MAX_EXTRACTED_BYTES / (1024 * 1024)} MB")

    fun tooManyEntries() = AddonLimitException(
        "the archive holds more than $MAX_ENTRIES files",
        "az archívumban több mint $MAX_ENTRIES fájl van")

    /** Copies [input] to [output], at most [limit] bytes; throws [tooLarge] instead of writing past it. Returns the bytes copied. */
    fun copyLimited(input: java.io.InputStream, output: java.io.OutputStream, limit: Long, tooLarge: () -> IOException): Long {
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val count = input.read(buffer)
            if (count < 0) return total
            total += count
            if (total > limit) throw tooLarge()
            output.write(buffer, 0, count)
        }
    }
    private val driveLetter = Regex("^[A-Za-z]:")

    /**
     * Extracts [zip] into [addonsDir] and returns the top-level addon folder names written, sorted.
     *
     * - The zip is fully extracted into a hidden staging dir inside [addonsDir] first (same
     *   filesystem, so the swap is a rename). A corrupt zip leaves existing folders untouched.
     * - Each existing folder is moved aside, the new one renamed in, then the old one deleted. A
     *   failed rename rolls every folder back.
     * - A single wrapper folder with no `.toc` whose children have TOCs is unwrapped.
     * - Entries that are absolute or contain `..` throw `IOException("unsafe path")`.
     */
    fun install(zip: File, addonsDir: File): List<String> = install(zip, addonsDir, MAX_EXTRACTED_BYTES, MAX_ENTRIES)

    /** [install] with the archive limits given (tests use small ones). */
    internal fun install(zip: File, addonsDir: File, maxExtractedBytes: Long, maxEntries: Int): List<String> {
        addonsDir.mkdirs()
        val staging = File(addonsDir, "$TEMP_PREFIX${System.nanoTime()}")
        if (!staging.mkdirs()) throw IOException("cannot create ${staging.path}")
        try {
            extract(zip, staging, maxExtractedBytes, maxEntries)
            val root = unwrap(staging)
            val folders = addonFolders(root)
            if (folders.isEmpty()) throw IOException("no addon folders in ${zip.name}")
            swapIn(folders, addonsDir)
            return folders.map { it.name }.sorted()
        } finally {
            staging.deleteRecursively()
        }
    }

    private fun extract(zip: File, dest: File, maxExtractedBytes: Long, maxEntries: Int) {
        val destPath = dest.canonicalFile.toPath()
        var entries = 0
        var written = 0L
        ZipInputStream(zip.inputStream().buffered()).use { input ->
            while (true) {
                val entry = input.nextEntry ?: break
                if (++entries > maxEntries) throw tooManyEntries()
                val name = safeName(entry.name)
                val target = File(dest, name)
                if (!target.canonicalFile.toPath().startsWith(destPath)) throw IOException("unsafe path")
                if (entry.isDirectory || entry.name.endsWith("/") || entry.name.endsWith("\\")) {
                    target.mkdirs()
                } else {
                    target.parentFile?.mkdirs()
                    target.outputStream().use { written += copyLimited(input, it, maxExtractedBytes - written, ::extractedTooLarge) }
                }
            }
        }
    }

    /** Normalizes separators and rejects absolute paths and `..` segments. */
    private fun safeName(raw: String): String {
        val name = raw.replace('\\', '/')
        val segments = name.split('/')
        if (name.startsWith("/") || driveLetter.containsMatchIn(name) || segments.any { it == ".." }) {
            throw IOException("unsafe path")
        }
        return segments.filter { it.isNotEmpty() && it != "." }.joinToString("/")
    }

    private fun childDirs(dir: File): List<File> = dir.listFiles()
        .orEmpty()
        .filter { it.isDirectory && !it.name.startsWith(".") && it.name != "__MACOSX" }

    private fun hasToc(dir: File): Boolean = dir.listFiles().orEmpty().any { it.isFile && it.name.endsWith(".toc", ignoreCase = true) }

    private fun unwrap(staging: File): File {
        val top = childDirs(staging)
        val only = top.singleOrNull() ?: return staging
        return if (!hasToc(only) && childDirs(only).any(::hasToc)) only else staging
    }

    private fun addonFolders(root: File): List<File> = childDirs(root)

    private fun swapIn(folders: List<File>, addonsDir: File) {
        val nonce = System.nanoTime()
        val done = mutableListOf<Pair<File, File?>>() // target to its backup
        try {
            for (folder in folders) {
                val target = File(addonsDir, folder.name)
                var backup: File? = null
                if (target.exists()) {
                    backup = File(addonsDir, ".${folder.name}.old-$nonce")
                    if (!target.renameTo(backup)) throw IOException("cannot move aside ${target.name}")
                }
                done += target to backup
                if (!folder.renameTo(target)) throw IOException("cannot move ${folder.name} into place")
            }
        } catch (e: IOException) {
            for ((target, backup) in done.asReversed()) {
                if (backup != null) {
                    target.deleteRecursively()
                    backup.renameTo(target)
                } else {
                    target.deleteRecursively()
                }
            }
            throw e
        }
        done.forEach { (_, backup) -> backup?.deleteRecursively() }
    }
}
