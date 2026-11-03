package app.gamenative

import android.hardware.display.DisplayManager
import android.os.Build
import android.util.DisplayMetrics
import android.view.Display
import androidx.compose.runtime.mutableStateOf
import androidx.navigation.NavController
import app.gamenative.events.EventDispatcher
import app.gamenative.ui.screen.xserver.RadialMenuCoordinator
import android.app.Application
import com.winlator.container.Container
import com.winlator.inputcontrols.InputControlsManager
import com.winlator.widget.InputControlsView
import com.winlator.widget.TouchpadView
import com.winlator.widget.XServerRendererView
import com.winlator.xenvironment.XEnvironment
import dagger.hilt.android.HiltAndroidApp
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

typealias NavChangedListener = NavController.OnDestinationChangedListener

@HiltAndroidApp
class PluviaApp : Application() {

    override fun onCreate() {
        app.gamenative.utils.AppLogging.disable()
        super.onCreate()
        instance = this
        android.system.Os.setenv("EVSHIM_BASE_PATH", filesDir.absolutePath, true)

        PrefManager.init(this)
        app.gamenative.ui.theme.WowTheme.load(this)
        app.gamenative.ui.screen.wow.WowUiLanguage.load(this)
        app.gamenative.ui.screen.wow.WowI18n.init(this)
        app.gamenative.ui.screen.wow.WowDiagnostics.initialize(this)
        com.winlator.core.FaultLog.init(this)
    }

    companion object {
        @JvmField
        val events: EventDispatcher = EventDispatcher()
        internal var onDestinationChangedListener: NavChangedListener? = null

        private lateinit var instance: PluviaApp
        private var cachedDefaultScreenSize: String? = null

        // TODO: find a way to make this saveable, this is terrible (leak that memory baby)
        internal var xEnvironment: XEnvironment? = null

        /**
         * The game screen that is on display now. The environment outlives a screen that is rebuilt, and its guest-termination
         * callback was created for the first one: it reports to this one, so a recreation cannot swallow the "game ended" event.
         */
        @Volatile
        internal var currentGameSession: app.gamenative.ui.screen.xserver.GameSessionLifecycle? = null
        internal var xServerView: XServerRendererView? = null
        var inputControlsView: InputControlsView? = null
        var inputControlsManager: InputControlsManager? = null
        var touchpadView: TouchpadView? = null
        var imeInputReceiver: app.gamenative.externaldisplay.IMEInputReceiver? = null
        var radialMenuCoordinator: RadialMenuCoordinator? = null

        /**
         * Compose snapshot state: the dialogs that grey out while a game runs read [keepAlive] in composition and are recomposed when it
         * changes (they used to poll it four times a second for as long as they were open). Reads and writes from any thread are fine.
         */
        private var keepAliveState by mutableStateOf(false)

        @JvmStatic
        var keepAlive: Boolean
            get() = keepAliveState
            set(value) {
                val changed = keepAliveState != value
                keepAliveState = value
                // The session's foreground service keeps Android from freezing the game while the app is in the background.
                if (changed && ::instance.isInitialized) app.gamenative.ui.screen.wow.WowSessionService.sessionChanged(instance, value)
            }

        var isOverlayPaused by mutableStateOf(false)
        @Volatile
        var isActivityInForeground: Boolean = true
        // True while the booting splash covers the game screen (and its Resume overlay).
        @Volatile
        var isBootingSplashShowing: Boolean = false

        // Active runtime suspend policy for the current in-game session.
        var activeSuspendPolicy: String = Container.SUSPEND_POLICY_MANUAL
            private set
        private var hasInitializedSuspendPolicyState: Boolean = false

        fun setActiveSuspendPolicy(policy: String) {
            activeSuspendPolicy = Container.normalizeSuspendPolicy(policy)
            hasInitializedSuspendPolicyState = true
        }

        private val teardownLock = java.util.concurrent.locks.ReentrantLock()
        private val teardownDone = teardownLock.newCondition()
        private var teardownsRunning = 0 // guarded by teardownLock

        /**
         * Finishes a process teardown on its own thread (e.g. a wineserver kill that can take seconds when it is wedged) so the UI
         * thread does not wait for it. The session still counts as alive ([keepAlive] stays set) until [work] is done: settings and
         * components stay locked, and a new launch [awaitTeardown]s first, because the old wineserver is still saving its registry.
         */
        @JvmStatic
        fun runTeardownInBackground(work: Runnable) {
            teardownLock.lock()
            try { teardownsRunning++ } finally { teardownLock.unlock() }
            val finish = {
                teardownLock.lock()
                try {
                    if (--teardownsRunning == 0) {
                        keepAlive = false
                        teardownDone.signalAll()
                    }
                } finally { teardownLock.unlock() }
            }
            try {
                Thread({
                    try {
                        work.run()
                    } catch (_: Throwable) {
                        // a failing cleanup must not leave the session marked alive for ever
                    } finally {
                        finish()
                    }
                }, "EnvTeardown").start()
            } catch (t: Throwable) {
                // The worker never existed (thread or native memory exhausted): give its count back, or the session would stay
                // "tearing down" for ever. The cleanup itself is then done right here, as it was before it ran in the background.
                try { work.run() } catch (_: Throwable) { } finally { finish() }
            }
        }

        @JvmStatic
        fun isTearingDown(): Boolean {
            teardownLock.lock()
            try { return teardownsRunning > 0 } finally { teardownLock.unlock() }
        }

        /** Waits until every background teardown is done. Never call on the UI thread. False when [timeoutMs] ran out first. */
        @JvmStatic
        fun awaitTeardown(timeoutMs: Long): Boolean {
            var left = java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(timeoutMs)
            teardownLock.lock()
            try {
                while (teardownsRunning > 0) {
                    if (left <= 0L) return false
                    left = teardownDone.awaitNanos(left)
                }
                return true
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            } finally { teardownLock.unlock() }
        }

        /**
         * full environment teardown — shared by XServerScreen.exit() and
         * MainActivity.onDestroy fallback so both paths clean up identically
         */
        fun shutdownEnvironment() {
            val env = xEnvironment

            // per-step catch so one failing teardown doesn't prevent the rest from running
            runCatching { touchpadView?.releasePointerCapture() }
                .onFailure { Unit }
            runCatching { radialMenuCoordinator?.detach() }
                .onFailure { Unit }
            runCatching { env?.stopEnvironmentComponents() }
                .onFailure { Unit }

            xEnvironment = null
            inputControlsView = null
            inputControlsManager = null
            touchpadView = null
            radialMenuCoordinator = null
            imeInputReceiver = null // a View: keeping it would hold the destroyed Activity
            // A wineserver kill still running in the background clears the flag itself when it is done.
            if (!isTearingDown()) keepAlive = false
            clearActiveSuspendState()
        }

        fun clearActiveSuspendState() {
            activeSuspendPolicy = Container.SUSPEND_POLICY_MANUAL
            isOverlayPaused = false
            hasInitializedSuspendPolicyState = false
        }

        fun hasValidSuspendPolicyState(): Boolean = hasInitializedSuspendPolicyState

        fun isNeverSuspendMode(): Boolean = activeSuspendPolicy.equals(Container.SUSPEND_POLICY_NEVER, ignoreCase = true)

        fun isManualSuspendMode(): Boolean = activeSuspendPolicy.equals(Container.SUSPEND_POLICY_MANUAL, ignoreCase = true)

        fun getDefaultScreenSize(): String {
            cachedDefaultScreenSize?.let { return it }

            return try {
                val displayManager = instance.getSystemService(DISPLAY_SERVICE) as? DisplayManager
                val display = displayManager?.getDisplay(Display.DEFAULT_DISPLAY)
                if (display != null) {
                    val width : Int
                    val height : Int

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        val mode = display.mode
                        width = mode.physicalWidth
                        height = mode.physicalHeight
                    } else {
                        // API < 30 - Use deprecated Display API
                        val displayMetrics = DisplayMetrics()
                        @Suppress("DEPRECATION")
                        display.getRealMetrics(displayMetrics)
                        width = displayMetrics.widthPixels
                        height = displayMetrics.heightPixels
                    }

                    // Calculate aspect ratio (always use landscape orientation for calculation)
                    val aspectRatio = maxOf(width, height).toFloat() / minOf(width, height).toFloat()

                    // Aspect ratio thresholds:
                    // 4:3 = 1.33
                    // 16:10 = 1.6
                    // 16:9 = 1.77

                    val result = when {
                        aspectRatio < 1.5f -> Container.DEFAULT_SCREEN_SIZE_4_3  // 4:3 aspect ratio devices
                        aspectRatio < 1.7f -> Container.DEFAULT_SCREEN_SIZE_16_10  // 16:10 aspect ratio devices
                        else -> Container.DEFAULT_SCREEN_SIZE_16_9  // 16:9 and wider aspect ratio devices
                    }
                    cachedDefaultScreenSize = result
                    result
                } else {
                    val fallback = Container.DEFAULT_SCREEN_SIZE_16_9  // Fallback to default
                    cachedDefaultScreenSize = fallback
                    fallback
                }
            } catch (e: Exception) {

                val fallback = Container.DEFAULT_SCREEN_SIZE_16_9  // Fallback to default
                cachedDefaultScreenSize = fallback
                fallback
            }
        }
    }

}
