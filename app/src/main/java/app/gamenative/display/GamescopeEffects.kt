package app.gamenative.display

import android.content.Context
import app.gamenative.ui.util.ScreenEffectsConfig
import app.gamenative.ui.util.loadScreenEffectsConfig
import app.gamenative.ui.util.persistScreenEffectsConfig
import com.winlator.container.Container
import com.winlator.xenvironment.ImageFs
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

internal data class GamescopeEffects(
    val mode: Int,
    val sharpness: Int,
    val look: GamescopePictureLook = GamescopePictureLook.OFF,
    val colors: ScreenEffectsConfig = ScreenEffectsConfig(),
) {
    private val filter get() = when (mode) {
        ScreenEffectsConfig.SCALING_MODE_NEAREST -> "nearest"
        ScreenEffectsConfig.SCALING_MODE_NIS -> "nis"
        else -> "linear"
    }
    private val scaler get() = when (mode) {
        ScreenEffectsConfig.SCALING_MODE_FILL -> "fill" to 3
        ScreenEffectsConfig.SCALING_MODE_STRETCH, ScreenEffectsConfig.SCALING_MODE_FSR -> "stretch" to 4
        else -> "fit" to 2
    }
    private val rawSharpness get() = (ScreenEffectsConfig.FSR_MAX_LEVEL -
        sharpness.coerceIn(ScreenEffectsConfig.FSR_MIN_LEVEL, ScreenEffectsConfig.FSR_MAX_LEVEL)) * 5
    /** A scaler or filter other than Gamescope's default (fit, linear): passed at launch. */
    val needsScalerArgs get() = mode != ScreenEffectsConfig.SCALING_MODE_NONE && mode != ScreenEffectsConfig.SCALING_MODE_LINEAR
    /**
     * Only the upscalers (FSR, FSR with aspect, NIS) gain from an output larger than the game; Nearest, Fill, Stretch, DLS and
     * Natural work at the game's size, and a 1.5x output only multiplied the composited and published pixels for them.
     */
    val needsPanelOutput get() = mode == ScreenEffectsConfig.SCALING_MODE_FSR || mode == ScreenEffectsConfig.SCALING_MODE_FSR_ASPECT ||
        mode == ScreenEffectsConfig.SCALING_MODE_NIS
    val isNis get() = filter == "nis"
    val inputScalingMode get() = when (scaler.first) {
        "stretch" -> com.winlator.renderer.VulkanRenderer.SCALE_STRETCH
        "fill" -> com.winlator.renderer.VulkanRenderer.SCALE_FILL
        else -> com.winlator.renderer.VulkanRenderer.SCALE_FIT
    }

    fun scanoutSize(guest: Pair<Int, Int>, requested: Pair<Int, Int>, surface: Pair<Int, Int>): Pair<Int, Int> =
        when {
            !needsPanelOutput -> requested   // no upscaling effect: never grow the output to the Surface
            canUpscale(guest, requested) -> requested
            // No display mode was readable at session start (requested = the game size): the same 1.5x from the surface, not the
            // whole surface (the 3.3 MP panel output that cost ~3 W).
            else -> outputSize(guest, surface.first, surface.second).takeIf { canUpscale(guest, it) } ?: requested
        }

    fun launchArgs(): String = if (!needsScalerArgs) "" else
        " --scaler ${scaler.first} --filter $filter --sharpness $rawSharpness"

    fun apply(previous: GamescopeEffects, setScaler: (Int) -> Boolean, send: (String) -> Int): Boolean {
        if (scaler != previous.scaler && !setScaler(scaler.second)) return false
        if (isNis && send("scaling_sharpness $rawSharpness") != 0) return false
        if (filter != previous.filter && send("scaling_filter $filter") != 0) return false
        val shader = shaderCommand()
        return shader == previous.shaderCommand() || send(shader) == 0
    }

    fun shaderCommand(): String {
        val shader = when (mode) {
            ScreenEffectsConfig.SCALING_MODE_FSR, ScreenEffectsConfig.SCALING_MODE_FSR_ASPECT -> 1
            ScreenEffectsConfig.SCALING_MODE_DLS -> 2
            ScreenEffectsConfig.SCALING_MODE_NATURAL -> 5
            else -> 0
        }
        val mask = (if (colors.enableToon) 1 else 0) or (if (colors.enableFXAA) 2 else 0) or
            (if (colors.enableVivid) 4 else 0) or (if (colors.enableCRT) 8 else 0) or
            (if (colors.enableNTSC) 16 else 0)
        fun Float.units(default: Float, min: Float, max: Float, multiplier: Int) =
            ((takeIf { it.isFinite() } ?: default).coerceIn(min, max) * multiplier).roundToInt()
        val strength = if (shader == 1 || shader == 2) (20 - rawSharpness) * 5 else 0
        return "wow_quick_effects $shader $strength $mask " +
            "${colors.brightness.units(0f, -100f, 100f, 10)} " +
            "${colors.contrast.units(0f, -100f, 100f, 10)} ${colors.gamma.units(1f, 0.5f, 2.5f, 1000)}"
    }

    fun save(container: Container) {
        container.putExtra(MODE, mode)
        container.putExtra(SHARPNESS, sharpness)
        val current = loadScreenEffectsConfig(container)
        persistScreenEffectsConfig(container, colors.copy(
            scalingMode = current.scalingMode, fsrSharpnessLevel = current.fsrSharpnessLevel))
    }

    companion object {
        private const val MODE = "gamescopeScreenEffectsMode"
        private const val SHARPNESS = "gamescopeScreenEffectsSharpness"

        fun supports(mode: Int) = mode in ScreenEffectsConfig.SCALING_MODE_NONE..ScreenEffectsConfig.SCALING_MODE_NIS

        fun load(container: Container): GamescopeEffects {
            val colors = loadScreenEffectsConfig(container)
            val legacyMode = when (container.getExtra("gamescopeScalingFilter")) {
                "fsr" -> ScreenEffectsConfig.SCALING_MODE_FSR_ASPECT
                "nis" -> ScreenEffectsConfig.SCALING_MODE_NIS
                "nearest" -> ScreenEffectsConfig.SCALING_MODE_NEAREST
                "linear" -> ScreenEffectsConfig.SCALING_MODE_LINEAR
                else -> colors.scalingMode.takeIf(::supports) ?: ScreenEffectsConfig.SCALING_MODE_NONE
            }
            val mode = container.getExtra(MODE).toIntOrNull()?.takeIf(::supports) ?: legacyMode
            val legacySharpness = container.getExtra("gamescopeFilterSharpness").toIntOrNull()?.coerceIn(0, 20)?.let {
                (it + 2) / 5 + 1
            }
            val sharpness = (container.getExtra(SHARPNESS).toIntOrNull() ?: legacySharpness ?: colors.fsrSharpnessLevel)
                .coerceIn(ScreenEffectsConfig.FSR_MIN_LEVEL, ScreenEffectsConfig.FSR_MAX_LEVEL)
            // Picture presets are session-only, including profiles saved by older builds.
            return GamescopeEffects(mode, sharpness, colors = colors.copy(
                scalingMode = ScreenEffectsConfig.SCALING_MODE_NONE, fsrSharpnessLevel = ScreenEffectsConfig.FSR_DEFAULT_LEVEL))
        }

        /** Gamescope only runs FSR/NIS when both source axes are smaller than the output. */
        fun canUpscale(guest: Pair<Int, Int>, output: Pair<Int, Int>) =
            guest.first < output.first && guest.second < output.second

        /**
         * The output an upscaling effect renders at: 1.5x the game resolution (FSR's "Quality" ratio), at most the panel. Every
         * output pixel costs shader work each frame, so the panel's own scaler enlarges the rest for free: 1344x608 -> 2016x912
         * (1.8 MP) instead of the full 2688x1216 (3.3 MP), about 44% less effect work and published memory per frame.
         */
        fun outputSize(guest: Pair<Int, Int>, panelWidth: Int, panelHeight: Int): Pair<Int, Int> {
            if (panelWidth !in 64..16384 || panelHeight !in 64..16384) return guest
            val longEdge = maxOf(panelWidth, panelHeight)
            val shortEdge = minOf(panelWidth, panelHeight)
            val panel = if (guest.first >= guest.second) longEdge to shortEdge else shortEdge to longEdge
            val scale = minOf(EFFECT_SCALE, panel.first.toDouble() / guest.first, panel.second.toDouble() / guest.second)
            // A game at or above the panel's size has nothing to upscale to: never an output smaller than the game.
            if (scale <= 1.0) return guest
            fun even(v: Double) = (kotlin.math.round(v / 2) * 2).toInt()
            return minOf(panel.first, even(guest.first * scale)) to minOf(panel.second, even(guest.second * scale))
        }
        const val EFFECT_SCALE = 1.5

        /** The bundled XCB helper sets the live Gamescope scaler on its own Xwayland root. */
        fun setScaler(context: Context, scaler: Int): Boolean {
            val runtime = WowGamescopePayload.directory(context)
            val helper = File(runtime, "bin/gn-scaling-mode")
            val imageFs = ImageFs.find(context) ?: return false
            if (!helper.isFile) return false
            val process = ProcessBuilder("/system/bin/linker64", helper.path, scaler.toString())
                .redirectErrorStream(true)
                .redirectOutput(File("/dev/null"))
                .apply {
                    environment()["LD_LIBRARY_PATH"] = File(runtime, "lib").path
                    environment()["GAMESCOPE_X11_DISPLAY"] = File(imageFs.rootDir, "usr/tmp/.X11-unix/X0").path
                }
                .start()
            try {
                return process.waitFor(3, TimeUnit.SECONDS) && process.exitValue() == 0
            } finally {
                if (process.isAlive) process.destroyForcibly()
            }
        }
    }
}
