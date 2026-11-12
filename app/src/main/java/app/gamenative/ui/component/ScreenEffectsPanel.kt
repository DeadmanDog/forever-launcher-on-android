package app.gamenative.ui.component

import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.KeyEvent
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.gamenative.R
import app.gamenative.display.GameNativeDrm
import app.gamenative.display.GamescopeEffects
import app.gamenative.display.GamescopePictureLook
import app.gamenative.display.WowDisplayBackend
import app.gamenative.display.WowGamescopeRuntime
import app.gamenative.ui.screen.wow.WowI18n
import app.gamenative.ui.theme.PluviaTheme
import app.gamenative.ui.util.ScreenEffectsConfig
import app.gamenative.ui.util.applyScreenEffectsConfig
import app.gamenative.ui.util.loadScreenEffectsConfig
import app.gamenative.ui.util.persistScreenEffectsConfig
import app.gamenative.utils.BrightnessManager
import com.winlator.container.Container
import com.winlator.renderer.VulkanRenderer
import com.winlator.xenvironment.ImageFs
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

private const val SCREEN_EFFECT_PERCENT_STEP = 5f
private const val SCREEN_EFFECT_GAMMA_STEP = 0.1f

/** The effect settings are written one at a time, in the order the player made the changes. */
private val effectsPersistLock = Mutex()

private val VULKAN_SUPPORTED_SCALING_MODES = listOf(
    ScreenEffectsConfig.SCALING_MODE_NONE,
    ScreenEffectsConfig.SCALING_MODE_NEAREST,
    ScreenEffectsConfig.SCALING_MODE_LINEAR,
    ScreenEffectsConfig.SCALING_MODE_FILL,
    ScreenEffectsConfig.SCALING_MODE_STRETCH,
    ScreenEffectsConfig.SCALING_MODE_FSR,
    ScreenEffectsConfig.SCALING_MODE_FSR_ASPECT,
    ScreenEffectsConfig.SCALING_MODE_DLS,
    ScreenEffectsConfig.SCALING_MODE_NATURAL,
)

private fun scalingModeLabelRes(mode: Int): Int = when (mode) {
    ScreenEffectsConfig.SCALING_MODE_NEAREST -> R.string.screen_effects_scaling_mode_nearest
    ScreenEffectsConfig.SCALING_MODE_LINEAR -> R.string.screen_effects_scaling_mode_linear
    ScreenEffectsConfig.SCALING_MODE_FILL -> R.string.screen_effects_scaling_mode_fill
    ScreenEffectsConfig.SCALING_MODE_STRETCH -> R.string.screen_effects_scaling_mode_stretch
    ScreenEffectsConfig.SCALING_MODE_FSR -> R.string.screen_effects_scaling_mode_fsr
    ScreenEffectsConfig.SCALING_MODE_FSR_ASPECT -> R.string.screen_effects_scaling_mode_fsr_aspect
    ScreenEffectsConfig.SCALING_MODE_DLS -> R.string.screen_effects_scaling_mode_dls
    ScreenEffectsConfig.SCALING_MODE_NATURAL -> R.string.screen_effects_scaling_mode_natural
    else -> R.string.screen_effects_scaling_mode_none
}

private fun scalingModeDescRes(mode: Int): Int = when (mode) {
    ScreenEffectsConfig.SCALING_MODE_NEAREST -> R.string.screen_effects_scaling_mode_nearest_desc
    ScreenEffectsConfig.SCALING_MODE_LINEAR -> R.string.screen_effects_scaling_mode_linear_desc
    ScreenEffectsConfig.SCALING_MODE_FILL -> R.string.screen_effects_scaling_mode_fill_desc
    ScreenEffectsConfig.SCALING_MODE_STRETCH -> R.string.screen_effects_scaling_mode_stretch_desc
    ScreenEffectsConfig.SCALING_MODE_FSR -> R.string.screen_effects_scaling_mode_fsr_desc
    ScreenEffectsConfig.SCALING_MODE_FSR_ASPECT -> R.string.screen_effects_scaling_mode_fsr_aspect_desc
    ScreenEffectsConfig.SCALING_MODE_DLS -> R.string.screen_effects_scaling_mode_dls_desc
    ScreenEffectsConfig.SCALING_MODE_NATURAL -> R.string.screen_effects_scaling_mode_natural_desc
    else -> R.string.screen_effects_scaling_mode_none_desc
}

// Scaling modes split into an "Upscaling" group (super-resolution / sharpen) and a
// "Basic scaling" group, per renderer (GL does not implement DLS/Natural).
private val VULKAN_UPSCALING_MODES = listOf(
    ScreenEffectsConfig.SCALING_MODE_FSR,
    ScreenEffectsConfig.SCALING_MODE_FSR_ASPECT,
    ScreenEffectsConfig.SCALING_MODE_DLS,
)
// "None" is rendered as a standalone first row, so it is omitted from these lists.
private val VULKAN_BASIC_MODES = listOf(
    ScreenEffectsConfig.SCALING_MODE_NEAREST,
    ScreenEffectsConfig.SCALING_MODE_LINEAR,
    ScreenEffectsConfig.SCALING_MODE_FILL,
    ScreenEffectsConfig.SCALING_MODE_STRETCH,
    // TODO: "Natural" is a color/tone filter, not a scaling mode. Kept here so it
    // stays reachable until it moves to the Effects group (needs an EFFECT_MASK_NATURAL
    // path in window.frag so it can compose with the upscaler).
    ScreenEffectsConfig.SCALING_MODE_NATURAL,
)

@Composable
private fun DisplayBrightnessRow(
    focusRequester: FocusRequester? = null,
) {
    val context = LocalContext.current
    val activity = remember(context) { BrightnessManager.findActivity(context) }
    // Without an Activity there is no window brightness target to control.
    if (activity == null) return
    var displayBrightness by remember(activity) {
        mutableFloatStateOf(BrightnessManager.readDisplayBrightness(activity))
    }

    DisposableEffect(activity) {
        val contentResolver = activity.contentResolver
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                displayBrightness = BrightnessManager.readDisplayBrightness(activity)
            }
        }

        contentResolver.registerContentObserver(
            Settings.System.getUriFor(Settings.System.SCREEN_BRIGHTNESS),
            false,
            observer,
        )
        contentResolver.registerContentObserver(
            Settings.System.getUriFor(Settings.System.SCREEN_BRIGHTNESS_MODE),
            false,
            observer,
        )

        onDispose {
            contentResolver.unregisterContentObserver(observer)
        }
    }

    fun setDisplayBrightness(value: Float) {
        val next = BrightnessManager.snapDisplayBrightness(value)
        displayBrightness = next
        BrightnessManager.applyDisplayBrightness(activity, next)
    }

    ScreenEffectAdjustmentRow(
        title = stringResource(R.string.display_brightness),
        valueText = stringResource(
            R.string.performance_hud_percentage_value,
            (displayBrightness * 100f).roundToInt(),
        ),
        progress = normalizedProgress(
            displayBrightness,
            BrightnessManager.DISPLAY_BRIGHTNESS_MIN,
            BrightnessManager.DISPLAY_BRIGHTNESS_MAX,
        ),
        onDecrease = {
            setDisplayBrightness(displayBrightness - BrightnessManager.DISPLAY_BRIGHTNESS_STEP)
        },
        onIncrease = {
            setDisplayBrightness(displayBrightness + BrightnessManager.DISPLAY_BRIGHTNESS_STEP)
        },
        focusRequester = focusRequester,
    )
}

@Composable
fun ScreenEffectsTabContent(
    renderer: VulkanRenderer,
    modifier: Modifier = Modifier,
    container: Container? = null,
    firstItemFocusRequester: FocusRequester? = null,
    scrollState: ScrollState = rememberScrollState(),
) {
    val context = LocalContext.current
    val gamescope = WowDisplayBackend.isGamescope(container)
    val gamescopeInitial = remember(container, gamescope) {
        container?.takeIf { gamescope }?.let {
            GamescopeEffects.load(it).copy(look = GameNativeDrm.activeEffects?.look ?: GamescopePictureLook.OFF)
        }
    }
    var gamescopeEffectMounted by remember(container, gamescope) { mutableStateOf(false) }
    var pictureLook by remember(container, gamescope) { mutableStateOf(gamescopeInitial?.look ?: GamescopePictureLook.OFF) }
    var gamescopeMessage by remember(container) { mutableStateOf<String?>(null) }
    val initialConfig = remember(renderer, container) { loadScreenEffectsConfig(container) }

    val sanitizedInitialMode = if (initialConfig.scalingMode in VULKAN_SUPPORTED_SCALING_MODES) {
        initialConfig.scalingMode
    } else {
        ScreenEffectsConfig.SCALING_MODE_NONE
    }

    var scalingMode by remember(renderer, container) {
        mutableIntStateOf(gamescopeInitial?.mode ?: sanitizedInitialMode)
    }
    var fsrSharpnessLevel by remember(renderer, container) {
        mutableIntStateOf(gamescopeInitial?.sharpness ?: initialConfig.fsrSharpnessLevel)
    }
    var brightness by remember(renderer, container) {
        mutableFloatStateOf(initialConfig.brightness)
    }
    var contrast by remember(renderer, container) {
        mutableFloatStateOf(initialConfig.contrast)
    }
    var gamma by remember(renderer, container) {
        mutableFloatStateOf(initialConfig.gamma)
    }
    var enableToon by remember(renderer, container) {
        mutableStateOf(initialConfig.enableToon)
    }
    var enableFXAA by remember(renderer, container) {
        mutableStateOf(initialConfig.enableFXAA)
    }
    var enableVivid by remember(renderer, container) {
        mutableStateOf(initialConfig.enableVivid)
    }
    var enableCRT by remember(renderer, container) {
        mutableStateOf(initialConfig.enableCRT)
    }
    var enableNTSC by remember(renderer, container) {
        mutableStateOf(initialConfig.enableNTSC)
    }

    LaunchedEffect(
        scalingMode,
        fsrSharpnessLevel,
        pictureLook,
        brightness,
        contrast,
        gamma,
        enableToon,
        enableFXAA,
        enableVivid,
        enableCRT,
        enableNTSC,
    ) {
        val config = initialConfig.copy(
            scalingMode = scalingMode,
            fsrSharpnessLevel = fsrSharpnessLevel,
            brightness = brightness,
            contrast = contrast,
            gamma = gamma,
            enableToon = enableToon,
            enableFXAA = enableFXAA,
            enableVivid = enableVivid,
            enableCRT = enableCRT,
            enableNTSC = enableNTSC,
        )
        if (gamescope && container != null) {
            val next = GamescopeEffects(scalingMode, fsrSharpnessLevel, pictureLook, config.copy(
                scalingMode = ScreenEffectsConfig.SCALING_MODE_NONE,
                fsrSharpnessLevel = ScreenEffectsConfig.FSR_DEFAULT_LEVEL))
            if (!gamescopeEffectMounted) {
                gamescopeEffectMounted = true
                if (next == gamescopeInitial) return@LaunchedEffect
            }
            val live = try {
                persistGamescopeEffects(context, container, next)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                gamescopeMessage = WowI18n.pick(context, "Could not save the Gamescope effects.",
                    "A Gamescope-effektek mentése sikertelen.")
                return@LaunchedEffect
            }
            GameNativeDrm.activeEffects?.let { renderer.setGamescopeInputScaling(it.inputScalingMode) }
            // The output size is fixed when the session starts (game-sized unless an upscaler was on, to save power): an upscaler
            // chosen mid-session runs at 1:1 (FSR only sharpens, NIS is off) until the next start. Say so instead of nothing.
            val outputNow = GameNativeDrm.outputSize
            val needsRestart = next.needsPanelOutput && outputNow != null &&
                !GamescopeEffects.canUpscale(WowGamescopeRuntime.geometry(container.screenSize), outputNow)
            gamescopeMessage = if (needsRestart) WowI18n.pick(context,
                "Saved. FSR and NIS upscale fully from the next game start.",
                "Mentve. Az FSR és a NIS a következő játékindítástól skáláz fel teljesen.")
            else if (live) null else WowI18n.pick(context,
                "Saved. Gamescope could not apply every setting live. NIS needs a game resolution smaller than the output.",
                "Mentve. A Gamescope nem tudott minden beállítást élőben alkalmazni. A NIS a kimenetnél kisebb játékfelbontást igényel.")
            return@LaunchedEffect
        }
        // Apply immediately for live preview
        applyScreenEffectsConfig(renderer, config)
        // Debounce persist to disk
        delay(300)
        // The write (it ends in an fsync) happens off the UI thread; it is not cancelled half-way when the next slider step
        // restarts this effect, and writes never overlap.
        withContext(Dispatchers.IO + NonCancellable) {
            effectsPersistLock.withLock {
                persistScreenEffectsConfig(container, config)
                container?.saveData()
            }
        }
    }

    fun resetEffects() {
        scalingMode = ScreenEffectsConfig.SCALING_MODE_NONE
        fsrSharpnessLevel = ScreenEffectsConfig.FSR_DEFAULT_LEVEL
        if (gamescope) pictureLook = GamescopePictureLook.OFF
        brightness = 0f
        contrast = 0f
        gamma = 1.0f
        enableToon = false
        enableFXAA = false
        enableVivid = false
        enableCRT = false
        enableNTSC = false
    }

    Column(
        modifier = modifier
            .verticalScroll(scrollState)
            .focusGroup()
            .padding(vertical = 12.dp),
    ) {
        DisplayBrightnessRow(focusRequester = firstItemFocusRequester)

        if (gamescope) {
            gamescopeMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            GameNativeDrm.outputSize?.let { (width, height) ->
                val guest = container?.screenSize.orEmpty().replace('x', '×')
                Text(WowI18n.pick(context, "Game $guest · Gamescope output ${width}×$height",
                    "Játék $guest · Gamescope-kimenet ${width}×$height"), style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.height(20.dp))
            OptionSectionHeader(text = WowI18n.pick(context, "DroidDeck picture presets", "DroidDeck képprofilok"))
            Text(WowI18n.pick(context, "Picture presets apply to the current game session only.",
                "A képprofil csak az aktuális játékmenetre érvényes."), style = MaterialTheme.typography.bodySmall)
            GamescopePictureLook.entries.forEach { look ->
                ScreenEffectRadioRow(
                    title = if (look == GamescopePictureLook.OFF) WowI18n.pick(context, "Off", "Ki") else look.label,
                    selected = pictureLook == look,
                    onSelect = { pictureLook = look },
                )
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        ScreenEffectRadioRow(
            title = stringResource(scalingModeLabelRes(ScreenEffectsConfig.SCALING_MODE_NONE)),
            selected = scalingMode == ScreenEffectsConfig.SCALING_MODE_NONE,
            onSelect = { scalingMode = ScreenEffectsConfig.SCALING_MODE_NONE },
        )

        Spacer(modifier = Modifier.height(20.dp))

        OptionSectionHeader(text = stringResource(R.string.screen_effects_upscaling))
        VULKAN_UPSCALING_MODES.forEach { mode ->
            ScreenEffectRadioRow(
                title = stringResource(scalingModeLabelRes(mode)),
                subtitle = stringResource(scalingModeDescRes(mode)),
                selected = scalingMode == mode,
                onSelect = { scalingMode = mode },
            )
        }
        if (gamescope) ScreenEffectRadioRow(
            title = "NVIDIA Image Scaling (NIS)",
            selected = scalingMode == ScreenEffectsConfig.SCALING_MODE_NIS,
            onSelect = { scalingMode = ScreenEffectsConfig.SCALING_MODE_NIS },
        )

        if (scalingMode == ScreenEffectsConfig.SCALING_MODE_FSR ||
            scalingMode == ScreenEffectsConfig.SCALING_MODE_FSR_ASPECT ||
            scalingMode == ScreenEffectsConfig.SCALING_MODE_DLS ||
            scalingMode == ScreenEffectsConfig.SCALING_MODE_NIS
        ) {
            ScreenEffectAdjustmentRow(
                title = if (scalingMode == ScreenEffectsConfig.SCALING_MODE_NIS) "NIS · " +
                    WowI18n.pick(context, "Sharpness", "Élesség") else stringResource(R.string.screen_effects_fsr_sharpness),
                valueText = stringResource(R.string.screen_effects_fsr_sharpness_value, fsrSharpnessLevel),
                progress = normalizedProgress(
                    fsrSharpnessLevel.toFloat(),
                    ScreenEffectsConfig.FSR_MIN_LEVEL.toFloat(),
                    ScreenEffectsConfig.FSR_MAX_LEVEL.toFloat(),
                ),
                onDecrease = {
                    fsrSharpnessLevel = (fsrSharpnessLevel - 1).coerceAtLeast(ScreenEffectsConfig.FSR_MIN_LEVEL)
                },
                onIncrease = {
                    fsrSharpnessLevel = (fsrSharpnessLevel + 1).coerceAtMost(ScreenEffectsConfig.FSR_MAX_LEVEL)
                },
            )
        }

        Spacer(modifier = Modifier.height(20.dp))

        OptionSectionHeader(text = stringResource(R.string.screen_effects_basic_scaling))
        VULKAN_BASIC_MODES.forEach { mode ->
            ScreenEffectRadioRow(
                title = stringResource(scalingModeLabelRes(mode)),
                subtitle = stringResource(scalingModeDescRes(mode)),
                selected = scalingMode == mode,
                onSelect = { scalingMode = mode },
            )
        }

        Spacer(modifier = Modifier.height(20.dp))

        OptionSectionHeader(text = stringResource(R.string.screen_effects_color_adjustments))

        ScreenEffectAdjustmentRow(
            title = stringResource(R.string.screen_effects_brightness),
            valueText = formatPercent(brightness),
            progress = normalizedProgress(brightness, -100f, 100f),
            onDecrease = {
                brightness = (brightness - SCREEN_EFFECT_PERCENT_STEP).coerceIn(-100f, 100f)
            },
            onIncrease = {
                brightness = (brightness + SCREEN_EFFECT_PERCENT_STEP).coerceIn(-100f, 100f)
            },
        )
        ScreenEffectAdjustmentRow(
            title = stringResource(R.string.screen_effects_contrast),
            valueText = formatPercent(contrast),
            progress = normalizedProgress(contrast, -100f, 100f),
            onDecrease = {
                contrast = (contrast - SCREEN_EFFECT_PERCENT_STEP).coerceIn(-100f, 100f)
            },
            onIncrease = {
                contrast = (contrast + SCREEN_EFFECT_PERCENT_STEP).coerceIn(-100f, 100f)
            },
        )
        ScreenEffectAdjustmentRow(
            title = stringResource(R.string.screen_effects_gamma),
            valueText = String.format("%.2fx", gamma),
            progress = normalizedProgress(gamma, 0.5f, 2.5f),
            onDecrease = {
                gamma = (gamma - SCREEN_EFFECT_GAMMA_STEP).coerceIn(0.5f, 2.5f)
            },
            onIncrease = {
                gamma = (gamma + SCREEN_EFFECT_GAMMA_STEP).coerceIn(0.5f, 2.5f)
            },
        )

        Spacer(modifier = Modifier.height(20.dp))

        OptionSectionHeader(text = stringResource(R.string.screen_effects_shader_toggles))

        ScreenEffectToggleRow(
            title = stringResource(R.string.screen_effects_toon),
            subtitle = stringResource(R.string.screen_effects_toon_description),
            enabled = enableToon,
            onToggle = { enableToon = !enableToon },
        )
        ScreenEffectToggleRow(
            title = stringResource(R.string.screen_effects_fxaa),
            subtitle = stringResource(R.string.screen_effects_fxaa_description),
            enabled = enableFXAA,
            onToggle = { enableFXAA = !enableFXAA },
        )
        ScreenEffectToggleRow(
            title = stringResource(R.string.screen_effects_vivid),
            subtitle = stringResource(R.string.screen_effects_vivid_description),
            enabled = enableVivid,
            onToggle = { enableVivid = !enableVivid },
        )
        ScreenEffectToggleRow(
            title = stringResource(R.string.screen_effects_crt),
            subtitle = stringResource(R.string.screen_effects_crt_description),
            enabled = enableCRT,
            onToggle = { enableCRT = !enableCRT },
        )
        ScreenEffectToggleRow(
            title = stringResource(R.string.screen_effects_ntsc),
            subtitle = stringResource(R.string.screen_effects_ntsc_description),
            enabled = enableNTSC,
            onToggle = { enableNTSC = !enableNTSC },
        )

        Spacer(modifier = Modifier.height(20.dp))

        AccentActionRow(
            title = stringResource(R.string.screen_effects_reset),
            icon = Icons.Default.RestartAlt,
            accentColor = PluviaTheme.colors.accentPurple,
            onClick = ::resetEffects,
        )

        Spacer(modifier = Modifier.height(24.dp))
    }
}

private suspend fun persistGamescopeEffects(
    context: android.content.Context,
    container: Container,
    effects: GamescopeEffects,
): Boolean {
    val epoch = GameNativeDrm.sessionEpoch()
    val guest = WowGamescopeRuntime.geometry(container.screenSize)
    return withContext(Dispatchers.IO + NonCancellable) {
        effectsPersistLock.withLock {
            val scaling = effects.copy(look = GamescopePictureLook.OFF)
            if (scaling != GamescopeEffects.load(container)) {
                scaling.save(container)
                container.saveData()
            }
            val output = GameNativeDrm.outputSize
            val active = GameNativeDrm.activeEffects
            if (epoch != GameNativeDrm.sessionEpoch() || active == null || output == null) return@withLock false

            if (effects.look != active.look) {
                val lookApplied = runCatching {
                    val name = effects.look.stage(context, File(ImageFs.find(context).home_path))
                    if (epoch != GameNativeDrm.sessionEpoch()) return@runCatching false
                    if (name == null) GameNativeDrm.nativeReshadeDisable() else GameNativeDrm.nativeReshadeSetEffect(name)
                    true
                }.getOrDefault(false)
                if (!lookApplied) return@withLock false
                GameNativeDrm.activeEffects = active.copy(look = effects.look)
            }
            val applied = runCatching {
                val ready = if (effects.isNis && !GamescopeEffects.canUpscale(guest, output))
                    effects.copy(mode = ScreenEffectsConfig.SCALING_MODE_NONE) else effects
                ready.apply(active,
                    setScaler = { epoch == GameNativeDrm.sessionEpoch() && GamescopeEffects.setScaler(context, it) },
                    send = { if (epoch == GameNativeDrm.sessionEpoch()) GameNativeDrm.nativeGamescopeCommand(it) else -1 })
                    .also { if (it && epoch == GameNativeDrm.sessionEpoch()) GameNativeDrm.activeEffects = ready }
            }.getOrDefault(false)
            applied && (!effects.isNis || GamescopeEffects.canUpscale(guest, output))
        }
    }
}

@Composable
private fun ScreenEffectAdjustmentRow(
    title: String,
    valueText: String,
    progress: Float,
    onDecrease: () -> Unit,
    onIncrease: () -> Unit,
    focusRequester: FocusRequester? = null,
    available: Boolean = true,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val accentColor = PluviaTheme.colors.accentPurple
    val shape = RoundedCornerShape(14.dp)
    var isAdjustmentLocked by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .alpha(if (available) 1f else 0.45f)
            .padding(horizontal = 8.dp, vertical = 2.dp)
            .clip(shape)
            .background(
                if (isFocused) {
                    Brush.horizontalGradient(
                        colors = listOf(
                            accentColor.copy(alpha = 0.16f),
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
                },
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
                    isAdjustmentLocked = false
                }
            }
            .focusable(enabled = available, interactionSource = interactionSource)
            .onPreviewKeyEvent { keyEvent ->
                if (available && keyEvent.nativeKeyEvent.action == KeyEvent.ACTION_DOWN && isFocused) {
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
                enabled = available,
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

        Spacer(modifier = Modifier.height(10.dp))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(36.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ScreenEffectAdjustmentButton(
                text = "-",
                rowIsFocused = isFocused,
                isAdjustmentLocked = isAdjustmentLocked,
                accentColor = accentColor,
                onClick = onDecrease,
                available = available,
            )

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                contentAlignment = Alignment.Center,
            ) {
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
                                enabled = available,
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
                                enabled = available,
                                onClick = onIncrease,
                            ),
                    )
                }
            }

            ScreenEffectAdjustmentButton(
                text = "+",
                rowIsFocused = isFocused,
                isAdjustmentLocked = isAdjustmentLocked,
                accentColor = accentColor,
                onClick = onIncrease,
                available = available,
            )
        }
    }
}

@Composable
private fun ScreenEffectAdjustmentButton(
    text: String,
    rowIsFocused: Boolean,
    isAdjustmentLocked: Boolean,
    accentColor: Color,
    onClick: () -> Unit,
    available: Boolean = true,
) {
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
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                enabled = available,
                onClick = onClick,
            ),
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
private fun ScreenEffectToggleRow(
    title: String,
    subtitle: String? = null,
    enabled: Boolean,
    onToggle: () -> Unit,
    available: Boolean = true,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val accentColor = PluviaTheme.colors.accentPurple

    Row(
        modifier = Modifier
            .alpha(if (available) 1f else 0.45f)
            .padding(horizontal = 8.dp, vertical = 2.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(
                if (isFocused) {
                    Brush.horizontalGradient(
                        colors = listOf(
                            accentColor.copy(alpha = 0.16f),
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
                },
            )
            .selectable(
                enabled = available,
                selected = isFocused,
                interactionSource = interactionSource,
                indication = null,
                onClick = onToggle,
            )
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
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

        Box(contentAlignment = Alignment.CenterEnd) {
            ScreenEffectSwitch(enabled = enabled, accentColor = accentColor)
        }
    }
}

@Composable
private fun ScreenEffectRadioRow(
    title: String,
    subtitle: String? = null,
    selected: Boolean,
    onSelect: () -> Unit,
    focusRequester: FocusRequester? = null,
    available: Boolean = true,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val accentColor = PluviaTheme.colors.accentPurple
    val shape = RoundedCornerShape(14.dp)

    Row(
        modifier = Modifier
            .alpha(if (available) 1f else 0.45f)
            .padding(horizontal = 8.dp, vertical = 2.dp)
            .clip(shape)
            .background(
                if (isFocused) {
                    Brush.horizontalGradient(
                        colors = listOf(
                            accentColor.copy(alpha = 0.16f),
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
                    Modifier.border(width = 2.dp, color = accentColor.copy(alpha = 0.7f), shape = shape)
                } else {
                    Modifier
                },
            )
            .then(
                if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier,
            )
            .selectable(
                enabled = available,
                selected = selected,
                interactionSource = interactionSource,
                indication = null,
                onClick = onSelect,
            )
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        ScreenEffectRadioIndicator(selected = selected, accentColor = accentColor)

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = if (selected || isFocused) FontWeight.SemiBold else FontWeight.Medium,
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
    }
}

@Composable
private fun ScreenEffectRadioIndicator(selected: Boolean, accentColor: Color) {
    Box(
        modifier = Modifier
            .size(22.dp)
            .clip(CircleShape)
            .border(
                width = 2.dp,
                color = if (selected) accentColor else MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
                shape = CircleShape,
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) {
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .clip(CircleShape)
                    .background(accentColor),
            )
        }
    }
}

@Composable
private fun ScreenEffectSwitch(
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

private fun normalizedProgress(
    value: Float,
    min: Float,
    max: Float,
): Float = ((value - min) / (max - min)).coerceIn(0f, 1f)

private fun formatPercent(value: Float): String {
    val rounded = value.toInt()
    return if (rounded > 0) "+${rounded}%" else "${rounded}%"
}

@Composable
fun OptionSectionHeader(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f),
        letterSpacing = MaterialTheme.typography.labelMedium.letterSpacing * 1.5f,
        modifier = modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}
