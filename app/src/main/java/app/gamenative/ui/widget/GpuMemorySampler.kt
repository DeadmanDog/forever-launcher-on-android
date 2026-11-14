package app.gamenative.ui.widget

import android.os.Debug
import java.io.File
import java.util.Locale

/** This app's own graphics memory in bytes from Android's memory statistics, or null. */
internal fun readOwnGraphicsBytes(): Long? = runCatching {
    val info = Debug.MemoryInfo()
    Debug.getMemoryInfo(info)
    info.getMemoryStat("summary.graphics")?.toLongOrNull()?.times(1024)
}.getOrNull()

/**
 * Graphics memory in use. On Adreno the GPU shares system memory, so "VRAM" is what the KGSL driver has allocated
 * (system-wide: this game's Wine processes plus the compositor). Falls back to this app's own graphics memory from
 * Android's memory stats when the driver statistics are not readable.
 */
internal class GpuMemorySampler(
    private val kgslDirectory: File = File("/sys/class/kgsl/kgsl"),
    private val clock: () -> Long = { System.nanoTime() / 1_000_000L },
    private val appGraphicsBytes: () -> Long? = { null },
) {
    // The fallback parses /proc/self/smaps (several ms, and it holds the mmap lock the render threads need): at most every 10 s.
    private var fallbackAt = Long.MIN_VALUE
    private var fallback: Long? = null

    private fun readBytes(name: String): Long? =
        runCatching { File(kgslDirectory, name).readText().trim().toLongOrNull() }.getOrNull()?.takeIf { it >= 0 }

    fun bytes(): Long? {
        val pages = readBytes("page_alloc")
        if (pages != null) return pages + (readBytes("coherent") ?: 0L)
        val now = clock()
        if (fallbackAt == Long.MIN_VALUE || now - fallbackAt >= FALLBACK_INTERVAL_MS) {
            fallback = appGraphicsBytes()?.takeIf { it > 0 }
            fallbackAt = now
        }
        return fallback
    }

    fun text(): String {
        val used = bytes() ?: return "N/A"
        val gb = used / (1024.0 * 1024.0 * 1024.0)
        return if (gb >= 1.0) String.format(Locale.US, "%.1fGB", gb) else "${used / (1024L * 1024L)}MB"
    }

    private companion object { const val FALLBACK_INTERVAL_MS = 10_000L }
}
