package app.gamenative.ui.screen.wow

import android.content.Context

/**
 * Display-hardware upscaling: the compositor renders the guest frame 1:1 into a guest-sized
 * buffer (SurfaceHolder.setFixedSize) and SurfaceFlinger/HWC scales the layer to the view.
 * On by default but it only acts when the guest resolution fits the display (fits); scanout already scales in hardware and takes precedence.
 */
object WowHardwareScale {
    const val KEY = "hwscale"

    @JvmStatic fun requested(context: Context): Boolean =
        WowLaunchPolicy.enabled(context, KEY) && !WowLaunchPolicy.enabled(context, "scanout")

    /** Same rule as ViewTransformation: filling the view may cost at most one guest pixel per edge. */
    @JvmStatic fun fits(viewWidth: Int, viewHeight: Int, guestWidth: Int, guestHeight: Int): Boolean {
        if (viewWidth <= 0 || viewHeight <= 0 || guestWidth <= 0 || guestHeight <= 0) return false
        val fill = maxOf(viewWidth.toFloat() / guestWidth, viewHeight.toFloat() / guestHeight)
        val edgeX = (guestWidth * fill - viewWidth) / (2f * fill)
        val edgeY = (guestHeight * fill - viewHeight) / (2f * fill)
        return edgeX <= 1f && edgeY <= 1f
    }

    /** UI hint: compares landscape sides of the physical display with a "WxH" resolution. */
    fun fitsDisplay(displayWidth: Int, displayHeight: Int, resolution: String): Boolean {
        val parts = resolution.split('x').mapNotNull { it.toIntOrNull() }
        if (parts.size != 2) return false
        return fits(maxOf(displayWidth, displayHeight), minOf(displayWidth, displayHeight), parts[0], parts[1])
    }
}
