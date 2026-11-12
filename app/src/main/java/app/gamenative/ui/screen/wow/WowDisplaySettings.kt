package app.gamenative.ui.screen.wow

import android.content.Context
import com.winlator.container.Container
import com.winlator.container.ContainerManager
import com.winlator.contents.ContentsManager
import java.io.File

/** Explicit user-owned virtual display; no global preference or component changes. Call persistence on IO. */
internal object WowDisplaySettings {
    /** Exactly half of the RedMagic 11 Pro display (2688x1216): the display scales it by a whole 2 in both directions. */
    const val REDMAGIC = "1344x608"
    /** The earlier RedMagic preset (two thirds of the display, a fractional scale); replaced by [REDMAGIC] once, see WowContainerProfile. */
    const val LEGACY_REDMAGIC = "1792x810"
    internal const val MANAGED = "wowManagedDisplay"
    /** [fullscreen]: the "Full screen" entry for this phone (see [fullscreenFor]). */
    data class Option(val value: String, val label: String, val fullscreen: Boolean = false)
    // 960x544: the small 16:9 size Winlator also offers.
    private val supported = listOf("960x544", "1280x720", REDMAGIC, "1600x900", "1920x1080", "2560x1440")
    /**
     * Full screen for any phone. A display whose exact half is 600 to 720 pixels high uses that half, which the display scales by a
     * whole 2 (sharp, and 40% fewer pixels than 720 high on the RedMagic): 2688x1216 (RedMagic 11 Pro) -> 1344x608, 3200x1440 ->
     * 1600x720. Any other display gets [fullscreen720For]: 2400x1080 -> 1600x720, 2340x1080 -> 1560x720.
     */
    fun fullscreenFor(displayWidth: Int, displayHeight: Int): String? {
        val long = maxOf(displayWidth, displayHeight); val shortSide = minOf(displayWidth, displayHeight)
        if (long > 0 && long % 2 == 0 && shortSide % 2 == 0 && shortSide / 2 in 600..720) return "${long / 2}x${shortSide / 2}"
        return fullscreen720For(displayWidth, displayHeight)
    }

    /**
     * 1.0.0's Full screen: 720 pixels high (or the display's own height if it is lower) and as wide as the display's own aspect
     * allows (rounded down to an even width), so the game fills the screen without stretching. 2688x1216 -> 1590x720.
     */
    fun fullscreen720For(displayWidth: Int, displayHeight: Int): String? {
        val long = maxOf(displayWidth, displayHeight); val shortSide = minOf(displayWidth, displayHeight)
        if (long <= 0 || shortSide <= 0) return null
        val h = minOf(720, shortSide)
        val w = (h.toLong() * long / shortSide).toInt() / 2 * 2
        return if (w >= 320 && h >= 200) "${w}x$h" else null
    }
    /** This phone's full-screen resolution (cached for the game screen's cutout handling). */
    @Volatile private var lastFullscreen: String? = null
    fun fullscreen(context: Context): String? {
        val wm = context.getSystemService(android.view.WindowManager::class.java)
        val size = if (android.os.Build.VERSION.SDK_INT >= 30) wm.maximumWindowMetrics.bounds.let { it.width() to it.height() }
            else context.resources.displayMetrics.let { it.widthPixels to it.heightPixels }
        return fullscreenFor(size.first, size.second).also { if (it != null) lastFullscreen = it }
    }
    /** This phone's Full screen value in 1.0.0 (720 high), for the one-time move to the current one. */
    fun previousFullscreen(context: Context): String? {
        val wm = context.getSystemService(android.view.WindowManager::class.java)
        val size = if (android.os.Build.VERSION.SDK_INT >= 30) wm.maximumWindowMetrics.bounds.let { it.width() to it.height() }
            else context.resources.displayMetrics.let { it.widthPixels to it.heightPixels }
        return fullscreen720For(size.first, size.second)
    }
    fun options(current: String, fullscreen: String? = null): List<Option> =
        (listOfNotNull(fullscreen) + supported + current).distinct().filter { valid(it) }.map {
            Option(it, if (it == fullscreen) "Full screen (${it.replace('x', '×')})" else it.replace('x', '×'), it == fullscreen)
        }
    private fun valid(value: String): Boolean {
        val parts = value.split('x')
        return parts.size == 2 && parts.all { (it.toIntOrNull() ?: 0) in 1..32767 }
    }
    fun cutoutMode(resolution: String, portrait: Boolean, sdk: Int): Int? = when {
        (resolution != REDMAGIC && resolution != lastFullscreen) || portrait || sdk < 28 -> null
        sdk >= 30 -> android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        else -> android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
    }
    /** True when the physical display has the aspect of the RedMagic fullscreen resolution (1344x608, half of 2688x1216). */
    fun redMagicFits(context: Context): Boolean {
        val wm = context.getSystemService(android.view.WindowManager::class.java)
        val size = if (android.os.Build.VERSION.SDK_INT >= 30) wm.maximumWindowMetrics.bounds.let { it.width() to it.height() }
            else context.resources.displayMetrics.let { it.widthPixels to it.heightPixels }
        return WowHardwareScale.fitsDisplay(size.first, size.second, REDMAGIC)
    }
    fun gameResolution(container: Container): String? = container.screenSize.takeIf {
        container.getExtra(MANAGED) == "1" && valid(it)
    }
    fun apply(context: Context, value: String, active: () -> Boolean): String =
        synchronized(ContentsManager.getLocalInstallLock()) {
            check(!active()) { "Stop the game before changing resolution." }
            val container = ContainerManager(context).getContainerById(WowContainerProfile.CONTAINER_ID)
                ?: error("WoW container has not been created yet")
            require(options(container.screenSize, fullscreen(context)).any { it.value == value }) { "Unsupported display resolution." }
            check(!active()) { "Stop the game before changing resolution." }
            container.screenSize = value
            container.putExtra(MANAGED, "1")
            container.saveData()
            val saved = ContainerManager(context).getContainerById(container.id)
            check(saved?.screenSize == value && gameResolution(saved) == value) { "Unable to save display resolution." }
            value
        }

    /** Set actual game display sizes, preserving graphics quality, camera, UI scale and window mode. */
    fun configLines(lines: List<String>, resolution: String): List<String> {
        require(valid(resolution)) { "Invalid display resolution." }
        // The client's two display sizes (windowed and full screen; jaredgei 2.3.3 forces both too). gxResolution is not a CVar of
        // this client any more: an old line is dropped. Without gxFullscreenResolution, switching WoW to full screen rendered at its
        // own auto-detected size and the picture was scaled.
        val values = linkedMapOf("gxFullscreenResolution" to resolution, "gxWindowedResolution" to resolution,
            "gxMaximize" to "1")
        val seen = mutableSetOf<String>()
        val result = mutableListOf<String>()
        val setting = Regex("^\\s*SET\\s+(\\S+)\\s+.*$", RegexOption.IGNORE_CASE)
        for (line in lines) {
            val key = setting.matchEntire(line)?.groupValues?.get(1)
            if (key.equals("gxResolution", true)) continue
            val owned = values.keys.firstOrNull { it.equals(key, true) }
            if (owned == null) result += line
            else if (seen.add(owned)) result += "SET $owned \"${values.getValue(owned)}\""
        }
        values.forEach { (key, value) -> if (key !in seen) result += "SET $key \"$value\"" }
        return result
    }
    fun alignGameConfig(file: File, resolution: String?) {
        if (resolution == null) return
        val before = file.readLines()
        val after = configLines(before, resolution)
        if (before != after) {
            // Temp file + atomic move: a crash mid-write must not corrupt the player's Config.wtf.
            val temp = File(file.parentFile, "${file.name}.tmp")
            temp.writeText(after.joinToString("\n", postfix = "\n"))
            java.nio.file.Files.move(temp.toPath(), file.toPath(),
                java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        }
    }
}
