package app.gamenative.display

import java.io.File
import java.io.InputStream
import java.security.MessageDigest

/** XRandR from the Android/Termux X11 stack used by Gamescope, not the Winlator ImageFS stack. */
internal object GamescopeRandrLibrary {
    const val ASSET = "gamescope-libs/libXrandr.so"
    const val SHA256 = "86384babd4bbd1276949025b86b77d3ee44e4553d635995540db49563b0301c8"
    private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 255) }

    fun install(input: InputStream, staging: File) {
        val bytes = input.readBytes()
        check(bytes.size >= 64 && bytes[0] == 0x7f.toByte() && bytes[1] == 'E'.code.toByte() &&
            bytes[2] == 'L'.code.toByte() && bytes[3] == 'F'.code.toByte() &&
            bytes[4] == 2.toByte() && bytes[5] == 1.toByte() &&
            bytes[16] == 3.toByte() && bytes[17] == 0.toByte() &&
            bytes[18] == 183.toByte() && bytes[19] == 0.toByte()) { "Invalid ARM64 Gamescope XRandR library" }
        check(digest(bytes) == SHA256) { "Gamescope XRandR library hash mismatch" }
        val library = File(staging, "lib/libXrandr.so")
        check(library.parentFile!!.let { it.isDirectory || it.mkdirs() })
        library.writeBytes(bytes)
    }

    fun isReady(directory: File): Boolean {
        val library = File(directory, "lib/libXrandr.so")
        return library.isFile && digest(library.readBytes()) == SHA256
    }
}
