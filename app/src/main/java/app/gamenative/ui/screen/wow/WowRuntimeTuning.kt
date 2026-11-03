package app.gamenative.ui.screen.wow

import android.content.Context
import com.winlator.container.Container
import com.winlator.core.envvars.EnvVars

/** Launch-local API settings. Saved user variables always take precedence. */
internal object WowRuntimeTuning {
    private val options = mapOf(
        "latency" to ("VKD3D_SWAPCHAIN_LATENCY_FRAMES" to setOf("1", "2", "3")),
        "images" to ("VKD3D_SWAPCHAIN_IMAGES" to setOf("2", "3", "4")),
        "present" to ("MESA_VK_WSI_PRESENT_MODE" to setOf("fifo", "mailbox")),
        "async" to ("DXVK_ASYNC" to setOf("0", "1", "2")),
        // TurnRIP 1.0.6+: 1 keeps the KGSL PWR_MAX constraint (Performance), 0 lets DVFS decide (Balanced, default).
        // TurnRIP 1.0.7+: fNN = DVFS with a floor of NN% of the max GPU clock (see gpuFloor).
        "gpu" to ("TU_KGSL_PWR_MAX" to setOf("0", "1") + gpuFloors.map { "f$it" }),
        // Proton r10+: 1 hands a signaled object directly to one waiter instead of waking all (default 0).
        "handoff" to ("NTSYNC_WAKE_HANDOFF" to setOf("0", "1")),
    )
    /** Frequency floors offered for the regulated GPU mode, in percent of the max clock. */
    val gpuFloors get() = listOf(50, 65, 80)

    /** "f65" -> 65; null for Performance/Balanced/default. */
    fun gpuFloor(choice: String): Int? = choice.removePrefix("f").takeIf { choice.startsWith("f") }?.toIntOrNull()?.takeIf { it in gpuFloors }

    /** v1.3.4+: GPL is the default DX11 shader mode (fast linking, no skipped draws) until the player picks another; Hybrid compiles every pipeline in full on this GPU. */
    fun asyncIsDefault(context: Context): Boolean = !context.getSharedPreferences(WowLaunchPolicy.PREFS, 0).contains("async")

    /**
     * Until the player picks a present mode the default applies: FIFO since v1.3.39 (Mailbox from v1.0). With the vsync-locked
     * pacer FIFO measured smooth (no dropped frame); Mailbox dropped one every second or two (1.3.28 diagnostics).
     */
    fun presentIsDefault(context: Context): Boolean = !context.getSharedPreferences(WowLaunchPolicy.PREFS, 0).contains("present")

    fun selected(context: Context, key: String): String =
        if (key == "async" && asyncIsDefault(context)) "0"
        else if (key == "present" && presentIsDefault(context)) "fifo"
        else context.getSharedPreferences(WowLaunchPolicy.PREFS, 0).getString(key, "").orEmpty()
            // v0.1.73+: Balanced is the GPU default, so an explicit "0" is the same choice as "".
            .let { if (key == "gpu" && it == "0") "" else it }
            // The old "container setting" DX11 choice ("") left the container's DXVK_ASYNC=1, i.e. Async: shown and applied as Async.
            .let { if (key == "async" && it == "") "1" else it }
            // One FIFO entry: an old explicit "Default (FIFO)" ("") reads and applies as FIFO; handoff "" was the same as Off (0).
            .let { if (key == "present" && it == "") "fifo" else if (key == "handoff" && it == "") "0" else it }

    /** Choices that map to app behaviour instead of a single env variable. */
    private val appChoices = mapOf("pacing" to setOf("api", "timer", "phase"), "fifolatency" to setOf("low", "lowest"), "cpuprofile" to setOf("laptop", "off"), "gpuprofile" to setOf("laptop", "off"),
        // The WoW game account (WoW1, WoW2 ...) preselected at login when the Battle.net account has several; "" = the last used one.
        GAME_ACCOUNT to setOf("1", "2"),
        // The game region the next launch connects to; "" = the install's own region (.build.info). The game data is the same.
        REGION to WowClientDownloader.REGIONS.toSet(),
        // The game's text and voice language; "" leaves what Config.wtf has. These are the locales the wowf install manifest lists.
        GAME_LOCALE to GAME_LOCALES.toSet())

    /**
     * The exec/W^X shim every Wine process preloads: the app's own open libwowexec.so (app/src/main/cpp/wowexec) from the APK's
     * native library folder. Android denies running files from the app's data folder (W^X); the shim starts such programs through
     * the system linker and makes their mapped code executable. Since 1.4.3 it replaces GameNative's closed libredirect, which the
     * 1.4.1/1.4.2 test switch proved unneeded (own = starts like GameNative's, none = Wine does not start).
     */
    @JvmStatic
    fun execShim(nativeLibDir: String): String = "$nativeLibDir/libwowexec.so"

    const val GAME_LOCALE = "gamelocale"
    val GAME_LOCALES get() = listOf("enUS", "deDE", "frFR", "esES", "esMX", "ptBR", "ruRU", "koKR", "zhCN", "zhTW")

    /** The game language for the next launch, or null to keep the one in Config.wtf. */
    fun gameLocale(context: Context): String? = selected(context, GAME_LOCALE).takeIf { it in GAME_LOCALES }

    const val REGION = "region"

    /** The region the next launch connects to, or null for the install's own one. */
    fun region(context: Context): String? = selected(context, REGION).takeIf { it in WowClientDownloader.REGIONS }

    const val GAME_ACCOUNT = "gameaccount"

    /** The game account to preselect (1-based), or null for the one WoW used last. */
    fun gameAccount(context: Context): Int? = selected(context, GAME_ACCOUNT).toIntOrNull()?.takeIf { it in 1..2 }

    fun save(context: Context, key: String, value: String) {
        require(value.isEmpty() || value in (appChoices[key] ?: options.getValue(key).second))
        check(!app.gamenative.PluviaApp.keepAlive && app.gamenative.PluviaApp.xEnvironment == null) {
            "Stop the game before changing launch settings."
        }
        check(context.getSharedPreferences(WowLaunchPolicy.PREFS, 0).edit().putString(key, value).commit())
    }

    fun target(container: Container): Int =
        if (container.getExtra("fpsLimiterEnabled").equals("false", true)) 0
        else container.getExtra("fpsLimiterTarget").toIntOrNull()?.takeIf { it in 5..1000 } ?: 60

    /**
     * The frame limit in a DXVK_CONFIG. DXVK 3.x (the Vegas 3.1.1 line, [alias] true) also reads dxvk.maxFrameRate and prefers it
     * over dxgi.maxFrameRate; Vegas 2.7.3 (r50) knows only dxgi.maxFrameRate, so there the newer key limits nothing.
     */
    fun configFrameRate(config: String, alias: Boolean = false): String? {
        fun last(key: String) = config.split(';').mapNotNull { item ->
            val index = item.indexOf('=')
            if (index >= 0 && item.substring(0, index).trim() == key) item.substring(index + 1).trim() else null
        }.lastOrNull()
        // DXVK reads the newer key as an integer and falls back to dxgi.maxFrameRate when it is not one.
        return (if (alias) last("dxvk.maxFrameRate")?.takeIf { it.toIntOrNull() != null } else null) ?: last("dxgi.maxFrameRate")
    }

    /** The container's DXVK reads dxvk.maxFrameRate (the Vegas 3.1.1 line). */
    fun readsDxvkFrameRateAlias(container: Container): Boolean = container.dxWrapper.startsWith("dxvk-Vegas-3.")

    /** A frame-rate limit the user typed into the saved environment for DXVK/VKD3D. */
    fun hasUserApiLimiter(saved: EnvVars, alias: Boolean = false): Boolean =
        saved.has("VKD3D_FRAME_RATE") || saved.has("DXVK_FRAME_RATE") || configFrameRate(saved.get("DXVK_CONFIG"), alias) != null

    /**
     * Frame pacing owner. Default (as up to v0.1.46): the app's X Present limiter, which
     * releases frames on display vsync. "api" (v0.1.47-v0.1.62 behaviour): DXVK/VKD3D
     * timer limiters, which are not vsync-aligned and made motion judder on both APIs.
     * A limiter typed into the saved environment always selects the API path.
     */
    fun appPacing(context: Context, container: Container): Boolean =
        selected(context, "pacing") != "api" && !hasUserApiLimiter(EnvVars(container.envVars), readsDxvkFrameRateAlias(container))

    /**
     * App pacing releases buffers on the display's vsync (v0.1.114+ default). "timer" = the
     * free-running 1/limit timer of v0.1.63-v0.1.113, which drifts against the panel's real rate.
     */
    fun vsyncPacing(context: Context): Boolean = selected(context, "pacing") != "timer"

    /** Experimental (v1.3.33): vsync pacing whose releases follow the measured display phase (see PresentPhase). */
    fun phaseAlign(context: Context): Boolean = selected(context, "pacing") == "phase"

    /**
     * FIFO latency drain (v1.3.39, three levels since v1.3.41): "" = smooth, the queue as it fills (about 60 ms at 60 Hz, never a
     * skip); "low" = one step after a hitch, then stop; "lowest" = also tries one step further (an extra hitch if it does not hold).
     */
    fun fifoDrainMode(context: Context): Int = when (selected(context, "fifolatency")) { "low" -> 1; "lowest" -> 2; else -> 0 }
    fun fifoLowLatency(context: Context): Boolean = fifoDrainMode(context) != 0

    /** App pacing: no API limiter is injected; inherited API limiter env is dropped. */
    fun applyAppPacing(env: EnvVars, target: Int): String {
        env.remove("DXVK_FRAME_RATE")
        env.remove("VKD3D_FRAME_RATE")
        return if (target > 0) "App Present limiter: $target FPS; API limiter OFF"
        else "App Present limiter OFF; API limiter OFF"
    }

    fun applyLimiter(env: EnvVars, dx12: Boolean, target: Int, alias: Boolean = false): String {
        if (dx12) {
            if (!env.has("VKD3D_FRAME_RATE") && target > 0) env.put("VKD3D_FRAME_RATE", target)
            return if (env.has("VKD3D_FRAME_RATE")) "VKD3D: ${env.get("VKD3D_FRAME_RATE")} FPS (app Present limiter OFF)"
            else "API limiter OFF (app Present limiter OFF)"
        }
        val custom = configFrameRate(env.get("DXVK_CONFIG"), alias)
        if (custom != null) {
            env.remove("DXVK_FRAME_RATE") // Explicit config wins over the legacy limiter env.
            return "DXVK user config: $custom FPS (app Present limiter OFF)"
        }
        if (env.has("DXVK_FRAME_RATE")) return "DXVK user env: ${env.get("DXVK_FRAME_RATE")} FPS (app Present limiter OFF)"
        if (target > 0) {
            val current = env.get("DXVK_CONFIG").trimEnd().trimEnd(';')
            env.put("DXVK_CONFIG", (if (current.isEmpty()) "" else "$current;") + "dxgi.maxFrameRate=$target")
            return "DXVK: $target FPS (app Present limiter OFF)"
        }
        return "API limiter OFF (app Present limiter OFF)"
    }

    /** WowLaunchPolicy switch: DX11 frame latency 1 (on by default since v0.1.113). */
    const val LOW_LATENCY = "lowlatency"

    /**
     * What the game is told about the processor and GPU (Proton r22+ / Vegas r48+). Saved choice values:
     * "" = this phone's own SoC and Adreno (the default), "laptop" = a Windows-on-ARM laptop (Snapdragon X Plus +
     * Adreno X1), "off" = what Android gives (no CPU name; the NVIDIA GeForce name for the GPU).
     */
    const val CPU_PROFILE = "cpuprofile"
    const val GPU_PROFILE = "gpuprofile"
    const val CPU_VENDOR = "Qualcomm Technologies Inc"
    // The Wine side keeps at most 48 characters of the name.
    const val LAPTOP_CPU_NAME = "Snapdragon(R) X Plus Oryon(TM) CPU"

    /** The marketing name of a Snapdragon SoC from Build.SOC_MODEL; null when unknown. */
    fun realCpuName(socModel: String): String? = when (socModel.uppercase()) {
        "SM8850" -> "Snapdragon(R) 8 Elite Gen 5 Oryon(TM) CPU"
        "SM8750" -> "Snapdragon(R) 8 Elite Oryon(TM) CPU"
        "SM8650" -> "Snapdragon(R) 8 Gen 3 Kryo(TM) CPU"
        "SM8550" -> "Snapdragon(R) 8 Gen 2 Kryo(TM) CPU"
        "SM8475" -> "Snapdragon(R) 8+ Gen 1 Kryo(TM) CPU"
        "SM8450" -> "Snapdragon(R) 8 Gen 1 Kryo(TM) CPU"
        else -> null
    }

    /** "Adreno (TM) 840" -> "Qualcomm(R) Adreno(TM) 840 GPU"; null when no model number is found. */
    fun realGpuName(gpuModel: String?): String? =
        Regex("""\d{3}""").find(gpuModel.orEmpty())?.value?.let { "Qualcomm(R) Adreno(TM) $it GPU" }

    /** The device id the Adreno reports itself (Mesa/Freedreno chip ids); null for models not in the table. */
    fun realGpuDeviceId(gpuModel: String?): String? = when (Regex("""\d{3}""").find(gpuModel.orEmpty())?.value) {
        "840" -> "44050a31"
        "830" -> "44050001"
        "750" -> "43051401"
        "740" -> "43050a01"
        else -> null
    }

    /** Saved WINE_CPU_NAME / _VENDOR / _PEAK_MHZ (Environment Variables) always win. Every core reports the chip's peak frequency. */
    fun applyCpuIdentity(env: EnvVars, saved: EnvVars, profile: String, socModel: String) {
        val values = when (profile) {
            "off" -> null
            "laptop" -> mapOf("WINE_CPU_VENDOR" to CPU_VENDOR, "WINE_CPU_NAME" to LAPTOP_CPU_NAME, "WINE_CPU_PEAK_MHZ" to "1")
            else -> mapOf("WINE_CPU_VENDOR" to CPU_VENDOR,
                "WINE_CPU_NAME" to (realCpuName(socModel) ?: "Snapdragon(R) $socModel CPU"), "WINE_CPU_PEAK_MHZ" to "1")
        }
        for (key in listOf("WINE_CPU_VENDOR", "WINE_CPU_NAME", "WINE_CPU_PEAK_MHZ")) {
            if (saved.has(key)) continue
            val value = values?.get(key)
            if (value != null) env.put(key, value) else env.remove(key)
        }
    }

    /** Saved DXVK_VEGAS_* (Environment Variables) always win. the default keeps the Qualcomm vendor with this Adreno's own name and id. */
    fun applyGpuIdentity(env: EnvVars, saved: EnvVars, profile: String, gpuModel: String?) {
        val values = when (profile) {
            "off" -> emptyMap()
            "laptop" -> mapOf("DXVK_VEGAS_PERSONA" to "qualcomm")
            else -> buildMap {
                put("DXVK_VEGAS_PERSONA", "qualcomm")
                realGpuName(gpuModel)?.let { put("DXVK_VEGAS_GPU_NAME", it) }
                realGpuDeviceId(gpuModel)?.let { put("DXVK_VEGAS_GPU_DEVICE", it) }
            }
        }
        for (key in listOf("DXVK_VEGAS_PERSONA", "DXVK_VEGAS_GPU_NAME", "DXVK_VEGAS_GPU_DEVICE")) {
            if (saved.has(key)) continue
            val value = values[key]
            if (value != null) env.put(key, value) else env.remove(key)
        }
    }

    /** WowLaunchPolicy switch: report the highest-clocked cores as performance cores (Proton r21+, off by default). */
    const val CPU_CLASSES = "cpuclasses"

    /** Saved WINE_CPU_EFFICIENCY wins; OFF removes an inherited value. */
    fun applyCpuClasses(env: EnvVars, saved: EnvVars, enabled: Boolean) {
        if (saved.has("WINE_CPU_EFFICIENCY")) return
        if (enabled) env.put("WINE_CPU_EFFICIENCY", "1") else env.remove("WINE_CPU_EFFICIENCY")
    }

    /** The Adreno model text from sysfs ("Adreno (TM) 840"), or null. */
    fun kgslGpuModel(): String? = listOf("/sys/class/kgsl/kgsl-3d0/gpu_model", "/sys/class/kgsl/kgsl-3d0/devfreq/device/gpu_model")
        .firstNotNullOfOrNull { runCatching { java.io.File(it).readText().trim() }.getOrNull()?.takeIf { text -> text.isNotEmpty() } }

    /** WowLaunchPolicy switch: Vegas r49+ real pipeline bind-skip (experimental, off by default; not in the Quick Menu). */
    const val VEGAS_BINDSKIP = "vegasbindskip"
    const val HYBRID_GPL = "hybridgpl"

    /** Saved DXVK_HYBRID_GPL (Environment Variables) wins; off removes an inherited value. Only acts in Hybrid mode (Vegas r50+). */
    fun applyHybridGpl(env: EnvVars, saved: EnvVars, enabled: Boolean) {
        if (saved.has("DXVK_HYBRID_GPL")) return
        if (enabled) env.put("DXVK_HYBRID_GPL", "1") else env.remove("DXVK_HYBRID_GPL")
    }

    /** Saved VEGAS_BINDSKIP (Environment Variables) wins; off removes an inherited value. */
    fun applyVegasBindSkip(env: EnvVars, saved: EnvVars, enabled: Boolean) {
        if (saved.has("VEGAS_BINDSKIP")) return
        if (enabled) env.put("VEGAS_BINDSKIP", "1") else env.remove("VEGAS_BINDSKIP")
    }

    fun configFrameLatency(config: String): String? = config.split(';').mapNotNull { item ->
        val index = item.indexOf('=')
        if (index >= 0 && item.substring(0, index).trim() == "dxgi.maxFrameLatency")
            item.substring(index + 1).trim() else null
    }.lastOrNull()

    /**
     * DX11: the app paces by holding presented images back, so DXVK let the game queue up to
     * min(3, its buffer count) frames ahead: 33-50 ms more input lag at 60 FPS. Frame latency 1
     * keeps one frame in flight. A maxFrameLatency in the saved DXVK_CONFIG always wins.
     */
    fun applyFrameLatency(env: EnvVars, dx12: Boolean, enabled: Boolean) {
        if (dx12 || !enabled || configFrameLatency(env.get("DXVK_CONFIG")) != null) return
        val current = env.get("DXVK_CONFIG").trimEnd().trimEnd(';')
        env.put("DXVK_CONFIG", (if (current.isEmpty()) "" else "$current;") + "dxgi.maxFrameLatency=1")
    }

    fun applyChoices(env: EnvVars, saved: EnvVars, dx12: Boolean, choices: Map<String, String>) {
        for ((key, pair) in options) {
            // DXVK pipeline mode is DX11-only and VKD3D swapchain controls are DX12-only;
            // the game present mode and GPU power mode apply to both APIs.
            if (key == "async" && dx12) continue
            if ((key == "latency" || key == "images") && !dx12) continue
            val value = choices[key].orEmpty()
            // The original managed DXVK_ASYNC=1 remains the creation default.
            // Choosing another mode explicitly supersedes that exact default.
            val managedAsync = key == "async" && saved.get(pair.first) == "1"
            if (key == "gpu" && value.isEmpty()) {
                // v0.1.73+: no choice = Balanced (PWR_MAX off; TurnRIP wait boost and ADPF regulate).
                if (!saved.has(pair.first)) env.put(pair.first, "0")
                continue
            }
            if (key == "gpu" && gpuFloor(value) != null) {
                // The floor only acts with PWR_MAX off; saved variables still win.
                if (!saved.has(pair.first)) env.put(pair.first, "0")
                if (!saved.has("TU_KGSL_GPU_FLOOR")) env.put("TU_KGSL_GPU_FLOOR", gpuFloor(value)!!)
                continue
            }
            if (value in pair.second && (!saved.has(pair.first) || managedAsync)) env.put(pair.first, value)
        }
    }

    fun effectiveLimiter(container: Container, env: EnvVars): String {
        val saved = EnvVars(container.envVars)
        val dx12 = WowVkd3dComponents.api(container) == WowVkd3dComponents.DX12
        val alias = readsDxvkFrameRateAlias(container)
        val user = if (dx12) saved.has("VKD3D_FRAME_RATE") else configFrameRate(saved.get("DXVK_CONFIG"), alias) != null || saved.has("DXVK_FRAME_RATE")
        val value = if (dx12) env.get("VKD3D_FRAME_RATE") else configFrameRate(env.get("DXVK_CONFIG"), alias) ?: env.get("DXVK_FRAME_RATE")
        return "${if (dx12) "VKD3D" else "DXVK"}: ${value.ifBlank { "OFF" }} (${if (user) "user" else "container"})"
    }
    fun surfaceTarget(container: Container, requested: Int): Int {
        val saved = EnvVars(container.envVars)
        val value = if (WowVkd3dComponents.api(container) == WowVkd3dComponents.DX12) {
            if (saved.has("VKD3D_FRAME_RATE")) saved.get("VKD3D_FRAME_RATE") else null
        } else configFrameRate(saved.get("DXVK_CONFIG"), readsDxvkFrameRateAlias(container)) ?: saved.get("DXVK_FRAME_RATE").takeIf { saved.has("DXVK_FRAME_RATE") }
        return if (value == null) requested else value.toDoubleOrNull()?.takeIf { it.isFinite() && it in 0.0..1000.0 }?.toInt() ?: 0
    }

    /** The frame limit DXVK/VKD3D were started with in this launch (0 = none, or the app limiter owns pacing). Set by [apply]. */
    @Volatile
    var apiLimitAtLaunch: Int = 0
        private set

    private fun apiLimitIn(env: EnvVars, dx12: Boolean, alias: Boolean): Int {
        val value = if (dx12) env.get("VKD3D_FRAME_RATE") else configFrameRate(env.get("DXVK_CONFIG"), alias) ?: env.get("DXVK_FRAME_RATE")
        return value.toDoubleOrNull()?.takeIf { it.isFinite() && it > 0.0 && it <= 1000.0 }?.toInt() ?: 0
    }

    /**
     * What a limit change made in the running game really does. [app] is the app's Present limiter, [inForce] the limit that now holds
     * (for the HUD's burst smoothing), [deferred] true when the request cannot take effect before the next launch.
     */
    class LiveLimit(val app: Int, val inForce: Int, val deferred: Boolean)

    /**
     * When the app limiter owns pacing the request is simply its limit. When DXVK/VKD3D own it, their limit is fixed in the running
     * process (an environment variable of the game): a LOWER request is enforced by the app limiter on top of it (the lower of the two
     * binds, so they never fight), while a higher one, or OFF, cannot lift the API limit before the next launch.
     */
    fun live(apiOwned: Boolean, requested: Int, apiLimit: Int = apiLimitAtLaunch): LiveLimit {
        if (!apiOwned) return LiveLimit(requested, requested, false)
        val lower = requested > 0 && (apiLimit <= 0 || requested < apiLimit)
        val inForce = if (lower) requested else apiLimit
        return LiveLimit(if (lower) requested else 0, inForce, requested != inForce)
    }

    fun liveNotice(context: Context, live: LiveLimit): String {
        val now = if (live.inForce > 0) "${live.inForce} FPS" else WowI18n.pick(context, "none", "nincs")
        return WowI18n.pick(context, "This limit applies from the next launch (now: $now)", "Ez a korlát a következő indításkor lép érvénybe (most: $now)")
    }

    // Hybrid GPL/async exists from Vegas r14 onwards; match the naming scheme so newer bundles qualify. The experimental r60+ line is the
    // same Vegas layer on upstream DXVK 3.1.1 ("Vegas-3.1.1-..."), so the base version is part of the pattern, not fixed to 2.7.3.
    private val hybridVegas = Regex("""dxvk-Vegas-(?:2\.7\.3|3\.1\.1)-wow-arm64-hybrid-gamenative-r(\d+)-(\d+)""")

    fun supportsHybrid(container: Container): Boolean = container.dxWrapper == WowBundledComponents.DXVK_ID ||
        hybridVegas.matchEntire(container.dxWrapper)?.destructured
            ?.let { (build, code) -> build == code && build.toInt() >= 14 } ?: false

    fun presentMode(context: Context, container: Container): String =
        WowLaunchEnvironment.resolvePresentMode(container.envVars, selected(context, "present"),
            com.winlator.core.KeyValueSet(container.graphicsDriverConfig).get("presentMode"), container.rendererPresentMode)
    @JvmStatic
    fun apply(context: Context, container: Container, env: EnvVars): String = applyTo(context, container, env, launch = true)

    /** [launch] false: only describes the next launch, so the running game's [apiLimitAtLaunch] stays as it was. */
    private fun applyTo(context: Context, container: Container, env: EnvVars, launch: Boolean): String {
        val dx12 = WowVkd3dComponents.api(container) == WowVkd3dComponents.DX12
        val choices = options.keys.associateWith { selected(context, it) }.toMutableMap()
        if (choices["async"] == "2" && !supportsHybrid(container)) {
            // The default silently stays on the saved mode with an older DXVK; an explicit Hybrid choice must fail loudly.
            if (asyncIsDefault(context)) choices["async"] = ""
            else if (!dx12 && EnvVars(container.envVars).get("DXVK_ASYNC") in setOf("", "1"))
                error("Hybrid needs the bundled DXVK. Apply it on the Components tab or choose Saved / default.")
        }
        applyChoices(env, EnvVars(container.envVars), dx12, choices)
        applyAdpfTarget(env, EnvVars(container.envVars), target(container))
        applyFrameLatency(env, dx12, WowLaunchPolicy.enabled(context, LOW_LATENCY))
        applyVegasBindSkip(env, EnvVars(container.envVars), WowLaunchPolicy.enabled(context, VEGAS_BINDSKIP))
        applyHybridGpl(env, EnvVars(container.envVars), WowLaunchPolicy.enabled(context, HYBRID_GPL))
        applyCpuIdentity(env, EnvVars(container.envVars), selected(context, CPU_PROFILE), android.os.Build.SOC_MODEL.orEmpty())
        applyCpuClasses(env, EnvVars(container.envVars), WowLaunchPolicy.enabled(context, CPU_CLASSES))
        applyGpuIdentity(env, EnvVars(container.envVars), selected(context, GPU_PROFILE), kgslGpuModel())
        // These probes are launch-local and share the existing diagnostic switch. OFF removes
        // inherited paths too, while the saved container environment remains untouched.
        applyDiagnosticStats(env, EnvVars(container.envVars), WowDiagnostics.enabled(context))
        val appOwns = appPacing(context, container)
        val alias = readsDxvkFrameRateAlias(container)
        val notes = if (appOwns) applyAppPacing(env, target(container)) else applyLimiter(env, dx12, target(container), alias)
        if (launch) apiLimitAtLaunch = if (appOwns) 0 else apiLimitIn(env, dx12, alias)
        return notes
    }

    /** The GPU mode ("performance", "balanced", "floorNN") and the frame limit ("off" or FPS) the next launch requests. */
    data class LaunchPreview(val gpuMode: String, val fps: String)

    /**
     * [LaunchPreview] from the same rules as [apply] (saved variables win, a typed DXVK/VKD3D limiter owns pacing), worked out
     * on a scratch copy of the saved environment: nothing is written (Codex UI16-03).
     */
    fun preview(context: Context, container: Container): LaunchPreview {
        val saved = EnvVars(container.envVars)
        val dx12 = runCatching { WowVkd3dComponents.api(container) == WowVkd3dComponents.DX12 }.getOrDefault(false)
        val gpu = EnvVars(container.envVars).also { applyChoices(it, saved, dx12, mapOf("gpu" to selected(context, "gpu"))) }
        val floor = gpu.get("TU_KGSL_GPU_FLOOR").trim().toIntOrNull()?.takeIf { it in 1..100 }
        val gpuMode = when {
            gpu.get("TU_KGSL_PWR_MAX").trim() == "1" -> "performance"
            floor != null -> "floor$floor"
            else -> "balanced"
        }
        val target = target(container)
        val limit = if (appPacing(context, container)) target else {
            val alias = readsDxvkFrameRateAlias(container)
            EnvVars(container.envVars).let { applyLimiter(it, dx12, target, alias); apiLimitIn(it, dx12, alias) }
        }
        return LaunchPreview(gpuMode, if (limit > 0) "$limit" else "off")
    }

    const val STATS_FILE = "/storage/emulated/0/Download/turnrip-stats.txt"
    const val REQ_STATS_FILE = "/storage/emulated/0/Download/wine-reqstats.txt"

    fun applyDiagnosticStats(env: EnvVars, saved: EnvVars, enabled: Boolean) {
        for ((key, path) in listOf("WINE_REQ_STATS" to REQ_STATS_FILE, "TU_STATS_FILE" to STATS_FILE)) {
            if (!enabled) env.remove(key)
            else env.put(key, if (saved.has(key)) saved.get(key) else path)
        }
    }

    /** TurnRIP 1.1.2+: CPU frame-time target for the driver's ADPF session; 60 when unlimited. Saved values win. */
    fun applyAdpfTarget(env: EnvVars, saved: EnvVars, target: Int) {
        if (!saved.has("TU_ADPF_TARGET_FPS")) env.put("TU_ADPF_TARGET_FPS", if (target > 0) target else 60)
    }

    fun description(context: Context, container: Container): String =
        runCatching { applyTo(context, container, EnvVars(container.envVars), launch = false) }.getOrElse { it.message ?: "Invalid launch setting" }
}
