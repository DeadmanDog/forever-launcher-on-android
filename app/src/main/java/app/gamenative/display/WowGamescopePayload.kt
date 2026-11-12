package app.gamenative.display

import android.content.Context
import android.system.Os
import com.winlator.core.TarCompressorUtils
import java.io.File
import java.security.MessageDigest

object WowGamescopePayload {
    const val ARCHIVE = "gamescope-gamenative-20261105.4-wow-dd4-cursor.tzst"
    const val SHA256 = "d945e532c6c44b90dcc3ba780e7e5e7e69f0e5b97ea2bd4afc2eb672194d0629"
    @JvmStatic fun directory(context: Context) = File(context.filesDir, "gamescope")
    private const val INSTALL_REVISION = "xkb-relocation-randr-2"
    private fun prefix(context: Context) = GamescopePaths.prefix(context.filesDir.canonicalFile.parentFile!!.path)
    private fun marker(context: Context) = "$SHA256:$INSTALL_REVISION:${prefix(context)}"
    private val required = listOf("bin/gamescope", "bin/gamescopereaper", "bin/Xwayland", "bin/xkbcomp", "bin/gn-xtree-probe", "bin/gn-scaling-mode", "lib/libgndrm_shim.so", "lib/libadrenotools_gamescope.so", "share/X11/xkb/rules/base")

    @JvmStatic fun isReady(context: Context): Boolean {
        val target = directory(context)
        val stamp = File(target, ".wow-payload-sha256")
        return stamp.isFile && stamp.readText() == marker(context) && required.all { File(target, it).isFile } &&
            File(target, "tmp/xkb").isDirectory && GamescopeRandrLibrary.isReady(target)
    }
    private fun ensureAlias(context: Context) {
        val alias = File(context.filesDir.canonicalFile.parentFile, GamescopePaths.ALIAS)
        val target = directory(context).absolutePath
        if (java.nio.file.Files.isSymbolicLink(alias.toPath())) {
            check(File(Os.readlink(alias.path)).canonicalFile == File(target).canonicalFile) { "Unexpected Gamescope private path alias" }
        } else {
            check(!alias.exists()) { "Gamescope private path alias is occupied" }
            Os.symlink(target, alias.path)
        }
    }
    @Synchronized @JvmStatic fun ensure(context: Context): File {
        val target = directory(context)
        ensureAlias(context)
        if (isReady(context)) return target
        val archive = File(context.cacheDir, ARCHIVE)
        val staging = File(context.filesDir, "gamescope.staging")
        check(staging.deleteRecursively())
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            context.assets.open(ARCHIVE).use { input -> archive.outputStream().use { output ->
                val buffer = ByteArray(65536)
                while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n); output.write(buffer, 0, n) }
            } }
            check(digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) } == SHA256) { "Gamescope payload hash mismatch" }
            check(staging.mkdirs())
            check(TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, archive, staging)) { "Gamescope extraction failed" }
            check(required.all { File(staging, it).isFile }) { "Incomplete Gamescope payload" }
            context.assets.open(GamescopeRandrLibrary.ASSET).use { GamescopeRandrLibrary.install(it, staging) }
            val relocatedPrefix = prefix(context)
            for ((name, count) in GamescopePaths.binaries) {
                val binary = File(staging, name)
                binary.writeBytes(GamescopePaths.relocate(binary.readBytes(), relocatedPrefix, count))
            }
            check(File(staging, "tmp/xkb").let { it.isDirectory || it.mkdirs() }) { "Cannot create writable XKB output directory" }
            File(staging, ".wow-payload-sha256").writeText(marker(context))
            val old = File(context.filesDir, "gamescope.previous")
            check(old.deleteRecursively())
            if (target.exists()) check(target.renameTo(old))
            if (!staging.renameTo(target)) { old.renameTo(target); error("Gamescope promotion failed") }
            old.deleteRecursively()
            return target
        } finally { archive.delete(); staging.deleteRecursively() }
    }
}
