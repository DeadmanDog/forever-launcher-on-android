package app.gamenative.ui.data

/** Stable IDs shared by the settings list and both HUD layouts. */
enum class HudMetric(val label: String, val isGraph: Boolean = false) {
    FPS("FPS"), FPS_GRAPH("FPS GRAPH", true), FPS_LOW1("1% LOW FPS"), FPS_LOW01("0.1% LOW FPS"), FRAMETIME("FRAMETIME"),
    CPU("CPU USAGE"), CPU_GRAPH("CPU GRAPH", true),
    GPU("GPU USAGE"), GPU_GRAPH("GPU GRAPH", true),
    RAM("RAM"), BATTERY("BATTERY"), POWER("POWER"), RUNTIME("BATTERY RUNTIME"),
    BATTERY_TEMP("BAT T."), CLOCK("TIME"), CPU_TEMP("CPU T."), GPU_TEMP("GPU T."), CPU_CLOCK("CPU C."), GPU_CLOCK("GPU C."), VRAM("VRAM");

    fun enabled(c: PerformanceHudConfig): Boolean = when (this) {
        FPS -> c.showFrameRate; FPS_GRAPH -> c.showFrameRateGraph
        FPS_LOW1 -> c.showFps1Low; FPS_LOW01 -> c.showFps01Low; FRAMETIME -> c.showFrameTime
        CPU -> c.showCpuUsage; CPU_GRAPH -> c.showCpuUsageGraph
        GPU -> c.showGpuUsage; GPU_GRAPH -> c.showGpuUsageGraph
        RAM -> c.showRamUsage; BATTERY -> c.showBatteryLevel; POWER -> c.showPowerDraw
        RUNTIME -> c.showBatteryRuntime; BATTERY_TEMP -> c.showBatteryTemperature
        CLOCK -> c.showClockTime; CPU_TEMP -> c.showCpuTemperature; GPU_TEMP -> c.showGpuTemperature
        CPU_CLOCK -> c.showCpuClock; GPU_CLOCK -> c.showGpuClock; VRAM -> c.showVramUsage
    }

    fun toggle(c: PerformanceHudConfig): PerformanceHudConfig = when (this) {
        FPS -> c.copy(showFrameRate = !c.showFrameRate); FPS_GRAPH -> c.copy(showFrameRateGraph = !c.showFrameRateGraph)
        FPS_LOW1 -> c.copy(showFps1Low = !c.showFps1Low); FPS_LOW01 -> c.copy(showFps01Low = !c.showFps01Low)
        FRAMETIME -> c.copy(showFrameTime = !c.showFrameTime)
        CPU -> c.copy(showCpuUsage = !c.showCpuUsage); CPU_GRAPH -> c.copy(showCpuUsageGraph = !c.showCpuUsageGraph)
        GPU -> c.copy(showGpuUsage = !c.showGpuUsage); GPU_GRAPH -> c.copy(showGpuUsageGraph = !c.showGpuUsageGraph)
        RAM -> c.copy(showRamUsage = !c.showRamUsage); BATTERY -> c.copy(showBatteryLevel = !c.showBatteryLevel)
        POWER -> c.copy(showPowerDraw = !c.showPowerDraw); RUNTIME -> c.copy(showBatteryRuntime = !c.showBatteryRuntime)
        BATTERY_TEMP -> c.copy(showBatteryTemperature = !c.showBatteryTemperature)
        CLOCK -> c.copy(showClockTime = !c.showClockTime); CPU_TEMP -> c.copy(showCpuTemperature = !c.showCpuTemperature)
        GPU_TEMP -> c.copy(showGpuTemperature = !c.showGpuTemperature); CPU_CLOCK -> c.copy(showCpuClock = !c.showCpuClock)
        GPU_CLOCK -> c.copy(showGpuClock = !c.showGpuClock); VRAM -> c.copy(showVramUsage = !c.showVramUsage)
    }

    companion object {
        fun scale(percent: Int): Float = percent.coerceIn(40, 100) / 100f
        fun normalizeOrder(order: List<HudMetric>): List<HudMetric> = (order + entries).distinct()
        fun parseOrder(value: String): List<HudMetric> = normalizeOrder(value.split(",").mapNotNull { name ->
            entries.firstOrNull { it.name == name }
        })
        fun move(order: List<HudMetric>, metric: HudMetric, delta: Int): List<HudMetric> {
            val result = normalizeOrder(order).toMutableList()
            val from = result.indexOf(metric)
            val to = (from + delta).coerceIn(0, result.lastIndex)
            result.removeAt(from); result.add(to, metric)
            return result
        }
    }
}
