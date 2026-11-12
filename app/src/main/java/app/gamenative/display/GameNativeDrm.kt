package app.gamenative.display

import android.os.Handler
import android.os.Looper
import android.view.Surface
import app.gamenative.PluviaApp
import app.gamenative.events.AndroidEvent

/** GameNative Android display bridge; ABI paired with payload 20261105.4. */
object GameNativeDrm {
    init { System.loadLibrary("gamenative_drm") }
    const val E_NO_SESSION = -2
    @Volatile var frameListener: (() -> Unit)? = null
    @Volatile private var shimPacesFifo = false
    @Volatile private var cursorPending = false
    private var cursorVisible = true
    @Volatile private var cursorReady = false
    private var cursorRetryAt = 0L
    private val frameLimit = GamescopeFrameLimit()
    @Volatile private var userLimit = 0
    @Volatile private var background = false
    @JvmStatic fun setBackground(value: Boolean) {
        if (background == value) return
        background = value
        applyLimit()
    }
    @JvmStatic fun setFpsLimit(fps: Int) {
        userLimit = fps.coerceIn(0, 1000)
        applyLimit()
    }
    private fun applyLimit() {
        val limit = GamescopeFrameLimit.effectiveLimit(userLimit, background)
        // Preserve the daemon's native cached cap as well as its compositor command.
        nativeSetFpsLimit(limit)
        // Gamescope's limiter is the cap on every route; with FIFO it is the only one (WowRuntimeTuning.gamescopeOwnsFifoPacing).
        frameLimit.request(limit)
        applyFrameLimit(false)
    }
    private val sendFrameLimit: (Int) -> Int = { nativeGamescopeCommand("debug_set_fps_limit $it") }
    private fun applyFrameLimit(frameAvailable: Boolean) {
        if (frameLimit.pending) frameLimit.apply(System.nanoTime(), frameAvailable, sendFrameLimit)
    }
    private val epoch = java.util.concurrent.atomic.AtomicLong()
    @Volatile internal var outputSize: Pair<Int, Int>? = null
    @Volatile internal var activeEffects: GamescopeEffects? = null
    @Volatile private var waitingForGame = false
    private val clearOnFrame = java.util.concurrent.atomic.AtomicBoolean(false)
    internal fun beginSession(waitForGame: Boolean, scanoutSize: Pair<Int, Int>, effects: GamescopeEffects, shimPacesFifo: Boolean) {
        this.shimPacesFifo = shimPacesFifo
        background = false
        frameLimit.beginSession()
        outputSize = scanoutSize
        activeEffects = effects
        resetCursor()
        epoch.incrementAndGet(); waitingForGame = waitForGame; clearOnFrame.set(false)
    }
    fun sessionEpoch() = epoch.get()
    fun cancelReadiness() {
        epoch.incrementAndGet()
        waitingForGame = false
        clearOnFrame.set(false)
        outputSize = null
        activeEffects = null
        resetCursor()
    }
    /**
     * The WoW window has not appeared for a long time: take the splash down anyway, so whatever Gamescope shows (a WoW or Wine
     * error dialog, a slow first start) is visible instead of hidden behind it. The game keeps running and is still watched.
     */
    fun revealWithoutGameWindow(token: Long) {
        if (epoch.get() != token || !waitingForGame) return
        waitingForGame = false
        clearBootingSplash()
    }
    fun gameWindowDetected(token: Long) {
        if (epoch.get() != token) return
        activeEffects?.shaderCommand()?.let(::nativeGamescopeCommand)
        if (epoch.get() == token) { waitingForGame = false; clearOnFrame.set(true) }
    }

    @Synchronized private fun resetCursor() {
        cursorVisible = true
        cursorReady = false
        cursorPending = false
        cursorRetryAt = 0L
    }

    @JvmStatic @Synchronized fun setGuestCursorVisible(visible: Boolean) {
        if (cursorVisible == visible) return
        cursorVisible = visible
        cursorPending = true
        cursorRetryAt = 0L
        applyCursorVisibility()
    }

    @Synchronized private fun applyCursorVisibility() {
        val now = System.nanoTime()
        if (cursorReady && cursorPending && now >= cursorRetryAt) {
            cursorPending = nativeGamescopeCommand("wow_quick_cursor ${if (cursorVisible) 1 else 0}") != 0
            cursorRetryAt = now + 500_000_000L
        }
    }

    @Synchronized private fun markCursorReady() {
        cursorReady = true
        applyCursorVisibility()
    }
    @JvmStatic fun clearBootingSplash() {
        if (waitingForGame) return
        val token = epoch.get()
        Handler(Looper.getMainLooper()).post {
        if (token != epoch.get() || waitingForGame) return@post
        app.gamenative.ui.screen.wow.WowLaunchProgress.complete()
        PluviaApp.events.emit(AndroidEvent.ClearBootingSplash)
    } }
    @JvmStatic fun reportStartFailure(context: android.content.Context, error: Throwable) { Handler(Looper.getMainLooper()).post {
        app.gamenative.ui.util.SnackbarManager.show("Gamescope: " + (error.message ?: error.javaClass.simpleName))
        PluviaApp.events.emit(AndroidEvent.GuestProgramTerminated)
    } }
    @JvmStatic external fun nativeDisplayInfo(): String
    @JvmStatic fun onFramePublished() {
        applyFrameLimit(true)
        if (!cursorReady) markCursorReady()
        else if (cursorPending) applyCursorVisibility()
        GamescopeFrameLog.record(android.os.SystemClock.elapsedRealtimeNanos())
        frameListener?.invoke()
        if (clearOnFrame.compareAndSet(true, false)) clearBootingSplash()
    }
    @JvmStatic external fun nativeSetPanelRefreshHz(refreshHz: Int)
    @JvmStatic external fun nativeSetScanoutGeometry(width: Int, height: Int)
    @JvmStatic external fun nativeStart(socketName: String, surface: Surface, width: Int, height: Int): Int
    @JvmStatic external fun nativeRetargetSurface(surface: Surface, width: Int, height: Int): Int
    @JvmStatic external fun nativeStop()
    @JvmStatic external fun nativeScanoutLayout(): String
    @JvmStatic external fun nativeInjectorStart(xdgRuntimeDir: String, waylandDisplay: String): Int
    @JvmStatic external fun nativeInjectorStop()
    @JvmStatic external fun nativeSetFpsLimit(fps: Int)
    @JvmStatic external fun nativeGamescopeCommand(command: String): Int
    @JvmStatic external fun nativeReshadeSetEffect(name: String)
    @JvmStatic external fun nativeReshadeDisable()
    @JvmStatic external fun nativePointerAbsolute(x: Int, y: Int)
    @JvmStatic external fun nativePointerMotion(dx: Int, dy: Int)
    @JvmStatic external fun nativePointerButton(button: Int, pressed: Boolean)
    @JvmStatic external fun nativePointerAxis(axis: Int, value: Float)
    @JvmStatic external fun nativeKey(keyCode: Int, pressed: Boolean)
}
