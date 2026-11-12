package app.gamenative.ui.screen.wow

import android.content.Context
import com.winlator.core.envvars.EnvVars

/** Preferences for future launch experiments; never mutate the Container. */
internal object WowLaunchPolicy {
    const val PREFS = "wow_launch_experiments"
    enum class Memory(val label: String) {
        SYSMEM("Sysmem"), GMEM("GMEM"), DEFAULT("Driver default")
    }
    fun memory(context: Context): Memory = runCatching {
        Memory.valueOf(context.getSharedPreferences(PREFS, 0).getString("memory", Memory.SYSMEM.name)!!)
    }.getOrDefault(Memory.SYSMEM)
    fun saveMemory(context: Context, value: Memory) {
        check(!app.gamenative.PluviaApp.keepAlive) { "Stop the game before changing launch experiments." }
        check(context.getSharedPreferences(PREFS, 0).edit().putString("memory", value.name).commit())
    }
    /** Switches that are ON until the player turns them off (everything else is off by default). */
    private val onByDefault = setOf(WowHardwareScale.KEY, "priority", WowRuntimeTuning.LOW_LATENCY, WowRuntimeTuning.EVENT_POOL,
        // The user's measured best set (2026-11-07): big/little cores, Hybrid keeps GPL, DXVK bind-skip, safe buffer hand-back.
        WowRuntimeTuning.CPU_CLASSES, WowRuntimeTuning.HYBRID_GPL, WowRuntimeTuning.VEGAS_BINDSKIP, "presentidle",
        // 1.1.0: DX12 game frames wait for their GPU work before the X server gets them (the loading bar jumping back); 1.1.1 in FIFO.
        WowRuntimeTuning.GAME_GPU_WAIT)
    /** A switch split from an older one starts from the older one's saved value (1.1.7: DX12 low latency from the shared switch). */
    private val inheritsFrom = mapOf(WowRuntimeTuning.LOW_LATENCY_DX12 to WowRuntimeTuning.LOW_LATENCY)
    fun enabled(context: Context, key: String): Boolean {
        val prefs = context.getSharedPreferences(PREFS, 0)
        val fallback = inheritsFrom[key]?.let { prefs.getBoolean(it, it in onByDefault) } ?: (key in onByDefault)
        return prefs.getBoolean(key, fallback)
    }
    fun save(context: Context, key: String, value: Boolean) {
        check(!app.gamenative.PluviaApp.keepAlive) { "Stop the game before changing launch experiments." }
        val prefs = context.getSharedPreferences(PREFS, 0)
        val edit = prefs.edit()
        // 1.1.9: a split-off switch keeps the value it started from once its parent changes (it only inherits until then).
        for ((child, parent) in inheritsFrom) if (parent == key && !prefs.contains(child)) edit.putBoolean(child, enabled(context, child))
        check(edit.putBoolean(key, value).commit())
    }
    /**
     * 1.3.2: one choice for how the game image reaches the display instead of two switches that overlapped (scanout already
     * scales in hardware, so the Hardware scaling switch did nothing while it was on).
     */
    enum class DisplayOutput { HARDWARE_SCALE, COMPOSITOR, SCANOUT }

    fun displayOutput(scanout: Boolean, hardwareScale: Boolean): DisplayOutput =
        if (scanout) DisplayOutput.SCANOUT else if (hardwareScale) DisplayOutput.HARDWARE_SCALE else DisplayOutput.COMPOSITOR

    fun displayOutput(context: Context): DisplayOutput =
        displayOutput(enabled(context, "scanout"), enabled(context, WowHardwareScale.KEY))

    /** Scanout leaves the hardware-scaling switch as it is (unused while scanout is on); the other two set both. */
    fun saveDisplayOutput(context: Context, mode: DisplayOutput) {
        save(context, "scanout", mode == DisplayOutput.SCANOUT)
        if (mode != DisplayOutput.SCANOUT) save(context, WowHardwareScale.KEY, mode == DisplayOutput.HARDWARE_SCALE)
    }

    fun hasSavedTuDebugDisplay(environment: String): Boolean =
        environment.lineSequence().any { it.startsWith("TU_DEBUG=") }
    fun flags(current: String, mode: Memory): String {
        val kept = current.split(',').map(String::trim).filter { it.isNotEmpty() && it != "sysmem" && it != "gmem" }.toMutableList()
        if ("noconform" !in kept) kept.add("noconform")
        when (mode) { Memory.SYSMEM -> kept.add("sysmem"); Memory.GMEM -> kept.add("gmem"); Memory.DEFAULT -> Unit }
        return kept.distinct().joinToString(",")
    }
    fun apply(context: Context, launch: EnvVars, saved: String) {
        if (EnvVars(saved).has("TU_DEBUG")) return
        launch.put("TU_DEBUG", flags(launch.get("TU_DEBUG"), memory(context)))
    }
}
