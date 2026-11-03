package app.gamenative.ui.component

import app.gamenative.ui.theme.WowTheme

import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Gamepad
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Mouse
import androidx.compose.material.icons.filled.QueryStats
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.gamenative.PluviaApp
import app.gamenative.PrefManager
import app.gamenative.R
import app.gamenative.ui.data.PerformanceHudConfig
import app.gamenative.ui.data.PerformanceHudStyle
import app.gamenative.ui.data.HudMetric
import androidx.compose.material3.IconButton
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.ArrowDownward
import app.gamenative.ui.theme.PluviaTheme
import app.gamenative.ui.util.adaptivePanelWidth
import app.gamenative.utils.MathUtils.normalizedProgress
import com.winlator.container.Container
import com.winlator.renderer.VulkanRenderer
import com.winlator.winhandler.ProcessInfo
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

object QuickMenuAction {
    const val KEYBOARD = 1
    const val INPUT_CONTROLS = 2
    const val EXIT_GAME = 3
    const val EDIT_CONTROLS = 4
    const val EDIT_PHYSICAL_CONTROLLER = 5
    const val PERFORMANCE_HUD = 6
    const val TOUCHSCREEN_MODE = 7
    const val DISABLE_MOUSE = 8
    const val RADIAL_MENU = 10
    const val BATTLE_NET_SIGN_IN = 12
    const val PAUSE_ON_MENU = 13
    const val AUTO_BNET_SIGN_IN = 14
}

private object QuickMenuTab {
    const val HUD = 0
    /** 1 was the Lossless Scaling tab (removed); a saved 1 opens the HUD tab. */
    const val EFFECTS = 2
    const val CONTROLLER = 3
    const val TOOLS = 4
}



data class QuickMenuItem(
    val id: Int,
    val icon: ImageVector,
    val labelResId: Int,
    val accentColor: Color = Color.Unspecified,
    val enabled: Boolean = true,
)

internal enum class PerformanceHudPreset(val labelResId: Int) {
    FPS_ONLY(R.string.performance_hud_preset_fps_only),
    ESSENTIAL(R.string.performance_hud_preset_essential),
    BATTERY(R.string.performance_hud_preset_battery),
    FULL(R.string.performance_hud_preset_full),
}

internal fun applyPerformanceHudPreset(
    currentConfig: PerformanceHudConfig,
    preset: PerformanceHudPreset,
): PerformanceHudConfig {
    return when (preset) {
        PerformanceHudPreset.FPS_ONLY -> currentConfig.copy(
            showFrameRate = true,
            showCpuUsage = false,
            showGpuUsage = false,
            showRamUsage = false,
            showBatteryLevel = false,
            showPowerDraw = false,
            showBatteryRuntime = false,
            showBatteryTemperature = false,
            showClockTime = false,
            showCpuTemperature = false,
            showGpuTemperature = false,
            showFrameRateGraph = false,
            showCpuUsageGraph = false,
            showGpuUsageGraph = false,
            // The newer metrics belong to the preset too: "FPS only" must not keep sampling the GPU clock, graphics memory and frame statistics.
            showCpuClock = false,
            showGpuClock = false,
            showVramUsage = false,
            showFrameTime = false,
            showFps1Low = false,
            showFps01Low = false,
        )

        PerformanceHudPreset.ESSENTIAL -> currentConfig.copy(
            showFrameRate = true,
            showCpuUsage = true,
            showGpuUsage = true,
            showRamUsage = true,
            showBatteryLevel = false,
            showPowerDraw = false,
            showBatteryRuntime = false,
            showBatteryTemperature = false,
            showClockTime = false,
            showCpuTemperature = false,
            showGpuTemperature = false,
            showFrameRateGraph = false,
            showCpuUsageGraph = false,
            showGpuUsageGraph = false,
            // The newer metrics belong to the preset too: "FPS only" must not keep sampling the GPU clock, graphics memory and frame statistics.
            showCpuClock = false,
            showGpuClock = false,
            showVramUsage = false,
            showFrameTime = false,
            showFps1Low = false,
            showFps01Low = false,
        )

        PerformanceHudPreset.BATTERY -> currentConfig.copy(
            showFrameRate = true,
            showCpuUsage = true,
            showGpuUsage = true,
            showRamUsage = true,
            showBatteryLevel = true,
            showPowerDraw = false,
            showBatteryRuntime = true,
            showBatteryTemperature = true,
            showClockTime = false,
            showCpuTemperature = false,
            showGpuTemperature = false,
            showFrameRateGraph = true,
            showCpuUsageGraph = false,
            showGpuUsageGraph = false,
            // The newer metrics belong to the preset too: "FPS only" must not keep sampling the GPU clock, graphics memory and frame statistics.
            showCpuClock = false,
            showGpuClock = false,
            showVramUsage = false,
            showFrameTime = false,
            showFps1Low = false,
            showFps01Low = false,
        )

        PerformanceHudPreset.FULL -> currentConfig.copy(
            showFrameRate = true,
            showCpuUsage = true,
            showGpuUsage = true,
            showRamUsage = true,
            showBatteryLevel = true,
            showPowerDraw = true,
            showBatteryRuntime = true,
            showBatteryTemperature = true,
            showClockTime = true,
            showCpuTemperature = true,
            showGpuTemperature = true,
            showFrameRateGraph = true,
            showCpuUsageGraph = true,
            showGpuUsageGraph = true,
            // The newer metrics belong to the preset too: "FPS only" must not keep sampling the GPU clock, graphics memory and frame statistics.
            showCpuClock = true,
            showGpuClock = true,
            showVramUsage = true,
            showFrameTime = true,
            showFps1Low = true,
            showFps01Low = true,
        )
    }
}

internal fun matchesPerformanceHudPreset(
    currentConfig: PerformanceHudConfig,
    preset: PerformanceHudPreset,
): Boolean {
    val presetConfig = applyPerformanceHudPreset(currentConfig, preset)
    return currentConfig.showFrameRate == presetConfig.showFrameRate &&
        currentConfig.showCpuUsage == presetConfig.showCpuUsage &&
        currentConfig.showGpuUsage == presetConfig.showGpuUsage &&
        currentConfig.showRamUsage == presetConfig.showRamUsage &&
        currentConfig.showBatteryLevel == presetConfig.showBatteryLevel &&
        currentConfig.showPowerDraw == presetConfig.showPowerDraw &&
        currentConfig.showBatteryRuntime == presetConfig.showBatteryRuntime &&
        currentConfig.showBatteryTemperature == presetConfig.showBatteryTemperature &&
        currentConfig.showClockTime == presetConfig.showClockTime &&
        currentConfig.showCpuTemperature == presetConfig.showCpuTemperature &&
        currentConfig.showGpuTemperature == presetConfig.showGpuTemperature &&
        currentConfig.showFrameRateGraph == presetConfig.showFrameRateGraph &&
        currentConfig.showCpuUsageGraph == presetConfig.showCpuUsageGraph &&
        currentConfig.showGpuUsageGraph == presetConfig.showGpuUsageGraph &&
        currentConfig.showCpuClock == presetConfig.showCpuClock &&
        currentConfig.showGpuClock == presetConfig.showGpuClock &&
        currentConfig.showVramUsage == presetConfig.showVramUsage &&
        currentConfig.showFrameTime == presetConfig.showFrameTime &&
        currentConfig.showFps1Low == presetConfig.showFps1Low &&
        currentConfig.showFps01Low == presetConfig.showFps01Low
}

// fpsLimiterSteps / fpsLimiterCurrentIndex / fpsLimiterProgress /
// nextFpsLimiterValue / previousFpsLimiterValue live in FpsLimiterUtils.kt

/** Performance HUD + FPS limiter state/callbacks as one QuickMenu parameter instead of eight —
 * XServerScreen's call site sits at the dex verifier's 255-register limit, and every argument
 * of that call costs a register there (a runtime VerifyError from exactly that was reproduced
 * on device). */
class PerformanceQuickMenuState(
    val hudEnabled: Boolean = false,
    val hudConfig: PerformanceHudConfig = PerformanceHudConfig(),
    val fpsLimiterEnabled: Boolean = true,
    val fpsLimiterTarget: Int = 60,
    val fpsLimiterMax: Int = 60,
    val onHudConfigChanged: (PerformanceHudConfig) -> Unit = {},
    val onFpsLimiterEnabledChanged: (Boolean) -> Unit = {},
    val onFpsLimiterChanged: (Int) -> Unit = {},
)

@Composable
fun QuickMenu(
    isVisible: Boolean,
    onDismiss: () -> Unit,
    onItemSelected: (Int) -> Boolean,
    renderer: VulkanRenderer? = null,
    container: Container? = null,
    wineProcesses: List<ProcessInfo> = emptyList(),
    isWineProcessesLoading: Boolean = false,
    onToolsVisibilityChanged: (Boolean) -> Unit = {},
    onEndWineProcess: (ProcessInfo) -> Unit = {},
    performance: PerformanceQuickMenuState = PerformanceQuickMenuState(),
    hasPhysicalController: Boolean = false,
    isTouchscreenModeActive: Boolean = false,
    onTouchGestureSettingsClick: () -> Unit = {},
    activeToggleIds: Set<Int> = emptySet(),
    onAnimationComplete: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    // The in-game menu uses the launcher's navy and gold instead of the old purple/cyan look.
    // Everything (sizes and text) is drawn 18% smaller than the system scale: a compact, less bulky panel.
    val systemDensity = androidx.compose.ui.platform.LocalDensity.current
    androidx.compose.runtime.CompositionLocalProvider(
        androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(systemDensity.density * 0.82f, systemDensity.fontScale),
    ) {
    app.gamenative.ui.component.dialog.WowLauncherTheme(colors = app.gamenative.ui.component.dialog.wowOverlayColors) {
        app.gamenative.ui.theme.WowOverlayPluviaColors {
            // The menu speaks the in-app language, not the phone's (its texts are Android resources: values / values-hu).
            app.gamenative.ui.screen.wow.WowLocalizedResources {
            QuickMenuThemed(
                isVisible = isVisible,
                onDismiss = onDismiss,
                onItemSelected = onItemSelected,
                renderer = renderer,
                container = container,
                wineProcesses = wineProcesses,
                isWineProcessesLoading = isWineProcessesLoading,
                onToolsVisibilityChanged = onToolsVisibilityChanged,
                onEndWineProcess = onEndWineProcess,
                performance = performance,
                hasPhysicalController = hasPhysicalController,
                isTouchscreenModeActive = isTouchscreenModeActive,
                onTouchGestureSettingsClick = onTouchGestureSettingsClick,
                activeToggleIds = activeToggleIds,
                onAnimationComplete = onAnimationComplete,
                modifier = modifier,
            )
            }
        }
    }
    }
}

@Composable
private fun QuickMenuThemed(
    isVisible: Boolean,
    onDismiss: () -> Unit,
    onItemSelected: (Int) -> Boolean,
    renderer: VulkanRenderer? = null,
    container: Container? = null,
    wineProcesses: List<ProcessInfo> = emptyList(),
    isWineProcessesLoading: Boolean = false,
    onToolsVisibilityChanged: (Boolean) -> Unit = {},
    onEndWineProcess: (ProcessInfo) -> Unit = {},
    performance: PerformanceQuickMenuState = PerformanceQuickMenuState(),
    hasPhysicalController: Boolean = false,
    isTouchscreenModeActive: Boolean = false,
    onTouchGestureSettingsClick: () -> Unit = {},
    activeToggleIds: Set<Int> = emptySet(),
    onAnimationComplete: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val isPerformanceHudEnabled = performance.hudEnabled
    val performanceHudConfig = performance.hudConfig
    val fpsLimiterEnabled = performance.fpsLimiterEnabled
    val fpsLimiterTarget = performance.fpsLimiterTarget
    val fpsLimiterMax = performance.fpsLimiterMax
    val onPerformanceHudConfigChanged = performance.onHudConfigChanged
    val onFpsLimiterEnabledChanged = performance.onFpsLimiterEnabledChanged
    val onFpsLimiterChanged = performance.onFpsLimiterChanged
    val exitGameItem = QuickMenuItem(
        id = QuickMenuAction.EXIT_GAME,
        icon = Icons.AutoMirrored.Filled.ExitToApp,
        labelResId = R.string.exit_game,
        accentColor = PluviaTheme.colors.accentDanger,
    )

    // Kept here rather than plumbed through XServerScreen: that composable sits at the
    // dex verifier's register limit and extra locals can produce invalid bytecode.

    // Handled inside the menu (no plumbing through XServerScreen): off = the game keeps running behind the menu.
    var pauseOnMenu by remember { mutableStateOf(PrefManager.pauseGameOnQuickMenu) }
    var autoBnetSignIn by remember { mutableStateOf(PrefManager.bnetAutoSignIn) }
    val menuContext = androidx.compose.ui.platform.LocalContext.current

    val controllerItems = buildList {
        add(
            QuickMenuItem(
                id = QuickMenuAction.PAUSE_ON_MENU,
                icon = Icons.Default.Pause,
                labelResId = R.string.pause_game_on_menu,
                accentColor = PluviaTheme.colors.accentPurple,
            )
        )
        add(
            QuickMenuItem(
                id = QuickMenuAction.BATTLE_NET_SIGN_IN,
                icon = Icons.Default.Key,
                labelResId = R.string.battle_net_sign_in,
                accentColor = PluviaTheme.colors.accentPurple,
            )
        )
        add(
            QuickMenuItem(
                id = QuickMenuAction.AUTO_BNET_SIGN_IN,
                icon = Icons.Default.Key,
                labelResId = R.string.bnet_auto_sign_in,
                accentColor = PluviaTheme.colors.accentPurple,
            )
        )
        add(
            QuickMenuItem(
                id = QuickMenuAction.DISABLE_MOUSE,
                icon = Icons.Filled.Mouse,
                labelResId = R.string.disable_mouse_input,
                accentColor = PluviaTheme.colors.accentPurple,
            )
        )
        add(
            QuickMenuItem(
                id = QuickMenuAction.KEYBOARD,
                icon = Icons.Default.Keyboard,
                labelResId = R.string.keyboard,
                accentColor = PluviaTheme.colors.accentPurple,
            )
        )
        add(
            QuickMenuItem(
                id = QuickMenuAction.INPUT_CONTROLS,
                icon = Icons.Default.TouchApp,
                labelResId = R.string.input_controls,
                accentColor = PluviaTheme.colors.accentPurple,
            )
        )
        if (hasPhysicalController) {
            add(
                QuickMenuItem(
                    id = QuickMenuAction.EDIT_PHYSICAL_CONTROLLER,
                    icon = Icons.Default.Gamepad,
                    labelResId = R.string.edit_physical_controller,
                    accentColor = PluviaTheme.colors.accentPurple,
                )
            )
        }
        add(
            QuickMenuItem(
                id = QuickMenuAction.EDIT_CONTROLS,
                icon = Icons.Default.Edit,
                labelResId = R.string.edit_controls,
                accentColor = PluviaTheme.colors.accentPurple,
            )
        )
        add(
            QuickMenuItem(
                id = QuickMenuAction.TOUCHSCREEN_MODE,
                icon = Icons.Default.Fingerprint,
                labelResId = R.string.touchscreen_mode,
                accentColor = PluviaTheme.colors.accentPurple,
            )
        )
        add(
            QuickMenuItem(
                id = QuickMenuAction.RADIAL_MENU,
                icon = Icons.Default.Settings,
                labelResId = R.string.radial_menu,
                accentColor = PluviaTheme.colors.accentPurple,
            )
        )

    }

    var selectedTab by rememberSaveable {
        mutableIntStateOf(
            when {
                PrefManager.quickMenuLastTab == 1 -> QuickMenuTab.HUD
                else -> PrefManager.quickMenuLastTab
            }
        )
    }
    val selectedTabLabelResId = when (selectedTab) {
        QuickMenuTab.HUD -> R.string.performance_hud
        QuickMenuTab.EFFECTS -> R.string.screen_effects
        QuickMenuTab.TOOLS -> R.string.task_manager
        else -> R.string.quick_menu_tab_controller
    }

    val hudScrollState = rememberScrollState()
    val effectsScrollState = rememberScrollState()
    val effectsTabFocusRequester = remember { FocusRequester() }
    val controllerScrollState = rememberScrollState()
    val hudTabFocusRequester = remember { FocusRequester() }
    val controllerTabFocusRequester = remember { FocusRequester() }
    val toolsTabFocusRequester = remember { FocusRequester() }
    val hudItemFocusRequester = remember { FocusRequester() }
    val effectsItemFocusRequester = remember { FocusRequester() }
    val controllerItemFocusRequester = remember { FocusRequester() }
    val toolsItemFocusRequester = remember { FocusRequester() }
    val exitFocusRequester = remember { FocusRequester() }

    val visibleState = remember { MutableTransitionState(false) }
    visibleState.targetState = isVisible

    LaunchedEffect(visibleState.currentState, visibleState.isIdle) {
        if (visibleState.isIdle) {
            onAnimationComplete(visibleState.currentState)
        }
    }

    BackHandler(enabled = isVisible) {
        onDismiss()
    }

    Box(
        modifier = modifier.fillMaxSize(),
    ) {
        AnimatedVisibility(
            visible = isVisible,
            enter = fadeIn(animationSpec = tween(200)),
            exit = fadeOut(animationSpec = tween(150)),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onDismiss,
                    ),
            )
        }

        AnimatedVisibility(
            visibleState = visibleState,
            enter = slideInHorizontally(
                initialOffsetX = { fullWidth -> -fullWidth },
                animationSpec = tween(200),
            ),
            exit = slideOutHorizontally(
                targetOffsetX = { fullWidth -> -fullWidth },
                animationSpec = tween(150),
            ),
            modifier = Modifier.align(Alignment.CenterStart),
        ) {
            val panelWidth = adaptivePanelWidth(440.dp)

            Surface(
                modifier = Modifier
                    .width(panelWidth)
                    .fillMaxHeight()
                    .background(
                        Brush.verticalGradient(listOf(WowTheme.palette.bgTop, WowTheme.palette.bgMid, WowTheme.palette.bgBottom)),
                        RoundedCornerShape(topEnd = 24.dp, bottomEnd = 24.dp),
                    ),
                shape = RoundedCornerShape(topEnd = 24.dp, bottomEnd = 24.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, WowTheme.palette.cardBorder),
                color = Color.Transparent,
                tonalElevation = 0.dp,
                shadowElevation = 24.dp,
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .statusBarsPadding(),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 20.dp, end = 8.dp, top = 16.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = stringResource(R.string.quick_menu_title),
                            style = MaterialTheme.typography.titleLarge.copy(
                                fontWeight = FontWeight.SemiBold,
                            ),
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        QuickMenuCloseButton(onClick = onDismiss)
                    }

                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
                    )

                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 12.dp, vertical = 12.dp),
                    ) {
                        Column(
                            modifier = Modifier
                                .width(64.dp)
                                .fillMaxHeight()
                                .focusGroup(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            val tabScrollState = rememberScrollState()
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .verticalScroll(tabScrollState),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                QuickMenuTabButton(
                                    icon = Icons.Default.QueryStats,
                                    contentDescriptionResId = R.string.performance_hud,
                                    selected = selectedTab == QuickMenuTab.HUD,
                                    accentColor = PluviaTheme.colors.accentPurple,
                                    onSelected = {
                                        selectedTab = QuickMenuTab.HUD
                                        PrefManager.quickMenuLastTab = selectedTab
                                    },
                                    modifier = Modifier.width(56.dp),
                                    focusRequester = hudTabFocusRequester,
                                )
                                if (renderer != null) {
                                    QuickMenuTabButton(
                                        icon = Icons.Default.AutoFixHigh,
                                        contentDescriptionResId = R.string.screen_effects,
                                        selected = selectedTab == QuickMenuTab.EFFECTS,
                                        accentColor = PluviaTheme.colors.accentPurple,
                                        onSelected = {
                                            selectedTab = QuickMenuTab.EFFECTS
                                            PrefManager.quickMenuLastTab = selectedTab
                                        },
                                        modifier = Modifier.width(56.dp),
                                        focusRequester = effectsTabFocusRequester,
                                    )
                                }
                                QuickMenuTabButton(
                                    icon = Icons.Default.Gamepad,
                                    contentDescriptionResId = R.string.quick_menu_tab_controller,
                                    selected = selectedTab == QuickMenuTab.CONTROLLER,
                                    accentColor = PluviaTheme.colors.accentPurple,
                                    onSelected = {
                                        selectedTab = QuickMenuTab.CONTROLLER
                                        PrefManager.quickMenuLastTab = selectedTab
                                    },
                                    modifier = Modifier.width(56.dp),
                                    focusRequester = controllerTabFocusRequester,
                                )
                                QuickMenuTabButton(
                                    icon = Icons.Default.BarChart,
                                    contentDescriptionResId = R.string.task_manager,
                                    selected = selectedTab == QuickMenuTab.TOOLS,
                                    accentColor = PluviaTheme.colors.accentPurple,
                                    onSelected = { selectedTab = QuickMenuTab.TOOLS },
                                    modifier = Modifier.width(56.dp),
                                    focusRequester = toolsTabFocusRequester,
                                )
                            }

                            Box(
                                modifier = Modifier
                                    .padding(horizontal = 4.dp, vertical = 12.dp)
                                    .fillMaxWidth()
                                    .height(1.dp)
                                    .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f)),
                            )

                            QuickMenuRailActionButton(
                                item = exitGameItem,
                                onClick = {
                                    if (onItemSelected(QuickMenuAction.EXIT_GAME)) {
                                        onDismiss()
                                    }
                                },
                                modifier = Modifier.width(56.dp),
                                focusRequester = exitFocusRequester,
                            )
                        }

                        Box(
                            modifier = Modifier
                                .padding(horizontal = 12.dp)
                                .width(1.dp)
                                .fillMaxHeight()
                                .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f)),
                        )

                        Column(
                            modifier = Modifier
                                .fillMaxSize(),
                        ) {
                            Text(
                                text = stringResource(selectedTabLabelResId),
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            )

                            HorizontalDivider(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f),
                            )

                            Box(
                                modifier = Modifier.weight(1f),
                            ) {
                                when (selectedTab) {
                                    QuickMenuTab.HUD -> {
                                        PerformanceHudQuickMenuTab(
                                            isPerformanceHudEnabled = isPerformanceHudEnabled,
                                            performanceHudConfig = performanceHudConfig,
                                            fpsLimiterEnabled = fpsLimiterEnabled,
                                            fpsLimiterTarget = fpsLimiterTarget,
                                            fpsLimiterMax = fpsLimiterMax,
                                            onTogglePerformanceHud = {
                                                onItemSelected(QuickMenuAction.PERFORMANCE_HUD)
                                            },
                                            onPerformanceHudConfigChanged = onPerformanceHudConfigChanged,
                                            onFpsLimiterEnabledChanged = onFpsLimiterEnabledChanged,
                                            onFpsLimiterChanged = onFpsLimiterChanged,
                                            container = container,
                                            scrollState = hudScrollState,
                                            focusRequester = hudItemFocusRequester,
                                            modifier = Modifier.fillMaxSize(),
                                        )
                                    }

                                    QuickMenuTab.EFFECTS -> {
                                        if (renderer != null) {
                                            ScreenEffectsTabContent(
                                                renderer = renderer,
                                                container = container,
                                                modifier = Modifier.fillMaxSize(),
                                                firstItemFocusRequester = effectsItemFocusRequester,
                                                scrollState = effectsScrollState,
                                            )
                                        } else {
                                            Box(
                                                modifier = Modifier
                                                    .fillMaxSize()
                                                    .padding(horizontal = 8.dp, vertical = 16.dp),
                                                contentAlignment = Alignment.TopStart,
                                            ) {
                                                Text(
                                                    text = stringResource(R.string.main_loading),
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                )
                                            }
                                        }
                                    }

                                    QuickMenuTab.TOOLS -> {
                                        ToolsQuickMenuTab(
                                            processes = wineProcesses,
                                            isLoadingProcesses = isWineProcessesLoading,
                                            onEndProcess = onEndWineProcess,
                                            firstItemFocusRequester = toolsItemFocusRequester,
                                            modifier = Modifier.fillMaxSize(),
                                        )
                                    }

                                    QuickMenuTab.CONTROLLER -> {
                                        Column(
                                            modifier = Modifier
                                                .fillMaxSize()
                                                .verticalScroll(controllerScrollState)
                                                .focusGroup(),
                                            verticalArrangement = Arrangement.spacedBy(4.dp),
                                        ) {
                                            controllerItems.forEachIndexed { index, item ->
                                                QuickMenuItemRow(
                                                    item = item,
                                                    isActive = item.id in activeToggleIds ||
                                                        (item.id == QuickMenuAction.PAUSE_ON_MENU && pauseOnMenu) ||
                                                        (item.id == QuickMenuAction.AUTO_BNET_SIGN_IN && autoBnetSignIn),
                                                    onClick = {
                                                        if (item.id == QuickMenuAction.PAUSE_ON_MENU) {
                                                            pauseOnMenu = !pauseOnMenu
                                                            PrefManager.pauseGameOnQuickMenu = pauseOnMenu
                                                            if (!pauseOnMenu && PluviaApp.isOverlayPaused) {
                                                                // Turning it off while the game is suspended resumes it right away.
                                                                PluviaApp.xEnvironment?.onResume()
                                                                PluviaApp.isOverlayPaused = false
                                                            }
                                                        } else if (item.id == QuickMenuAction.AUTO_BNET_SIGN_IN) {
                                                            if (!autoBnetSignIn &&
                                                                !app.gamenative.ui.screen.wow.BattleNetSignIn.hasSaved(menuContext)
                                                            ) {
                                                                app.gamenative.ui.util.SnackbarManager.show(
                                                                    menuContext.getString(R.string.bnet_auto_sign_in_needs_login),
                                                                )
                                                            } else {
                                                                autoBnetSignIn = !autoBnetSignIn
                                                                PrefManager.bnetAutoSignIn = autoBnetSignIn
                                                            }
                                                        } else if (onItemSelected(item.id)) {
                                                            onDismiss()
                                                        }
                                                    },
                                                    focusRequester = if (index == 0) controllerItemFocusRequester else null,
                                                    secondaryIcon = if (item.id == QuickMenuAction.TOUCHSCREEN_MODE && isTouchscreenModeActive)
                                                        Icons.Default.Settings
                                                    else null,
                                                    secondaryContentDescriptionResId = if (item.id == QuickMenuAction.TOUCHSCREEN_MODE && isTouchscreenModeActive)
                                                        R.string.gesture_settings_title
                                                    else null,
                                                    onSecondaryClick = if (item.id == QuickMenuAction.TOUCHSCREEN_MODE && isTouchscreenModeActive)
                                                        onTouchGestureSettingsClick
                                                    else null,
                                                )
                                            }
                                        }
                                    }

                                    else -> Unit
                                }
                            }
                        }
                    }
                }
            }
        }
    }



    LaunchedEffect(isVisible, selectedTab) {
        onToolsVisibilityChanged(isVisible && selectedTab == QuickMenuTab.TOOLS)
    }



    LaunchedEffect(isVisible) {
        if (isVisible) {
            repeat(3) { attempt ->
                try {
                    when (selectedTab) {
                        QuickMenuTab.HUD -> hudItemFocusRequester.requestFocus()
                        QuickMenuTab.EFFECTS -> effectsItemFocusRequester.requestFocus()
                        QuickMenuTab.TOOLS -> toolsItemFocusRequester.requestFocus()
                        else -> controllerItemFocusRequester.requestFocus()
                    }

                    return@LaunchedEffect
                } catch (t: Exception) {

                    delay(80)
                }
            }

        }
    }
}

@Composable
private fun ToolsQuickMenuTab(
    processes: List<ProcessInfo>,
    isLoadingProcesses: Boolean,
    onEndProcess: (ProcessInfo) -> Unit,
    firstItemFocusRequester: FocusRequester? = null,
    modifier: Modifier = Modifier,
) {
    val scrollState = rememberScrollState()
    val accentColor = PluviaTheme.colors.accentPurple

    Column(
        modifier = modifier
            .verticalScroll(scrollState)
            .focusGroup(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        QuickMenuSectionHeader(
            title = if (isLoadingProcesses) {
                stringResource(R.string.main_loading)
            } else {
                stringResource(R.string.tools_wine_processes_running_hint, processes.size)
            },
        )

        if (!isLoadingProcesses && processes.isEmpty()) {
            Text(
                text = stringResource(R.string.tools_wine_processes_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        } else {
            // Two compact columns so the whole task list fits without scrolling.
            @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
            androidx.compose.foundation.layout.FlowRow(
                modifier = Modifier.fillMaxWidth(),
                maxItemsInEachRow = 2,
            ) {
                processes.forEachIndexed { index, process ->
                    QuickMenuDetailRow(
                        title = process.name + if (process.wow64Process) " *32" else "",
                        subtitle = process.formattedMemoryUsage,
                        accentColor = accentColor,
                        onActivate = {
                            onEndProcess(process)
                        },
                        focusRequester = if (index == 0) firstItemFocusRequester else null,
                        compact = true,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun PerformanceHudQuickMenuTab(
    isPerformanceHudEnabled: Boolean,
    performanceHudConfig: PerformanceHudConfig,
    fpsLimiterEnabled: Boolean,
    fpsLimiterTarget: Int,
    fpsLimiterMax: Int,
    onTogglePerformanceHud: () -> Unit,
    onPerformanceHudConfigChanged: (PerformanceHudConfig) -> Unit,
    onFpsLimiterEnabledChanged: (Boolean) -> Unit,
    onFpsLimiterChanged: (Int) -> Unit,
    container: Container?,
    scrollState: ScrollState,
    focusRequester: FocusRequester? = null,
    modifier: Modifier = Modifier,
) {
    val accentColor = PluviaTheme.colors.accentPurple

    Column(
        modifier = modifier
            .verticalScroll(scrollState)
            .focusGroup(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        // ── FPS Limiter (topmost) ────────────────────────────────────────
        QuickMenuToggleRow(
            title = stringResource(R.string.performance_hud_fps_limiter),
            subtitle = null,
            enabled = fpsLimiterEnabled,
            onToggle = { onFpsLimiterEnabledChanged(!fpsLimiterEnabled) },
            accentColor = accentColor,
            focusRequester = focusRequester,
        )

        AnimatedVisibility(
            visible = fpsLimiterEnabled,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            Column {
                Spacer(modifier = Modifier.height(4.dp))
                QuickMenuAdjustmentRow(
                    title = stringResource(R.string.performance_hud_fps_limiter_target),
                    valueText = stringResource(
                        R.string.performance_hud_fps_limiter_value,
                        fpsLimiterTarget,
                    ),
                    progress = fpsLimiterProgress(fpsLimiterTarget, fpsLimiterMax),
                    onDecrease = {
                        onFpsLimiterChanged(previousFpsLimiterValue(fpsLimiterTarget, fpsLimiterMax))
                    },
                    onIncrease = {
                        onFpsLimiterChanged(nextFpsLimiterValue(fpsLimiterTarget, fpsLimiterMax))
                    },
                    onSetProgress = { f ->
                        val steps = fpsLimiterSteps(fpsLimiterMax)
                        val v = steps[(f * steps.lastIndex).roundToInt().coerceIn(0, steps.lastIndex)]
                        if (v != fpsLimiterTarget) onFpsLimiterChanged(v)
                    },
                    accentColor = accentColor,
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // ── Performance HUD ──────────────────────────────────────────────
        QuickMenuToggleRow(
            title = stringResource(R.string.performance_hud),
            subtitle = stringResource(R.string.performance_hud_description),
            enabled = isPerformanceHudEnabled,
            onToggle = onTogglePerformanceHud,
            accentColor = accentColor,
        )

        // Read and written here so the Quick Menu state plumbing stays untouched.
        var hudLocked by remember { mutableStateOf(PrefManager.performanceHudLocked) }
        QuickMenuToggleRow(
            title = stringResource(R.string.performance_hud_lock),
            subtitle = stringResource(R.string.performance_hud_lock_description),
            enabled = hudLocked,
            onToggle = {
                hudLocked = !hudLocked
                PrefManager.performanceHudLocked = hudLocked
            },
            accentColor = accentColor,
        )

        Spacer(modifier = Modifier.height(8.dp))

        QuickMenuSectionHeader(
            title = stringResource(R.string.performance_hud_presets),
        )

        Row(
            modifier = Modifier
                .padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PerformanceHudPreset.values().forEach { preset ->
                QuickMenuChoiceChip(
                    text = stringResource(preset.labelResId),
                    selected = matchesPerformanceHudPreset(performanceHudConfig, preset),
                    accentColor = accentColor,
                    onClick = {
                        onPerformanceHudConfigChanged(applyPerformanceHudPreset(performanceHudConfig, preset))
                        if (!isPerformanceHudEnabled) {
                            onTogglePerformanceHud()
                        }
                    },
                    modifier = Modifier.width(56.dp),
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        QuickMenuSectionHeader(
            title = stringResource(R.string.performance_hud_appearance),
        )

        // The style chips scroll sideways when they do not fit the panel width.
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp),
        ) {
            PerformanceHudStyle.entries.forEach { style ->
                QuickMenuChoiceChip(
                    text = style.label,
                    selected = performanceHudConfig.style == style,
                    accentColor = accentColor,
                    onClick = { onPerformanceHudConfigChanged(performanceHudConfig.copy(style = style)) },
                )
            }
        }
        QuickMenuAdjustmentRow(
            title = stringResource(R.string.performance_hud_size),
            valueText = stringResource(R.string.performance_hud_percentage_value, performanceHudConfig.sizePercent),
            progress = normalizedProgress(performanceHudConfig.sizePercent.toFloat(), 40f, 100f),
            onDecrease = {
                onPerformanceHudConfigChanged(
                    performanceHudConfig.copy(sizePercent = (performanceHudConfig.sizePercent - 1).coerceIn(40, 100)),
                )
            },
            onIncrease = {
                onPerformanceHudConfigChanged(
                    performanceHudConfig.copy(sizePercent = (performanceHudConfig.sizePercent + 1).coerceIn(40, 100)),
                )
            },
            onSetProgress = { f ->
                val v = (40 + f * 60).roundToInt().coerceIn(40, 100)
                if (v != performanceHudConfig.sizePercent) onPerformanceHudConfigChanged(performanceHudConfig.copy(sizePercent = v))
            },
            accentColor = accentColor,
        )

        QuickMenuAdjustmentRow(
            title = stringResource(R.string.performance_hud_background_opacity),
            valueText = stringResource(
                R.string.performance_hud_percentage_value,
                (performanceHudConfig.backgroundOpacity * 100f).roundToInt(),
            ),
            progress = normalizedProgress(performanceHudConfig.backgroundOpacity, 0f, 1f),
            onDecrease = {
                onPerformanceHudConfigChanged(
                    performanceHudConfig.copy(
                        backgroundOpacity = (performanceHudConfig.backgroundOpacity - 0.05f).coerceIn(0f, 1f),
                    ),
                )
            },
            onIncrease = {
                onPerformanceHudConfigChanged(
                    performanceHudConfig.copy(
                        backgroundOpacity = (performanceHudConfig.backgroundOpacity + 0.05f).coerceIn(0f, 1f),
                    ),
                )
            },
            onSetProgress = { f ->
                val v = ((f * 20f).roundToInt() / 20f).coerceIn(0f, 1f)
                if (v != performanceHudConfig.backgroundOpacity) onPerformanceHudConfigChanged(performanceHudConfig.copy(backgroundOpacity = v))
            },
            accentColor = accentColor,
        )

        QuickMenuAdjustmentRow(
            title = stringResource(R.string.performance_hud_color_intensity),
            valueText = stringResource(
                R.string.performance_hud_percentage_value,
                (performanceHudConfig.colorIntensity * 100f).roundToInt(),
            ),
            progress = normalizedProgress(performanceHudConfig.colorIntensity, 0f, 1f),
            onDecrease = {
                onPerformanceHudConfigChanged(
                    performanceHudConfig.copy(
                        colorIntensity = (performanceHudConfig.colorIntensity - 0.05f).coerceIn(0f, 1f),
                    ),
                )
            },
            onIncrease = {
                onPerformanceHudConfigChanged(
                    performanceHudConfig.copy(
                        colorIntensity = (performanceHudConfig.colorIntensity + 0.05f).coerceIn(0f, 1f),
                    ),
                )
            },
            onSetProgress = { f ->
                val v = ((f * 20f).roundToInt() / 20f).coerceIn(0f, 1f)
                if (v != performanceHudConfig.colorIntensity) onPerformanceHudConfigChanged(performanceHudConfig.copy(colorIntensity = v))
            },
            accentColor = accentColor,
        )

        QuickMenuToggleRow(
            title = stringResource(R.string.performance_hud_text_outline),
            enabled = performanceHudConfig.showTextOutline,
            onToggle = {
                onPerformanceHudConfigChanged(
                    performanceHudConfig.copy(showTextOutline = !performanceHudConfig.showTextOutline),
                )
            },
            accentColor = accentColor,
        )

        Spacer(modifier = Modifier.height(8.dp))

        QuickMenuSectionHeader(
            title = stringResource(R.string.performance_hud_metrics),
        )

        val order = HudMetric.normalizeOrder(performanceHudConfig.metricOrder)
        order.forEachIndexed { index, metric ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                QuickMenuToggleRow(
                    title = metric.label,
                    enabled = metric.enabled(performanceHudConfig),
                    onToggle = { onPerformanceHudConfigChanged(metric.toggle(performanceHudConfig)) },
                    accentColor = accentColor,
                    modifier = Modifier.weight(1f),
                    compact = true,
                )
                IconButton(
                    modifier = Modifier.size(36.dp),
                    enabled = index > 0,
                    onClick = { onPerformanceHudConfigChanged(performanceHudConfig.copy(metricOrder = HudMetric.move(order, metric, -1))) },
                ) { Icon(Icons.Default.ArrowUpward, "Move ${metric.label} up", modifier = Modifier.size(18.dp)) }
                IconButton(
                    modifier = Modifier.size(36.dp),
                    enabled = index < order.lastIndex,
                    onClick = { onPerformanceHudConfigChanged(performanceHudConfig.copy(metricOrder = HudMetric.move(order, metric, 1))) },
                ) { Icon(Icons.Default.ArrowDownward, "Move ${metric.label} down", modifier = Modifier.size(18.dp)) }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))
    }
}

@Composable
internal fun QuickMenuSectionHeader(
    title: String,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.SemiBold,
        )
        if (!subtitle.isNullOrBlank()) {
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun QuickMenuCloseButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val shape = RoundedCornerShape(14.dp)

    Box(
        modifier = modifier
            .size(44.dp)
            .clip(shape)
            .background(
                if (isFocused) {
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                } else {
                    Color.Transparent
                },
            )
            .focusRing(interactionSource, shape, width = 2.dp)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            )
            .focusable(interactionSource = interactionSource),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Default.Close,
            contentDescription = stringResource(R.string.quick_menu_back),
            tint = if (isFocused) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.size(24.dp),
        )
    }
}

@Composable
private fun QuickMenuTabButton(
    icon: ImageVector,
    contentDescriptionResId: Int,
    selected: Boolean,
    accentColor: Color,
    onSelected: () -> Unit,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val shape = RoundedCornerShape(14.dp)

    Box(
        modifier = modifier
            .size(56.dp)
            .clip(shape)
            .background(
                when {
                    selected -> accentColor.copy(alpha = 0.11f)
                    isFocused -> accentColor.copy(alpha = 0.12f)
                    else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                },
            )
            .focusRing(interactionSource, shape, width = 2.dp)
            .then(
                if (focusRequester != null) {
                    Modifier.focusRequester(focusRequester)
                } else {
                    Modifier
                }
            )
            .onFocusChanged {
                if (it.isFocused && !selected) {
                    onSelected()
                }
            }
            .selectable(
                selected = selected,
                interactionSource = interactionSource,
                indication = null,
                onClick = onSelected,
            )
            .focusable(interactionSource = interactionSource),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = stringResource(contentDescriptionResId),
            tint = when {
                selected || isFocused -> accentColor
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.size(24.dp),
        )
    }
}

@Composable
private fun QuickMenuRailActionButton(
    item: QuickMenuItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val accentColor = if (item.accentColor != Color.Unspecified) {
        item.accentColor
    } else {
        MaterialTheme.colorScheme.error
    }
    val shape = RoundedCornerShape(14.dp)

    Box(
        modifier = modifier
            .size(56.dp)
            .then(
                if (isFocused) {
                    Modifier.border(
                        width = 2.dp,
                        color = accentColor.copy(alpha = 0.7f),
                        shape = shape,
                    )
                } else {
                    Modifier.border(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f),
                        shape = shape,
                    )
                }
            )
            .clip(shape)
            .background(
                if (isFocused) {
                    accentColor.copy(alpha = 0.11f)
                } else {
                    accentColor.copy(alpha = 0.08f)
                },
            )
            .then(
                if (focusRequester != null) {
                    Modifier.focusRequester(focusRequester)
                } else {
                    Modifier
                }
            )
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            )
            .focusable(interactionSource = interactionSource),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = item.icon,
            contentDescription = stringResource(item.labelResId),
            tint = if (isFocused) accentColor else accentColor.copy(alpha = 0.9f),
            modifier = Modifier.size(24.dp),
        )
    }
}

@Composable
internal fun QuickMenuChoiceChip(
    text: String,
    selected: Boolean,
    accentColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val shape = RoundedCornerShape(12.dp)

    Box(
        modifier = modifier
            .height(44.dp)
            .then(
                if (isFocused) {
                    Modifier.border(
                        width = 2.dp,
                        color = accentColor.copy(alpha = 0.7f),
                        shape = shape,
                    )
                } else {
                    Modifier.border(
                        width = 1.dp,
                        color = if (selected) accentColor.copy(alpha = 0.55f) else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f),
                        shape = shape,
                    )
                }
            )
            .clip(shape)
            .background(
                when {
                    selected -> accentColor.copy(alpha = 0.11f)
                    isFocused -> accentColor.copy(alpha = 0.12f)
                    else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.22f)
                },
            )
            .then(
                if (focusRequester != null) {
                    Modifier.focusRequester(focusRequester)
                } else {
                    Modifier
                }
            )
            .selectable(
                selected = selected,
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            )
            .focusable(interactionSource = interactionSource)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected || isFocused) accentColor else MaterialTheme.colorScheme.onSurface,
            fontWeight = if (selected || isFocused) FontWeight.SemiBold else FontWeight.Medium,
        )
    }
}

@Composable
internal fun QuickMenuAdjustmentRow(
    title: String,
    valueText: String,
    progress: Float,
    onDecrease: () -> Unit,
    onIncrease: () -> Unit,
    accentColor: Color,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
    subtitle: String? = null,
    /** Touch: tap or drag on the track sets the value (0f..1f along the track). */
    onSetProgress: ((Float) -> Unit)? = null,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val shape = RoundedCornerShape(14.dp)
    var isAdjustmentLocked by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .padding(horizontal = 8.dp, vertical = 2.dp)
            .clip(shape)
            .background(
                if (isFocused) {
                    Brush.horizontalGradient(
                        colors = listOf(
                            accentColor.copy(alpha = 0.10f),
                            accentColor.copy(alpha = 0.08f),
                        ),
                    )
                } else {
                    Brush.horizontalGradient(
                        colors = listOf(
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.18f),
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.10f),
                        ),
                    )
                },
            )
            .then(
                if (isFocused && !isAdjustmentLocked) {
                    Modifier.border(
                        width = 2.dp,
                        color = accentColor.copy(alpha = 0.7f),
                        shape = shape,
                    )
                } else {
                    Modifier
                }
            )
            .then(
                if (focusRequester != null) {
                    Modifier.focusRequester(focusRequester)
                } else {
                    Modifier
                }
            )
            .onFocusChanged {
                if (!it.isFocused) {
                    if (isAdjustmentLocked) {

                    }
                    isAdjustmentLocked = false
                }
            }
            .focusable(interactionSource = interactionSource)
            .onPreviewKeyEvent { keyEvent ->
                if (keyEvent.nativeKeyEvent.action == KeyEvent.ACTION_DOWN && isFocused) {
                    when {
                        keyEvent.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_BUTTON_A -> {
                            isAdjustmentLocked = !isAdjustmentLocked

                            true
                        }

                        isAdjustmentLocked &&
                            keyEvent.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_BUTTON_B -> {
                            isAdjustmentLocked = false
                            true
                        }

                        isAdjustmentLocked && keyEvent.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_DPAD_LEFT -> {
                            onDecrease()
                            true
                        }

                        isAdjustmentLocked && keyEvent.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT -> {
                            onIncrease()
                            true
                        }

                        else -> false
                    }
                } else {
                    false
                }
            }
            .selectable(
                selected = isFocused,
                interactionSource = interactionSource,
                indication = null,
                onClick = {},
            )
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = if (isFocused) FontWeight.SemiBold else FontWeight.Medium,
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = valueText,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (isFocused) accentColor else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (isAdjustmentLocked) {
                    Text(
                        text = "●",
                        style = MaterialTheme.typography.labelSmall,
                        color = accentColor,
                    )
                }
            }
        }

        if (subtitle != null) {
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(modifier = Modifier.height(10.dp))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(36.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            QuickMenuAdjustmentButton(
                text = "-",
                rowIsFocused = isFocused,
                isAdjustmentLocked = isAdjustmentLocked,
                accentColor = accentColor,
                onClick = onDecrease,
            )

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                contentAlignment = Alignment.Center,
            ) {
                if (onSetProgress != null) {
                    // A real slider: tap anywhere on the track or drag the thumb.
                    val setProgress by androidx.compose.runtime.rememberUpdatedState(onSetProgress)
                    val trackColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
                    val shown = progress.coerceIn(0f, 1f)
                    androidx.compose.foundation.Canvas(
                        modifier = Modifier
                            .fillMaxSize()
                            .sizedPointerInput { size ->
                                detectTapGestures { offset ->
                                    setProgress((offset.x / size.width).coerceIn(0f, 1f))
                                }
                            }
                            .sizedPointerInput { size ->
                                detectHorizontalDragGestures(
                                    onDragStart = { offset -> setProgress((offset.x / size.width).coerceIn(0f, 1f)) },
                                    onHorizontalDrag = { change, _ ->
                                        change.consume()
                                        setProgress((change.position.x / size.width).coerceIn(0f, 1f))
                                    },
                                )
                            },
                    ) {
                        val barHeight = 8.dp.toPx()
                        val radius = 10.dp.toPx()
                        val centerY = size.height / 2f
                        val corner = androidx.compose.ui.geometry.CornerRadius(barHeight / 2f)
                        drawRoundRect(trackColor, androidx.compose.ui.geometry.Offset(0f, centerY - barHeight / 2f),
                            androidx.compose.ui.geometry.Size(size.width, barHeight), corner)
                        drawRoundRect(accentColor, androidx.compose.ui.geometry.Offset(0f, centerY - barHeight / 2f),
                            androidx.compose.ui.geometry.Size(size.width * shown, barHeight), corner)
                        val thumbX = (size.width * shown).coerceIn(radius, (size.width - radius).coerceAtLeast(radius))
                        drawCircle(accentColor, radius, androidx.compose.ui.geometry.Offset(thumbX, centerY))
                        drawCircle(WowTheme.palette.overlaySurface, radius - 4.dp.toPx(), androidx.compose.ui.geometry.Offset(thumbX, centerY))
                    }
                } else {
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .clip(RoundedCornerShape(999.dp)),
                    color = accentColor,
                    trackColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
                )

                Row(modifier = Modifier.fillMaxSize()) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = onDecrease,
                            ),
                    )
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = onIncrease,
                            ),
                    )
                }
                }
            }

            QuickMenuAdjustmentButton(
                text = "+",
                rowIsFocused = isFocused,
                isAdjustmentLocked = isAdjustmentLocked,
                accentColor = accentColor,
                onClick = onIncrease,
            )
        }
    }
}

@Composable
private fun QuickMenuAdjustmentButton(
    text: String,
    rowIsFocused: Boolean,
    isAdjustmentLocked: Boolean,
    accentColor: Color,
    onClick: () -> Unit,
) {
    // Every step changes the HUD config and so recreates onClick; the gesture must keep running with the newest one
    // instead of restarting (a changing pointerInput key cancelled the hold's own repeater after the first step).
    val latestOnClick by androidx.compose.runtime.rememberUpdatedState(onClick)
    Box(
        modifier = Modifier
            .width(44.dp)
            .fillMaxHeight()
            .clip(RoundedCornerShape(10.dp))
            .background(
                if (isAdjustmentLocked) {
                    accentColor.copy(alpha = 0.25f)
                } else {
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = if (rowIsFocused) 0.32f else 0.45f)
                },
            )
            .border(
                width = if (isAdjustmentLocked) 2.dp else 1.dp,
                color = if (isAdjustmentLocked) {
                    accentColor.copy(alpha = 0.9f)
                } else {
                    MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
                },
                shape = RoundedCornerShape(10.dp),
            )
            // A tap steps once; holding the button repeats the step (so 40..100% is not 60 separate taps).
            .pointerInput(Unit) {
                val repeated = BooleanArray(1)
                detectTapGestures(
                    onPress = {
                        repeated[0] = false
                        kotlinx.coroutines.coroutineScope {
                            val repeater = launch {
                                kotlinx.coroutines.delay(450)
                                while (true) {
                                    repeated[0] = true
                                    latestOnClick()
                                    kotlinx.coroutines.delay(70)
                                }
                            }
                            tryAwaitRelease()
                            repeater.cancel()
                        }
                    },
                    onTap = { if (!repeated[0]) latestOnClick() },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = if (isAdjustmentLocked) accentColor else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
internal fun QuickMenuToggleRow(
    title: String,
    enabled: Boolean,
    selectable: Boolean = true,
    onToggle: () -> Unit,
    accentColor: Color,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    focusRequester: FocusRequester? = null,
    compact: Boolean = false,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 2.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(
                if (isFocused) {
                    Brush.horizontalGradient(
                        colors = listOf(
                            accentColor.copy(alpha = 0.10f),
                            accentColor.copy(alpha = 0.08f),
                        ),
                    )
                } else {
                    Brush.horizontalGradient(
                        colors = listOf(
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.18f),
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.10f),
                        ),
                    )
                },
            )
            .then(
                if (isFocused) {
                    Modifier.border(
                        width = 2.dp,
                        color = accentColor.copy(alpha = 0.7f),
                        shape = RoundedCornerShape(14.dp),
                    )
                } else {
                    Modifier
                }
            )
            .then(
                if (focusRequester != null) {
                    Modifier.focusRequester(focusRequester)
                } else {
                    Modifier
                }
            )
            .selectable(
                selected = isFocused,
                interactionSource = interactionSource,
                indication = null,
                onClick = { if (selectable) onToggle() },
            )
            .focusable(interactionSource = interactionSource)
            .padding(horizontal = if (compact) 12.dp else 16.dp, vertical = if (compact) 8.dp else 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 16.dp),
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .then(if (!selectable) Modifier.alpha(0.5f) else Modifier)
        ) {
            Text(
                text = title,
                maxLines = 2,
                style = if (compact) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = if (isFocused) FontWeight.SemiBold else FontWeight.Medium,
            )
            if (!subtitle.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Box(
            modifier = if (!selectable) Modifier.alpha(0.5f) else Modifier
        ) {
            QuickMenuSwitch(
                enabled = enabled,
                accentColor = accentColor,
            )
        }
    }
}

@Composable
private fun QuickMenuSwitch(
    enabled: Boolean,
    accentColor: Color,
) {
    Box(
        modifier = Modifier
            .width(56.dp)
            .height(32.dp)
            .clip(RoundedCornerShape(999.dp))
            .background(
                if (enabled) accentColor else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
            )
            .border(
                width = 1.dp,
                color = if (enabled) accentColor.copy(alpha = 0.8f) else MaterialTheme.colorScheme.outline.copy(alpha = 0.35f),
                shape = RoundedCornerShape(999.dp),
            )
            .padding(4.dp),
    ) {
        Box(
            modifier = Modifier
                .size(22.dp)
                .align(if (enabled) Alignment.CenterEnd else Alignment.CenterStart)
                .background(Color.White, CircleShape),
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun QuickMenuDetailRow(
    title: String,
    subtitle: String,
    accentColor: Color,
    onActivate: () -> Unit,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
    compact: Boolean = false,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val shape = RoundedCornerShape(if (compact) 10.dp else 14.dp)

    Row(
        modifier = modifier
            .then(if (compact) Modifier else Modifier.fillMaxWidth())
            .padding(horizontal = if (compact) 4.dp else 8.dp, vertical = if (compact) 1.dp else 2.dp)
            .clip(shape)
            .background(
                if (isFocused) {
                    Brush.horizontalGradient(
                        colors = listOf(
                            accentColor.copy(alpha = 0.09f),
                            accentColor.copy(alpha = 0.06f),
                        ),
                    )
                } else {
                    Brush.horizontalGradient(
                        colors = listOf(
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.18f),
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.10f),
                        ),
                    )
                },
            )
            .then(
                if (isFocused) {
                    Modifier.border(
                        width = 2.dp,
                        color = accentColor.copy(alpha = 0.8f),
                        shape = shape,
                    )
                } else {
                    Modifier
                }
            )
            .then(
                if (focusRequester != null) {
                    Modifier.focusRequester(focusRequester)
                } else {
                    Modifier
                }
            )
            .focusable(interactionSource = interactionSource)
            .onPreviewKeyEvent { keyEvent ->
                if (keyEvent.nativeKeyEvent.action == KeyEvent.ACTION_DOWN && isFocused) {
                    when (keyEvent.nativeKeyEvent.keyCode) {
                        KeyEvent.KEYCODE_BUTTON_A,
                        KeyEvent.KEYCODE_DPAD_CENTER,
                        KeyEvent.KEYCODE_ENTER -> {
                            onActivate()
                            true
                        }

                        else -> false
                    }
                } else {
                    false
                }
            }
            .selectable(
                selected = isFocused,
                interactionSource = interactionSource,
                indication = null,
                onClick = onActivate,
            )
            .padding(horizontal = if (compact) 10.dp else 16.dp, vertical = if (compact) 7.dp else 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = if (compact) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodyLarge,
                color = if (isFocused) accentColor else MaterialTheme.colorScheme.onSurface,
                fontWeight = if (isFocused) FontWeight.SemiBold else FontWeight.Medium,
                maxLines = 1,
                modifier = Modifier
                    .fillMaxWidth()
                    .basicMarquee(iterations = Int.MAX_VALUE),
            )
            Spacer(modifier = Modifier.height(if (compact) 0.dp else 4.dp))
            Text(
                text = subtitle,
                style = if (compact) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium,
                color = if (isFocused) accentColor.copy(alpha = 0.92f) else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun QuickMenuItemRow(
    item: QuickMenuItem,
    isActive: Boolean = false,
    onClick: () -> Unit,
    focusRequester: FocusRequester? = null,
    secondaryIcon: ImageVector? = null,
    secondaryContentDescriptionResId: Int? = null,
    onSecondaryClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val fallbackFocusRequester = remember { FocusRequester() }
    val rowFocusRequester = focusRequester ?: fallbackFocusRequester
    val secondaryFocusRequester = remember { FocusRequester() }
    val secondaryInteractionSource = remember { MutableInteractionSource() }
    val isSecondaryFocused by secondaryInteractionSource.collectIsFocusedAsState()
    val isEnabled = item.enabled
    val hasSecondaryAction = secondaryIcon != null && onSecondaryClick != null

    val accentColor = if (item.accentColor != Color.Unspecified) {
        item.accentColor
    } else {
        MaterialTheme.colorScheme.primary
    }

    val disabledAlpha = 0.4f
    val shape = RoundedCornerShape(12.dp)

    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .clip(shape)
                .then(
                    if (isFocused && isEnabled) {
                        Modifier.background(
                            brush = Brush.horizontalGradient(
                                colors = listOf(
                                    accentColor.copy(alpha = 0.09f),
                                    accentColor.copy(alpha = 0.05f),
                                ),
                            ),
                        )
                    } else Modifier
                )
                .then(
                    if (isEnabled) Modifier.focusRing(interactionSource, shape, width = 2.dp)
                    else Modifier
                )
                .focusRequester(rowFocusRequester)
                .onPreviewKeyEvent { keyEvent ->
                    if (keyEvent.nativeKeyEvent.action != KeyEvent.ACTION_DOWN || !isFocused) {
                        return@onPreviewKeyEvent false
                    }
                    when (keyEvent.nativeKeyEvent.keyCode) {
                        KeyEvent.KEYCODE_DPAD_RIGHT -> {
                            if (hasSecondaryAction) {
                                secondaryFocusRequester.requestFocus()
                                true
                            } else false
                        }
                        KeyEvent.KEYCODE_BUTTON_A,
                        KeyEvent.KEYCODE_DPAD_CENTER,
                        KeyEvent.KEYCODE_ENTER,
                        -> {
                            onClick()
                            true
                        }
                        else -> false
                    }
                }
                .selectable(
                    selected = isFocused,
                    enabled = isEnabled,
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = onClick,
                )
                .focusable(enabled = isEnabled, interactionSource = interactionSource)
                .padding(horizontal = 12.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .then(
                        if (isActive) Modifier.border(BorderStroke(2.dp, accentColor), CircleShape)
                        else Modifier
                    )
                    .clip(CircleShape)
                    .background(
                        when {
                            !isEnabled -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                            isFocused || isActive -> accentColor.copy(alpha = 0.2f)
                            else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                        },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = item.icon,
                    contentDescription = null,
                    tint = when {
                        !isEnabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = disabledAlpha)
                        isFocused || isActive -> accentColor
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.size(22.dp),
                )
            }

            Text(
                text = stringResource(item.labelResId),
                style = MaterialTheme.typography.bodyLarge,
                color = when {
                    !isEnabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = disabledAlpha)
                    isFocused -> accentColor
                    else -> MaterialTheme.colorScheme.onSurface
                },
                modifier = Modifier.weight(1f),
            )
        }

        if (secondaryIcon != null && onSecondaryClick != null) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .focusRing(secondaryInteractionSource, CircleShape, width = 2.dp)
                    .clip(CircleShape)
                    .background(
                        if (isSecondaryFocused) accentColor.copy(alpha = 0.2f)
                        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    )
                    .focusRequester(secondaryFocusRequester)
                    .onPreviewKeyEvent { keyEvent ->
                        if (keyEvent.nativeKeyEvent.action != KeyEvent.ACTION_DOWN || !isSecondaryFocused) {
                            return@onPreviewKeyEvent false
                        }
                        when (keyEvent.nativeKeyEvent.keyCode) {
                            KeyEvent.KEYCODE_DPAD_LEFT -> {
                                rowFocusRequester.requestFocus()
                                true
                            }
                            KeyEvent.KEYCODE_BUTTON_A,
                            KeyEvent.KEYCODE_DPAD_CENTER,
                            KeyEvent.KEYCODE_ENTER,
                            -> {
                                onSecondaryClick()
                                true
                            }
                            else -> false
                        }
                    }
                    .clickable(
                        interactionSource = secondaryInteractionSource,
                        indication = null,
                        role = Role.Button,
                        onClick = onSecondaryClick,
                    )
                    .focusable(interactionSource = secondaryInteractionSource),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = secondaryIcon,
                    contentDescription = stringResource(
                        secondaryContentDescriptionResId ?: R.string.gesture_settings_title,
                    ),
                    tint = if (isSecondaryFocused) accentColor else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

/** pointerInput(Unit) whose block also receives the element size, for the slider gestures. */
private fun Modifier.sizedPointerInput(
    block: suspend androidx.compose.ui.input.pointer.PointerInputScope.(androidx.compose.ui.unit.IntSize) -> Unit,
): Modifier = this.then(
    androidx.compose.ui.Modifier.pointerInput(Unit) { block(size) },
)
