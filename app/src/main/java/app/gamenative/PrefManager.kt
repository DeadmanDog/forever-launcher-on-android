package app.gamenative

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.gamenative.ui.enums.Orientation
import java.io.IOException
import java.util.EnumSet
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/**
 * A universal Preference Manager that can be used anywhere within gamenative.
 * Note: King of ugly though.
 */
object PrefManager {

    // NOT Dispatchers.IO: getPref runBlocking-waits on the datastore from IO threads, so if the read also
    // needed an IO thread, enough concurrent readers would starve the pool and deadlock.
    private val dataStoreDispatcher =
        Executors.newSingleThreadExecutor { r -> Thread(r, "PrefDataStore") }.asCoroutineDispatcher()

    private val Context.datastore by preferencesDataStore(
        name = "PluviaPreferences",
        corruptionHandler = ReplaceFileCorruptionHandler {

            emptyPreferences()
        },
        scope = CoroutineScope(dataStoreDispatcher + SupervisorJob()),
    )

    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var writer: PrefWriter

    fun init(context: Context) {
        if (!::dataStore.isInitialized) {
            dataStore = context.datastore
            writer = PrefWriter(dataStore)
        }
    }

    /** Preferences set inside [block] on this thread are saved as one transaction, in order (e.g. a whole HUD profile). */
    fun batch(block: () -> Unit) = writer.batch(block)

    fun getBoolean(key: String, defaultValue: Boolean): Boolean =
        getPref(booleanPreferencesKey(key), defaultValue)

    fun getFloat(key: String, defaultValue: Float): Float =
        getPref(floatPreferencesKey(key), defaultValue)

    @Suppress("SameParameterValue")
    private fun <T> getPref(key: Preferences.Key<T>, defaultValue: T): T = runBlocking {
        try {
            dataStore.data.first()[key] ?: defaultValue
        } catch (e: IOException) {

            defaultValue
        }
    }

    @Suppress("SameParameterValue")
    private fun <T> setPref(key: Preferences.Key<T>, value: T) {
        writer.set(key, value)
    }

    private val QUICK_MENU_LAST_TAB = intPreferencesKey("quick_menu_last_tab")
    var quickMenuLastTab: Int
        get() = getPref(QUICK_MENU_LAST_TAB, 0)
        set(value) {
            setPref(QUICK_MENU_LAST_TAB, value.coerceIn(0, 6))
        }

    private val SHOW_FPS = booleanPreferencesKey("show_fps")
    var showFps: Boolean
        get() = getPref(SHOW_FPS, false)
        set(value) {
            setPref(SHOW_FPS, value)
        }

    // The player's own HUD profile is the default (from 1.3.10): CapFrameX style (green FPS, blue CPU, red GPU, purple RAM),
    // vertical list, 69%, no panel, top-left corner, and this metric order. Saved choices always win over these defaults.
    private const val DEFAULT_PERFORMANCE_HUD_SIZE_PERCENT = 69
    private const val DEFAULT_PERFORMANCE_HUD_METRIC_ORDER =
        "FPS,FPS_LOW1,FPS_LOW01,FRAMETIME,CPU_CLOCK,CPU,CPU_TEMP,GPU_CLOCK,VRAM,GPU,GPU_TEMP,RAM,POWER,RUNTIME,BATTERY,BATTERY_TEMP,CLOCK,FPS_GRAPH,CPU_GRAPH,GPU_GRAPH"

    private val PAUSE_GAME_ON_QUICK_MENU = booleanPreferencesKey("pause_game_on_quick_menu")
    /** Off (default): the game keeps running behind the Quick Menu. On: it is suspended while the menu is open. */
    var pauseGameOnQuickMenu: Boolean
        get() = getPref(PAUSE_GAME_ON_QUICK_MENU, false)
        set(value) {
            setPref(PAUSE_GAME_ON_QUICK_MENU, value)
        }

    private val BNET_AUTO_SIGN_IN = booleanPreferencesKey("bnet_auto_sign_in")
    /** On: the saved Battle.net login is typed automatically once WoW's login screen is recognised. */
    var bnetAutoSignIn: Boolean
        get() = getPref(BNET_AUTO_SIGN_IN, false)
        set(value) {
            setPref(BNET_AUTO_SIGN_IN, value)
        }

    private val PERFORMANCE_HUD_LOCKED = booleanPreferencesKey("performance_hud_locked")
    /** On: the HUD ignores touches, so it cannot be moved or toggled by accident during play. */
    var performanceHudLocked: Boolean
        get() = getPref(PERFORMANCE_HUD_LOCKED, false)
        set(value) {
            setPref(PERFORMANCE_HUD_LOCKED, value)
        }

    private val PERFORMANCE_HUD_COMPACT_MODE = booleanPreferencesKey("performance_hud_compact_mode")
    var performanceHudCompactMode: Boolean
        get() = getPref(PERFORMANCE_HUD_COMPACT_MODE, false)
        set(value) {
            setPref(PERFORMANCE_HUD_COMPACT_MODE, value)
        }

    private val PERFORMANCE_HUD_SHOW_FRAME_RATE = booleanPreferencesKey("performance_hud_show_frame_rate")
    var performanceHudShowFrameRate: Boolean
        get() = getPref(PERFORMANCE_HUD_SHOW_FRAME_RATE, true)
        set(value) {
            setPref(PERFORMANCE_HUD_SHOW_FRAME_RATE, value)
        }

    private val PERFORMANCE_HUD_SHOW_CPU_USAGE = booleanPreferencesKey("performance_hud_show_cpu_usage")
    var performanceHudShowCpuUsage: Boolean
        get() = getPref(PERFORMANCE_HUD_SHOW_CPU_USAGE, true)
        set(value) {
            setPref(PERFORMANCE_HUD_SHOW_CPU_USAGE, value)
        }

    private val PERFORMANCE_HUD_SHOW_GPU_USAGE = booleanPreferencesKey("performance_hud_show_gpu_usage")
    var performanceHudShowGpuUsage: Boolean
        get() = getPref(PERFORMANCE_HUD_SHOW_GPU_USAGE, true)
        set(value) {
            setPref(PERFORMANCE_HUD_SHOW_GPU_USAGE, value)
        }

    private val PERFORMANCE_HUD_SHOW_RAM_USAGE = booleanPreferencesKey("performance_hud_show_ram_usage")
    var performanceHudShowRamUsage: Boolean
        get() = getPref(PERFORMANCE_HUD_SHOW_RAM_USAGE, true)
        set(value) {
            setPref(PERFORMANCE_HUD_SHOW_RAM_USAGE, value)
        }

    private val PERFORMANCE_HUD_SHOW_VRAM_USAGE = booleanPreferencesKey("performance_hud_show_vram_usage")
    var performanceHudShowVramUsage: Boolean
        get() = getPref(PERFORMANCE_HUD_SHOW_VRAM_USAGE, true)
        set(value) {
            setPref(PERFORMANCE_HUD_SHOW_VRAM_USAGE, value)
        }

    private val PERFORMANCE_HUD_SHOW_FPS_1_LOW = booleanPreferencesKey("performance_hud_show_fps_1_low")
    var performanceHudShowFps1Low: Boolean
        get() = getPref(PERFORMANCE_HUD_SHOW_FPS_1_LOW, true)
        set(value) { setPref(PERFORMANCE_HUD_SHOW_FPS_1_LOW, value) }

    private val PERFORMANCE_HUD_SHOW_FPS_01_LOW = booleanPreferencesKey("performance_hud_show_fps_01_low")
    var performanceHudShowFps01Low: Boolean
        get() = getPref(PERFORMANCE_HUD_SHOW_FPS_01_LOW, true)
        set(value) { setPref(PERFORMANCE_HUD_SHOW_FPS_01_LOW, value) }

    private val PERFORMANCE_HUD_SHOW_FRAME_TIME = booleanPreferencesKey("performance_hud_show_frame_time")
    var performanceHudShowFrameTime: Boolean
        get() = getPref(PERFORMANCE_HUD_SHOW_FRAME_TIME, true)
        set(value) { setPref(PERFORMANCE_HUD_SHOW_FRAME_TIME, value) }

    private val PERFORMANCE_HUD_SHOW_BATTERY_LEVEL = booleanPreferencesKey("performance_hud_show_battery_level")
    var performanceHudShowBatteryLevel: Boolean
        get() = getPref(PERFORMANCE_HUD_SHOW_BATTERY_LEVEL, true)
        set(value) {
            setPref(PERFORMANCE_HUD_SHOW_BATTERY_LEVEL, value)
        }

    private val PERFORMANCE_HUD_SHOW_POWER_DRAW = booleanPreferencesKey("performance_hud_show_power_draw")
    var performanceHudShowPowerDraw: Boolean
        get() = getPref(PERFORMANCE_HUD_SHOW_POWER_DRAW, true)
        set(value) {
            setPref(PERFORMANCE_HUD_SHOW_POWER_DRAW, value)
        }

    private val PERFORMANCE_HUD_SHOW_BATTERY_RUNTIME = booleanPreferencesKey("performance_hud_show_battery_runtime")
    var performanceHudShowBatteryRuntime: Boolean
        get() = getPref(PERFORMANCE_HUD_SHOW_BATTERY_RUNTIME, true)
        set(value) {
            setPref(PERFORMANCE_HUD_SHOW_BATTERY_RUNTIME, value)
        }

    private val PERFORMANCE_HUD_SHOW_BATTERY_TEMPERATURE = booleanPreferencesKey("performance_hud_show_battery_temperature")
    var performanceHudShowBatteryTemperature: Boolean
        get() = getPref(PERFORMANCE_HUD_SHOW_BATTERY_TEMPERATURE, true)
        set(value) {
            setPref(PERFORMANCE_HUD_SHOW_BATTERY_TEMPERATURE, value)
        }

    private val PERFORMANCE_HUD_SHOW_CLOCK_TIME = booleanPreferencesKey("performance_hud_show_clock_time")
    var performanceHudShowClockTime: Boolean
        get() = getPref(PERFORMANCE_HUD_SHOW_CLOCK_TIME, false)
        set(value) {
            setPref(PERFORMANCE_HUD_SHOW_CLOCK_TIME, value)
        }

    private val PERFORMANCE_HUD_SHOW_CPU_TEMPERATURE = booleanPreferencesKey("performance_hud_show_cpu_temperature")
    var performanceHudShowCpuTemperature: Boolean
        get() = getPref(PERFORMANCE_HUD_SHOW_CPU_TEMPERATURE, true)
        set(value) {
            setPref(PERFORMANCE_HUD_SHOW_CPU_TEMPERATURE, value)
        }

    private val PERFORMANCE_HUD_SHOW_GPU_TEMPERATURE = booleanPreferencesKey("performance_hud_show_gpu_temperature")
    var performanceHudShowGpuTemperature: Boolean
        get() = getPref(PERFORMANCE_HUD_SHOW_GPU_TEMPERATURE, true)
        set(value) {
            setPref(PERFORMANCE_HUD_SHOW_GPU_TEMPERATURE, value)
        }

    private val PERFORMANCE_HUD_SHOW_FRAME_RATE_GRAPH = booleanPreferencesKey("performance_hud_show_frame_rate_graph")
    var performanceHudShowFrameRateGraph: Boolean
        get() = getPref(PERFORMANCE_HUD_SHOW_FRAME_RATE_GRAPH, false)
        set(value) {
            setPref(PERFORMANCE_HUD_SHOW_FRAME_RATE_GRAPH, value)
        }

    private val PERFORMANCE_HUD_SHOW_CPU_USAGE_GRAPH = booleanPreferencesKey("performance_hud_show_cpu_usage_graph")
    var performanceHudShowCpuUsageGraph: Boolean
        get() = getPref(PERFORMANCE_HUD_SHOW_CPU_USAGE_GRAPH, false)
        set(value) {
            setPref(PERFORMANCE_HUD_SHOW_CPU_USAGE_GRAPH, value)
        }

    private val PERFORMANCE_HUD_SHOW_GPU_USAGE_GRAPH = booleanPreferencesKey("performance_hud_show_gpu_usage_graph")
    var performanceHudShowGpuUsageGraph: Boolean
        get() = getPref(PERFORMANCE_HUD_SHOW_GPU_USAGE_GRAPH, false)
        set(value) {
            setPref(PERFORMANCE_HUD_SHOW_GPU_USAGE_GRAPH, value)
        }

    private val PERFORMANCE_HUD_BACKGROUND_OPACITY = floatPreferencesKey("performance_hud_background_opacity")
    var performanceHudBackgroundOpacity: Float
        get() = getPref(PERFORMANCE_HUD_BACKGROUND_OPACITY, 0f)
        set(value) {
            setPref(PERFORMANCE_HUD_BACKGROUND_OPACITY, value.coerceIn(0f, 1f))
        }

    private val PERFORMANCE_HUD_COLOR_INTENSITY = floatPreferencesKey("performance_hud_color_intensity")
    var performanceHudColorIntensity: Float
        get() = getPref(PERFORMANCE_HUD_COLOR_INTENSITY, 1f)
        set(value) {
            setPref(PERFORMANCE_HUD_COLOR_INTENSITY, value.coerceIn(0f, 1f))
        }

    private val PERFORMANCE_HUD_SHOW_TEXT_OUTLINE = booleanPreferencesKey("performance_hud_show_text_outline")
    var performanceHudShowTextOutline: Boolean
        get() = getPref(PERFORMANCE_HUD_SHOW_TEXT_OUTLINE, true)
        set(value) {
            setPref(PERFORMANCE_HUD_SHOW_TEXT_OUTLINE, value)
        }

    private val PERFORMANCE_HUD_STYLE = stringPreferencesKey("performance_hud_style")
    var performanceHudStyle: String
        get() = getPref(PERFORMANCE_HUD_STYLE, "capframex")
        set(value) { setPref(PERFORMANCE_HUD_STYLE, value) }

    private val PERFORMANCE_HUD_CPU_CLOCK = booleanPreferencesKey("performance_hud_cpu_clock")
    var performanceHudShowCpuClock: Boolean
        get() = getPref(PERFORMANCE_HUD_CPU_CLOCK, true)
        set(value) { setPref(PERFORMANCE_HUD_CPU_CLOCK, value) }
    private val PERFORMANCE_HUD_GPU_CLOCK = booleanPreferencesKey("performance_hud_gpu_clock")
    var performanceHudShowGpuClock: Boolean
        get() = getPref(PERFORMANCE_HUD_GPU_CLOCK, true)
        set(value) { setPref(PERFORMANCE_HUD_GPU_CLOCK, value) }

    private val PERFORMANCE_HUD_SIZE_PERCENT = intPreferencesKey("performance_hud_size_percent")
    var performanceHudSizePercent: Int
        get() = getPref(PERFORMANCE_HUD_SIZE_PERCENT, DEFAULT_PERFORMANCE_HUD_SIZE_PERCENT).coerceIn(40, 100)
        set(value) { setPref(PERFORMANCE_HUD_SIZE_PERCENT, value.coerceIn(40, 100)) }

    private val PERFORMANCE_HUD_METRIC_ORDER = stringPreferencesKey("performance_hud_metric_order")
    var performanceHudMetricOrder: String
        get() = getPref(PERFORMANCE_HUD_METRIC_ORDER, DEFAULT_PERFORMANCE_HUD_METRIC_ORDER)
        set(value) { setPref(PERFORMANCE_HUD_METRIC_ORDER, value) }

    private val PERFORMANCE_HUD_X_FRACTION = floatPreferencesKey("performance_hud_x_fraction")
    var performanceHudXFraction: Float
        get() = getPref(PERFORMANCE_HUD_X_FRACTION, 0f)
        set(value) {
            setPref(PERFORMANCE_HUD_X_FRACTION, value.coerceIn(-1f, 1f))
        }

    private val PERFORMANCE_HUD_Y_FRACTION = floatPreferencesKey("performance_hud_y_fraction")
    var performanceHudYFraction: Float
        get() = getPref(PERFORMANCE_HUD_Y_FRACTION, 0f)
        set(value) {
            setPref(PERFORMANCE_HUD_Y_FRACTION, value.coerceIn(-1f, 1f))
        }

    private val ALLOWED_ORIENTATION = intPreferencesKey("allowed_orientation")
    val allowedOrientation: EnumSet<Orientation>
        get() {
            val defaultValue = Orientation.toInt(
                EnumSet.of(Orientation.LANDSCAPE, Orientation.REVERSE_LANDSCAPE),
            )
            val value = getPref(ALLOWED_ORIENTATION, defaultValue)
            return Orientation.fromInt(value)
        }

}
