package app.gamenative.ui.data

enum class PerformanceHudStyle(val prefValue: String, val label: String) {
    CLASSIC("classic", "Classic"), TERMINAL("terminal", "Terminal"),
    // Looks inspired by popular benchmarking overlays: amber OSD text, and a slate panel with accent colours.
    AFTERBURNER("afterburner", "Afterburner"), CAPFRAMEX("capframex", "CapFrameX"),
    // The launcher's own look: navy panel, bronze-gold border, cream FPS and gold metrics.
    FOREVER("forever", "Forever");

    companion object {
        fun fromPrefValue(value: String?) = entries.firstOrNull { it.prefValue == value } ?: CLASSIC
    }
}

/**
 * Controls which metrics are rendered inside the floating performance HUD.
 */
data class PerformanceHudConfig(
    val showFrameRate: Boolean = true,
    val showCpuUsage: Boolean = true,
    val showGpuUsage: Boolean = true,
    val showRamUsage: Boolean = true,
    val showBatteryLevel: Boolean = true,
    val showPowerDraw: Boolean = true,
    val showBatteryRuntime: Boolean = false,
    val showBatteryTemperature: Boolean = false,
    val showClockTime: Boolean = false,
    val showCpuTemperature: Boolean = true,
    val showGpuTemperature: Boolean = true,
    val showFrameRateGraph: Boolean = false,
    val showCpuUsageGraph: Boolean = false,
    val showGpuUsageGraph: Boolean = false,
    val backgroundOpacity: Float = DEFAULT_BACKGROUND_OPACITY,
    val colorIntensity: Float = DEFAULT_COLOR_INTENSITY,
    val showTextOutline: Boolean = DEFAULT_SHOW_TEXT_OUTLINE,
    val sizePercent: Int = 85,
    val style: PerformanceHudStyle = PerformanceHudStyle.CLASSIC,
    val showCpuClock: Boolean = false,
    /** GPU clock in MHz (kgsl/devfreq); shows what the GPU power mode does. On by default from v0.1.114. */
    val showGpuClock: Boolean = true,
    /** Graphics memory in use system-wide (KGSL page allocations); off by default. */
    val showVramUsage: Boolean = false,
    /**
     * Frame time (ms; 1000 / the FPS shown) and the 1% / 0.1% LOW FPS. A LOW here is the LOW AVERAGE of the slowest 1% / 0.1% of the last
     * 30 s of frames (not a single percentile frame), never shown above the current FPS; pauses over 2 s are not frames. CapFrameX
     * names several different "lows": only the look is borrowed, not an identical definition.
     */
    val showFrameTime: Boolean = false,
    val showFps1Low: Boolean = false,
    val showFps01Low: Boolean = false,
    val metricOrder: List<HudMetric> = HudMetric.entries.toList(),
) {
    companion object {
        const val DEFAULT_BACKGROUND_OPACITY = 0.72f
        const val DEFAULT_COLOR_INTENSITY = 1f
        const val DEFAULT_SHOW_TEXT_OUTLINE = true
    }
}
