package app.gamenative.ui.screen.wow

import com.winlator.core.OneShotTasks
import com.winlator.core.RuntimeLog
import java.io.File

/** Opt-in observation of presented buffers, not a claim about game-internal 3D targets. */
internal class WowDisplayGeometry(private val directory: File, private val enqueue: ((() -> Unit) -> Unit) = { task -> OneShotTasks.execute { task() } }) {
    private val sizes = LinkedHashMap<Int, Long>()
    /** [label] is only evaluated for a new record (a window's first buffer or a size change), never on a repeated size (A14-HUD-01). */
    @Synchronized
    fun presented(window: Int, width: Int, height: Int, label: () -> String = { "" }): Boolean {
        if (width <= 0 || height <= 0) return false
        val size = (width.toLong() shl 32) or height.toLong()
        if (sizes[window] == size) return false
        if (sizes.size >= 64 && window !in sizes) sizes.remove(sizes.keys.first())
        sizes[window] = size
        val bytes = "${System.currentTimeMillis()} presented_pixmap window=$window width=$width height=$height label=${label().replace('\n', ' ').take(256)} (backbuffer observation; not internal 3D target)\n".toByteArray()
        enqueue { append(File(directory, "display-geometry.log"), bytes) }
        return true
    }
    companion object {
        @Synchronized fun append(file: File, bytes: ByteArray) {
            try {
                file.parentFile?.mkdirs()
                val previous = if (file.isFile) file.readBytes().takeLast(64 * 1024).toByteArray() else byteArrayOf()
                RuntimeLog(file, 128L * 1024).use { it.append(previous, previous.size); it.append(bytes, bytes.size) }
            } catch (_: Exception) { }
        }
    }
}
