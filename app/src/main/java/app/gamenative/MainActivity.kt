package app.gamenative

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Color.TRANSPARENT
import android.hardware.input.InputManager
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import app.gamenative.events.AndroidEvent
import app.gamenative.ui.PluviaMain
import app.gamenative.ui.screen.xserver.GameBackKeyHandler
import app.gamenative.ui.enums.Orientation
import app.gamenative.ui.util.LocalSnackbarHostController
import app.gamenative.ui.util.SnackbarHostController
import com.winlator.core.AppUtils
import com.winlator.inputcontrols.ControllerManager
import dagger.hilt.android.AndroidEntryPoint
import java.util.EnumSet
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private val gameBackKeyHandler = GameBackKeyHandler()

    private val onSetSystemUi: (AndroidEvent.SetSystemUIVisibility) -> Unit = {
        desiredSystemUiVisible = it.visible
        applyImmersiveMode()
    }

    private val onSetAllowedOrientation: (AndroidEvent.SetAllowedOrientation) -> Unit = {
        setOrientationTo(it.orientations)
    }

    private val onEndProcess: (AndroidEvent.EndProcess) -> Unit = {
        finishAndRemoveTask()
    }

    private var controllerInputManager: InputManager? = null
    private val controllerDeviceListener = object : InputManager.InputDeviceListener {
        override fun onInputDeviceAdded(deviceId: Int) {
            ControllerManager.getInstance().onDeviceConnected(deviceId)
        }

        override fun onInputDeviceRemoved(deviceId: Int) {
            ControllerManager.getInstance().onDeviceDisconnected(deviceId)
        }

        override fun onInputDeviceChanged(deviceId: Int) {
            ControllerManager.getInstance().onDeviceConnected(deviceId)
        }
    }

    private var desiredSystemUiVisible: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        // Full immersive mode - transparent system bars for console-like experience
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(TRANSPARENT),
        )
        super.onCreate(savedInstanceState)

        // stale keepAlive from a prior crash/swipe — no container is actually running
        if (PluviaApp.keepAlive && PluviaApp.xEnvironment == null && !PluviaApp.isTearingDown()) {

            PluviaApp.shutdownEnvironment()
        }

        // Apply immersive mode based on user preference
        applyImmersiveMode()

        // Initialize the controller management system
        ControllerManager.getInstance().init(applicationContext)
        controllerInputManager = getSystemService(Context.INPUT_SERVICE) as InputManager
        controllerInputManager?.registerInputDeviceListener(controllerDeviceListener, null)

        PluviaApp.events.on<AndroidEvent.SetSystemUIVisibility, Unit>(onSetSystemUi)
        PluviaApp.events.on<AndroidEvent.SetAllowedOrientation, Unit>(onSetAllowedOrientation)
        PluviaApp.events.on<AndroidEvent.EndProcess, Unit>(onEndProcess)

        setContent {
            // The screen stays on while a game runs (the launcher asks for it itself while it installs or launches), not for an idle launcher.
            val gameRunning = PluviaApp.keepAlive
            LaunchedEffect(gameRunning) { app.gamenative.ui.util.ScreenAwake.set(this@MainActivity, "game", gameRunning, this@MainActivity) }
            // The static owner map must not keep a destroyed Activity (and its "game" request) alive; a newer owner is not touched.
            DisposableEffect(Unit) { onDispose { app.gamenative.ui.util.ScreenAwake.set(this@MainActivity, "game", false, this@MainActivity) } }
            val snackbarController = remember { SnackbarHostController() }
            CompositionLocalProvider(
                LocalSnackbarHostController provides snackbarController,
            ) {
                PluviaMain()
            }
        }
    }
    override fun onDestroy() {
        // emit before super so Compose DisposableEffects (which unregister
        // listeners during super.onDestroy's lifecycle transition) still fire
        if (!isChangingConfigurations) {
            PluviaApp.events.emit(AndroidEvent.ActivityDestroyed)

            // if exit() didn't run (listener already unregistered, race, etc.)
            // force-clear so the app isn't stuck on next launch
            if (PluviaApp.keepAlive) {

                PluviaApp.shutdownEnvironment()
            }
        }

        super.onDestroy()

        controllerInputManager?.unregisterInputDeviceListener(controllerDeviceListener)
        controllerInputManager = null

        PluviaApp.events.off<AndroidEvent.SetSystemUIVisibility, Unit>(onSetSystemUi)
        PluviaApp.events.off<AndroidEvent.SetAllowedOrientation, Unit>(onSetAllowedOrientation)
        PluviaApp.events.off<AndroidEvent.EndProcess, Unit>(onEndProcess)
    }

    private fun hasReadyGameLifecycleState(action: String): Boolean {
        if (!PluviaApp.keepAlive) return false
        if (!PluviaApp.hasValidSuspendPolicyState()) {

            return false
        }
        if (PluviaApp.xEnvironment == null) {

            return false
        }
        return true
    }

    override fun onResume() {
        super.onResume()
        PluviaApp.isActivityInForeground = true

        // Re-apply immersive mode to ensure fullscreen persists
        if (!desiredSystemUiVisible) {
            applyImmersiveMode()
        }

        // Resume game according to the active suspend policy.
        if (hasReadyGameLifecycleState("resume")) {
            when {
                PluviaApp.isNeverSuspendMode() -> {

                }
                PluviaApp.isOverlayPaused -> {
                    if (PluviaApp.isBootingSplashShowing) {
                        // The Resume overlay sits under the booting splash, so the user could
                        // never press it; nothing is being played yet, so just carry on booting.
                        PluviaApp.xEnvironment?.onResume()
                        PluviaApp.isOverlayPaused = false

                    } else if (PluviaApp.isManualSuspendMode()) {

                    }
                }
                else -> {
                    PluviaApp.xEnvironment?.onResume()

                }
            }
        }
    }

    override fun onPause() {
        PluviaApp.isActivityInForeground = false
        if (hasReadyGameLifecycleState("pause")) {
            when {
                PluviaApp.isNeverSuspendMode() -> {

                }
                else -> {
                    // WoW is an online game: stopping its processes while the app is in the background made the server drop the
                    // connection within seconds. The game keeps running (WowSessionService keeps Android from freezing it); only
                    // the microphone pauses. The Quick Menu's own pause option still stops the game.
                    PluviaApp.xEnvironment?.onBackground()
                }
            }
        }
        super.onPause()
    }

    @SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // Handle Back before game/Compose input: one completed press is one action.
        // Modern uses the same dispatcher as gesture Back, including open dialogs.
        if (gameBackKeyHandler.handle(event, PluviaApp.keepAlive) {
                onBackPressedDispatcher.onBackPressed()
            }) return true

        val eventDispatched = PluviaApp.events.emitAny(AndroidEvent.KeyEvent(event))

        return if (!eventDispatched) super.dispatchKeyEvent(event) else true
    }

    override fun dispatchGenericMotionEvent(ev: MotionEvent?): Boolean {
        val eventDispatched = PluviaApp.events.emitAny(AndroidEvent.MotionEvent(ev))

        return if (!eventDispatched) super.dispatchGenericMotionEvent(ev) else true
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)

    }

    private fun applyImmersiveMode() {
        if (desiredSystemUiVisible) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                window.setDecorFitsSystemWindows(true)
                window.insetsController?.show(
                    android.view.WindowInsets.Type.statusBars() or
                        android.view.WindowInsets.Type.navigationBars(),
                )
            } else {
                @Suppress("DEPRECATION")
                run {
                    window.decorView.systemUiVisibility = android.view.View.SYSTEM_UI_FLAG_VISIBLE
                }
            }
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let { controller ->
                controller.hide(
                    android.view.WindowInsets.Type.statusBars() or
                        android.view.WindowInsets.Type.navigationBars(),
                )
                controller.systemBarsBehavior =
                    android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or android.view.View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    or android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or android.view.View.SYSTEM_UI_FLAG_FULLSCREEN
                    or android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                )
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && !desiredSystemUiVisible) {
            applyImmersiveMode()
        }
    }

    private fun setOrientationTo(conformTo: EnumSet<Orientation>) {
        requestedOrientation = when {
            conformTo.contains(Orientation.PORTRAIT) -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            conformTo.contains(Orientation.LANDSCAPE) && conformTo.contains(Orientation.REVERSE_LANDSCAPE) ->
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            conformTo.contains(Orientation.LANDSCAPE) -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            conformTo.contains(Orientation.REVERSE_LANDSCAPE) -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE
            else -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }
}
