package app.gamenative.ui.widget

internal enum class MetricId {
    FPS,
    FPS_LOW1,
    FPS_LOW01,
    FRAMETIME,
    CPU,
    GPU,
    RAM,
    BATTERY,
    POWER,
    RUNTIME,
    BATTERY_TEMP,
    CLOCK,
    CPU_CLOCK,
    GPU_CLOCK,
    FPS_GRAPH,
    CPU_GRAPH,
    GPU_GRAPH,
    CPU_TEMP,
    GPU_TEMP,
    FAN,
    TUNE,
    VRAM,
}

internal enum class GraphScaleMode {
    FPS_DYNAMIC,
    PERCENT_100,
}

internal data class BatterySnapshot(
    val percent: Int? = null,
    val powerWatts: Double? = null,
    val runtimeText: String? = null,
    val temperatureC: Int? = null,
)

internal data class HudSnapshot(
    val fpsValue: Float,
    val cpuValue: Float?,
    val gpuValue: Float?,
    val fps: String,
    val cpu: String?,
    val gpu: String?,
    val ram: String,
    val battery: String?,
    val power: String?,
    val runtime: String?,
    val batteryTemp: String?,
    val clock: String,
    val cpuTemp: String?,
    val gpuTemp: String?,
    val fan: String?,
    val tune: String?,
    val cpuClock: String? = null,
    val gpuClock: String? = null,
    val vram: String? = null,
    val fpsLow1: String? = null,
    val fpsLow01: String? = null,
    val frameTime: String? = null,

)

internal data class HudAppearance(
    val textSizeSp: Float,
    val containerHorizontalPaddingDp: Int,
    val containerVerticalPaddingDp: Int,
    val rowVerticalPaddingDp: Int,
    val rowSpacingDp: Int,
    val columnSpacingDp: Int,
    val cornerRadiusDp: Int,
    val strokeWidthDp: Int,
    val stackedGraphWidthDp: Int,
    val stackedGraphHeightDp: Int,
    val compactGraphWidthDp: Int,
    val compactGraphHeightDp: Int,
    val stackedGraphTopMarginDp: Int,
    val stackedGraphBottomMarginDp: Int,
    val compactGraphStartMarginDp: Int,
)

internal data class MetricSignature(
    val id: MetricId,
    val showText: Boolean,
    val showGraph: Boolean,
)
