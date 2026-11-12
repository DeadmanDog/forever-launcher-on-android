package app.gamenative.ui.widget

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.BatteryManager
import android.text.TextUtils
import android.text.format.DateFormat
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import app.gamenative.ui.data.PerformanceHudConfig
import app.gamenative.ui.data.PerformanceHudStyle
import app.gamenative.ui.data.HudMetric
import app.gamenative.utils.DateTimeUtils.formatRuntimeHours
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Lightweight floating HUD shown above the in-game surface.
 *
 * Metric collection runs off the main thread and rows are hidden automatically
 * when a given stat is not available on the current device.
 */
class PerformanceHudView(
    context: Context,
    private val fpsProvider: () -> Float,
    initialConfig: PerformanceHudConfig = PerformanceHudConfig(),
    initialCompactMode: Boolean = false,
    private val frameStatsProvider: () -> FrameTimeReading? = { null },
) : FrameLayout(context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var updateJob: Job? = null
    private var config = initialConfig
    private var isCompactMode = initialCompactMode
    private var lastSnapshot: HudSnapshot? = null
    private var attachedMetricSignature: List<MetricSignature> = emptyList()
    private var appearance = appearanceFor(initialConfig.sizePercent)
    private var smoothedBatteryRuntimeHours: Double? = null
    private var isPaused = false

    private val backgroundDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
    }

    private val stackedContainer = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = LayoutParams(
            LayoutParams.WRAP_CONTENT,
            LayoutParams.WRAP_CONTENT,
        )
    }

    private val compactContainer = WrapLayout(context).apply {
        layoutParams = LayoutParams(
            LayoutParams.WRAP_CONTENT,
            LayoutParams.WRAP_CONTENT,
        )
    }

    private val fpsMetric = createMetricViews(MetricId.FPS, 0xFF66BB6A.toInt())
    private val fpsLow1Metric = createMetricViews(MetricId.FPS_LOW1, 0xFF66BB6A.toInt())
    private val fpsLow01Metric = createMetricViews(MetricId.FPS_LOW01, 0xFF66BB6A.toInt())
    private val frameTimeMetric = createMetricViews(MetricId.FRAMETIME, 0xFF66BB6A.toInt())
    private val cpuMetric = createMetricViews(MetricId.CPU, 0xFF42A5F5.toInt())
    private val gpuMetric = createMetricViews(MetricId.GPU, 0xFFEF5350.toInt())
    private val fpsGraphMetric = createMetricViews(MetricId.FPS_GRAPH, 0xFF66BB6A.toInt(), 0xFF66BB6A.toInt(), GraphScaleMode.FPS_DYNAMIC)
    private val cpuGraphMetric = createMetricViews(MetricId.CPU_GRAPH, 0xFF42A5F5.toInt(), 0xFF42A5F5.toInt(), GraphScaleMode.PERCENT_100)
    private val gpuGraphMetric = createMetricViews(MetricId.GPU_GRAPH, 0xFFEF5350.toInt(), 0xFFEF5350.toInt(), GraphScaleMode.PERCENT_100)
    private val ramMetric = createMetricViews(MetricId.RAM, 0xFFFFEE58.toInt())
    private val batteryMetric = createMetricViews(MetricId.BATTERY, 0xFFFFFFFF.toInt())
    private val powerMetric = createMetricViews(MetricId.POWER, 0xFF4DD0E1.toInt())
    private val runtimeMetric = createMetricViews(MetricId.RUNTIME, 0xFFA5D6A7.toInt())
    private val clockMetric = createMetricViews(MetricId.CLOCK, 0xFFFFCC80.toInt())
    private val cpuTempMetric = createMetricViews(MetricId.CPU_TEMP, 0xFFBDBDBD.toInt())
    private val gpuTempMetric = createMetricViews(MetricId.GPU_TEMP, 0xFFBDBDBD.toInt())
    private val batteryTempMetric = createMetricViews(MetricId.BATTERY_TEMP, 0xFFBDBDBD.toInt())
    private val fanMetric = createMetricViews(MetricId.FAN, 0xFF80DEEA.toInt())
    private val tuneMetric = createMetricViews(MetricId.TUNE, 0xFFCE93D8.toInt())

    private val cpuClockMetric = createMetricViews(MetricId.CPU_CLOCK, 0xFF80CBC4.toInt())
    private val gpuClockMetric = createMetricViews(MetricId.GPU_CLOCK, 0xFFFFAB91.toInt())
    private val vramMetric = createMetricViews(MetricId.VRAM, 0xFFB39DDB.toInt())
    private val gpuMemorySampler = GpuMemorySampler(appGraphicsBytes = ::readOwnGraphicsBytes)
    private val clockSampler = HardwareClockSampler()

    private val allMetrics = listOf(
        cpuClockMetric,
        gpuClockMetric,
        vramMetric,
        fpsGraphMetric,
        cpuGraphMetric,
        gpuGraphMetric,
        fpsMetric,
        fpsLow1Metric,
        fpsLow01Metric,
        frameTimeMetric,
        cpuMetric,
        gpuMetric,
        ramMetric,
        batteryMetric,
        powerMetric,
        runtimeMetric,
        clockMetric,
        cpuTempMetric,
        gpuTempMetric,
        batteryTempMetric,
        fanMetric,
        tuneMetric,
    )

    private val allTextRows = allMetrics.flatMap { listOf(it.stackedText, it.compactText) }
    private val allGraphs = allMetrics.flatMap { listOfNotNull(it.stackedGraph, it.compactGraph) }

    private val cpuSampler = CpuUsageSampler(processTicks = SystemMetricsSources::readProcessCpuTicks)
    private val gpuSampler = GpuUsageSampler()

    init {
        background = backgroundDrawable
        addView(stackedContainer)
        addView(compactContainer)
        applyAppearance()
        applyLayoutMode()
        refreshVisibleMetrics()
    }

    fun isCompactMode(): Boolean = isCompactMode

    fun setCompactMode(compactMode: Boolean) {
        if (isCompactMode == compactMode) {
            return
        }

        isCompactMode = compactMode
        applyLayoutMode()
        requestLayout()
    }

    fun setConfig(config: PerformanceHudConfig) {
        if (this.config == config) {
            return
        }

        this.config = config
        applyAppearance()
        lastSnapshot?.let(::applySnapshotText) ?: refreshVisibleMetrics()
        refreshVisibleMetrics()
    }

    fun pause() {
        if (!isPaused) {
            isPaused = true
            stopUpdates()
        }
    }

    fun resume() {
        if (isPaused) {
            isPaused = false
            if (isAttachedToWindow) {
                startUpdates()
            }
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (!isPaused) {
            startUpdates()
        }
    }

    override fun onDetachedFromWindow() {
        stopUpdates()
        super.onDetachedFromWindow()
    }

    private fun applyLayoutMode() {
        stackedContainer.visibility = if (isCompactMode) GONE else VISIBLE
        compactContainer.visibility = if (isCompactMode) VISIBLE else GONE
    }

    private fun startUpdates() {
        if (updateJob?.isActive == true) {
            return
        }

        updateJob = scope.launch {
            while (isActive) {
                val rawFps = fpsProvider()
                val currentFps = if (rawFps.isFinite()) rawFps.coerceAtLeast(0f) else 0f
                val requested = config
                val wantsFrameStats = requested.showFps1Low || requested.showFps01Low || requested.showFrameTime
                val snapshot = withContext(Dispatchers.IO) {
                    // The statistics (a copy and a sort of the window) are only asked for while a row needs them, and off the UI thread.
                    collectSnapshot(currentFps, requested, if (wantsFrameStats) frameStatsProvider() else null)
                }
                renderSnapshot(snapshot)
                delay(UPDATE_INTERVAL_MS)
            }
        }
    }

    private fun stopUpdates() {
        updateJob?.cancel()
        updateJob = null
    }

    private fun applyAppearance() {
        appearance = appearanceFor(config.sizePercent)
        val opacity = config.backgroundOpacity.coerceIn(MIN_BACKGROUND_OPACITY, MAX_BACKGROUND_OPACITY)

        setPadding(
            appearance.containerHorizontalPaddingDp.dp,
            appearance.containerVerticalPaddingDp.dp,
            appearance.containerHorizontalPaddingDp.dp,
            appearance.containerVerticalPaddingDp.dp,
        )

        val panelColor = when (config.style) {
            PerformanceHudStyle.CLASSIC -> Color.BLACK
            PerformanceHudStyle.TERMINAL -> Color.rgb(0, 16, 8)
            PerformanceHudStyle.AFTERBURNER -> Color.BLACK
            PerformanceHudStyle.CAPFRAMEX -> Color.rgb(12, 18, 28)
            PerformanceHudStyle.FOREVER -> Color.rgb(14, 21, 35)
        }
        backgroundDrawable.cornerRadius = when (config.style) {
            PerformanceHudStyle.TERMINAL, PerformanceHudStyle.AFTERBURNER -> 0f
            PerformanceHudStyle.CAPFRAMEX -> minOf(appearance.cornerRadiusDp, 6).dp.toFloat()
            PerformanceHudStyle.FOREVER -> maxOf(appearance.cornerRadiusDp, 10).dp.toFloat()
            else -> appearance.cornerRadiusDp.dp.toFloat()
        }
        backgroundDrawable.setColor(
            Color.argb(
                (opacity * 255f).roundToInt(),
                Color.red(panelColor),
                Color.green(panelColor),
                Color.blue(panelColor),
            ),
        )
        backgroundDrawable.setStroke(
            appearance.strokeWidthDp.dp.coerceAtLeast(1),
            if (config.style == PerformanceHudStyle.FOREVER) {
                Color.argb((opacity * 200f).roundToInt(), 199, 156, 110) // launcher gold
            } else {
                Color.argb((opacity * 96f).roundToInt(), 255, 255, 255)
            },
        )

        compactContainer.horizontalSpacing = appearance.columnSpacingDp.dp
        compactContainer.verticalSpacing = appearance.rowSpacingDp.dp

        allTextRows.forEach { textView ->
            textView.setTextSize(TypedValue.COMPLEX_UNIT_SP, appearance.textSizeSp)
            val sansSerif = config.style == PerformanceHudStyle.AFTERBURNER || config.style == PerformanceHudStyle.CAPFRAMEX ||
                config.style == PerformanceHudStyle.FOREVER
            textView.setTypeface(
                if (sansSerif) Typeface.SANS_SERIF else Typeface.MONOSPACE,
                Typeface.BOLD,
            )
            textView.maxLines = 1
            textView.ellipsize = TextUtils.TruncateAt.END
            textView.setShadowLayer(
                if (config.showTextOutline) {
                    (textView.textSize * HUD_TEXT_SHADOW_RADIUS_RATIO)
                        .coerceIn(MIN_HUD_TEXT_SHADOW_RADIUS_PX, MAX_HUD_TEXT_SHADOW_RADIUS_PX)
                } else {
                    0f
                },
                0f,
                0f,
                HUD_TEXT_SHADOW_COLOR,
            )
        }

        allMetrics.forEach(::applyMetricAppearance)
        allGraphs.forEach { it.applyAppearance(appearance) }

        attachedMetricSignature = emptyList()
        requestLayout()
    }

    private fun applyMetricAppearance(metric: MetricViews) {
        val textColor = blendMetricColor(metric.baseTextColor, metric.id)
        metric.stackedText.setTextColor(textColor)
        metric.compactText.setTextColor(textColor)
        metric.stackedText.setPadding(0, appearance.rowVerticalPaddingDp.dp, 0, 0)
        metric.compactText.setPadding(0, 0, 0, 0)
        metric.stackedGraph?.setLineColor(blendMetricColor(metric.baseGraphColor ?: metric.baseTextColor, metric.id))
        metric.compactGraph?.setLineColor(blendMetricColor(metric.baseGraphColor ?: metric.baseTextColor, metric.id))

        (metric.stackedGraph?.layoutParams as? LinearLayout.LayoutParams)?.let { params ->
            params.width = appearance.stackedGraphWidthDp.dp
            params.height = appearance.stackedGraphHeightDp.dp
            params.topMargin = appearance.stackedGraphTopMarginDp.dp
            params.bottomMargin = appearance.stackedGraphBottomMarginDp.dp
            metric.stackedGraph.layoutParams = params
        }

        (metric.compactGraph?.layoutParams as? LinearLayout.LayoutParams)?.let { params ->
            params.width = appearance.compactGraphWidthDp.dp
            params.height = appearance.compactGraphHeightDp.dp
            params.marginStart = appearance.compactGraphStartMarginDp.dp
            metric.compactGraph.layoutParams = params
        }
    }

    private fun collectSnapshot(currentFps: Float, requested: PerformanceHudConfig, frameStats: FrameTimeReading?): HudSnapshot {
        val cpuReading = if (requested.showCpuUsage || requested.showCpuUsageGraph) cpuSampler.sample() else null
        // A clock-to-maximum ratio (the last-resort source) is no load: neither the text nor the graph shows it.
        val cpuPercent = cpuReading?.loadPercent
        val gpuPercent = if (requested.showGpuUsage || requested.showGpuUsageGraph) gpuSampler.sample()?.percent else null
        val batterySnapshot = if (requested.showBatteryLevel || requested.showPowerDraw ||
            requested.showBatteryRuntime || requested.showBatteryTemperature) collectBatterySnapshot() else BatterySnapshot()
        return HudSnapshot(
            fpsValue = currentFps,
            cpuValue = cpuPercent?.toFloat(),
            gpuValue = gpuPercent?.toFloat(),
            fps = String.format(Locale.US, "FPS %.1f", currentFps),
            cpu = cpuPercent?.let { "CPU $it%" },
            gpu = gpuPercent?.let { "GPU $it%" },
            ram = if (requested.showRamUsage) "RAM ${readUsedRamText()}" else "",
            battery = batterySnapshot.percent?.let { "BAT $it%" },
            power = batterySnapshot.powerWatts?.let { watts ->
                String.format(Locale.US, "PWR %.1fW", watts)
            },
            runtime = batterySnapshot.runtimeText,
            batteryTemp = batterySnapshot.temperatureC?.let { "BAT T. ${it}°C" },
            clock = if (requested.showClockTime) readClockText() else "",
            cpuTemp = if (requested.showCpuTemperature) SystemMetricsSources.readTemperatureC(SystemMetricsSources.cpuTempPaths())
                ?.let { "CPU T. ${it}°C" } else null,
            gpuTemp = if (requested.showGpuTemperature) SystemMetricsSources.readTemperatureC(SystemMetricsSources.gpuTempPaths())
                ?.let { "GPU T. ${it}°C" } else null,
            fan = readFanText(),
            tune = readTuneText(),
            cpuClock = if (requested.showCpuClock) "CPU C. ${clockSampler.cpuMHz()?.let { "$it MHz" } ?: "N/A"}" else null,
            gpuClock = if (requested.showGpuClock) "GPU C. ${clockSampler.gpuMHz()?.let { "$it MHz" } ?: "N/A"}" else null,
            vram = if (requested.showVramUsage) "VRAM ${gpuMemorySampler.text()}" else null,
            // Lows = average of the slowest 1% / 0.1% of the last 30 s; frame time = the last second. Both follow the FPS shown above
            // (its limiter-aware readout ignores buffer bursts that are never displayed): a low can never read above the FPS, and the
            // frame time is 1000 / FPS. Hidden until enough frames exist.
            fpsLow1 = if (requested.showFps1Low) frameStats?.let { String.format(Locale.US, "1%% LOW %.1f", cappedLow(it.low1Fps, currentFps)) } else null,
            fpsLow01 = if (requested.showFps01Low) frameStats?.let { String.format(Locale.US, "0.1%% LOW %.1f", cappedLow(it.low01Fps, currentFps)) } else null,
            frameTime = if (requested.showFrameTime) {
                val ms = if (currentFps > 0f) 1000f / currentFps else frameStats?.frameTimeMs
                ms?.let { String.format(Locale.US, "FT %.1fms", it) }
            } else null,
        )
    }

    private fun cappedLow(low: Float, fps: Float): Float = if (fps > 0f) minOf(low, fps) else low

    private fun readFanText(): String? = null

    private fun readTuneText(): String? = null

    private var batteryIntent: Intent? = null
    private var batteryIntentAtMs = 0L
    private fun collectBatterySnapshot(): BatterySnapshot {
        val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
            ?: return BatterySnapshot()

        val percent = batteryManager
            .getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            .takeIf { it in 0..100 }

        // The sticky ACTION_BATTERY_CHANGED intent (status, voltage, temperature) is a full system-server round trip with a
        // parcelled Intent; it changes slowly, so it is read every 10 s instead of every HUD second. Current and charge stay live.
        val now = android.os.SystemClock.elapsedRealtime()
        val statusIntent: Intent = batteryIntent?.takeIf { now - batteryIntentAtMs < BATTERY_INTENT_REFRESH_MS }
            ?: context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))?.also { batteryIntent = it; batteryIntentAtMs = now }
            ?: return BatterySnapshot(percent = percent)

        val status = statusIntent.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN)
        val currentMicroAmps = abs(batteryManager.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW))
        val chargeCounterMicroAmpHours = batteryManager.getLongProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
        val voltageMilliVolts = statusIntent.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0)
        val temperatureC = statusIntent
            .getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0)
            .takeIf { it > 0 }
            ?.let { (it / 10f).roundToInt() }

        val powerWatts = if (currentMicroAmps > 0L && voltageMilliVolts > 0) {
            (currentMicroAmps.toDouble() * voltageMilliVolts.toDouble()) / 1_000_000_000.0
        } else {
            null
        }

        val runtimeText = when {
            status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL -> {
                smoothedBatteryRuntimeHours = null
                "LEFT CHG"
            }
            currentMicroAmps <= 0L || chargeCounterMicroAmpHours <= 0L -> null
            else -> {
                val rawHours = chargeCounterMicroAmpHours.toDouble() / currentMicroAmps.toDouble()
                if (!rawHours.isFinite() || rawHours <= 0.0 || rawHours > MAX_RUNTIME_HOURS) {
                    null
                } else {
                    val smoothedHours = smoothedBatteryRuntimeHours
                        ?.let { (it * RUNTIME_SMOOTHING_OLD_WEIGHT) + (rawHours * RUNTIME_SMOOTHING_NEW_WEIGHT) }
                        ?: rawHours
                    smoothedBatteryRuntimeHours = smoothedHours
                    "LEFT ${formatRuntimeHours(smoothedHours)}"
                }
            }
        }

        return BatterySnapshot(
            percent = percent,
            powerWatts = powerWatts,
            runtimeText = runtimeText,
            temperatureC = temperatureC,
        )
    }

    private fun readClockText(): String {
        return "TIME ${DateFormat.getTimeFormat(context).format(Date())}"
    }

    private fun renderSnapshot(snapshot: HudSnapshot) {
        lastSnapshot = snapshot
        recordGraphSamples(snapshot)
        applySnapshotText(snapshot)
        refreshVisibleMetrics()
    }

    private fun recordGraphSamples(snapshot: HudSnapshot) {
        if (config.showFrameRateGraph) {
            fpsGraphMetric.stackedGraph?.addSample(snapshot.fpsValue)
            fpsGraphMetric.compactGraph?.addSample(snapshot.fpsValue)
        }
        if (config.showCpuUsageGraph) {
            cpuGraphMetric.stackedGraph?.addSample(snapshot.cpuValue)
            cpuGraphMetric.compactGraph?.addSample(snapshot.cpuValue)
        }
        if (config.showGpuUsageGraph) {
            gpuGraphMetric.stackedGraph?.addSample(snapshot.gpuValue)
            gpuGraphMetric.compactGraph?.addSample(snapshot.gpuValue)
        }
    }

    private fun applySnapshotText(snapshot: HudSnapshot) {
        updateMetricText(cpuClockMetric, snapshot.cpuClock)
        updateMetricText(gpuClockMetric, snapshot.gpuClock)
        updateMetricText(vramMetric, snapshot.vram)
        updateMetricText(fpsMetric, snapshot.fps)
        updateMetricText(fpsLow1Metric, snapshot.fpsLow1)
        updateMetricText(fpsLow01Metric, snapshot.fpsLow01)
        updateMetricText(frameTimeMetric, snapshot.frameTime)
        updateMetricText(cpuMetric, snapshot.cpu)
        updateMetricText(gpuMetric, snapshot.gpu)
        updateMetricText(ramMetric, snapshot.ram)
        updateMetricText(batteryMetric, snapshot.battery)
        updateMetricText(powerMetric, snapshot.power)
        updateMetricText(runtimeMetric, snapshot.runtime)
        updateMetricText(batteryTempMetric, snapshot.batteryTemp)
        updateMetricText(clockMetric, snapshot.clock)
        updateMetricText(cpuTempMetric, snapshot.cpuTemp)
        updateMetricText(gpuTempMetric, snapshot.gpuTemp)
        updateMetricText(fanMetric, snapshot.fan)
        updateMetricText(tuneMetric, snapshot.tune)
    }

    private fun updateMetricText(metric: MetricViews, text: String?) {
        val safeText = text.orEmpty()
        if (metric.stackedText.text.toString() != safeText) metric.stackedText.text = safeText
        if (metric.compactText.text.toString() != safeText) metric.compactText.text = safeText
    }

    private fun refreshVisibleMetrics() {
        val viewsById = allMetrics.associateBy { it.id.name }
        val visibleMetrics = buildList {
            HudMetric.normalizeOrder(config.metricOrder).forEach { metric ->
                viewsById[metric.name]?.let { views ->
                    addMetricIfVisible(views, metric.enabled(config) && !metric.isGraph,
                        showGraph = metric.enabled(config) && metric.isGraph)
                }
            }
        }

        val signatures = visibleMetrics.map {
            MetricSignature(
                id = it.metric.id,
                showText = it.showText,
                showGraph = it.showGraph,
            )
        }
        if (signatures != attachedMetricSignature) {
            rebuildVisibleMetrics(visibleMetrics)
            attachedMetricSignature = signatures
        }

        visibility = if (visibleMetrics.isEmpty()) GONE else VISIBLE
    }

    private fun MutableList<VisibleMetric>.addMetricIfVisible(
        metric: MetricViews,
        enabled: Boolean,
        showGraph: Boolean = false,
    ) {
        val hasText = metric.stackedText.text.isNotBlank()
        val shouldShowText = enabled && hasText
        val shouldShowGraph = showGraph && metric.supportsGraph

        metric.stackedText.visibility = if (shouldShowText) VISIBLE else GONE
        metric.compactText.visibility = if (shouldShowText) VISIBLE else GONE
        metric.stackedGraph?.visibility = if (shouldShowGraph) VISIBLE else GONE
        metric.compactGraph?.visibility = if (shouldShowGraph) VISIBLE else GONE

        if (shouldShowText || shouldShowGraph) {
            add(
                VisibleMetric(
                    metric = metric,
                    showText = shouldShowText,
                    showGraph = shouldShowGraph,
                ),
            )
        }
    }

    private fun rebuildVisibleMetrics(visibleMetrics: List<VisibleMetric>) {
        stackedContainer.removeAllViews()
        compactContainer.removeAllViews()

        visibleMetrics.forEachIndexed { index, visibleMetric ->
            stackedContainer.addView(
                visibleMetric.metric.stackedContainer,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply {
                    if (index < visibleMetrics.lastIndex) {
                        bottomMargin = appearance.rowSpacingDp.dp
                    }
                },
            )
            compactContainer.addView(visibleMetric.metric.compactContainer)
        }
    }

    private fun createMetricViews(
        id: MetricId,
        textColor: Int,
        graphColor: Int? = null,
        graphScaleMode: GraphScaleMode? = null,
    ): MetricViews {
        val stackedText = createTextView(textColor)
        val compactText = createTextView(textColor)
        val stackedGraph = if (graphColor != null && graphScaleMode != null) {
            MetricGraphView(context, graphColor, graphScaleMode)
        } else {
            null
        }
        val compactGraph = if (graphColor != null && graphScaleMode != null) {
            MetricGraphView(context, graphColor, graphScaleMode)
        } else {
            null
        }

        val stackedContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(
                stackedText,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
            stackedGraph?.let {
                addView(
                    it,
                    LinearLayout.LayoutParams(
                        appearance.stackedGraphWidthDp.dp,
                        appearance.stackedGraphHeightDp.dp,
                    ),
                )
                it.visibility = GONE
            }
        }

        val compactContainer = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(
                compactText,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
            compactGraph?.let {
                addView(
                    it,
                    LinearLayout.LayoutParams(
                        appearance.compactGraphWidthDp.dp,
                        appearance.compactGraphHeightDp.dp,
                    ),
                )
                it.visibility = GONE
            }
        }

        return MetricViews(
            id = id,
            supportsGraph = stackedGraph != null && compactGraph != null,
            baseTextColor = textColor,
            baseGraphColor = graphColor,
            stackedText = stackedText,
            compactText = compactText,
            stackedContainer = stackedContainer,
            compactContainer = compactContainer,
            stackedGraph = stackedGraph,
            compactGraph = compactGraph,
        )
    }

    /** Per-style text colour: Terminal is all green, Afterburner is amber OSD text, CapFrameX uses an accent per metric. */
    private fun styleColor(original: Int, id: MetricId): Int = when (config.style) {
        PerformanceHudStyle.TERMINAL -> Color.rgb(100, 255, 130)
        PerformanceHudStyle.AFTERBURNER -> if (id == MetricId.FPS || id == MetricId.FPS_GRAPH || id == MetricId.FPS_LOW1 || id == MetricId.FPS_LOW01 || id == MetricId.FRAMETIME) Color.WHITE else Color.rgb(255, 193, 7)
        PerformanceHudStyle.CAPFRAMEX -> when (id) {
            MetricId.FPS, MetricId.FPS_GRAPH, MetricId.FPS_LOW1, MetricId.FPS_LOW01, MetricId.FRAMETIME -> Color.rgb(0, 230, 118)
            MetricId.CPU, MetricId.CPU_GRAPH, MetricId.CPU_TEMP, MetricId.CPU_CLOCK -> Color.rgb(64, 196, 255)
            MetricId.GPU, MetricId.GPU_GRAPH, MetricId.GPU_TEMP, MetricId.GPU_CLOCK, MetricId.VRAM -> Color.rgb(255, 82, 82)
            MetricId.RAM -> Color.rgb(179, 136, 255)
            else -> Color.rgb(224, 230, 238)
        }
        PerformanceHudStyle.FOREVER -> when (id) {
            MetricId.FPS, MetricId.FPS_GRAPH, MetricId.FPS_LOW1, MetricId.FPS_LOW01, MetricId.FRAMETIME -> Color.rgb(240, 230, 210)
            MetricId.CPU, MetricId.CPU_GRAPH, MetricId.CPU_TEMP, MetricId.CPU_CLOCK,
            MetricId.GPU, MetricId.GPU_GRAPH, MetricId.GPU_TEMP, MetricId.GPU_CLOCK, MetricId.VRAM -> Color.rgb(199, 156, 110)
            else -> Color.rgb(226, 232, 240)
        }
        else -> original
    }

    private fun blendMetricColor(original: Int, id: MetricId): Int {
        val color = styleColor(original, id)
        val intensity = config.colorIntensity.coerceIn(0f, 1f)
        if (intensity >= 0.999f) return color

        val hsv = FloatArray(3)
        Color.colorToHSV(color, hsv)
        hsv[1] *= intensity
        return Color.HSVToColor(Color.alpha(color), hsv)
    }

    private fun createTextView(color: Int): TextView {
        return TextView(context).apply {
            setTextColor(color)
            setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
        }
    }

    private fun readUsedRamText(): String {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return "—"
        val info = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(info)
        val usedBytes = (info.totalMem - info.availMem).coerceAtLeast(0L)
        val usedGb = usedBytes.toDouble() / (1024.0 * 1024.0 * 1024.0)
        return if (usedGb >= 1.0) {
            String.format(Locale.US, "%.1fGB", usedGb)
        } else {
            val usedMb = usedBytes / (1024L * 1024L)
            "${usedMb}MB"
        }
    }

    private fun appearanceFor(percent: Int): HudAppearance {
        val scale = HudMetric.scale(percent)
        fun scaled(value: Int) = (value * scale).roundToInt().coerceAtLeast(0)
        return HudAppearance(
            textSizeSp = 13f * scale,
            containerHorizontalPaddingDp = scaled(12),
            containerVerticalPaddingDp = scaled(10),
            rowVerticalPaddingDp = scaled(3),
            rowSpacingDp = scaled(8),
            columnSpacingDp = scaled(14),
            cornerRadiusDp = scaled(12),
            strokeWidthDp = 1,
            stackedGraphWidthDp = scaled(88),
            stackedGraphHeightDp = scaled(20),
            compactGraphWidthDp = scaled(56),
            compactGraphHeightDp = scaled(16),
            stackedGraphTopMarginDp = scaled(2),
            stackedGraphBottomMarginDp = scaled(4),
            compactGraphStartMarginDp = scaled(8),
        )
    }

    private val Int.dp: Int
        get() = (this * resources.displayMetrics.density).roundToInt()

    private val Float.dpF: Float
        get() = this * resources.displayMetrics.density

    private data class MetricViews(
        val id: MetricId,
        val supportsGraph: Boolean,
        val baseTextColor: Int,
        val baseGraphColor: Int?,
        val stackedText: TextView,
        val compactText: TextView,
        val stackedContainer: LinearLayout,
        val compactContainer: LinearLayout,
        val stackedGraph: MetricGraphView? = null,
        val compactGraph: MetricGraphView? = null,
    )

    private data class VisibleMetric(
        val metric: MetricViews,
        val showText: Boolean,
        val showGraph: Boolean,
    )

    private inner class MetricGraphView(
        context: Context,
        lineColor: Int,
        private val scaleMode: GraphScaleMode,
    ) : View(context) {
        private val samples = ArrayDeque<Float>()
        private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(102, Color.red(lineColor), Color.green(lineColor), Color.blue(lineColor))
            style = Paint.Style.STROKE
            strokeWidth = 2.5f.dpF
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = lineColor
            style = Paint.Style.STROKE
            strokeWidth = 1.5f.dpF
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        private val path = Path()

        fun setLineColor(color: Int) {
            linePaint.color = color
            glowPaint.color = Color.argb(102, Color.red(color), Color.green(color), Color.blue(color))
            invalidate()
        }

        fun applyAppearance(appearance: HudAppearance) {
            linePaint.strokeWidth = when (appearance.textSizeSp) {
                in 0f..10.5f -> 1.2f.dpF
                in 10.5f..12f -> 1.5f.dpF
                else -> 1.8f.dpF
            }
            glowPaint.strokeWidth = linePaint.strokeWidth * 2f
            invalidate()
        }

        fun addSample(value: Float?) {
            val sample = value
                ?.takeIf { it.isFinite() && it >= 0f }
                ?: Float.NaN
            if (samples.size >= GRAPH_SAMPLE_COUNT) {
                samples.removeFirst()
            }
            samples.addLast(sample)
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            if (width <= 0 || height <= 0 || samples.isEmpty()) {
                return
            }

            val values = samples.toList()
            val validValues = values.filter { it.isFinite() }
            if (validValues.size < 2) {
                return
            }

            val chartWidth = width.toFloat()
            val chartHeight = height.toFloat()
            val maxValue = when (scaleMode) {
                GraphScaleMode.FPS_DYNAMIC -> max(GRAPH_FPS_MIN_SCALE, (validValues.maxOrNull() ?: GRAPH_FPS_MIN_SCALE) * 1.05f)
                GraphScaleMode.PERCENT_100 -> 100f
            }
            val xStep = if (values.size > 1) chartWidth / (values.size - 1) else chartWidth

            path.reset()
            var hasActiveSegment = false
            values.forEachIndexed { index, value ->
                if (!value.isFinite()) {
                    hasActiveSegment = false
                    return@forEachIndexed
                }

                val x = index * xStep
                val normalized = (value / maxValue).coerceIn(0f, 1f)
                val y = chartHeight - (normalized * chartHeight)
                if (!hasActiveSegment) {
                    path.moveTo(x, y)
                    hasActiveSegment = true
                } else {
                    path.lineTo(x, y)
                }
            }

            canvas.drawPath(path, glowPaint)
            canvas.drawPath(path, linePaint)
        }
    }

    private inner class WrapLayout(context: Context) : ViewGroup(context) {
        var horizontalSpacing: Int = 0
            set(value) {
                field = value
                requestLayout()
            }

        var verticalSpacing: Int = 0
            set(value) {
                field = value
                requestLayout()
            }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val widthMode = MeasureSpec.getMode(widthMeasureSpec)
            val maxContentWidth = when (widthMode) {
                MeasureSpec.UNSPECIFIED -> Int.MAX_VALUE
                else -> (MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight).coerceAtLeast(0)
            }

            var lineWidth = 0
            var lineHeight = 0
            var maxLineWidthUsed = 0
            var totalHeight = paddingTop + paddingBottom
            var visibleChildCount = 0

            for (index in 0 until childCount) {
                val child = getChildAt(index)
                if (child.visibility == GONE) continue

                measureChild(child, widthMeasureSpec, heightMeasureSpec)
                val childWidth = child.measuredWidth
                val childHeight = child.measuredHeight
                val proposedWidth = if (lineWidth == 0) {
                    childWidth
                } else {
                    lineWidth + horizontalSpacing + childWidth
                }

                if (lineWidth > 0 && proposedWidth > maxContentWidth) {
                    maxLineWidthUsed = max(maxLineWidthUsed, lineWidth)
                    totalHeight += lineHeight + verticalSpacing
                    lineWidth = childWidth
                    lineHeight = childHeight
                } else {
                    lineWidth = proposedWidth
                    lineHeight = max(lineHeight, childHeight)
                }
                visibleChildCount++
            }

            if (visibleChildCount > 0) {
                maxLineWidthUsed = max(maxLineWidthUsed, lineWidth)
                totalHeight += lineHeight
            }

            val measuredWidth = resolveSize(maxLineWidthUsed + paddingLeft + paddingRight, widthMeasureSpec)
            val measuredHeight = resolveSize(totalHeight, heightMeasureSpec)
            setMeasuredDimension(measuredWidth, measuredHeight)
        }

        override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
            val maxContentWidth = (right - left - paddingLeft - paddingRight).coerceAtLeast(0)
            var x = paddingLeft
            var y = paddingTop
            var lineHeight = 0

            for (index in 0 until childCount) {
                val child = getChildAt(index)
                if (child.visibility == GONE) continue

                val childWidth = child.measuredWidth
                val childHeight = child.measuredHeight
                val proposedRight = if (x == paddingLeft) {
                    x + childWidth
                } else {
                    x + horizontalSpacing + childWidth
                }

                if (x != paddingLeft && proposedRight - paddingLeft > maxContentWidth) {
                    x = paddingLeft
                    y += lineHeight + verticalSpacing
                    lineHeight = 0
                }

                if (x != paddingLeft) {
                    x += horizontalSpacing
                }

                child.layout(
                    x,
                    y,
                    x + childWidth,
                    y + childHeight,
                )
                x += childWidth
                lineHeight = max(lineHeight, childHeight)
            }
        }
    }

    private companion object {
        const val UPDATE_INTERVAL_MS = 1_000L
        const val MIN_BACKGROUND_OPACITY = 0.0f
        const val MAX_BACKGROUND_OPACITY = 1.0f
        const val MAX_RUNTIME_HOURS = 72.0
        const val BATTERY_INTENT_REFRESH_MS = 10_000L
        const val RUNTIME_SMOOTHING_OLD_WEIGHT = 0.65
        const val RUNTIME_SMOOTHING_NEW_WEIGHT = 0.35
        const val GRAPH_SAMPLE_COUNT = 30
        const val GRAPH_FPS_MIN_SCALE = 60f
        const val HUD_TEXT_SHADOW_RADIUS_RATIO = 0.18f
        const val MIN_HUD_TEXT_SHADOW_RADIUS_PX = 1.5f
        const val MAX_HUD_TEXT_SHADOW_RADIUS_PX = 4f
        val HUD_TEXT_SHADOW_COLOR: Int = Color.argb(220, 0, 0, 0)
    }
}
