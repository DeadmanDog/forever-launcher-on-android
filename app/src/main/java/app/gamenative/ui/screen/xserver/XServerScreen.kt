package app.gamenative.ui.screen.xserver

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.os.Build
import android.os.SystemClock
import android.view.Display
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.View.OnLayoutChangeListener
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.hardware.display.DisplayManager
import android.hardware.input.InputManager
import android.view.InputDevice
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import app.gamenative.MainActivity
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.ui.Alignment
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import app.gamenative.R
import app.gamenative.ui.util.SnackbarManager
import app.gamenative.ui.util.applyScreenEffectsConfig
import app.gamenative.ui.util.loadScreenEffectsConfig
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.gamenative.PluviaApp
import app.gamenative.PrefManager
import app.gamenative.events.AndroidEvent
import app.gamenative.ui.enums.Orientation
import java.util.EnumSet
import app.gamenative.externaldisplay.ExternalDisplayInputController
import app.gamenative.externaldisplay.ExternalDisplaySwapController
import app.gamenative.externaldisplay.SwapInputOverlayView
import app.gamenative.ui.component.PerformanceQuickMenuState
import app.gamenative.ui.component.QuickMenu
import app.gamenative.ui.component.QuickMenuAction
import app.gamenative.ui.component.parseBooleanExtra
import app.gamenative.ui.component.parsePositiveFpsLimit
import app.gamenative.ui.data.PerformanceHudConfig
import app.gamenative.ui.data.PerformanceHudStyle
import app.gamenative.ui.data.HudMetric
import app.gamenative.ui.data.XServerState
import app.gamenative.ui.widget.PerformanceHudView
import app.gamenative.utils.AssetUtils
import app.gamenative.utils.ContainerUtils
import app.gamenative.utils.BrightnessManager
import app.gamenative.utils.WineProcessSnapshotHelper
import com.winlator.container.Container
import com.winlator.container.ContainerFiles
import com.winlator.container.ContainerManager
import com.winlator.container.ContainerOverlayMigrator
import com.winlator.contents.AdrenotoolsManager
import com.winlator.contents.ContentProfile
import com.winlator.contents.ContentsManager
import com.winlator.core.AppUtils
import com.winlator.core.Callback
import com.winlator.core.DXVKHelper
import com.winlator.core.DefaultVersion
import com.winlator.core.FileUtils
import com.winlator.core.GPUHelper
import com.winlator.core.GPUInformation
import com.winlator.core.KeyValueSet
import com.winlator.core.OnExtractFileListener
import com.winlator.core.ProcessHelper
import com.winlator.core.TarCompressorUtils
import com.winlator.core.Win32AppWorkarounds
import com.winlator.core.WineInfo
import com.winlator.core.WineRegistryEditor
import com.winlator.core.WineThemeManager
import com.winlator.core.WineUtils
import com.winlator.core.envvars.EnvVars
import com.winlator.inputcontrols.ControllerManager
import com.winlator.inputcontrols.ControlsProfile
import com.winlator.inputcontrols.ExternalController
import com.winlator.inputcontrols.InputControlsManager
import com.winlator.widget.FrameRating
import com.winlator.widget.InputControlsView
import com.winlator.widget.TouchpadView
import com.winlator.renderer.VulkanRenderer
import com.winlator.widget.XServerRendererView
import com.winlator.widget.XServerView
import com.winlator.winhandler.WinHandler
import com.winlator.winhandler.WinHandler.PreferredInputApi
import com.winlator.winhandler.OnGetProcessInfoListener
import com.winlator.winhandler.ProcessInfo
import com.winlator.xconnector.UnixSocketConfig
import com.winlator.xenvironment.ImageFs
import com.winlator.xenvironment.XEnvironment
import com.winlator.xenvironment.components.BionicProgramLauncherComponent
import com.winlator.xenvironment.components.NetworkInfoUpdateComponent
import com.winlator.xenvironment.components.MicrophoneComponent
import com.winlator.xenvironment.components.PulseAudioComponent
import com.winlator.xenvironment.components.SysVSharedMemoryComponent
import com.winlator.xenvironment.components.XServerComponent
import com.winlator.xserver.Keyboard
import com.winlator.xserver.Property
import com.winlator.xserver.ScreenInfo
import com.winlator.xserver.ShmFramePacer
import com.winlator.xserver.Window
import com.winlator.xserver.WindowManager
import com.winlator.xserver.XServer
import com.winlator.xserver.extensions.PresentExtension
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.nio.file.Paths
import java.util.Locale
import kotlin.math.ceil
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.io.path.name
import kotlin.math.roundToInt
import kotlin.text.lowercase
import com.winlator.PrefManager as WinlatorPrefManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue


// Guard to prevent duplicate game_exited events when multiple exit triggers fire simultaneously


private const val EXIT_PROCESS_TIMEOUT_MS = 30_000L
private const val EXIT_PROCESS_POLL_INTERVAL_MS = 1_000L
private const val EXIT_PROCESS_RESPONSE_TIMEOUT_MS = 2_000L
private const val QUICK_MENU_PROCESS_POLL_INTERVAL_MS = 2_000L
private const val DEFAULT_FPS_LIMITER_MAX_HZ = 60
private const val DEFAULT_FPS_LIMITER_TARGET_HZ = 60
private const val FPS_LIMITER_ENABLED_EXTRA = "fpsLimiterEnabled"
private const val FPS_LIMITER_TARGET_EXTRA = "fpsLimiterTarget"

private fun initialFpsLimiterEnabled(container: Container): Boolean =
    parseBooleanExtra(container.getExtra(FPS_LIMITER_ENABLED_EXTRA)) ?: true

private fun initialFpsLimiterTarget(container: Container): Int =
    parsePositiveFpsLimit(container.getExtra(FPS_LIMITER_TARGET_EXTRA))
        ?: DEFAULT_FPS_LIMITER_TARGET_HZ

/** Releases the keys the game still holds; the Quick Menu takes the keyboard, so their key-up would never arrive. */
private fun releaseGuestKeys(view: XServerRendererView?) {
    (view as? com.winlator.widget.XServerView)?.getxServer()?.keyboard?.releaseAllKeys()
}

private fun detectMaxRefreshRateHz(context: Context, attachedView: View?): Int {
    val display = attachedView?.display
        ?: context.display
        ?: ContextCompat.getSystemService(context, DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY)

    val refreshRate = when {
        display == null -> DEFAULT_FPS_LIMITER_MAX_HZ.toFloat()
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.M -> {
            val supportedMax = display.supportedModes.maxOfOrNull { it.refreshRate } ?: display.refreshRate
            if (supportedMax.isFinite() && supportedMax > 0f) supportedMax else display.refreshRate
        }
        else -> display.refreshRate
    }

    return refreshRate
        .takeIf { it.isFinite() && it > 0f }
        ?.roundToInt()
        ?.coerceAtLeast(5)
        ?: DEFAULT_FPS_LIMITER_MAX_HZ
}

private data class XServerViewReleaseBinding(
    val xServerView: XServerRendererView,
    val windowModificationListener: WindowManager.OnWindowModificationListener,
    var gameHost: FrameLayout? = null,
    var gameHostLayoutListener: OnLayoutChangeListener? = null,
    val screenWidth: Int,
)

private val CORE_WINE_PROCESSES = setOf(
    "wineserver",
    "services",
    "start",
    "winhandler",
    "tabtip",
    "explorer",
    "winedevice",
    "svchost",
)

private fun normalizeProcessName(name: String): String {
    val trimmed = name.trim().trim('"')
    val base = trimmed.substringAfterLast('/').substringAfterLast('\\')
    val lower = base.lowercase(Locale.getDefault())
    return if (lower.endsWith(".exe")) lower.removeSuffix(".exe") else lower
}

internal fun parseScreenSize(screenSize: String): Pair<Int, Int>? {
    val parts = screenSize.lowercase(Locale.getDefault()).split("x")
    if (parts.size != 2) return null
    val width = parts[0].trim().toIntOrNull() ?: return null
    val height = parts[1].trim().toIntOrNull() ?: return null
    if (width <= 0 || height <= 0) return null
    return width to height
}

internal fun portraitGameHostHeight(
    isPortrait: Boolean,
    screenWidth: Int,
    availableHeight: Int,
    screenSize: String,
): Int {
    if (!isPortrait) return ViewGroup.LayoutParams.MATCH_PARENT
    val (renderWidth, renderHeight) = parseScreenSize(screenSize) ?: return ViewGroup.LayoutParams.MATCH_PARENT
    val aspectHeight = ceil(screenWidth * (renderHeight.toFloat() / renderWidth.toFloat())).toInt()
    return if (availableHeight > 0) minOf(aspectHeight, availableHeight) else aspectHeight
}

internal fun portraitCutoutTopInset(belowCutout: Boolean, cutoutTop: Int, hostTopInWindow: Int): Int {
    if (!belowCutout) return 0
    return (cutoutTop - hostTopInWindow).coerceAtLeast(0)
}

private fun portraitCutoutTopInset(host: View, belowCutout: Boolean): Int {
    if (!belowCutout) return 0
    val cutoutTop = ViewCompat.getRootWindowInsets(host)
        ?.getInsets(WindowInsetsCompat.Type.displayCutout())?.top ?: return 0
    val location = IntArray(2)
    host.getLocationInWindow(location)
    return portraitCutoutTopInset(belowCutout, cutoutTop, location[1])
}

private fun updatePortraitGameHostHeight(
    gameHost: View,
    isPortrait: Boolean,
    belowCutout: Boolean,
    screenWidth: Int,
    screenSize: String,
) {
    val params = gameHost.layoutParams ?: return
    val host = gameHost.parent as? View
    val topInset = if (isPortrait && host != null) portraitCutoutTopInset(host, belowCutout) else 0
    if (host != null && host.paddingTop != topInset) {
        host.setPadding(host.paddingLeft, topInset, host.paddingRight, host.paddingBottom)
    }
    val height = portraitGameHostHeight(
        isPortrait,
        screenWidth,
        ((host?.height ?: 0) - topInset).coerceAtLeast(0),
        screenSize,
    )
    if (params.height != height) {
        params.height = height
        gameHost.layoutParams = params
    }
}

private fun extractExecutableBasename(path: String): String {
    if (path.isBlank()) return ""
    return normalizeProcessName(path)
}

private fun windowMatchesExecutable(window: Window, targetExecutable: String): Boolean {
    if (targetExecutable.isBlank()) return false
    val normalizedTarget = normalizeProcessName(targetExecutable)
    val candidates = listOf(window.name, window.className)
    return candidates.any { candidate ->
        candidate.split('\u0000')
            .asSequence()
            .map { normalizeProcessName(it) }
            .any { it == normalizedTarget }
    }
}

private fun buildEssentialProcessAllowlist(): Set<String> {
    val essentialServices = WineUtils.getEssentialServiceNames()
        .map { normalizeProcessName(it) }
    return (essentialServices + CORE_WINE_PROCESSES).toSet()
}

@Composable
@OptIn(ExperimentalComposeUiApi::class)
fun XServerScreen(
    lifecycleOwner: LifecycleOwner = LocalLifecycleOwner.current,
    appId: String,
    container: Container,
    isOffline: Boolean = false,
    registerBackAction: ( ( ) -> Unit ) -> Unit,
    navigateBack: () -> Unit,
    onExit: (onComplete: (() -> Unit)?) -> Unit,
    onWindowMapped: ((Context, Window) -> Unit)? = null,
    onWindowUnmapped: ((Window) -> Unit)? = null,
    onGameLaunchError: ((String) -> Unit)? = null,
) {

    val context = LocalContext.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val imm = remember(context) {
        context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
    }

    PluviaApp.events.emit(AndroidEvent.SetSystemUIVisibility(false))

    // seems to be used to indicate when a custom wine is being installed (intent extra "generate_wineprefix")
    // val generateWinePrefix = false
    var firstTimeBoot = false
    var needsUnpacking = false
    var containerVariantChanged = false
    var frameRating by remember { mutableStateOf<FrameRating?>(null) }
    var frameRatingWindowId = -1
    var taskAffinityMask = 0
    var taskAffinityMaskWoW64 = 0

    val session = remember(appId) { GameSessionLifecycle() }
    DisposableEffect(session) {
        PluviaApp.currentGameSession = session
        // The game screen keeps the display on from the moment it is shown: PluviaApp.keepAlive only rises when the guest process
        // starts, which can be many seconds later on a first launch.
        app.gamenative.ui.util.ScreenAwake.set(context as? Activity, "game-screen", true, session)
        onDispose {
            app.gamenative.ui.util.ScreenAwake.set(context as? Activity, "game-screen", false, session)
            session.beginExit()
            if (PluviaApp.currentGameSession === session) PluviaApp.currentGameSession = null
        }
    }

    val activity = remember(context) { BrightnessManager.findActivity(context) }
    DisposableEffect(activity, container.screenSize, container.isPortraitMode) {
        val mode = app.gamenative.ui.screen.wow.WowDisplaySettings.cutoutMode(
            container.screenSize, container.isPortraitMode, Build.VERSION.SDK_INT)
        val window = activity?.window
        val previous = if (mode != null && window != null) window.attributes.layoutInDisplayCutoutMode else null
        if (mode != null && window != null) {
            window.attributes = window.attributes.apply { layoutInDisplayCutoutMode = mode }
        }
        onDispose {
            if (previous != null && window != null) {
                window.attributes = window.attributes.apply { layoutInDisplayCutoutMode = previous }
            }
        }
    }


    DisposableEffect(activity) {
        onDispose {
            if (activity != null) {
                BrightnessManager.clearDisplayBrightnessOverride(activity)
            }
        }
    }

    val suspendPolicy = remember(container.id) { container.suspendPolicy }
    val neverSuspend = suspendPolicy.equals(Container.SUSPEND_POLICY_NEVER, ignoreCase = true)
    val manualResumeMode = suspendPolicy.equals(Container.SUSPEND_POLICY_MANUAL, ignoreCase = true)

    SideEffect {
        PluviaApp.setActiveSuspendPolicy(suspendPolicy)
    }

    PluviaApp.events.emit(
        AndroidEvent.SetAllowedOrientation(
            if (container.isPortraitMode) EnumSet.of(Orientation.PORTRAIT)
            else PrefManager.allowedOrientation,
        ),
    )

    val xServerState = rememberSaveable(stateSaver = XServerState.Saver) {
        mutableStateOf(
            XServerState(
                graphicsDriver = container.graphicsDriver,
                graphicsDriverVersion = container.graphicsDriverVersion,
                dxwrapper = container.dxWrapper,
                dxwrapperConfig = DXVKHelper.parseConfig(container.dxWrapperConfig),
                screenSize = container.screenSize,
            ),
        )
    }

    var keyboard by remember { mutableStateOf<Keyboard?>(null) }

    var xServerView: XServerRendererView? by remember {
        val result = mutableStateOf<XServerRendererView?>(null)

        result
    }

    var swapInputOverlay: SwapInputOverlayView? by remember { mutableStateOf(null) }
    var imeInputReceiver: app.gamenative.externaldisplay.IMEInputReceiver? by remember { mutableStateOf(null) }

    var win32AppWorkarounds: Win32AppWorkarounds? by remember { mutableStateOf(null) }
    var physicalControllerHandler: PhysicalControllerHandler? by remember { mutableStateOf(null) }
    var exitWatchJob: Job? by remember { mutableStateOf(null) }
    val keyboardEscMenuHandler = remember(scope) { KeyboardEscMenuHandler(scope) }

    DisposableEffect(Unit) {
        onDispose {
            PluviaApp.radialMenuCoordinator?.detach()
            PluviaApp.radialMenuCoordinator = null
            physicalControllerHandler?.cleanup()
            physicalControllerHandler = null
            exitWatchJob?.cancel()
            exitWatchJob = null
            keyboardEscMenuHandler.cancel()
        }
    }
    var areControlsVisible by remember { mutableStateOf(false) }
    var isDisableMouseInput by remember(container.id) { mutableStateOf(container.isDisableMouseInput) }
    var isEditMode by remember { mutableStateOf(false) }
    var gameRoot by remember { mutableStateOf<View?>(null) }
    var windowModificationListener by remember { mutableStateOf<WindowManager.OnWindowModificationListener?>(null) }
    // Snapshot of element positions before entering edit mode (for cancel behavior)
    var elementPositionsSnapshot by remember { mutableStateOf<Map<com.winlator.inputcontrols.ControlElement, Pair<Int, Int>>>(emptyMap()) }
    var showElementEditor by remember { mutableStateOf(false) }
    var elementToEdit by remember { mutableStateOf<com.winlator.inputcontrols.ControlElement?>(null) }
    var showPhysicalControllerDialog by remember { mutableStateOf(false) }
    var showTouchGestureDialog by remember { mutableStateOf(false) }
    var isTouchscreenModeActive by remember { mutableStateOf(container.isTouchscreenMode) }
    var currentGestureConfig by remember {
        mutableStateOf(app.gamenative.data.TouchGestureConfig.fromJson(container.getGestureConfig()))
    }
    fun shouldShowMouseCursor(): Boolean {
        return !container.isDisableMouseInput &&
            (!container.isTouchscreenMode || currentGestureConfig.showCursorInTouchscreenMode)
    }
    fun applyMouseCursorVisibility() {
        xServerView?.renderer?.setCursorVisible(shouldShowMouseCursor())
    }
    val clickHighlightPoints = remember { mutableStateListOf<app.gamenative.ui.component.HighlightPoint>() }
    var debugGestureName by remember { mutableStateOf("") }
    var debugGestureKey by remember { mutableIntStateOf(0) }
    var keyboardRequestedFromOverlay by remember { mutableStateOf(false) }
    var bnetSignInRequestedFromOverlay by remember { mutableStateOf(false) }
    var shouldForceResumeOnMenuClose by remember { mutableStateOf(false) }
    var showQuickMenu by remember { mutableStateOf(false) }
    var quickMenuToolsVisible by remember { mutableStateOf(false) }
    var quickMenuWineProcesses by remember { mutableStateOf<List<ProcessInfo>>(emptyList()) }
    var quickMenuWineProcessesLoading by remember { mutableStateOf(false) }
    var hasPhysicalController by remember { mutableStateOf(false) }
    var keepPausedForEditor by remember { mutableStateOf(false) }
    var hasPhysicalKeyboard by remember { mutableStateOf(false) }
    var hasPhysicalMouse by remember { mutableStateOf(false) }
    var usingScreenMirror by remember { mutableStateOf(false) }
    var hasInternalTouchpad by remember { mutableStateOf(false) }
    var hasUpdatedScreenGamepad by remember { mutableStateOf(false) }
    var isPerformanceHudEnabled by remember { mutableStateOf(PrefManager.showFps) }
    val shouldTrackDisplayedFrames = remember { AtomicBoolean(false) }
    var detectedMaxRefreshRateHz by remember { mutableIntStateOf(detectMaxRefreshRateHz(context, null)) }
    var fpsLimiterEnabled by rememberSaveable(container.id) { mutableStateOf(initialFpsLimiterEnabled(container)) }
    var fpsLimiterTarget by rememberSaveable(container.id) { mutableIntStateOf(initialFpsLimiterTarget(container)) }

    fun persistFpsLimiterState() {
        container.putExtra(FPS_LIMITER_ENABLED_EXTRA, fpsLimiterEnabled)
        container.putExtra(FPS_LIMITER_TARGET_EXTRA, fpsLimiterTarget)
        container.saveData()
    }

    fun loadPerformanceHudConfig(): PerformanceHudConfig {
        return PerformanceHudConfig(
            showFrameRate = PrefManager.performanceHudShowFrameRate,
            showCpuUsage = PrefManager.performanceHudShowCpuUsage,
            showGpuUsage = PrefManager.performanceHudShowGpuUsage,
            showRamUsage = PrefManager.performanceHudShowRamUsage,
            showVramUsage = PrefManager.performanceHudShowVramUsage,
            showFrameTime = PrefManager.performanceHudShowFrameTime,
            showFps1Low = PrefManager.performanceHudShowFps1Low,
            showFps01Low = PrefManager.performanceHudShowFps01Low,
            showBatteryLevel = PrefManager.performanceHudShowBatteryLevel,
            showPowerDraw = PrefManager.performanceHudShowPowerDraw,
            showBatteryRuntime = PrefManager.performanceHudShowBatteryRuntime,
            showBatteryTemperature = PrefManager.performanceHudShowBatteryTemperature,
            showClockTime = PrefManager.performanceHudShowClockTime,
            showCpuTemperature = PrefManager.performanceHudShowCpuTemperature,
            showGpuTemperature = PrefManager.performanceHudShowGpuTemperature,
            showFrameRateGraph = PrefManager.performanceHudShowFrameRateGraph,
            showCpuUsageGraph = PrefManager.performanceHudShowCpuUsageGraph,
            showGpuUsageGraph = PrefManager.performanceHudShowGpuUsageGraph,
            backgroundOpacity = PrefManager.performanceHudBackgroundOpacity,
            colorIntensity = PrefManager.performanceHudColorIntensity,
            showTextOutline = PrefManager.performanceHudShowTextOutline,
            sizePercent = PrefManager.performanceHudSizePercent,
            style = PerformanceHudStyle.fromPrefValue(PrefManager.performanceHudStyle),
            showCpuClock = PrefManager.performanceHudShowCpuClock,
            showGpuClock = PrefManager.performanceHudShowGpuClock,
            metricOrder = HudMetric.parseOrder(PrefManager.performanceHudMetricOrder),
        )
    }

    var performanceHudConfig by remember { mutableStateOf(loadPerformanceHudConfig()) }
    var performanceHudView by remember { mutableStateOf<PerformanceHudView?>(null) }
    var performanceHudHost by remember { mutableStateOf<FrameLayout?>(null) }
    var isDraggingPerformanceHud by remember { mutableStateOf(false) }
    var isTrackingPerformanceHudTouch by remember { mutableStateOf(false) }
    var performanceHudTouchDownRawX by remember { mutableStateOf(0f) }
    var performanceHudTouchDownRawY by remember { mutableStateOf(0f) }
    var performanceHudDragOffsetX by remember { mutableStateOf(0f) }
    var performanceHudDragOffsetY by remember { mutableStateOf(0f) }
    val performanceHudTouchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()

    fun persistPerformanceHudConfig(config: PerformanceHudConfig) = PrefManager.batch {
        PrefManager.performanceHudShowFrameRate = config.showFrameRate
        PrefManager.performanceHudShowCpuUsage = config.showCpuUsage
        PrefManager.performanceHudShowGpuUsage = config.showGpuUsage
        PrefManager.performanceHudShowRamUsage = config.showRamUsage
        PrefManager.performanceHudShowVramUsage = config.showVramUsage
        PrefManager.performanceHudShowFrameTime = config.showFrameTime
        PrefManager.performanceHudShowFps1Low = config.showFps1Low
        PrefManager.performanceHudShowFps01Low = config.showFps01Low
        PrefManager.performanceHudShowBatteryLevel = config.showBatteryLevel
        PrefManager.performanceHudShowPowerDraw = config.showPowerDraw
        PrefManager.performanceHudShowBatteryRuntime = config.showBatteryRuntime
        PrefManager.performanceHudShowBatteryTemperature = config.showBatteryTemperature
        PrefManager.performanceHudShowClockTime = config.showClockTime
        PrefManager.performanceHudShowCpuTemperature = config.showCpuTemperature
        PrefManager.performanceHudShowGpuTemperature = config.showGpuTemperature
        PrefManager.performanceHudShowFrameRateGraph = config.showFrameRateGraph
        PrefManager.performanceHudShowCpuUsageGraph = config.showCpuUsageGraph
        PrefManager.performanceHudShowGpuUsageGraph = config.showGpuUsageGraph
        PrefManager.performanceHudBackgroundOpacity = config.backgroundOpacity
        PrefManager.performanceHudColorIntensity = config.colorIntensity
        PrefManager.performanceHudShowTextOutline = config.showTextOutline
        PrefManager.performanceHudSizePercent = config.sizePercent
        PrefManager.performanceHudStyle = config.style.prefValue
        PrefManager.performanceHudShowCpuClock = config.showCpuClock
        PrefManager.performanceHudShowGpuClock = config.showGpuClock
        PrefManager.performanceHudMetricOrder = HudMetric.normalizeOrder(config.metricOrder).joinToString(",") { it.name }
    }

    fun applyPerformanceHudConfig(config: PerformanceHudConfig) {
        performanceHudConfig = config
        persistPerformanceHudConfig(config)
        performanceHudView?.setConfig(config)
    }

    LaunchedEffect(xServerView?.renderer, showQuickMenu, quickMenuToolsVisible, isPerformanceHudEnabled, areControlsVisible) {
        // Named reasons let diagnostics show why the compositor stayed in use.
        val scanoutBlockers = buildList {
            if (showQuickMenu || quickMenuToolsVisible) add("quickmenu")
            if (isPerformanceHudEnabled) add("hud")
            if (areControlsVisible) add("controls")
        }.joinToString("+")
        (xServerView?.renderer as? VulkanRenderer)?.setExperimentalScanout(
            container.id == "wow_forever" && app.gamenative.ui.screen.wow.WowLaunchPolicy.enabled(context,"scanout"),
            scanoutBlockers,
        )
    }

    LaunchedEffect(xServerView?.renderer) {
        val screenEffectsConfig = loadScreenEffectsConfig(container)
        when (val renderer = xServerView?.renderer) {
            is VulkanRenderer -> {
                applyScreenEffectsConfig(renderer, screenEffectsConfig)
            }
        }
    }

    // WoW: the app's Present limiter (vsync-locked by default) paces frames unless DXVK/VKD3D own pacing.
    val wowApiPacing = remember(container) {
        container.id == "wow_forever" && !app.gamenative.ui.screen.wow.WowRuntimeTuning.appPacing(context, container)
    }

    // WoW: CPU ADPF runs inside the game process (TurnRIP 1.1.11+); the app-side socket bridge is no longer started.

    fun applyFpsLimiterToEngines(limit: Int) {
        // With DXVK/VKD3D owning the pacing their limit cannot change in the running game: a lower request is enforced by the app
        // limiter on top of it, a higher one waits for the next launch (see WowRuntimeTuning.live).
        val live = app.gamenative.ui.screen.wow.WowRuntimeTuning.live(wowApiPacing, limit)
        FrameRating.setLimitHint(live.inForce)
        xServerView?.setFrameRateLimit(if (container.id == "wow_forever") app.gamenative.ui.screen.wow.WowRuntimeTuning.surfaceTarget(container, limit) else limit)
        xServerView?.getxServer()
            ?.getExtension<PresentExtension>(PresentExtension.MAJOR_OPCODE.toInt())
            ?.apply {
                setVsyncPacing(app.gamenative.ui.screen.wow.WowRuntimeTuning.vsyncPacing(context))
                com.winlator.xserver.extensions.PresentPhase.setEnabled(app.gamenative.ui.screen.wow.WowRuntimeTuning.phaseAlign(context))
            }
            ?.setFrameRateLimit(live.app)
        ShmFramePacer.setFrameRateLimit(live.app)
    }

    fun tellIfLimitWaitsForRestart(limit: Int) {
        val live = app.gamenative.ui.screen.wow.WowRuntimeTuning.live(wowApiPacing, limit)
        if (wowApiPacing && live.deferred) app.gamenative.ui.util.SnackbarManager.show(app.gamenative.ui.screen.wow.WowRuntimeTuning.liveNotice(context, live))
    }

    fun effectiveFpsLimit(): Int =
        if (fpsLimiterEnabled) fpsLimiterTarget else 0

    fun applyFpsLimiterEnabled(enabled: Boolean) {
        fpsLimiterEnabled = enabled
        applyFpsLimiterToEngines(effectiveFpsLimit())
        persistFpsLimiterState()
        tellIfLimitWaitsForRestart(effectiveFpsLimit())
    }

    fun applyFpsLimiterTarget(target: Int) {
        val sanitized = target.coerceAtLeast(5).coerceAtMost(detectedMaxRefreshRateHz)
        fpsLimiterTarget = sanitized
        if (fpsLimiterEnabled) {
            applyFpsLimiterToEngines(effectiveFpsLimit())
        }
        persistFpsLimiterState()
        if (fpsLimiterEnabled) tellIfLimitWaitsForRestart(effectiveFpsLimit())
    }

    LaunchedEffect(xServerView) {
        val detectedMax = detectMaxRefreshRateHz(context, xServerView as? View)
        detectedMaxRefreshRateHz = detectedMax
        val clampedTarget = fpsLimiterTarget.coerceAtMost(detectedMax).coerceAtLeast(5)
        if (clampedTarget != fpsLimiterTarget) {
            fpsLimiterTarget = clampedTarget
        }
        applyFpsLimiterToEngines(effectiveFpsLimit())
    }

    fun restorePerformanceHudPosition() {
        val host = performanceHudHost ?: return
        val hud = performanceHudView ?: return
        if (host.width <= 0 || host.height <= 0 || hud.width <= 0 || hud.height <= 0) return

        // The HUD has inner padding; the visible content (not the padded view) must be able to touch every screen edge.
        val minX = -hud.paddingLeft.toFloat()
        val minY = -hud.paddingTop.toFloat()
        val maxX = (host.width - hud.width + hud.paddingRight).toFloat().coerceAtLeast(minX)
        val maxY = (host.height - hud.height + hud.paddingBottom).toFloat().coerceAtLeast(minY)
        val spanX = maxX - minX
        val spanY = maxY - minY
        val margin = 12 * context.resources.displayMetrics.density
        val savedX = PrefManager.performanceHudXFraction
        val savedY = PrefManager.performanceHudYFraction

        hud.x = if (savedX in 0f..1f) minX + spanX * savedX else margin.coerceIn(minX, maxX)
        hud.y = if (savedY in 0f..1f) minY + spanY * savedY else margin.coerceIn(minY, maxY)

        PrefManager.performanceHudXFraction = if (spanX > 0f) (hud.x - minX) / spanX else 0f
        PrefManager.performanceHudYFraction = if (spanY > 0f) (hud.y - minY) / spanY else 0f
    }

    fun movePerformanceHud(rawX: Float, rawY: Float, save: Boolean) {
        val host = performanceHudHost ?: return
        val hud = performanceHudView ?: return
        if (host.width <= 0 || host.height <= 0 || hud.width <= 0 || hud.height <= 0) return

        val hostLocation = IntArray(2)
        host.getLocationOnScreen(hostLocation)
        // Same bounds as restorePerformanceHudPosition: the padded view may overhang the edge by its own padding.
        val minX = -hud.paddingLeft.toFloat()
        val minY = -hud.paddingTop.toFloat()
        val maxX = (host.width - hud.width + hud.paddingRight).toFloat().coerceAtLeast(minX)
        val maxY = (host.height - hud.height + hud.paddingBottom).toFloat().coerceAtLeast(minY)

        hud.x = (rawX - hostLocation[0] - performanceHudDragOffsetX).coerceIn(minX, maxX)
        hud.y = (rawY - hostLocation[1] - performanceHudDragOffsetY).coerceIn(minY, maxY)

        if (save) {
            PrefManager.performanceHudXFraction = if (maxX > minX) (hud.x - minX) / (maxX - minX) else 0f
            PrefManager.performanceHudYFraction = if (maxY > minY) (hud.y - minY) / (maxY - minY) else 0f
        }
    }

    fun removePerformanceHud() {
        isDraggingPerformanceHud = false
        isTrackingPerformanceHudTouch = false
        performanceHudView?.let { hud ->
            (hud.parent as? ViewGroup)?.removeView(hud)
        }
        performanceHudView = null
    }

    fun togglePerformanceHudLayout() {
        val hud = performanceHudView ?: return
        val compactMode = !hud.isCompactMode()
        hud.setCompactMode(compactMode)
        PrefManager.performanceHudCompactMode = compactMode
        hud.post {
            if (performanceHudView === hud && !isDraggingPerformanceHud) {
                restorePerformanceHudPosition()
            }
        }
    }

    fun updatePerformanceHud(show: Boolean) {
        if (!show) {
            removePerformanceHud()
            return
        }
        if (performanceHudView != null) {
            return
        }

        val targetLayout = performanceHudHost ?: return
        val hud = PerformanceHudView(
            context = context,
            fpsProvider = { frameRating?.currentFPS ?: 0f },
            frameStatsProvider = { frameRating?.frameStats },
            initialConfig = performanceHudConfig,
            initialCompactMode = PrefManager.performanceHudCompactMode,
        )
        val layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }

        targetLayout.addView(hud, layoutParams)
        performanceHudView = hud
        hud.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            if (!isDraggingPerformanceHud) restorePerformanceHudPosition()
        }
        targetLayout.post {
            if (performanceHudView === hud) restorePerformanceHudPosition()
        }
        hud.bringToFront()
    }

    fun clearOverlayPauseState() {
        PluviaApp.isOverlayPaused = false
    }

    fun pauseForOverlayIfAllowed() {
        // The Quick Menu only suspends the game when the player asked for it (Quick Menu > Controller).
        if (neverSuspend || !PrefManager.pauseGameOnQuickMenu) {

            return
        }
        PluviaApp.xEnvironment?.onPause()
        PluviaApp.isOverlayPaused = true
    }

    fun resumeIfAllowedAfterOverlay() {
        if (!PluviaApp.isOverlayPaused) return
        if (neverSuspend) {
            clearOverlayPauseState()
            return
        }
        if (manualResumeMode) {

            return
        }
        PluviaApp.xEnvironment?.onResume()
        clearOverlayPauseState()
    }

    fun forceResumeIfSuspended() {
        if (PluviaApp.isOverlayPaused && !neverSuspend) {
            PluviaApp.xEnvironment?.onResume()
        }
        clearOverlayPauseState()
    }

    fun resumeFromManualButton() {
        if (!PluviaApp.isOverlayPaused) return
        if (!neverSuspend) {
            PluviaApp.xEnvironment?.onResume()
        }
        keepPausedForEditor = false
        clearOverlayPauseState()
    }

    fun startExitWatchForUnmappedGameWindow(window: Window) {
        val winHandler = xServerView?.getxServer()?.winHandler ?: return
        if (exitWatchJob?.isActive == true) return
        val targetExecutable = extractExecutableBasename(
            container.executablePath,
        )
        if (!windowMatchesExecutable(window, targetExecutable)) return

        exitWatchJob = CoroutineScope(Dispatchers.IO).launch {
            val allowlist = buildEssentialProcessAllowlist()
            val previousListener = winHandler.getOnGetProcessInfoListener()
            val lock = Any()
            var pendingSnapshot: CompletableDeferred<List<ProcessInfo>?>? = null
            var currentList = mutableListOf<ProcessInfo>()
            var expectedCount = 0

            val listener = OnGetProcessInfoListener { index, count, processInfo ->
                previousListener?.onGetProcessInfo(index, count, processInfo)
                synchronized(lock) {
                    val deferred = pendingSnapshot ?: return@synchronized
                    if (count == 0 && processInfo == null) {
                        if (!deferred.isCompleted) deferred.complete(null)
                        return@synchronized
                    }
                    if (index == 0) {
                        currentList = mutableListOf()
                        expectedCount = count
                    }
                    if (processInfo != null) {
                        currentList.add(processInfo)
                    }
                    if (currentList.size >= expectedCount && !deferred.isCompleted) {
                        deferred.complete(currentList.toList())
                    }
                }
            }

            winHandler.setOnGetProcessInfoListener(listener)
            try {
                val startTime = System.currentTimeMillis()
                while (System.currentTimeMillis() - startTime < EXIT_PROCESS_TIMEOUT_MS) {
                    val deferred = CompletableDeferred<List<ProcessInfo>?>()
                    synchronized(lock) {
                        pendingSnapshot = deferred
                    }
                    winHandler.listProcesses()
                    val snapshot = withTimeoutOrNull(EXIT_PROCESS_RESPONSE_TIMEOUT_MS) {
                        deferred.await()
                    }
                    if (snapshot != null) {
                        val hasNonEssential = snapshot.any {
                            !allowlist.contains(normalizeProcessName(it.name))
                        }
                        if (!hasNonEssential) {
                            withContext(Dispatchers.Main) {
                                exit(
                                    session,
                                    winHandler,
                                    frameRating,
                                    container,
                                    appId,
                                    onExit,
                                    navigateBack,
                                    "processes_exited",
                                )
                            }
                            break
                        }
                    }
                    delay(EXIT_PROCESS_POLL_INTERVAL_MS)
                }
            } finally {
                winHandler.setOnGetProcessInfoListener(previousListener)
                synchronized(lock) {
                    pendingSnapshot = null
                }
            }
        }
    }

    val tryCapturePointer: () -> Boolean = {
        if (!showElementEditor && !keepPausedForEditor && !showQuickMenu && !isEditMode &&
            !container.isTouchscreenMode) {
            PluviaApp.touchpadView?.postDelayed({
                val view = PluviaApp.touchpadView
                if (view != null) {
                    view.requestFocus()
                    view.requestPointerCapture()
                }
            }, 100)
            true
        } else {
            false
        }
    }

    fun scanForExternalDevices() {
        val deviceIds = InputDevice.getDeviceIds()
        hasPhysicalKeyboard = deviceIds.any { id ->
            val device = InputDevice.getDevice(id) ?: return@any false
            val isExternal = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) device.isExternal else true
            Keyboard.isKeyboardDevice(device) && !device.isVirtual && isExternal
        }
        hasPhysicalMouse = deviceIds.any { id ->
            val device = InputDevice.getDevice(id) ?: return@any false
            val isMouse = device.supportsSource(InputDevice.SOURCE_MOUSE) || device.supportsSource(InputDevice.SOURCE_MOUSE_RELATIVE) ||
                          device.supportsSource(InputDevice.SOURCE_TOUCHPAD)
            val isExternal = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) device.isExternal else true
            isMouse && !device.isVirtual && isExternal
        }
        val controllerManager = ControllerManager.getInstance()
        controllerManager.autoAssignConnectedDevices()
        hasPhysicalController = controllerManager.getDetectedDevices().isNotEmpty()
        xServerView?.getxServer()?.winHandler?.refreshControllerMappingsForHotplug()

        if (!usingScreenMirror &&
            !hasInternalTouchpad && !hasPhysicalMouse && !hasPhysicalKeyboard && !hasPhysicalController &&
            !container.isTouchscreenMode) {
            val manager = PluviaApp.inputControlsManager
            val profiles = manager?.getProfiles(false) ?: listOf()

            if (profiles.isNotEmpty()) {
                // Use current profile (custom or Profile 0)
                val profileIdStr = container.getExtra("profileId", "0")
                val profileId = profileIdStr.toIntOrNull() ?: 0
                val targetProfile = if (profileId != 0) {
                    manager?.getProfile(profileId)
                } else {
                    null
                } ?: manager?.getProfile(0) ?: profiles.getOrNull(2) ?: profiles.first()

                if (!showElementEditor && !keepPausedForEditor && !showQuickMenu && !isEditMode) {

                    if (!areControlsVisible) {
                        showInputControls(targetProfile, xServerView!!.getxServer().winHandler, container)
                        areControlsVisible = true
                    }

                    PluviaApp.touchpadView?.postDelayed({
                        val view = PluviaApp.touchpadView
                        if (view != null) {
                            // Delay technically not required for the function to work but this can
                            // race against tryCapturePointer() and end up capturing after release
                            // was already called
                            view.releasePointerCapture()
                        }
                    }, 100)
                }
                hasUpdatedScreenGamepad = false
            }
        }
    }

    fun evaluateDevice(device: InputDevice) {
        // Some devices advertise all its capabilities on onInputDeviceAdded callback
        // but some can also do basic advertise on onInputDeviceAdded and only expand on onInputDeviceChanged
        val isExternal = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) device.isExternal else true
        if (!device.isVirtual && isExternal) {
            if (Keyboard.isKeyboardDevice(device)) {
                hasPhysicalKeyboard = true
                if (!showElementEditor && !keepPausedForEditor && !showQuickMenu && !isEditMode &&
                    !container.isTouchscreenMode &&
                    !hasUpdatedScreenGamepad) {
                    hasUpdatedScreenGamepad = true

                    hideInputControls()
                    areControlsVisible = false
                }
            }
            val isMouse = device.supportsSource(InputDevice.SOURCE_MOUSE) ||
                    device.supportsSource(InputDevice.SOURCE_MOUSE_RELATIVE) ||
                    device.supportsSource(InputDevice.SOURCE_TOUCHPAD)
            if (isMouse) {
                hasPhysicalMouse = true
                if (!hasUpdatedScreenGamepad && tryCapturePointer()) {
                    hasUpdatedScreenGamepad = true

                    hideInputControls()
                    areControlsVisible = false
                }
            }
        }
        val isGamepad = ExternalController.isGameController(device)
        if (isGamepad) {
            ControllerManager.getInstance().onDeviceConnected(device.id)
            xServerView?.getxServer()?.winHandler?.setCurrentController(device.id)
            xServerView?.getxServer()?.winHandler?.refreshControllerMappingsForHotplug()
            if (!showElementEditor && !keepPausedForEditor && !showQuickMenu && !isEditMode &&
                !container.isTouchscreenMode &&
                !hasUpdatedScreenGamepad) {
                hasUpdatedScreenGamepad = true

                hideInputControls()
                areControlsVisible = false
            }
        }
    }

    val dismissOverlayMenu: () -> Unit = {
        if (!keyboardRequestedFromOverlay) {
            imeInputReceiver?.hideKeyboard()
        }
        shouldForceResumeOnMenuClose = (keyboardRequestedFromOverlay || bnetSignInRequestedFromOverlay) && manualResumeMode && !keepPausedForEditor
        keyboardRequestedFromOverlay = false
        bnetSignInRequestedFromOverlay = false
        showQuickMenu = false
    }

    LaunchedEffect(showQuickMenu, quickMenuToolsVisible, xServerView) {
        if (!showQuickMenu || !quickMenuToolsVisible) {
            quickMenuWineProcesses = emptyList()
            quickMenuWineProcessesLoading = false
            return@LaunchedEffect
        }

        quickMenuWineProcessesLoading = true
        while (showQuickMenu && quickMenuToolsVisible) {
            quickMenuWineProcesses = withContext(Dispatchers.IO) {
                WineProcessSnapshotHelper.readFromProc()
            }
            quickMenuWineProcessesLoading = false
            delay(QUICK_MENU_PROCESS_POLL_INTERVAL_MS)
        }
    }

    // Shows the soft keyboard, anchored to [anchor]. Handles the Android 12+
    // post-delay quirk and routes input to the external display IME when needed.
    val showSoftKeyboard: (View, String) -> Unit = { anchor, _ ->
        anchor.post {
            if (anchor.windowToken != null) {
                val show = {
                    imeInputReceiver?.showKeyboard() ?: imm.toggleSoftInput(InputMethodManager.SHOW_FORCED, 0)
                }
                if (Build.VERSION.SDK_INT > 29) {
                    anchor.postDelayed({ show() }, 500)  // Pixel/Android-12+ quirk
                } else {
                    show()
                }
            }
        }
    }

    val onQuickMenuItemSelected: (Int) -> Boolean = { itemId ->
        when (itemId) {
            QuickMenuAction.BATTLE_NET_SIGN_IN -> {
                bnetSignInRequestedFromOverlay = true
                app.gamenative.ui.screen.wow.BattleNetSignIn.requested.value = true
                true
            }

            QuickMenuAction.KEYBOARD -> {
                keyboardRequestedFromOverlay = true
                showSoftKeyboard(view, "onscreen_keyboard_enabled")
                true
            }

            QuickMenuAction.INPUT_CONTROLS -> {
                if (areControlsVisible) {
                    hideInputControls()
                } else {
                    val manager = PluviaApp.inputControlsManager
                    val profiles = manager?.getProfiles(false) ?: listOf()
                    if (profiles.isNotEmpty()) {
                        // Use current profile (custom or Profile 0)
                        val profileIdStr = container.getExtra("profileId", "0")
                        val profileId = profileIdStr.toIntOrNull() ?: 0
                        val targetProfile = if (profileId != 0) {
                            manager?.getProfile(profileId)
                        } else {
                            null
                        } ?: manager?.getProfile(0) ?: profiles.getOrNull(2) ?: profiles.first()

                        showInputControls(targetProfile, xServerView!!.getxServer().winHandler, container)
                    }
                }
                areControlsVisible = !areControlsVisible
                true
            }

            QuickMenuAction.DISABLE_MOUSE -> {
                val newValue = !isDisableMouseInput
                isDisableMouseInput = newValue
                container.setDisableMouseInput(newValue)
                container.saveData()
                PluviaApp.touchpadView?.setTouchscreenMouseDisabled(newValue)
                if (newValue) {
                    xServerView?.renderer?.setCursorVisible(false)
                } else {
                    applyMouseCursorVisibility()
                }
                true
            }

            QuickMenuAction.EDIT_CONTROLS -> {
                keepPausedForEditor = true

                // Get or create profile for this container
                val manager = PluviaApp.inputControlsManager ?: InputControlsManager(context)
                val allProfiles = manager.getProfiles(false)

                val profileIdStr = container.getExtra("profileId", "0")
                val profileId = profileIdStr.toIntOrNull() ?: 0

                var activeProfile = if (profileId != 0) {
                    manager.getProfile(profileId)
                } else {
                    null
                }

                // If no custom profile exists, create one automatically
                if (activeProfile == null) {
                    val sourceProfile = manager.getProfile(0)
                        ?: allProfiles.firstOrNull { it.id == 2 }
                        ?: allProfiles.firstOrNull()

                    if (sourceProfile != null) {
                        try {
                            // Create game-specific profile by duplicating Profile 0
                            activeProfile = manager.duplicateProfile(sourceProfile)

                            // Rename to game name
                            val gameName = container.name
                            activeProfile.setName("$gameName - Controls")
                            activeProfile.save()

                            // Associate with container using extraData and save
                            container.putExtra("profileId", activeProfile.id.toString())
                            container.saveData()

                            // Apply the new profile to InputControlsView
                            PluviaApp.inputControlsView?.setProfile(activeProfile)
                            PluviaApp.radialMenuCoordinator?.setProfile(activeProfile)
                            physicalControllerHandler?.setProfile(activeProfile)
                        } catch (e: Exception) {

                            // Fallback to existing profile
                            activeProfile = sourceProfile
                        }
                    }
                }

                // Enable edit mode and show controls if not visible
                if (activeProfile != null) {
                    // Capture snapshot of element positions before entering edit mode
                    val profile = PluviaApp.inputControlsView?.profile
                    if (profile != null) {
                        val snapshot = mutableMapOf<com.winlator.inputcontrols.ControlElement, Pair<Int, Int>>()
                        profile.elements.forEach { element ->
                            snapshot[element] = Pair(element.x.toInt(), element.y.toInt())
                        }
                        elementPositionsSnapshot = snapshot
                    }

                    isEditMode = true
                    PluviaApp.inputControlsView?.setEditMode(true)
                    PluviaApp.inputControlsView?.let { icView ->
                        // Wait for view to be laid out before loading elements
                        icView.post {
                            activeProfile.loadElements(icView)
                        }
                    }

                    if (!areControlsVisible) {
                        showInputControls(activeProfile, xServerView!!.getxServer().winHandler, container)
                        areControlsVisible = true
                    }
                }
                true
            }

            QuickMenuAction.TOUCHSCREEN_MODE -> {
                val newMode = !container.isTouchscreenMode
                container.setTouchscreenMode(newMode)
                container.saveData()
                isTouchscreenModeActive = newMode

                // Notify TouchpadView of the mode change
                PluviaApp.touchpadView?.setTouchscreenMode(newMode)

                if (newMode) {
                    // Apply gesture config when enabling
                    PluviaApp.touchpadView?.setGestureConfig(currentGestureConfig)

                    // Hide on-screen controls (mirrors startup priority logic)
                    if (areControlsVisible) {
                        hideInputControls()
                        areControlsVisible = false
                    }

                    applyMouseCursorVisibility()
                } else {
                    applyMouseCursorVisibility()

                    // Re-evaluate whether to show on-screen controls
                    // (same logic as scanForExternalDevices startup path)
                    if (!hasPhysicalController && !hasPhysicalKeyboard &&
                        !hasPhysicalMouse && !hasInternalTouchpad) {
                        val manager = PluviaApp.inputControlsManager
                        val profiles = manager?.getProfiles(false) ?: listOf()
                        if (profiles.isNotEmpty() && !areControlsVisible) {
                            val profileIdStr = container.getExtra("profileId", "0")
                            val profileId = profileIdStr.toIntOrNull() ?: 0
                            val targetProfile = if (profileId != 0) {
                                manager?.getProfile(profileId)
                            } else {
                                null
                            } ?: manager?.getProfile(0) ?: profiles.getOrNull(2) ?: profiles.first()
                            showInputControls(targetProfile, xServerView!!.getxServer().winHandler, container)
                            areControlsVisible = true
                        }
                    }
                }
                false
            }


            QuickMenuAction.EDIT_PHYSICAL_CONTROLLER -> {
                keepPausedForEditor = true
                showPhysicalControllerDialog = true
                true
            }

            QuickMenuAction.RADIAL_MENU -> {
                PluviaApp.radialMenuCoordinator?.showSettingsDialog() == true
            }

            QuickMenuAction.PERFORMANCE_HUD -> {
                val enabled = !isPerformanceHudEnabled
                isPerformanceHudEnabled = enabled
                PrefManager.showFps = enabled
                updatePerformanceHud(enabled)
                false
            }

            QuickMenuAction.EXIT_GAME -> {
                imeInputReceiver?.hideKeyboard()
                // Resume processes before exiting so they can receive SIGTERM cleanly.
                // Don't resume audio to avoid resume->suspend race condition causing ANR.
                if (PluviaApp.isOverlayPaused && !neverSuspend) {
                    PluviaApp.xEnvironment?.resumeGameProcesses()
                }
                clearOverlayPauseState()
                exit(session, xServerView?.getxServer()?.winHandler, frameRating, container, appId, onExit, navigateBack, "quick_menu")
                true
            }

            else -> false
        }
    }

    val gameBack: () -> Unit = gameBack@{
        val imeVisible = ViewCompat.getRootWindowInsets(view)
            ?.isVisible(WindowInsetsCompat.Type.ime()) == true

        if (imeVisible) {
            imeInputReceiver?.hideKeyboard()
            view.post {
                if (Build.VERSION.SDK_INT >= 30) {
                    view.windowInsetsController?.hide(WindowInsets.Type.ime())
                } else {
                    val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                    if (view.windowToken != null) imm.hideSoftInputFromWindow(view.windowToken, 0)
                }
            }
            return@gameBack
        }

        if (showQuickMenu) {
            dismissOverlayMenu()
            return@gameBack
        }

        val controllerManager = ControllerManager.getInstance()
        controllerManager.scanForDevices()
        hasPhysicalController = controllerManager.getDetectedDevices().isNotEmpty()
        PluviaApp.touchpadView?.postDelayed({
            val view = PluviaApp.touchpadView
            if (view != null) {
                // Delay technically not required for the function to work but this can
                // race against tryCapturePointer() and end up capturing after release
                // was already called
                view.releasePointerCapture()
            }
        }, 100)

        // A key held in the game would never see its release while the menu has the keyboard.
        releaseGuestKeys(xServerView)
        showQuickMenu = true
    }

    DisposableEffect(Unit) {
        val inputManager = context.getSystemService(Context.INPUT_SERVICE) as InputManager

        val deviceListener = object : InputManager.InputDeviceListener {
            override fun onInputDeviceAdded(deviceId: Int) {
                ControllerManager.getInstance().onDeviceConnected(deviceId)
                scanForExternalDevices()
                val device = InputDevice.getDevice(deviceId) ?: return
                evaluateDevice(device)
            }

            override fun onInputDeviceRemoved(deviceId: Int) {
                physicalControllerHandler?.onInputDeviceRemoved(deviceId)
                ControllerManager.getInstance().onDeviceDisconnected(deviceId)
                scanForExternalDevices()
            }

            override fun onInputDeviceChanged(deviceId: Int) {
                ControllerManager.getInstance().onDeviceConnected(deviceId)
                scanForExternalDevices()
                val device = InputDevice.getDevice(deviceId) ?: return
                evaluateDevice(device)
            }
        }

        inputManager.registerInputDeviceListener(deviceListener, null)
        ControllerManager.getInstance().resetSessionActivity()
        scanForExternalDevices()

        onDispose {
            inputManager.unregisterInputDeviceListener(deviceListener)
        }
    }

    // Modern only: API 33+ routes gesture-back through OnBackInvokedDispatcher,
    // which no longer delivers KEYCODE_BACK to MainActivity.dispatchKeyEvent.
    // BackHandler registers with OnBackPressedDispatcher to catch that path.
    BackHandler(enabled = true) { gameBack() }

    DisposableEffect(container) {
        registerBackAction(gameBack)
        onDispose {
            removePerformanceHud()
            performanceHudHost = null
            imeInputReceiver?.hideKeyboard()
            imeInputReceiver = null
            if (!PluviaApp.keepAlive) {
                PluviaApp.clearActiveSuspendState()
            } else if (!manualResumeMode) {
                PluviaApp.isOverlayPaused = false
            }
            registerBackAction { }
        }   // preserve suspend state across activity recreation while a game is still running
    }

    // Event handlers defined in composable scope to capture latest state on each recomposition
    val onActivityDestroyed: (AndroidEvent.ActivityDestroyed) -> Unit = {

        exit(session, xServerView?.getxServer()?.winHandler, frameRating, container, appId, onExit, navigateBack, "activity_destroyed")
    }
    val onKeyEvent: (AndroidEvent.KeyEvent) -> Boolean = keyEvent@{
        if (session.isExiting) return@keyEvent false
        val isKeyboard = Keyboard.isKeyboardDevice(it.event.device)
        val isPhysicalKeyboard = isKeyboard && it.event.device?.isVirtual != true
        val isGamepad = ExternalController.isGameController(it.event.device)
        val waitingForManualResume =
            manualResumeMode &&
                PluviaApp.isOverlayPaused &&
                !showQuickMenu &&
                !keepPausedForEditor

        if (waitingForManualResume) {
            when (it.event.keyCode) {
                KeyEvent.KEYCODE_ENTER,
                KeyEvent.KEYCODE_BUTTON_A,
                KeyEvent.KEYCODE_BUTTON_START -> {
                    if (it.event.action == KeyEvent.ACTION_DOWN && it.event.repeatCount == 0) {
                        resumeFromManualButton()
                    }
                    true
                }
                else -> false
            }
        } else if ((showElementEditor || keepPausedForEditor || showQuickMenu || isEditMode) && (isGamepad || isKeyboard)) {
            val escPressed = !keepPausedForEditor &&
                isKeyboard &&
                it.event.keyCode == KeyEvent.KEYCODE_ESCAPE
            if (escPressed) {
                keyboardEscMenuHandler.handleOverlayEsc(it.event, keyboard)
                if (it.event.action == KeyEvent.ACTION_DOWN && it.event.repeatCount == 0) {
                    (context as? ComponentActivity)?.onBackPressedDispatcher?.onBackPressed()
                }
                true
            } else {
                // Let Compose focus system handle keyboard and gamepad navigation/selection while menu is visible.
                false
            }
        } else {
            var handled = false
            if (isGamepad) {
                val winHandler = xServerView?.getxServer()?.winHandler ?: return@keyEvent false
                if (it.event.action == KeyEvent.ACTION_DOWN && it.event.repeatCount == 0 &&
                    ControllerManager.getInstance().noteGamepadButton(it.event.device.id)
                ) {
                    winHandler.refreshControllerMappingsForHotplug()
                }
                val assignedSlot = ControllerManager.getInstance().getSlotForDevice(it.event.device.id)
                if (assignedSlot > 0) {
                    handled = winHandler.onKeyEvent(it.event)
                } else {
                    winHandler.setCurrentController(it.event.device.id)
                    handled = physicalControllerHandler?.onKeyEvent(it.event) == true
                    if (!handled) handled = PluviaApp.inputControlsView?.onKeyEvent(it.event) == true
                    // Final fallback to WinHandler passthrough
                    if (!handled) handled = winHandler.onKeyEvent(it.event)
                }
            }
            if (!handled && isKeyboard) {
                val isShiftEscPressed = it.event.keyCode == KeyEvent.KEYCODE_ESCAPE &&
                    it.event.isShiftPressed &&
                    it.event.action == KeyEvent.ACTION_DOWN &&
                    it.event.repeatCount == 0
                if (isShiftEscPressed &&
                    !showElementEditor && !keepPausedForEditor && !showQuickMenu && !isEditMode) {
                    keyboardEscMenuHandler.cancel()
                    gameBack()
                    handled = true
                } else if (isPhysicalKeyboard && keyboardEscMenuHandler.isEsc(it.event) &&
                    !showElementEditor && !keepPausedForEditor && !showQuickMenu && !isEditMode) {
                    handled = keyboardEscMenuHandler.handleGameEsc(
                        event = it.event,
                        keyboard = keyboard,
                        canOpenMenu = { !showElementEditor && !keepPausedForEditor && !showQuickMenu && !isEditMode },
                        openMenu = gameBack,
                    )
                } else {
                    if (it.event.device?.isVirtual == true) {
                        handled = keyboard?.onVirtualKeyEvent(it.event) == true
                    } else {
                        handled = keyboard?.onKeyEvent(it.event) == true
                    }
                }
            }
            handled
        }
    }

    val onMotionEvent: (AndroidEvent.MotionEvent) -> Boolean = motionEvent@{
        if (session.isExiting) return@motionEvent false
        val isGamepad = ExternalController.isGameController(it.event?.device)

        if ((showElementEditor || keepPausedForEditor || showQuickMenu || isEditMode) && isGamepad) {
            // Let Compose consume any gamepad motion while menu is visible.
            false
        } else {
            var handled = false
            if (isGamepad && it.event != null) {
                val winHandler = xServerView?.getxServer()?.winHandler ?: return@motionEvent false
                ControllerManager.getInstance().noteGamepadActivity(it.event)
                val assignedSlot = ControllerManager.getInstance().getSlotForDevice(it.event.device.id)
                if (assignedSlot > 0) {
                    handled = winHandler.onGenericMotionEvent(it.event)
                } else {
                    winHandler.setCurrentController(it.event.device.id)
                    handled = physicalControllerHandler?.onGenericMotionEvent(it.event!!) == true
                    if (!handled) handled = PluviaApp.inputControlsView?.onGenericMotionEvent(it.event) == true
                    // Final fallback to WinHandler passthrough
                    if (!handled) handled = winHandler.onGenericMotionEvent(it.event)
                }
            }
            if (PluviaApp.touchpadView?.hasPointerCapture() != true && !PluviaApp.isOverlayPaused) {
                if ((it.event != null) && (it.event.device != null)) {
                    val device = it.event.device
                    val isExternal = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) device.isExternal else true
                    if (device.supportsSource(InputDevice.SOURCE_TOUCHPAD) &&
                        !isExternal) {
                        // Samsung DeX Touchpad app
                        hasInternalTouchpad = true
                        if (!showElementEditor && !keepPausedForEditor && !showQuickMenu && !isEditMode &&
                            !hasUpdatedScreenGamepad) {
                            hasUpdatedScreenGamepad = true
                            hideInputControls()
                            areControlsVisible = false
                        }
                    }
                    tryCapturePointer()
                }
            }
            val event = it.event
            val device = event?.device
            if (!usingScreenMirror && device?.name == "scrcpy") {
                usingScreenMirror = true
            }
            handled
        }
    }
    // Process completion arrives on a worker thread, and can arrive after view disposal.
    val finishSessionOnMain: (String) -> Unit = { reason ->
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        session.dispatchIfActive({ handler.post(it) }) {
            exit(session, xServerView?.getxServer()?.winHandler, frameRating, container, appId, onExit, navigateBack, reason)
        }
    }
    val onGuestProgramTerminated: (AndroidEvent.GuestProgramTerminated) -> Unit = {
        finishSessionOnMain("guest_terminated")
    }
    val onForceCloseApp: (AndroidEvent.ForceCloseApp) -> Unit = {
        finishSessionOnMain("force_close")
    }

    DisposableEffect(Unit) {
        PluviaApp.events.on<AndroidEvent.ActivityDestroyed, Unit>(onActivityDestroyed)
        PluviaApp.events.on<AndroidEvent.KeyEvent, Boolean>(onKeyEvent)
        PluviaApp.events.on<AndroidEvent.MotionEvent, Boolean>(onMotionEvent)
        PluviaApp.events.on<AndroidEvent.GuestProgramTerminated, Unit>(onGuestProgramTerminated)
        PluviaApp.events.on<AndroidEvent.ForceCloseApp, Unit>(onForceCloseApp)

        onDispose {
            PluviaApp.events.off<AndroidEvent.ActivityDestroyed, Unit>(onActivityDestroyed)
            PluviaApp.events.off<AndroidEvent.KeyEvent, Boolean>(onKeyEvent)
            PluviaApp.events.off<AndroidEvent.MotionEvent, Boolean>(onMotionEvent)
            PluviaApp.events.off<AndroidEvent.GuestProgramTerminated, Unit>(onGuestProgramTerminated)
            PluviaApp.events.off<AndroidEvent.ForceCloseApp, Unit>(onForceCloseApp)

        }
    }

    DisposableEffect(lifecycleOwner, xServerView) {
        val currentXServerView = xServerView
        val currentXServerViewAsView = currentXServerView as? View
        if (currentXServerView == null || currentXServerViewAsView == null) {
            onDispose { }
        } else {
            fun syncRendererToCurrentLifecycleState() {
                val lifecycleState = lifecycleOwner.lifecycle.currentState
                if (lifecycleState == Lifecycle.State.DESTROYED) {
                }
                if (!currentXServerViewAsView.isAttachedToWindow) return

                when {
                    lifecycleState == Lifecycle.State.DESTROYED -> {
                        currentXServerView.onPause()
                    }
                    lifecycleState.isAtLeast(Lifecycle.State.RESUMED) -> {

                        currentXServerView.onResume()
                    }
                    else -> {

                        currentXServerView.onPause()
                    }
                }
            }

            val observer = LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_PAUSE,
                    Lifecycle.Event.ON_RESUME -> {

                        syncRendererToCurrentLifecycleState()
                        if (event == Lifecycle.Event.ON_RESUME) {
                            xServerView?.getxServer()?.winHandler?.reassertPrimaryController()
                        }
                    }
                    else -> Unit
                }
            }
            val attachStateListener = object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) {
                    syncRendererToCurrentLifecycleState()
                }

                override fun onViewDetachedFromWindow(v: View) = Unit
            }

            lifecycleOwner.lifecycle.addObserver(observer)
            currentXServerViewAsView.addOnAttachStateChangeListener(attachStateListener)
            syncRendererToCurrentLifecycleState()
            onDispose {
                currentXServerViewAsView.removeOnAttachStateChangeListener(attachStateListener)
                lifecycleOwner.lifecycle.removeObserver(observer)
            }
        }
    }

    DisposableEffect(lifecycleOwner, performanceHudView) {
        val hud = performanceHudView
        if (hud != null) {
            if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                hud.resume()
            } else {
                hud.pause()
            }

            val observer = LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_PAUSE -> {

                        hud.pause()
                    }
                    Lifecycle.Event.ON_RESUME -> {

                        hud.resume()
                    }
                    else -> Unit
                }
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose {
                lifecycleOwner.lifecycle.removeObserver(observer)
            }
        } else {
            onDispose { }
        }
    }

    val isPortrait = container.isPortraitMode
    Box(modifier = Modifier.fillMaxSize()) {
        key(isPortrait) {
        AndroidView(
        modifier = Modifier
            .fillMaxSize()
            .pointerHoverIcon(PointerIcon.Default)
            .pointerInteropFilter { event ->
                val hud = performanceHudView
                if (hud != null) {
                    when (event.actionMasked) {
                        MotionEvent.ACTION_DOWN -> {
                            if (hud.isShown && hud.width > 0 && hud.height > 0 && !PrefManager.performanceHudLocked) {
                                val hudLocation = IntArray(2)
                                hud.getLocationOnScreen(hudLocation)
                                val insideHud =
                                    event.rawX >= hudLocation[0] &&
                                        event.rawX <= hudLocation[0] + hud.width &&
                                        event.rawY >= hudLocation[1] &&
                                        event.rawY <= hudLocation[1] + hud.height
                                if (insideHud) {
                                    performanceHudTouchDownRawX = event.rawX
                                    performanceHudTouchDownRawY = event.rawY
                                    performanceHudDragOffsetX = event.rawX - hudLocation[0]
                                    performanceHudDragOffsetY = event.rawY - hudLocation[1]
                                    isTrackingPerformanceHudTouch = true
                                    isDraggingPerformanceHud = false
                                    return@pointerInteropFilter true
                                }
                            }
                        }
                        MotionEvent.ACTION_MOVE -> {
                            if (isTrackingPerformanceHudTouch) {
                                if (!isDraggingPerformanceHud) {
                                    val deltaX = event.rawX - performanceHudTouchDownRawX
                                    val deltaY = event.rawY - performanceHudTouchDownRawY
                                    val distanceSquared = (deltaX * deltaX) + (deltaY * deltaY)
                                    if (distanceSquared >= performanceHudTouchSlop * performanceHudTouchSlop) {
                                        isDraggingPerformanceHud = true
                                    }
                                }
                                if (isDraggingPerformanceHud) {
                                    movePerformanceHud(event.rawX, event.rawY, save = false)
                                    return@pointerInteropFilter true
                                }
                            }
                        }
                        MotionEvent.ACTION_POINTER_DOWN,
                        MotionEvent.ACTION_POINTER_UP,
                        -> {
                            if (isTrackingPerformanceHudTouch || isDraggingPerformanceHud) {
                                isTrackingPerformanceHudTouch = false
                                isDraggingPerformanceHud = false
                                return@pointerInteropFilter true
                            }
                        }
                        MotionEvent.ACTION_UP -> {
                            if (isTrackingPerformanceHudTouch) {
                                if (isDraggingPerformanceHud) {
                                    movePerformanceHud(event.rawX, event.rawY, save = true)
                                } else {
                                    hud.performClick()
                                    togglePerformanceHudLayout()
                                }
                                isTrackingPerformanceHudTouch = false
                                isDraggingPerformanceHud = false
                                return@pointerInteropFilter true
                            }
                        }
                        MotionEvent.ACTION_CANCEL -> {
                            if (isTrackingPerformanceHudTouch || isDraggingPerformanceHud) {
                                if (isDraggingPerformanceHud) {
                                    movePerformanceHud(event.rawX, event.rawY, save = true)
                                }
                                isTrackingPerformanceHudTouch = false
                                isDraggingPerformanceHud = false
                                return@pointerInteropFilter true
                            }
                        }
                    }
                }

                val radialMenuHandled = PluviaApp.radialMenuCoordinator?.onHostTouchEvent(event) == true
                if (radialMenuHandled) return@pointerInteropFilter true

                val overlayHandled = swapInputOverlay
                    ?.takeIf { it.visibility == View.VISIBLE }
                    ?.dispatchTouchEvent(event) == true
                if (overlayHandled) return@pointerInteropFilter true

                if (isPortrait) {
                    gameRoot?.dispatchTouchEvent(event)
                } else {
                    val controlsHandled = if (areControlsVisible) {
                        PluviaApp.inputControlsView?.onTouchEvent(event) ?: false
                    } else {
                        false
                    }
                    if (!controlsHandled) {
                        PluviaApp.touchpadView?.onTouchEvent(event)
                    }
                }
                true
            },
        factory = { context ->

            val dm = context.resources.displayMetrics
            val screenWidth = if (isPortrait) minOf(dm.widthPixels, dm.heightPixels) else dm.widthPixels
            val controlsHeightPortrait = screenWidth * 9 / 16
            val mainRoot = if (isPortrait) {
                LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    setBackgroundColor(Color.TRANSPARENT)
                }
            } else {
                FrameLayout(context)
            }
            val frameLayout = if (isPortrait) {
                val top = FrameLayout(context)
                top.setBackgroundColor(Color.BLACK)
                mainRoot.addView(top, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
                top
            } else {
                mainRoot as FrameLayout
            }
            performanceHudHost = frameLayout
            val appId = appId
            val existingXServer =
                PluviaApp.xEnvironment
                    ?.getComponent<XServerComponent>(XServerComponent::class.java)
                    ?.xServer
            com.winlator.core.DisplayThreadPriority.configure(container.id == "wow_forever" &&
                app.gamenative.ui.screen.wow.WowLaunchPolicy.enabled(context,"priority"))
            val xServerToUse = existingXServer ?: XServer(
                ScreenInfo(xServerState.value.screenSize),
                false,
            )
            // The Vulkan renderer is the only compositor (the OpenGL/VirGL path was removed in v0.1.117).
            val xServerViewInstance: XServerRendererView = XServerView(context, xServerToUse, container.displayRenderer)
            val xServerView = xServerViewInstance.apply {
                xServerView = this
                FrameRating.setLimitHint(app.gamenative.ui.screen.wow.WowRuntimeTuning.live(wowApiPacing, if (fpsLimiterEnabled) fpsLimiterTarget else 0).inForce)
                setFrameRateLimit(if (container.id == "wow_forever") app.gamenative.ui.screen.wow.WowRuntimeTuning.surfaceTarget(container, if (fpsLimiterEnabled) fpsLimiterTarget else 0) else if (fpsLimiterEnabled) fpsLimiterTarget else 0)
                val renderer = this.renderer
                if (renderer is VulkanRenderer) {
                    renderer.setSchedulingHints(
                        container.id == "wow_forever" && app.gamenative.ui.screen.wow.WowLaunchPolicy.enabled(context,"priority"),
                        container.id == "wow_forever" && android.os.Build.VERSION.SDK_INT >= 33 && app.gamenative.ui.screen.wow.WowLaunchPolicy.enabled(context,"adpf"),
                    )
                    val pm = if (container.id == "wow_forever") app.gamenative.ui.screen.wow.WowRuntimeTuning.presentMode(context, container) else container.rendererPresentMode.ifEmpty { "fifo" }
                    val vkMode = when (pm.lowercase(Locale.getDefault())) {
                        "mailbox" -> 1
                        "immediate" -> 0
                        "relaxed", "fifo_relaxed" -> 3
                        else -> 2
                    }
                    renderer.setVkPresentMode(vkMode)
                    renderer.setFifoLatencyDrain(if (container.id == "wow_forever") app.gamenative.ui.screen.wow.WowRuntimeTuning.fifoDrainMode(context) else 0)
                    renderer.setPresentIdleTracking(container.id == "wow_forever" && app.gamenative.ui.screen.wow.WowLaunchPolicy.enabled(context, "presentidle"))
                    (this as? XServerView)?.setHardwareScale(container.id == "wow_forever" &&
                        app.gamenative.ui.screen.wow.WowHardwareScale.requested(context))
                }
                applyMouseCursorVisibility()
                renderer.setOnFrameRenderedListener {
                    app.gamenative.ui.screen.wow.WowDiagnostics.rendererFrame()
                    if (shouldTrackDisplayedFrames.get()) {
                        // FrameRating collects on this thread and posts at most one UI update.
                        // Never enqueue a main-thread task for every presented frame.
                        frameRating?.updateNanos(SystemClock.elapsedRealtimeNanos())
                    }
                }
                getxServer().renderer = renderer
                PluviaApp.touchpadView = TouchpadView(context, getxServer(), PrefManager.getBoolean("capture_pointer_on_external_mouse", true))
                PluviaApp.touchpadView?.setMoveCursorToTouchpoint(PrefManager.getBoolean("move_cursor_to_touchpoint", false))

                // Wire keyboard toggle callback for gesture "Show Keyboard" action.
                // Mirrors the QuickMenuAction.KEYBOARD external-display routing
                // (uses imeInputReceiver on external displays) but skips the
                // 500ms post-delay used by the menu path — a gesture is already
                // a direct touch interaction and should respond immediately.
                PluviaApp.touchpadView?.setShowKeyboardCallback {
                    val anchor = PluviaApp.touchpadView ?: return@setShowKeyboardCallback
                    anchor.post {
                        if (anchor.windowToken == null) return@post
                        imeInputReceiver?.showKeyboard()
                            ?: imm.toggleSoftInput(InputMethodManager.SHOW_FORCED, 0)
                    }
                }

                // Wire click highlight + gesture debug listener
                PluviaApp.touchpadView?.setClickHighlightListener(object : com.winlator.widget.TouchpadView.ClickHighlightListener {
                    override fun onClickAt(screenX: Float, screenY: Float) {
                        PluviaApp.touchpadView?.post {
                            if (currentGestureConfig.showClickHighlight) {
                                clickHighlightPoints.add(
                                    app.gamenative.ui.component.HighlightPoint(
                                        screenX, screenY,
                                        androidx.compose.animation.core.Animatable(0.5f),
                                    ),
                                )
                            }
                        }
                    }
                    override fun onGestureTriggered(gestureName: String) {
                        PluviaApp.touchpadView?.post {
                            if (currentGestureConfig.showGestureDebugOverlay) {
                                debugGestureName = gestureName
                                debugGestureKey++
                            }
                        }
                    }
                })

                // Add invisible IME receiver to capture system keyboard input when keyboard is on external display
                val imeDisplayContext = context.display?.let { display ->
                    context.createDisplayContext(display)
                } ?: context

                val imeReceiver = app.gamenative.externaldisplay.IMEInputReceiver(
                    context = context,
                    displayContext = imeDisplayContext,
                    xServer = getxServer(),
                ).apply {
                    layoutParams = android.widget.FrameLayout.LayoutParams(
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                    alpha = 0f
                    isClickable = false
                }
                frameLayout.addView(imeReceiver)
                imeInputReceiver = imeReceiver
                PluviaApp.imeInputReceiver = imeReceiver

                getxServer().winHandler = WinHandler.attachOrCreate(getxServer(), this, existingXServer != null)
                win32AppWorkarounds = Win32AppWorkarounds(getxServer())
                keyboard = Keyboard(getxServer())
                renderer.setUnviewableWMClasses("explorer.exe")
                if (container.executablePath.isNotBlank()) {
                    renderer.forceFullscreenWMClass = Paths.get(container.executablePath).name
                }
                // Remove any previous listener before adding a new one (handles key(isPortrait) recreation)
                windowModificationListener?.let {
                    getxServer().windowManager.removeOnWindowModificationListener(it)
                }

                val wmListener = object : WindowManager.OnWindowModificationListener {

                        private fun findTopmostApplicationWindow(window: Window): Window? {
                            val children = window.children
                            for (i in children.indices.reversed()) {
                                val child = children[i]
                                if (!child.attributes.isMapped()) continue
                                val topmostInChild = findTopmostApplicationWindow(child)
                                if (topmostInChild != null) return topmostInChild
                                if (child.isApplicationWindow() && child.isRenderable()) {
                                    return child
                                }
                            }
                            return null
                        }

                        private fun refreshFrameRatingTracking() {
                            val rating = frameRating ?: return
                            val topmost = findTopmostApplicationWindow(getxServer().windowManager.rootWindow)
                            val nextId = topmost?.id ?: -1
                            if (frameRatingWindowId == nextId) return

                            if (topmost == null) {
                                if (frameRatingWindowId != -1) {

                                }
                                frameRatingWindowId = -1
                                (renderer as? VulkanRenderer)?.setHudWindowId(-1)
                                (context as? Activity)?.runOnUiThread {
                                    rating.visibility = View.GONE
                                }
                                return
                            }

                            frameRatingWindowId = nextId
                            (renderer as? VulkanRenderer)?.setHudWindowId(nextId)

                            (context as? Activity)?.runOnUiThread {
                                rating.reset()
                                rating.visibility = View.VISIBLE
                            }
                        }

                        override fun onUpdateWindowContent(window: Window) {
                            if (!xServerState.value.winStarted && window.isApplicationWindow()) {
                                if (shouldShowMouseCursor()) renderer?.setCursorVisible(true)
                                xServerState.value.winStarted = true
                            }
                            if (frameRatingWindowId == -1 && window.isApplicationWindow()) {
                                refreshFrameRatingTracking()
                            }
                            if (window.id == frameRatingWindowId) {
                                frameRating?.updateNanos(SystemClock.elapsedRealtimeNanos())
                            }
                        }

                        override fun onModifyWindowProperty(window: Window, property: Property) {
                            if (window.id == frameRatingWindowId || window.isApplicationWindow()) {
                                refreshFrameRatingTracking()
                            }
                        }

                        override fun onMapWindow(window: Window) {

                            refreshFrameRatingTracking()
                            win32AppWorkarounds?.applyWindowWorkarounds(window)
                            val targetExecutable = extractExecutableBasename(container.executablePath)
                            val isGameWindow = (targetExecutable.isNotBlank() && windowMatchesExecutable(window, targetExecutable)) ||
                                    (window.isApplicationWindow() && !window.name.equals("shell", ignoreCase = true) && !window.className.contains("explorer", ignoreCase = true))
                            if (isGameWindow) {

                                onWindowMapped?.invoke(context, window)
                            }
                        }

                        override fun onUnmapWindow(window: Window) {

                            refreshFrameRatingTracking()
                            startExitWatchForUnmappedGameWindow(window)
                            onWindowUnmapped?.invoke(window)
                        }

                        override fun onChangeWindowZOrder(window: Window) {
                            refreshFrameRatingTracking()
                        }

                        override fun onUpdateWindowGeometry(window: Window, resized: Boolean) {
                            if (window.id == frameRatingWindowId || window.isApplicationWindow()) {
                                refreshFrameRatingTracking()
                            }
                        }
                    }
                getxServer().windowManager.addOnWindowModificationListener(wmListener)
                windowModificationListener = wmListener
                mainRoot.tag = XServerViewReleaseBinding(this, wmListener, screenWidth = screenWidth)

                if (PluviaApp.xEnvironment == null) {
                    // Launch all blocking wine setup operations on a background thread to avoid blocking main thread
                    val setupExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
                        Thread(r, "WineSetup-Thread").apply { isDaemon = false }
                    }

                    setupExecutor.submit {
                        var preparedEnvironment: XEnvironment? = null
                        try {
                            if (session.isExiting) return@submit
                            val containerManager = ContainerManager(context)
                            // Configure WinHandler with container's input API settings
                            val handler = getxServer().winHandler
                            if (container.inputType !in 0..3) {
                                container.inputType = PreferredInputApi.BOTH.ordinal
                                container.saveData()
                            }
                            handler.setPreferredInputApi(PreferredInputApi.values()[container.inputType])
                            handler.setDInputMapperType(container.dinputMapperType)
                            if (container.isDisableMouseInput()) {
                                PluviaApp.touchpadView?.setTouchscreenMouseDisabled(true)
                            } else if (container.isTouchscreenMode()) {
                                PluviaApp.touchpadView?.setTouchscreenMode(true)
                                // Apply per-game gesture configuration
                                val gestureConfig = app.gamenative.data.TouchGestureConfig.fromJson(container.getGestureConfig())
                                PluviaApp.touchpadView?.setGestureConfig(gestureConfig)
                            }

                            containerManager.activateContainer(container)
                            app.gamenative.ui.screen.wow.WowLaunchProgress.reach(0.26f, 0.34f, 2500)

                            val imageFs = ImageFs.find(context)

                            taskAffinityMask = ProcessHelper.getAffinityMask(container.getCPUList(true)).toShort().toInt()
                            taskAffinityMaskWoW64 = ProcessHelper.getAffinityMask(container.getCPUListWoW64(true)).toShort().toInt()
                            win32AppWorkarounds?.setTaskAffinityMasks(taskAffinityMask, taskAffinityMaskWoW64)
                            val appliedVariantSeen = container.getExtra("appliedContainerVariant")
                            val appliedWineVersionSeen = container.getExtra("appliedWineVersion")
                            val markersAvail = appliedVariantSeen.isNotEmpty() && appliedWineVersionSeen.isNotEmpty()
                            val variantMismatch = markersAvail && container.containerVariant != appliedVariantSeen
                            val wineVersionMismatch = markersAvail && container.wineVersion != appliedWineVersionSeen
                            val imgVersionMismatch = container.getExtra("imgVersion") != imageFs.getVersion().toString()
                            containerVariantChanged = variantMismatch || wineVersionMismatch || imgVersionMismatch
                            firstTimeBoot = container.getExtra("appVersion").isEmpty() || containerVariantChanged
                            needsUnpacking = container.isNeedsUnpacking

                            val wineVersion = container.wineVersion

                            val contentsManager = ContentsManager(context)
                            contentsManager.syncContents()
                            val resolvedWineInfo = WineInfo.fromIdentifier(context, contentsManager, wineVersion)

                            xServerState.value = xServerState.value.copy(
                                wineInfo = resolvedWineInfo,
                            )

                            if (!xServerState.value.wineInfo.isMainWineVersion()) {

                                imageFs.setWinePath(xServerState.value.wineInfo.path)
                            } else {
                                imageFs.setWinePath(imageFs.rootDir.path + "/opt/wine")
                            }

                            val onExtractFileListener = if (!xServerState.value.wineInfo.isWin64) {
                                object : OnExtractFileListener {
                                    override fun onExtractFile(destination: File?, size: Long): File? {
                                        return destination?.path?.let {
                                            if (it.contains("system32/")) {
                                                null
                                            } else {
                                                File(it.replace("syswow64/", "system32/"))
                                            }
                                        }
                                    }
                                }
                            } else {
                                null
                            }

                            val envVars = EnvVars()

                            app.gamenative.ui.screen.wow.WowLaunchProgress.reach(0.34f, 0.50f, 5000)
                            runBlocking {
                                setupWineSystemFiles(
                                    context,
                                    firstTimeBoot,
                                    xServerView!!.getxServer().screenInfo,
                                    xServerState,
                                    container,
                                    containerManager,
                                    envVars,
                                    contentsManager,
                                    onExtractFileListener,
                                )
                            }

                            app.gamenative.ui.screen.wow.WowLaunchProgress.reach(0.52f, 0.60f, 2500)
                            runBlocking {
                                extractGraphicsDriverFiles(
                                    context,
                                    xServerState.value.graphicsDriver,
                                    xServerState.value.dxwrapper,
                                    xServerState.value.dxwrapperConfig!!,
                                    container,
                                    envVars,
                                    firstTimeBoot,
                                )
                            }

                            selectWinePulseDriver(container, ImageFs.find(context))
                            setImagefsContainerVariant(context, container)
                            if (session.isExiting) return@submit
                            app.gamenative.ui.screen.wow.WowLaunchProgress.reach(0.62f, 0.68f, 2000)
                            preparedEnvironment = setupXEnvironment(
                                session,
                                context,
                                appId,
                                xServerState,
                                envVars,
                                container,
                                xServerView!!.getxServer(),
                                containerVariantChanged,
                                onGameLaunchError,
                                isOffline,
                                contentsManager,
                            )

                            if (session.isExiting) {
                                preparedEnvironment.stopEnvironmentComponents()
                                return@submit
                            }
                            PluviaApp.xEnvironment = preparedEnvironment
                            // Wine and the game are starting: from here the bar only waits for the game's own window.
                            app.gamenative.ui.screen.wow.WowLaunchProgress.reach(0.70f, 0.97f, 9000)
                            if (!PluviaApp.isActivityInForeground && !neverSuspend) {
                                PluviaApp.xEnvironment?.onPause()
                                if (manualResumeMode) {
                                    view.post {
                                        PluviaApp.isOverlayPaused = true

                                    }
                                } else {

                                }
                            }
                        } catch (e: Exception) {
                            try {
                                preparedEnvironment?.stopEnvironmentComponents()
                            } catch (cleanupEx: Exception) { }
                            if (PluviaApp.xEnvironment === preparedEnvironment) PluviaApp.xEnvironment = null
                            if (!session.isExiting) onGameLaunchError?.invoke("Failed to setup wine: ${e.message}")
                        } finally {
                            setupExecutor.shutdown()
                        }
                    }
                }
            }
            PluviaApp.xServerView = xServerView

            val gameHost = FrameLayout(context).apply {
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    portraitGameHostHeight(
                        isPortrait,
                        screenWidth,
                        frameLayout.height,
                        container.screenSize,
                    ),
                )
            }
            frameLayout.addView(gameHost)
            gameHost.addView(xServerView as View)
            (mainRoot.tag as? XServerViewReleaseBinding)?.let { binding ->
                binding.gameHost = gameHost
                val layoutListener = OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                    updatePortraitGameHostHeight(
                        gameHost,
                        isPortrait,
                        container.isPortraitBelowCutout,
                        screenWidth,
                        container.screenSize,
                    )
                }
                frameLayout.addOnLayoutChangeListener(layoutListener)
                binding.gameHostLayoutListener = layoutListener
                frameLayout.post {
                    updatePortraitGameHostHeight(
                        gameHost,
                        isPortrait,
                        container.isPortraitBelowCutout,
                        screenWidth,
                        container.screenSize,
                    )
                }
            }
            val touchpadHost = if (isPortrait) gameHost else frameLayout
            touchpadHost.addView(PluviaApp.touchpadView)

            PluviaApp.inputControlsManager = InputControlsManager(context)
            RadialMenuCoordinator.install(
                context = context,
                host = mainRoot,
                anchor = view,
                container = container,
                xServer = xServerView.getxServer(),
                gameNameProvider = { container.name },
                showKeyboard = showSoftKeyboard,
                openQuickMenu = { showQuickMenu = true },
                onSettingsVisibilityChanged = { visible ->
                    keepPausedForEditor = visible
                    if (!visible) resumeIfAllowedAfterOverlay()
                },
            )

            // Store the loaded profile for auto-show logic later (declared outside apply block)
            var loadedProfile: ControlsProfile? = null

            // Create InputControlsView and add to FrameLayout
            val icView = InputControlsView(context).apply {
                // Configure InputControlsView
                setXServer(xServerView.getxServer())
                setTouchpadView(PluviaApp.touchpadView)

                // Load profile for this container
                val manager = PluviaApp.inputControlsManager
                val profiles = manager?.getProfiles(false) ?: listOf()
                PrefManager.init(context)

                if (profiles.isNotEmpty()) {
                    // Check if container has a custom profile associated
                    val profileIdStr = container.getExtra("profileId", "0")
                    val profileId = profileIdStr.toIntOrNull() ?: 0

                    val customProfile = if (profileId != 0) manager?.getProfile(profileId) else null

                    val targetProfile = if (customProfile != null) {
                        // Use the custom profile associated with this container

                        customProfile
                    } else {
                        // Use Profile 0 (Physical Controller Default) as fallback
                        val fallback = manager?.getProfile(0) ?: profiles.getOrNull(2) ?: profiles.first()

                        fallback
                    }

                    // Load controllers for this profile
                    val controllers = targetProfile.loadControllers()

                    controllers.forEachIndexed { index, controller ->

                    }

                    setProfile(targetProfile)
                    PluviaApp.radialMenuCoordinator?.setProfile(targetProfile)

                    val radialMenuCoordinator = PluviaApp.radialMenuCoordinator
                    physicalControllerHandler = PhysicalControllerHandler(
                        targetProfile,
                        xServerView.getxServer(),
                        gameBack,
                        onShowKeyboard = {
                            PluviaApp.inputControlsView?.triggerShowKeyboard()
                        },
                        onRadialMenuButtonStateChanged = radialMenuCoordinator?.let { coordinator ->
                            { isDown, commit -> coordinator.onRadialMenuButtonStateChanged(isDown, commit) }
                        },
                        onRadialMenuVectorChanged = radialMenuCoordinator?.let { it::onRadialMenuVectorChanged },
                        stickStateUpdater = { binding, isDown, offset, sourceKeyCode ->
                            updatePhysicalStickValue(binding, isDown, offset, sourceKeyCode)
                        },
                    )
                    radialMenuCoordinator?.bindPhysicalControllerHandler(physicalControllerHandler)

                    // Store profile for auto-show logic
                    loadedProfile = targetProfile
                }

                // Set overlay opacity from preferences if needed
                val opacity = PrefManager.getFloat("controls_opacity", InputControlsView.DEFAULT_OVERLAY_OPACITY)
                setOverlayOpacity(opacity)

            }
            PluviaApp.inputControlsView = icView

            // Wire SHOW_KEYBOARD binding callback for overlay control buttons
            icView.setShowKeyboardCallback {
                showSoftKeyboard(icView, "onscreen_keyboard_enabled_from_binding")
            }
            PluviaApp.radialMenuCoordinator?.bindInputControlsView(icView)

            xServerView.getxServer().winHandler.setInputControlsView(PluviaApp.inputControlsView)

            // Add InputControlsView (portrait: inside fixed-height container at bottom; landscape: overlay)
            if (isPortrait) {
                val controlsContainer = FrameLayout(context).apply {
                    setBackgroundColor(Color.BLACK)
                }
                mainRoot.addView(controlsContainer, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, controlsHeightPortrait))
                controlsContainer.addView(icView)
            } else {
                frameLayout.addView(icView)
            }
            val configuredExternalMode = ExternalDisplayInputController.fromConfig(container.externalDisplayMode)
            val swapEnabled = container.isExternalDisplaySwap

            val overlay = SwapInputOverlayView(context, xServerView.getxServer()).apply {
                visibility = View.GONE
                setMode(ExternalDisplayInputController.Mode.OFF)
            }
            frameLayout.addView(overlay)
            swapInputOverlay = overlay

            val externalDisplayController =
                if (!swapEnabled && configuredExternalMode != ExternalDisplayInputController.Mode.OFF) {
                    ExternalDisplayInputController(
                        context = context,
                        xServer = xServerView.getxServer(),
                        touchpadViewProvider = { PluviaApp.touchpadView },
                    ).apply {
                        setMode(configuredExternalMode)
                        start()
                    }
                } else {
                    null
                }

            val swapController =
                if (swapEnabled) {
                    val surfaceBg = ContextCompat.getColor(context, R.color.external_display_surface_background)
                    ExternalDisplaySwapController(
                        context = context,
                        xServerViewProvider = { xServerView as? View },
                        internalGameHostProvider = { gameHost },
                        onGameOnExternalChanged = { gameOnExternal ->
                            if (gameOnExternal) {
                                PluviaApp.touchpadView?.setBackgroundColor(surfaceBg)
                                when (configuredExternalMode) {
                                    ExternalDisplayInputController.Mode.KEYBOARD,
                                    ExternalDisplayInputController.Mode.HYBRID,
                                    -> {
                                        overlay.visibility = View.VISIBLE
                                        overlay.setMode(configuredExternalMode)
                                    }
                                    else -> {
                                        overlay.visibility = View.GONE
                                        overlay.setMode(ExternalDisplayInputController.Mode.OFF)
                                    }
                                }
                            } else {
                                PluviaApp.touchpadView?.setBackgroundColor(Color.TRANSPARENT)
                                overlay.visibility = View.GONE
                                overlay.setMode(ExternalDisplayInputController.Mode.OFF)
                            }
                        },
                    ).apply {
                        setSwapEnabled(true)
                        start()
                    }
                } else {
                    null
                }
            mainRoot.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) {}

                override fun onViewDetachedFromWindow(v: View) {
                    externalDisplayController?.stop()
                    swapController?.stop()
                }
            })
            // Don't call hideInputControls() here - let the auto-show logic below handle visibility
            // so that the view gets measured/laid out and has valid dimensions for element loading

            // Auto-show on-screen controls after the view has been laid out and has proper dimensions
            icView.post {

                loadedProfile?.let { profile ->
                    // Load elements if not already loaded (view has dimensions now)
                    if (!profile.isElementsLoaded) {

                        profile.loadElements(icView)
                    }

                    // Only auto-show if profile has on-screen elements

                    if (profile.elements.isNotEmpty()) {
                        // Check for ACTUAL physically connected controllers, not just saved bindings
                        val controllerManager = ControllerManager.getInstance()
                        controllerManager.scanForDevices()
                        val hasPhysicalController = controllerManager.getDetectedDevices().isNotEmpty()

                        // Determine if controls should be shown based on priority:
                        // 1. If touchscreen mode is true → always hide
                        // 2. Else if physical controller detected → hide
                        // 3. Else if physical mouse/keyboard detected → hide
                        // 4. Else → show
                        val shouldShowControls = when {
                            container.isTouchscreenMode -> false
                            hasPhysicalController -> false
                            hasPhysicalKeyboard || hasPhysicalMouse -> false
                            else -> true
                        }

                        if (shouldShowControls) {

                            showInputControls(profile, xServerView.getxServer().winHandler, container)
                            areControlsVisible = true
                        } else {

                            hideInputControls()
                            areControlsVisible = false
                        }
                    } else {

                    }
                }
            }
            frameRating = FrameRating(context)
            frameRating?.setVisibility(View.GONE)
            xServerView.renderer.setFrameRating(frameRating)

            if (isPerformanceHudEnabled) {
                frameLayout.post {
                    updatePerformanceHud(true)
                }
            }

            if (container.isDisableMouseInput){
                PluviaApp.touchpadView?.setTouchscreenMouseDisabled(true);
            }

            mainRoot

            // } else {

},
        update = { view ->
            gameRoot = view
            val binding = view.tag as? XServerViewReleaseBinding
            val gameHost = binding?.gameHost
            if (binding != null && gameHost != null) {
                updatePortraitGameHostHeight(
                    gameHost,
                    isPortrait,
                    container.isPortraitBelowCutout,
                    binding.screenWidth,
                    container.screenSize,
                )
            }
        },
        onRelease = { view ->
            gameRoot = null
            removePerformanceHud()
            performanceHudHost = null
            shouldTrackDisplayedFrames.set(false)
            ShmFramePacer.setFrameRateLimit(0)

            val releaseBinding = view.tag as? XServerViewReleaseBinding
            releaseBinding?.let { binding ->
                // Remove the WindowManager listener associated with the released AndroidView.
                binding.xServerView.renderer.setOnFrameRenderedListener(null)
                if (binding.xServerView.renderer is VulkanRenderer) {
                    (binding.xServerView as? XServerView)?.dispose()
                }
                binding.xServerView.getxServer().windowManager.removeOnWindowModificationListener(binding.windowModificationListener)
                binding.gameHostLayoutListener?.let { listener ->
                    (binding.gameHost?.parent as? View)?.removeOnLayoutChangeListener(listener)
                }
                if (PluviaApp.xServerView === binding.xServerView) {
                    PluviaApp.xServerView = null
                }
            }
            view.tag = null
        },
    )
        }

        // Click highlight overlay (gesture overhaul)
        if (currentGestureConfig.showClickHighlight && clickHighlightPoints.isNotEmpty()) {
            app.gamenative.ui.component.ClickHighlightOverlay(points = clickHighlightPoints)
        }

        // Debug gesture name overlay (gesture overhaul)
        if (currentGestureConfig.showGestureDebugOverlay && debugGestureName.isNotEmpty()) {
            LaunchedEffect(debugGestureKey) {
                kotlinx.coroutines.delay(1200)
                debugGestureName = ""
            }
            androidx.compose.foundation.layout.Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = 48.dp),
                contentAlignment = Alignment.TopCenter,
            ) {
                androidx.compose.material3.Text(
                    text = debugGestureName,
                    color = androidx.compose.ui.graphics.Color.White,
                    fontSize = 18.sp,
                    modifier = Modifier
                        .background(
                            androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.6f),
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
                        )
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        }

        // Floating toolbar for edit mode (always visible in edit mode)
        if (isEditMode && areControlsVisible) {
            EditModeToolbar(
                onAdd = {
                    if (PluviaApp.inputControlsView?.addElement() == true) {
                        // Element was added, refresh the view
                        PluviaApp.inputControlsView?.invalidate()
                    }
                },
                onEdit = {
                    val selectedElement = PluviaApp.inputControlsView?.getSelectedElement()
                    if (selectedElement != null) {
                        elementToEdit = selectedElement
                        showElementEditor = true
                    }
                },
                onDelete = {
                    PluviaApp.inputControlsView?.removeElement()
                },
                onSave = {
                    // Save profile changes
                    PluviaApp.inputControlsView?.profile?.save()
                    // Clear snapshot since changes were accepted
                    elementPositionsSnapshot = emptyMap()
                    // Exit edit mode
                    isEditMode = false
                    PluviaApp.inputControlsView?.setEditMode(false)
                    // Force redraw on next frame to ensure grid is removed
                    PluviaApp.inputControlsView?.post {
                        PluviaApp.inputControlsView?.invalidate()
                    }
                    keepPausedForEditor = false
                    resumeIfAllowedAfterOverlay()
                },
                onClose = {
                    // Restore element positions from snapshot (cancel behavior)
                    if (elementPositionsSnapshot.isNotEmpty()) {
                        elementPositionsSnapshot.forEach { (element, position) ->
                            element.setX(position.first)
                            element.setY(position.second)
                        }
                        elementPositionsSnapshot = emptyMap()
                    }

                    // Exit edit mode without saving
                    isEditMode = false
                    PluviaApp.inputControlsView?.setEditMode(false)
                    // Force redraw on next frame to ensure grid is removed
                    PluviaApp.inputControlsView?.post {
                        PluviaApp.inputControlsView?.profile?.loadElements(PluviaApp.inputControlsView)
                        PluviaApp.inputControlsView?.profile?.save()
                        PluviaApp.inputControlsView?.invalidate()
                    }
                    keepPausedForEditor = false
                    resumeIfAllowedAfterOverlay()
                },
                onDuplicate = { id ->
                    val manager = PluviaApp.inputControlsManager
                    val profile = manager?.getProfile(id)
                    val currentProfile = PluviaApp.inputControlsView?.profile
                    if (profile != null && currentProfile != null) {
                        // Wait for view to be laid out before loading elements
                        PluviaApp.inputControlsView?.let { icView ->
                            icView.post {
                                copyInputControlsProfileElements(profile, currentProfile, icView)
                                SnackbarManager.show(context.getString(R.string.toast_controls_reset))
                            }
                        }
                    }
                }
            )
        }

        // While WoW still streams its data: percent and speed at the top (own composable, keeps this function small).
        if (container.id == "wow_forever") app.gamenative.ui.screen.wow.WowDownloadOverlay()

        app.gamenative.ui.screen.wow.BattleNetSignInHost(
            onBeforeTyping = {
                dismissOverlayMenu()
                forceResumeIfSuspended()
            },
            gameView = { xServerView as? android.view.SurfaceView },
            onCursorHidden = { hidden ->
                xServerView?.renderer?.setCursorVisible(if (hidden) false else shouldShowMouseCursor())
            },
        )

        QuickMenu(
            isVisible = showQuickMenu,
            onDismiss = dismissOverlayMenu,
            onItemSelected = onQuickMenuItemSelected,
            renderer = xServerView?.renderer as? VulkanRenderer,
            container = container,
            wineProcesses = quickMenuWineProcesses,
            isWineProcessesLoading = quickMenuWineProcessesLoading,
            onToolsVisibilityChanged = { quickMenuToolsVisible = it },
            onEndWineProcess = { process ->
                val killed = runCatching {
                    ProcessHelper.killProcess(process.pid)
                }.onFailure { error ->

                }.isSuccess

                if (killed) {
                    quickMenuWineProcesses = quickMenuWineProcesses.filterNot { it.pid == process.pid }
                }
            },
            performance = PerformanceQuickMenuState(
                hudEnabled = isPerformanceHudEnabled,
                hudConfig = performanceHudConfig,
                fpsLimiterEnabled = fpsLimiterEnabled,
                fpsLimiterTarget = fpsLimiterTarget,
                fpsLimiterMax = detectedMaxRefreshRateHz,
                onHudConfigChanged = ::applyPerformanceHudConfig,
                onFpsLimiterEnabledChanged = ::applyFpsLimiterEnabled,
                onFpsLimiterChanged = ::applyFpsLimiterTarget,
            ),
            hasPhysicalController = hasPhysicalController,
            isTouchscreenModeActive = isTouchscreenModeActive,
            onTouchGestureSettingsClick = { showTouchGestureDialog = true },
            activeToggleIds = buildSet {
                if (areControlsVisible) add(QuickMenuAction.INPUT_CONTROLS)
                if (isTouchscreenModeActive) add(QuickMenuAction.TOUCHSCREEN_MODE)
                if (isDisableMouseInput) add(QuickMenuAction.DISABLE_MOUSE)
            },
            onAnimationComplete = { isMenuVisible ->
                if (isMenuVisible) {
                    pauseForOverlayIfAllowed()
                } else {
                    if (shouldForceResumeOnMenuClose) {
                        forceResumeIfSuspended()
                        shouldForceResumeOnMenuClose = false
                    } else if (!keepPausedForEditor) {
                        resumeIfAllowedAfterOverlay()
                    }
                }
            },
        )

        if (manualResumeMode && PluviaApp.isOverlayPaused && !showQuickMenu && !keepPausedForEditor) {
            ManualResumeOverlay(onResume = ::resumeFromManualButton)
        }
    }

    // Element Editor Dialog
    if (showElementEditor && elementToEdit != null && PluviaApp.inputControlsView != null) {
        app.gamenative.ui.component.dialog.ElementEditorDialog(
            element = elementToEdit!!,
            view = PluviaApp.inputControlsView!!,
            onDismiss = {
                showElementEditor = false
                // Keep edit mode active so user can edit other elements
            },
            onSave = {
                showElementEditor = false
                // Keep edit mode active so user can edit other elements
            }
        )
    }

    // Touch Gesture Settings Dialog
    if (showTouchGestureDialog) {
        app.gamenative.ui.component.dialog.TouchGestureSettingsDialog(
            gestureConfig = currentGestureConfig,
            onDismiss = { showTouchGestureDialog = false },
            onSave = { newConfig ->
                currentGestureConfig = newConfig
                container.setGestureConfig(newConfig.toJson())
                container.saveData()
                PluviaApp.touchpadView?.setGestureConfig(newConfig)
                applyMouseCursorVisibility()
                showTouchGestureDialog = false
            },
        )
    }


    // Physical Controller Config Dialog
    if (showPhysicalControllerDialog) {
        // Get profile from container settings, not from InputControlsView
        // (InputControlsView.profile is null when on-screen controls are hidden)
        val manager = PluviaApp.inputControlsManager ?: InputControlsManager(context)
        val profileIdStr = container.getExtra("profileId", "0")
        val profileId = profileIdStr.toIntOrNull() ?: 0

        // Get profile, but don't load profile 0 directly (will duplicate if needed)
        var profile = if (profileId != 0) {
            manager.getProfile(profileId)
        } else {
            null  // Will create new profile below
        }

        // Auto-create profile if using default (profile 0)
        if (profile == null) {
            val allProfiles = manager.getProfiles(false)
            val sourceProfile = manager.getProfile(0)
                ?: allProfiles.firstOrNull { it.id == 2 }
                ?: allProfiles.firstOrNull()

            if (sourceProfile != null) {
                try {
                    // Duplicate profile 0 to create game-specific profile
                    profile = manager.duplicateProfile(sourceProfile)

                    // Rename to game name
                    val gameName = container.name
                    profile.setName("$gameName - Physical Controller")
                    profile.save()

                    // Associate with container
                    container.putExtra("profileId", profile.id.toString())
                    container.saveData()
                } catch (e: Exception) {

                    profile = sourceProfile  // Fallback
                }
            }
        }

        if (profile != null) {
            androidx.compose.ui.window.Dialog(
                onDismissRequest = {
                    showPhysicalControllerDialog = false
                    keepPausedForEditor = false
                    resumeIfAllowedAfterOverlay()
                }
            ) {
                androidx.compose.foundation.layout.Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.95f))
                ) {
                    app.gamenative.ui.component.dialog.PhysicalControllerConfigSection(
                        profile = profile,
                        onDismiss = {
                            showPhysicalControllerDialog = false
                            keepPausedForEditor = false
                            resumeIfAllowedAfterOverlay()
                        },
                        onSave = {
                            // Ensure controllersLoaded is true before saving
                            // (addController sets the flag even if controller already exists)
                            profile.addController("*")

                            // Save profileId to container so it persists across launches
                            container.putExtra("profileId", profile.id.toString())
                            container.saveData()

                            // Save profile (will now write controllers since controllersLoaded = true)
                            profile.save()
                            profile.loadControllers()

                            // Reload the active profile without
                            // unintentionally showing controls that were hidden for a controller.
                            PluviaApp.inputControlsView?.setProfilePreservingOverlayVisibility(profile)
                            physicalControllerHandler?.setProfile(profile)
                            PluviaApp.radialMenuCoordinator?.setProfile(profile)
                            showPhysicalControllerDialog = false
                            keepPausedForEditor = false
                            resumeIfAllowedAfterOverlay()
                        }
                    )
                }
            }
        }
    }

}

/** Lives outside XServerScreen because that composable sits at the dex verifier's 255-register
 * limit — its FocusRequester/effect locals tripped a runtime VerifyError when inlined there. */
@Composable
private fun ManualResumeOverlay(onResume: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {},
            ),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(72.dp)
                .background(
                    color = androidx.compose.ui.graphics.Color.White,
                    shape = androidx.compose.foundation.shape.CircleShape,
                )
                .clickable(onClick = onResume),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Default.PlayArrow,
                contentDescription = stringResource(R.string.resume_game),
                tint = androidx.compose.ui.graphics.Color.Black,
                modifier = Modifier.size(40.dp),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditModeToolbar(
    onAdd: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onSave: () -> Unit,
    onClose: () -> Unit,
    onDuplicate: (Int) -> Unit
) {
    var duplicateProfileOpen by remember { mutableStateOf(false) }
    var toolbarOffsetX by remember { mutableStateOf(0f) }
    var toolbarOffsetY by remember { mutableStateOf(0f) }
    val density = LocalDensity.current

    Box(
        contentAlignment = androidx.compose.ui.Alignment.TopCenter,
        modifier = Modifier
            .offset(x = toolbarOffsetX.dp, y = toolbarOffsetY.dp)
            .fillMaxWidth()
            .wrapContentHeight()
            .padding(top = 16.dp)
            .pointerInput(density) {
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    toolbarOffsetX += dragAmount.x / density.density
                    toolbarOffsetY += dragAmount.y / density.density
                }
            }
    ) {
        Row(
            modifier = Modifier
                .wrapContentSize()
                .background(
                    color = androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.8f),
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp)
                )
                .padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Drag handle indicator
            Icon(
                imageVector = Icons.Default.Menu,
                contentDescription = "Drag to move",
                tint = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.7f),
                modifier = Modifier.padding(end = 4.dp)
            )

            // Add button
            TextButton(onClick = onAdd) {
                Icon(Icons.Default.Add, contentDescription = "Add", tint = androidx.compose.ui.graphics.Color.White)
                Spacer(modifier = Modifier.width(4.dp))
                Text(stringResource(R.string.add), color = androidx.compose.ui.graphics.Color.White)
            }

            // Edit button
            TextButton(onClick = onEdit) {
                Icon(Icons.Default.Edit, contentDescription = "Edit", tint = androidx.compose.ui.graphics.Color.White)
                Spacer(modifier = Modifier.width(4.dp))
                Text(stringResource(R.string.edit), color = androidx.compose.ui.graphics.Color.White)
            }

            // Delete button
            TextButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = "Delete", tint = androidx.compose.ui.graphics.Color.White)
                Spacer(modifier = Modifier.width(4.dp))
                Text(stringResource(R.string.delete), color = androidx.compose.ui.graphics.Color.White)
            }

            // Duplicate button with dropdown
            Box {
                TextButton(onClick = { duplicateProfileOpen = !duplicateProfileOpen }) {
                    Icon(Icons.Filled.ContentCopy, contentDescription = "Copy From", tint = androidx.compose.ui.graphics.Color.White)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(stringResource(R.string.copy_from), color = androidx.compose.ui.graphics.Color.White)
                }

                val knownProfiles = PluviaApp.inputControlsManager?.getProfiles(false) ?: emptyList()
                if (knownProfiles.isNotEmpty()) {
                    DropdownMenu(
                        expanded = duplicateProfileOpen,
                        onDismissRequest = { duplicateProfileOpen = false }
                    ) {
                        for (knownProfile in knownProfiles) {
                            DropdownMenuItem(
                                text = { Text(knownProfile.name) },
                                onClick = {
                                    onDuplicate(knownProfile.id)
                                    duplicateProfileOpen = false
                                },
                            )
                        }
                    }
                }
            }

            // Save button
            TextButton(onClick = onSave) {
                Icon(Icons.Default.Check, contentDescription = "Save", tint = androidx.compose.ui.graphics.Color.White)
                Spacer(modifier = Modifier.width(4.dp))
                Text(stringResource(R.string.save), color = androidx.compose.ui.graphics.Color.White)
            }

            // Close button
            TextButton(onClick = onClose) {
                Icon(Icons.Default.Close, contentDescription = "Close", tint = androidx.compose.ui.graphics.Color.White)
                Spacer(modifier = Modifier.width(4.dp))
                Text(stringResource(R.string.close), color = androidx.compose.ui.graphics.Color.White)
            }
        }
    }
}

private fun showInputControls(profile: ControlsProfile, winHandler: WinHandler, container: Container) {
    profile.setVirtualGamepad(true)
    PluviaApp.radialMenuCoordinator?.setProfile(profile)

    PluviaApp.inputControlsView?.let { icView ->
        // Check if we need to load/reload elements with valid dimensions
        if (!profile.isElementsLoaded || icView.width == 0 || icView.height == 0) {
            if (icView.width == 0 || icView.height == 0) {
                // View has no dimensions yet - wait for layout before loading elements

                icView.post {

                    profile.loadElements(icView)
                    icView.setProfile(profile)
                    icView.setShowTouchscreenControls(true)
                    icView.setVisibility(View.VISIBLE)
                    icView.requestFocus()
                    icView.invalidate()
                    winHandler.refreshControllerMappings()
                }
            } else {
                // View has dimensions but elements not loaded - load them now

                profile.loadElements(icView)
                icView.setProfile(profile)
                icView.setShowTouchscreenControls(true)
                icView.setVisibility(View.VISIBLE)
                icView.requestFocus()
                icView.invalidate()
                winHandler.refreshControllerMappings()
            }
        } else {
            // Elements already loaded with valid dimensions - just show

            icView.setProfile(profile)
            icView.setShowTouchscreenControls(true)
            icView.setVisibility(View.VISIBLE)
            icView.requestFocus()
            icView.invalidate()
            winHandler.refreshControllerMappings()
        }
    }

    PluviaApp.touchpadView?.setSensitivity(profile.getCursorSpeed() * 1.0f)

    // If the selected profile is a virtual gamepad, we must enable the P1 slot.
    if (container.containerVariant.equals(Container.BIONIC) && profile.isVirtualGamepad()) {
        val controllerManager: ControllerManager = ControllerManager.getInstance()

        // Ensure Player 1 slot is enabled so a vjoy device is created for it.
        controllerManager.setSlotEnabled(0, true)

        // Clear any physical device from P1 to prevent conflicts.
        controllerManager.unassignSlot(0)

        // Tell WinHandler to update its internal state.
        winHandler.refreshControllerMappings()
    }
}

private fun hideInputControls() {
    PluviaApp.inputControlsView?.setShowTouchscreenControls(false)
    PluviaApp.inputControlsView?.setVisibility(View.GONE)
    PluviaApp.inputControlsView?.hideProfileForOverlay()
    PluviaApp.xServerView?.getxServer()?.winHandler?.refreshControllerMappingsForHotplug()

    PluviaApp.touchpadView?.setSensitivity(1.0f)
    PluviaApp.touchpadView?.setPointerButtonLeftEnabled(true)
    PluviaApp.touchpadView?.setPointerButtonRightEnabled(true)
    PluviaApp.touchpadView?.isEnabled()?.let {
        if (!it) {
            PluviaApp.touchpadView?.setEnabled(true)
            PluviaApp.xServerView?.getRenderer()?.setCursorVisible(true)
        }
    }
    PluviaApp.inputControlsView?.invalidate()
}

/**
 * Shows or hides the onscreen controls
 */
fun showInputControls(context: Context, show: Boolean) {
    PluviaApp.inputControlsView?.let { icView ->
        if (show) {
            // Reload elements with current screen dimensions when showing controls
            icView.profile?.let { profile ->

                profile.loadElements(icView)
            }
        }
        icView.setShowTouchscreenControls(show)
        icView.invalidate()
    }
}

private fun setupXEnvironment(
    session: GameSessionLifecycle,
    context: Context,
    appId: String,
    xServerState: MutableState<XServerState>,
    envVars: EnvVars,
    container: Container?,
    xServer: XServer,
    containerVariantChanged: Boolean,
    onGameLaunchError: ((String) -> Unit)? = null,
    offline: Boolean = false,
    contentsManager: ContentsManager,
): XEnvironment {

    ProcessHelper.hardKillStaleWineProcesses()

    val lc_all = container!!.lC_ALL
    val imageFs = ImageFs.find(context)

    envVars.put("LC_ALL", lc_all)
    envVars.put("MESA_DEBUG", "silent")
    envVars.put("MESA_NO_ERROR", "1")
    envVars.put("WINEPREFIX", imageFs.wineprefix)
    envVars.put("WINEDEBUG", "-all")

    val rootPath = imageFs.getRootDir().getPath()
    FileUtils.clear(imageFs.getTmpDir())

    // WoW containers are always Bionic (the glibc launcher was removed in v0.1.117).
    val guestProgramLauncherComponent = BionicProgramLauncherComponent(
        contentsManager,
        contentsManager.getProfileByEntryName(container.wineVersion),
    )

        val wow64Mode = container.isWoW64Mode
        guestProgramLauncherComponent.setContainer(container)
        guestProgramLauncherComponent.setWineInfo(xServerState.value.wineInfo)
        val gameExecutable = "wine explorer /desktop=shell," + xServer.screenInfo + " " +
            getWineStartCommand(context, appId, container, envVars, guestProgramLauncherComponent) +
            (if (container.execArgs.isNotEmpty()) " " + container.execArgs else "")
        guestProgramLauncherComponent.guestExecutable = gameExecutable
        guestProgramLauncherComponent.isWoW64Mode = wow64Mode

        envVars.putAll(container.envVars)
        if (container.id == "wow_forever") {
            app.gamenative.ui.screen.wow.WowLaunchPolicy.apply(context, envVars, container.envVars)
        }
        if (container.id == "wow_forever") {
            app.gamenative.ui.screen.wow.WowRuntimeTuning.apply(context, container, envVars)
        } else {
            envVars.remove("DXVK_FRAME_RATE")
            envVars.remove("VKD3D_FRAME_RATE")
        }
        if (!envVars.has("WINEESYNC")) envVars.put("WINEESYNC", "1")

        val graphicsDriverConfig = KeyValueSet(container.getGraphicsDriverConfig())
        if (graphicsDriverConfig.get("version").lowercase(Locale.getDefault()).contains("gen8")) {
            var tuDebug = envVars.get("TU_DEBUG")
            if (!tuDebug.contains("nolrz")) tuDebug = (if (!tuDebug.isEmpty()) "$tuDebug," else "") + "nolrz"
            envVars.put("TU_DEBUG", tuDebug)
        }

        guestProgramLauncherComponent.setPreUnpack {
            unpackExecutableFile(container, container.isNeedsUnpacking)
            if (!session.isExiting) {
                PluviaApp.events.emit(AndroidEvent.SetBootingSplashText("Launching game..."))
            }
        }

    val environment = XEnvironment(context, imageFs)
    environment.addComponent(
        SysVSharedMemoryComponent(
            xServer,
            UnixSocketConfig.createSocket(rootPath, UnixSocketConfig.SYSVSHM_SERVER_PATH),
        ),
    )
    environment.addComponent(XServerComponent(xServer, UnixSocketConfig.createSocket(rootPath, UnixSocketConfig.XSERVER_PATH)))
    environment.addComponent(NetworkInfoUpdateComponent())

    // Microphone support is published through PulseAudio (module-pipe-source), which Wine/Proton's
    // winepulse.drv enumerates as a normal recording device. Opt-in per container.
    val micEnabled = container.getMicEnabled() && PulseAudioComponent.isMicModuleAvailable(context)
    envVars.put("PULSE_SERVER", imageFs.getRootDir().getPath() + UnixSocketConfig.PULSE_SERVER_PATH)
    environment.addComponent(PulseAudioComponent(
        UnixSocketConfig.createSocket(imageFs.getRootDir().getPath(), UnixSocketConfig.PULSE_SERVER_PATH),
        container.pulseaudioLowLatency,
        micEnabled,
        true
    ))

    if (micEnabled) {
        environment.addComponent(MicrophoneComponent(PulseAudioComponent.getMicFifoFile(context)))
    }

    app.gamenative.ui.screen.wow.WowLaunchEnvironment.apply(context, container, contentsManager, envVars)

    app.gamenative.ui.screen.wow.WowVkd3dRuntime.environment(container, envVars)

    guestProgramLauncherComponent.envVars = EnvVars().apply { putAll(envVars) }

    val gameTerminationCallback = Callback<Int> { status ->
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        (PluviaApp.currentGameSession ?: session).dispatchIfActive({ handler.post(it) }) {
            if (status != 0) onGameLaunchError?.invoke("Game terminated with error status: $status")
            PluviaApp.events.emit(AndroidEvent.GuestProgramTerminated)
        }
    }

    guestProgramLauncherComponent.setTerminationCallback(gameTerminationCallback)

    environment.addComponent(guestProgramLauncherComponent)



    if (session.isExiting) return environment
    try {
            environment.startEnvironmentComponents()

    } catch (e: Exception) {

        try {
            environment.stopEnvironmentComponents()
        } catch (cleanupEx: Exception) {

        }
        throw e
    }

    // put in separate scope since winhandler start method does some network stuff
    CoroutineScope(Dispatchers.IO).launch {
        xServer.winHandler.start()
    }
    envVars.clear()
    xServerState.value = xServerState.value.copy(
        dxwrapperConfig = null,
    )
    return environment

}
private fun getWineStartCommand(
    context: Context,
    appId: String,
    container: Container,
    envVars: EnvVars,
    guestProgramLauncherComponent: BionicProgramLauncherComponent,
): String {
    val tempDir = File(container.getRootDir(), ".wine/drive_c/windows/temp")
    FileUtils.clear(tempDir)

    val executablePath = container.executablePath
    if (executablePath.isEmpty()) {
        return "winhandler.exe \"wfm.exe\""
    }

    var gameFolderPath: String? = null
    for (drive in Container.drivesIterator(container.drives)) {
        if (drive[0] == "A") {
            gameFolderPath = drive[1]
            break
        }
    }

    val args = if (ContainerUtils.isAbsoluteWindowsPath(executablePath)) {
        val driveLetter = executablePath.substring(0, 1).uppercase()
        var driveHostPath: String? = null
        for (drive in Container.drivesIterator(container.drives)) {
            if (drive[0].uppercase() == driveLetter) {
                driveHostPath = drive[1]
                break
            }
        }
        if (driveHostPath != null) {
            val winSubPath = executablePath.substring(3).replace("\\", "/")
            val winDir = winSubPath.substringBeforeLast("/", "")
            val hostDir = File(driveHostPath, winDir)
            if (hostDir.exists() && hostDir.isDirectory) {
                guestProgramLauncherComponent.workingDir = hostDir
            }
        }
        val isArm64 = executablePath.contains("ARM64", ignoreCase = true)
        if (isArm64) {
            if (executablePath.contains(" ")) "\"$executablePath\"" else executablePath
        } else {
            "winhandler.exe \"$executablePath\""
        }
    } else {
        if (gameFolderPath == null) {

            return "winhandler.exe \"wfm.exe\""
        }

        val executableDir = gameFolderPath + "/" + executablePath.substringBeforeLast("/", "")
        guestProgramLauncherComponent.workingDir = File(executableDir)

        val normalizedPath = executablePath.replace('/', '\\')
        envVars.put("WINEPATH", "A:\\")
        "\"A:\\${normalizedPath}\""
    }

    return if (args.contains("ARM64", ignoreCase = true)) args else "winhandler.exe $args"
}
private fun exit(
    session: GameSessionLifecycle,
    winHandler: WinHandler?,
    frameRating: FrameRating?,
    container: Container,
    appId: String,
    onExit: (onComplete: (() -> Unit)?) -> Unit,
    navigateBack: () -> Unit,
    reason: String,
) {

    if (!session.beginExit()) {

        return
    }

    // only needed in exit() — OS reclaims on process death, so onDestroy fallback skips this
    try {
        winHandler?.stop()
    } catch (e: Exception) {

    }
    PluviaApp.shutdownEnvironment()

    // empty Wine/XDG trash in background after container stops
    CoroutineScope(Dispatchers.IO).launch {
        try {
            val trashDir = File(container.rootDir, ".local/share/Trash")
            val children = trashDir.listFiles()
            if (children == null) {

            } else if (children.isEmpty()) {

            } else {

                val deleted = children.count { child -> FileUtils.delete(child) }

            }
        } catch (e: Exception) {

        }
    }
    onExit(null)
    navigateBack()
}

private fun unpackExecutableFile(
    container: Container,
    needsUnpacking: Boolean,
) {
    if (needsUnpacking) {
        container.setNeedsUnpacking(false)
        container.saveData()
    }
}

private suspend fun setupWineSystemFiles(
    context: Context,
    firstTimeBoot: Boolean,
    screenInfo: ScreenInfo,
    xServerState: MutableState<XServerState>,
    container: Container,
    containerManager: ContainerManager,
    // shortcut: Shortcut?,
    envVars: EnvVars,
    contentsManager: ContentsManager,
    onExtractFileListener: OnExtractFileListener?,
) {

    val imageFs = ImageFs.find(context)
    if (container.id == "wow_forever" && File(container.rootDir, ".frontmage-dll-transaction").exists()) {
        app.gamenative.ui.screen.wow.WowVkd3dComponents.idle { false }
        app.gamenative.ui.screen.wow.WowD3d12DllTransaction.recover(container.rootDir,
            File(imageFs.rootDir, ImageFs.WINEPREFIX + "/drive_c/windows/system32"))
    }
    val appVersion = AppUtils.getVersionCode(context).toString()
    val imgVersion = imageFs.getVersion().toString()
    var containerDataChanged = false

    val appliedContainerVariant = container.getExtra("appliedContainerVariant")
    val appliedWineVersion = container.getExtra("appliedWineVersion")
    val markersMissing = appliedContainerVariant.isEmpty() || appliedWineVersion.isEmpty()
    val firstBoot = container.getExtra("appVersion").isEmpty()
    val imgVersionChanged = container.getExtra("imgVersion") != imgVersion
    val variantChanged = !markersMissing && container.containerVariant != appliedContainerVariant
    val wineVersionChanged = !markersMissing && container.wineVersion != appliedWineVersion

    if (firstBoot || imgVersionChanged || variantChanged || wineVersionChanged) {
        applyGeneralPatches(context, container, imageFs, xServerState.value.wineInfo, containerManager, onExtractFileListener)
        container.putExtra("appliedContainerVariant", container.containerVariant)
        container.putExtra("appliedWineVersion", container.wineVersion)
        container.putExtra("appVersion", appVersion)
        container.putExtra("imgVersion", imgVersion)
        containerDataChanged = true
    } else if (markersMissing) {
        // Pre-existing container: trust the on-disk prefix and adopt it as-is.
        container.putExtra("appliedContainerVariant", container.containerVariant)
        container.putExtra("appliedWineVersion", container.wineVersion)
        containerDataChanged = true
    }

    check(ContainerOverlayMigrator.migrateIfNeeded(context, contentsManager, container)) {
        "No base prefix for ${container.wineVersion}, cannot launch thin container ${container.id}"
    }

    // Always refresh components files
    refreshComponentsFiles(context)

    // Normalize dxwrapper for state (dxvk includes version for extraction switch)
    if (xServerState.value.dxwrapper == "dxvk") {
        xServerState.value = xServerState.value.copy(
            dxwrapper = "dxvk-" + xServerState.value.dxwrapperConfig?.get("version"),
        )
    }

    // Also normalize VKD3D to include version like vkd3d-<version>
    if (xServerState.value.dxwrapper == "vkd3d") {
        xServerState.value = xServerState.value.copy(
            dxwrapper = "vkd3d-" + xServerState.value.dxwrapperConfig?.get("vkd3dVersion"),
        )
    }

    val needReextract = xServerState.value.dxwrapper != container.getExtra("dxwrapper") || variantChanged || wineVersionChanged

    if (needReextract) {
        extractDXWrapperFiles(
            context,
            firstTimeBoot,
            container,
            containerManager,
            xServerState.value.dxwrapper,
            imageFs,
            contentsManager,
            onExtractFileListener,
        )
        container.putExtra("dxwrapper", xServerState.value.dxwrapper)
        containerDataChanged = true
    }

    val desktopTheme = container.desktopTheme
    if ((desktopTheme + "," + screenInfo) != container.getExtra("desktopTheme")) {
        WineThemeManager.apply(context, WineThemeManager.ThemeInfo(desktopTheme), screenInfo)
        container.putExtra("desktopTheme", desktopTheme + "," + screenInfo)
        containerDataChanged = true
    }

    WineUtils.createDosdevicesSymlinks(context, container)

    val effectiveStartupSelection = container.startupSelection
    val startupSelection = effectiveStartupSelection.toString()
    if (startupSelection != container.getExtra("startupSelection")) {
        WineUtils.changeServicesStatus(container, effectiveStartupSelection != Container.STARTUP_SELECTION_NORMAL)
        container.putExtra("startupSelection", startupSelection)
        containerDataChanged = true
    }

    app.gamenative.ui.screen.wow.WowVkd3dRuntime.prepare(context, container, imageFs)

    if (containerDataChanged) container.saveData()

}

private suspend fun applyGeneralPatches(
    context: Context,
    container: Container,
    imageFs: ImageFs,
    wineInfo: WineInfo,
    containerManager: ContainerManager,
    onExtractFileListener: OnExtractFileListener?,
) {

    val rootDir = imageFs.getRootDir()
    val contentsManager = ContentsManager(context)
    if (!container.isOverlay) {

        containerManager.extractContainerPatternCommon(rootDir, onExtractFileListener)
    }
    if (container.isOverlay) {
        check(ContainerOverlayMigrator.migrateIfNeeded(context, contentsManager, container)) {
            "No base prefix for ${container.wineVersion}, cannot switch thin container ${container.id}"
        }
    } else {

        containerManager.extractContainerPatternFile(container.wineVersion, contentsManager, container.rootDir, onExtractFileListener)
        if (container.basePrefix.isNotEmpty()) {
            container.basePrefix = ""
            container.saveData()
        }
    }
    WineUtils.applySystemTweaks(context, wineInfo)
    container.putExtra("graphicsDriver", null)
    container.putExtra("desktopTheme", null)
    container.putExtra("xaudioDllsExtracted", null)
    container.putExtra("wincomponents", null)
    container.putExtra("audioDriver", null)
    container.putExtra("startupSelection", null)
    WinlatorPrefManager.init(context)
}

private fun refreshComponentsFiles(context: Context) {
    val extractionPairs = listOf(
        "pulseaudio-gamenative-20260919.tzst" to File(context.filesDir, "pulseaudio")
    )

    AssetUtils.extractComponentsWithVersionCheck(
        extractionPairs,
        context.assets,
        TarCompressorUtils.Type.ZSTD
    )
}

private suspend fun extractDXWrapperFiles(
    context: Context,
    firstTimeBoot: Boolean,
    container: Container,
    containerManager: ContainerManager,
    dxwrapper: String,
    imageFs: ImageFs,
    contentsManager: ContentsManager,
    onExtractFileListener: OnExtractFileListener?,
) {

    val dlls = arrayOf(
        "d3d10.dll",
        "d3d10_1.dll",
        "d3d10core.dll",
        "d3d11.dll",
        "d3d12.dll",
        "d3d12core.dll",
        "d3d8.dll",
        "d3d9.dll",
        "dxgi.dll",
        "ddraw.dll",
    )
    val splitDxWrapper = dxwrapper.split("-")[0]
    if (firstTimeBoot && splitDxWrapper != "vkd3d" && !container.isOverlay) cloneOriginalDllFiles(imageFs, *dlls)
    // DXVK and VKD3D always come from a bundled or imported content profile; nothing is downloaded.
    val profile: ContentProfile = contentsManager.getProfileByEntryName(dxwrapper)
        ?: throw IllegalStateException("DirectX wrapper '$dxwrapper' is not installed.")
    if (splitDxWrapper != "vkd3d") {
        restoreOriginalDllFiles(context, container, containerManager, imageFs, "d3d12.dll", "d3d12core.dll", "ddraw.dll")
    }
    contentsManager.applyContent(profile)
}

private fun cloneOriginalDllFiles(imageFs: ImageFs, vararg dlls: String) {
    val rootDir = imageFs.rootDir
    val cacheDir = File(rootDir, ImageFs.CACHE_PATH + "/original_dlls")
    if (!cacheDir.isDirectory) cacheDir.mkdirs()
    val windowsDir = File(rootDir, ImageFs.WINEPREFIX + "/drive_c/windows")
    val dirnames = arrayOf("system32", "syswow64")

    for (dll in dlls) {
        for (dirname in dirnames) {
            val dllFile = File(windowsDir, "$dirname/$dll")
            if (dllFile.isFile) FileUtils.copy(dllFile, File(cacheDir, "$dirname/$dll"))
        }
    }
}
private fun restoreOriginalDllFiles(
    context: Context,
    container: Container,
    containerManager: ContainerManager,
    imageFs: ImageFs,
    vararg dlls: String,
) {
    val rootDir = imageFs.rootDir
    if (container.isOverlay) {
        val upperDir = ContainerFiles.upperDir(container)
        for (dll in dlls) {
            ContainerFiles.removeOverride(upperDir, "drive_c/windows/system32/$dll")
            ContainerFiles.removeOverride(upperDir, "drive_c/windows/syswow64/$dll")
        }
    } else {
        val windowsDir = File(rootDir, ImageFs.WINEPREFIX + "/drive_c/windows")
        var system32dlls: File? = null
        var syswow64dlls: File? = null

        if (container.wineVersion.contains("arm64ec")) system32dlls = File(imageFs.getWinePath() + "/lib/wine/aarch64-windows")
        else system32dlls = File(imageFs.getWinePath() + "/lib/wine/x86_64-windows")

        syswow64dlls = File(imageFs.getWinePath() + "/lib/wine/i386-windows")

        for (dll in dlls) {
            var srcFile = File(system32dlls, dll)
            var dstFile = File(windowsDir, "system32/" + dll)
            FileUtils.copy(srcFile, dstFile)
            srcFile = File(syswow64dlls, dll)
            dstFile = File(windowsDir, "syswow64/" + dll)
            FileUtils.copy(srcFile, dstFile)
        }
    }
}
private suspend fun extractGraphicsDriverFiles(
    context: Context,
    graphicsDriver: String,
    dxwrapper: String,
    dxwrapperConfig: KeyValueSet,
    container: Container,
    envVars: EnvVars,
    firstTimeBoot: Boolean,
) {

    var adrenoToolsDriverId: String? = ""
    val selectedDriverVersion: String?
    val graphicsDriverConfig = KeyValueSet(container.getGraphicsDriverConfig())
    val imageFs = ImageFs.find(context)

    val currentWrapperVersion: String? = graphicsDriverConfig.get("version", DefaultVersion.WRAPPER)

    selectedDriverVersion = currentWrapperVersion

    adrenoToolsDriverId =
        if (selectedDriverVersion!!.contains(DefaultVersion.WRAPPER)) DefaultVersion.WRAPPER else selectedDriverVersion

    val rootDir: File? = imageFs.getRootDir()

    if (dxwrapper.contains("dxvk")) {
        DXVKHelper.setEnvVars(context, dxwrapperConfig, envVars)
    } else if (dxwrapper.contains("vkd3d")) {
        DXVKHelper.setVKD3DEnvVars(context, dxwrapperConfig, envVars)
    }

    val useDRI3: Boolean = container.isUseDRI3
    if (!useDRI3) {
        envVars.put("MESA_VK_WSI_DEBUG", "sw")
    }

    envVars.put("VK_ICD_FILENAMES", imageFs.getShareDir().path + "/vulkan/icd.d/wrapper_icd.aarch64.json")

    // 1. Get the main WRAPPER selection (e.g., "Wrapper-v2") from the class field.
    val mainWrapperSelection: String = graphicsDriver

    // 2. Get the WRAPPER that was last saved to the container's settings.
    val lastInstalledMainWrapper = container.getExtra("lastInstalledMainWrapper")

    // 3. Check if we need to extract a new wrapper file.
    if (firstTimeBoot || mainWrapperSelection != lastInstalledMainWrapper) {
        // We only extract if the selection is actually a wrapper file.
        if (mainWrapperSelection.lowercase(Locale.getDefault()).startsWith("wrapper")) {
            val wrapperComponentId = mainWrapperSelection.lowercase(Locale.getDefault())

            val wrapperContentsManager = ContentsManager(context)
            val wrapperProfile: ContentProfile? = wrapperContentsManager.getProfileByEntryName(wrapperComponentId)
            if (wrapperProfile != null) {
                wrapperContentsManager.applyContent(wrapperProfile)
            } else {
                // The Vulkan wrapper ships inside the APK (assets/graphics_driver/<id>.txz); nothing is downloaded.
                val assetName = "graphics_driver/$wrapperComponentId.txz"
                check(context.assets.list("graphics_driver")?.contains("$wrapperComponentId.txz") == true) {
                    "Graphics driver '$wrapperComponentId' is not bundled."
                }
                check(TarCompressorUtils.extract(TarCompressorUtils.Type.XZ, context.assets, assetName, rootDir!!, null)) {
                    "Failed to install graphics driver '$wrapperComponentId'."
                }
            }
            container.putExtra("lastInstalledMainWrapper", mainWrapperSelection)
            container.saveData()
        }
    }

    if (adrenoToolsDriverId !== "System") {
        val adrenotoolsManager: AdrenotoolsManager = AdrenotoolsManager(context)
        adrenotoolsManager.setDriverById(envVars, imageFs, adrenoToolsDriverId)
    }

    var vulkanVersion = graphicsDriverConfig.get("vulkanVersion") ?: "1.0"
    val vulkanVersionPatch = GPUHelper.vkVersionPatch()

    vulkanVersion = "$vulkanVersion.$vulkanVersionPatch"
    envVars.put("WRAPPER_VK_VERSION", vulkanVersion)

    val blacklistedExtensions: String? = graphicsDriverConfig.get("blacklistedExtensions")
    envVars.put("WRAPPER_EXTENSION_BLACKLIST", blacklistedExtensions)

    val gpuName = graphicsDriverConfig.get("gpuName")
    // No gpuName configured: leave the real GPU identity (an empty name used to match the last gpu_cards.json entry, an Intel UHD 630).
    if (!gpuName.isNullOrEmpty() && gpuName != "Device") {
        envVars.put("WRAPPER_DEVICE_NAME", gpuName)
        envVars.put("WRAPPER_DEVICE_ID", GPUInformation.getDeviceIdFromGPUName(context, gpuName))
        envVars.put("WRAPPER_VENDOR_ID", GPUInformation.getVendorIdFromGPUName(context, gpuName))
    }

    val maxDeviceMemory: String? = graphicsDriverConfig.get("maxDeviceMemory", "0")
    if (maxDeviceMemory != null && maxDeviceMemory.toInt() > 0)
        envVars.put("WRAPPER_VMEM_MAX_SIZE", maxDeviceMemory)

    val presentMode = graphicsDriverConfig.get("presentMode")
    if (presentMode.contains("immediate")) {
        envVars.put("WRAPPER_MAX_IMAGE_COUNT", "1")
    }
    envVars.put("MESA_VK_WSI_PRESENT_MODE", presentMode)

    val resourceType = graphicsDriverConfig.get("resourceType")
    envVars.put("WRAPPER_RESOURCE_TYPE", resourceType)

    val syncFrame = graphicsDriverConfig.get("syncFrame")
    if (syncFrame == "1") envVars.put("MESA_VK_WSI_DEBUG", "forcesync")

    val disablePresentWait = graphicsDriverConfig.get("disablePresentWait")
    envVars.put("WRAPPER_DISABLE_PRESENT_WAIT", disablePresentWait)

    val isWrapperGamenative = graphicsDriver.equals("wrapper-gamenative", ignoreCase = true)
    val vendorId = GPUInformation.getVendorID(null, null)
    val isAdreno = vendorId == 0x5143
    val isXclipse = vendorId == 0x144D
    val excludeBcnCompute = isAdreno || (isWrapperGamenative && isXclipse)
    val bcnEmulation = graphicsDriverConfig.get("bcnEmulation")
    val bcnEmulationType = graphicsDriverConfig.get("bcnEmulationType")
    when (bcnEmulation) {
        "auto" -> {
            if (bcnEmulationType.equals("compute") && !excludeBcnCompute) {
                envVars.put("ENABLE_BCN_COMPUTE", "1");
                envVars.put("BCN_COMPUTE_AUTO", "1");
            }
            envVars.put("WRAPPER_EMULATE_BCN", "3");
        }
        "full" -> {
            if (bcnEmulationType.equals("compute") && !excludeBcnCompute) {
                envVars.put("ENABLE_BCN_COMPUTE", "1");
                envVars.put("BCN_COMPUTE_AUTO", "0");
            }
            envVars.put("WRAPPER_EMULATE_BCN", "2");
        }
        "none" -> envVars.put("WRAPPER_EMULATE_BCN", "0")
        else -> envVars.put("WRAPPER_EMULATE_BCN", "1")
    }

    val bcnEmulationCache = graphicsDriverConfig.get("bcnEmulationCache")
    envVars.put("WRAPPER_USE_BCN_CACHE", bcnEmulationCache)

    val transcoder = graphicsDriverConfig.get("transcoder", "cpu")
    envVars.put("WRAPPER_BCN_GPU", if (transcoder.equals("gpu", ignoreCase = true)) "1" else "0")

    val wrapperQuality = graphicsDriverConfig.get("quality", "low")
    envVars.put("WRAPPER_ASTC_BLOCK", if (wrapperQuality.equals("high", ignoreCase = true)) "4x4" else "8x8")


}

private fun selectWinePulseDriver(container: Container, imageFs: ImageFs) {
    if (container.getExtra("audioDriver") != "pulseaudio") {
        val userRegFile = File(imageFs.rootDir, ImageFs.WINEPREFIX + "/user.reg")
        WineRegistryEditor(userRegFile).use { registryEditor ->
            registryEditor.setStringValue("Software\\Wine\\Drivers", "Audio", "pulse")
        }
        container.putExtra("audioDriver", "pulseaudio")
        container.saveData()
    }
}
private fun setImagefsContainerVariant(context: Context, container: Container) {
    val imageFs = ImageFs.find(context)
    val containerVariant = container.containerVariant
    imageFs.createVariantFile(containerVariant)
}
