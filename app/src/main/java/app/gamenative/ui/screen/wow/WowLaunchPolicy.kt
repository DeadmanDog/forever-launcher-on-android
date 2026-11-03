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
    private val onByDefault = setOf(WowHardwareScale.KEY, "priority", WowRuntimeTuning.LOW_LATENCY)
    fun enabled(context: Context, key: String) = context.getSharedPreferences(PREFS, 0).getBoolean(key, key in onByDefault)
    fun save(context: Context, key: String, value: Boolean) {
        check(!app.gamenative.PluviaApp.keepAlive) { "Stop the game before changing launch experiments." }
        check(context.getSharedPreferences(PREFS, 0).edit().putBoolean(key, value).commit())
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
