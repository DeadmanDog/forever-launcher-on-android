package app.gamenative.ui.screen.wow

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.SurfaceView
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Grabs a small copy of what the game view is showing (no on-screen controls or HUD). */
object LoginScreenCapture {
    private const val WIDTH = 448
    private const val HEIGHT = 203
    private const val COPY_TIMEOUT_MS = 3000L

    // One bitmap and one pixel buffer for every capture: the auto sign-in looks at the screen every 0.7 s for up to ten minutes, and a
    // new bitmap plus a new array each time was ~730 KB of garbage per look (about 600 MB over the ten minutes).
    private val lock = Mutex()
    private var bitmap: Bitmap? = null
    private val pixels = IntArray(WIDTH * HEIGHT)
    private val frame = LoginFrame(WIDTH, HEIGHT, pixels)

    /** Result of the last attempt: [PixelCopy.SUCCESS] or the PixelCopy error code (-1 when there is no surface, -2 refused, -3 no answer in time). */
    @Volatile var lastResult: Int = -1
        private set

    /**
     * Captures the view and hands the frame (null when the capture failed) to [use]. The frame shares its pixel buffer with the next
     * capture, so it is only valid inside [use] and must not be kept.
     */
    suspend fun <T> withFrame(view: SurfaceView, use: (LoginFrame?) -> T): T = lock.withLock { use(grab(view)) }

    private suspend fun grab(view: SurfaceView): LoginFrame? {
        if (!view.holder.surface.isValid) {
            lastResult = -1
            return null
        }
        val target = bitmap ?: Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888).also { bitmap = it }
        var answered = false
        val result = try {
            withTimeoutOrNull(COPY_TIMEOUT_MS) {
                suspendCancellableCoroutine<Int> { cont ->
                    try {
                        PixelCopy.request(view, target, { code -> if (cont.isActive) cont.resume(code) }, Handler(Looper.getMainLooper()))
                    } catch (e: IllegalArgumentException) {
                        if (cont.isActive) cont.resume(-2)
                    }
                }
            }.also { answered = it != null }
        } finally {
            // A cancelled or timed-out request is still pending in the platform and may write into [target] later. The bitmap is
            // given up (never recycled: it may still be in use) and the next capture gets a fresh one, so two requests never
            // share a target.
            if (!answered) bitmap = null
        }
        if (result == null) {
            lastResult = -3
            return null
        }
        lastResult = result
        if (result != PixelCopy.SUCCESS) return null
        target.getPixels(pixels, 0, WIDTH, 0, 0, WIDTH, HEIGHT)
        return frame
    }
}
