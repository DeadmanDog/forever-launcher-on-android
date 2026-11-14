package app.gamenative.ui.screen.wow

import android.content.Context
import app.gamenative.display.WowDisplayBackend
import com.winlator.container.Container
import com.winlator.core.envvars.EnvVars

/** Launch-local API settings, including the game-side ceiling selected for Gamescope. */
internal object WowRuntimeTuning {
    private val options = mapOf(
        "present" to ("MESA_VK_WSI_PRESENT_MODE" to setOf("fifo", "mailbox")),
        "async" to ("DXVK_ASYNC" to setOf("0", "1", "2")),
        // TurnRIP 1.0.6+: 1 keeps the KGSL PWR_MAX constraint (Performance: fixed maximum clock, ~1200 MHz on the A840),
        // 0 = Balanced (default): the TurnRIP wait boost raises the clock only as far as the game needs.
        // 1.1.5: the Regulated floors (f50/f65/f80) are gone; an old saved fNN runs as Balanced (see selected).
        "gpu" to ("TU_KGSL_PWR_MAX" to setOf("0", "1")),
        // Proton r10+: 1 hands a signaled object directly to one waiter instead of waking all (default 0).
        "handoff" to ("NTSYNC_WAKE_HANDOFF" to setOf("0", "1")),
    )
    /** 1.0.1 code1046+: Hybrid is the default DX11 shader mode (the user's measured best, with Hybrid keeping GPL on); GPL from v1.3.4. A DXVK without Hybrid falls back to the saved mode. */
    fun asyncIsDefault(context: Context): Boolean = !context.getSharedPreferences(WowLaunchPolicy.PREFS, 0).contains("async")

    /**
     * Until the player picks a present mode the default applies: FIFO since v1.3.39 (Mailbox from v1.0). With the vsync-locked
     * pacer FIFO measured smooth (no dropped frame); Mailbox dropped one every second or two (1.3.28 diagnostics).
     */
    fun presentIsDefault(context: Context): Boolean = !context.getSharedPreferences(WowLaunchPolicy.PREFS, 0).contains("present")

    fun selected(context: Context, key: String): String =
        if (key == "async" && asyncIsDefault(context)) "2"
        else if (key == "present" && presentIsDefault(context)) "fifo"
        else context.getSharedPreferences(WowLaunchPolicy.PREFS, 0).getString(key, "").orEmpty()
            // v0.1.73+: Balanced is the GPU default, so an explicit "0" is the same choice as "". 1.1.5: so is an old Regulated "fNN"
            // (or anything else but Performance), which would otherwise leave PWR_MAX to TurnRIP's default: the maximum clock.
            .let { if (key == "gpu" && it != "1") "" else it }
            // 1.3.2: two limiters only. An old "timer" or "phase" (or the plain vsync-locked "") is the app's vsync-aligned pacer.
            .let { if (key == "pacing" && it != "api") "" else it }
            // The old "container setting" DX11 choice ("") left the container's DXVK_ASYNC=1, i.e. Async: shown and applied as Async.
            .let { if (key == "async" && it == "") "1" else it }
            // One FIFO entry: an old explicit "Default (FIFO)" ("") reads and applies as FIFO; handoff "" was the same as Off (0).
            .let { if (key == "present" && it == "") "fifo" else if (key == "handoff" && it == "") "0" else it }

    /** Choices that map to app behaviour instead of a single env variable. */
    private val appChoices = mapOf("pacing" to setOf("api"), "fifolatency" to setOf("low", "lowest"),
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
        !WowDisplayBackend.isGamescope(container) && selected(context, "pacing") != "api" &&
            !hasUserApiLimiter(EnvVars(container.envVars), readsDxvkFrameRateAlias(container))

    /**
     * App pacing releases buffers on the display's vsync (v0.1.114+ default). The free-running 1/limit "timer" of
     * v0.1.63-v0.1.113 drifted against the panel's real rate and is gone since 1.3.2.
     */
    fun vsyncPacing(context: Context): Boolean = true

    /**
     * Vsync pacing whose releases follow the measured display phase (see PresentPhase). Experimental from v1.3.33, the release
     * default since 2026-11-07 (the user's measured best) and since 1.3.2 the app pacer's only mode.
     */
    fun phaseAlign(context: Context): Boolean = selected(context, "pacing") != "api"

    /**
     * FIFO latency drain (v1.3.39, three levels since v1.3.41): "" = smooth, the queue as it fills (about 60 ms at 60 Hz, never a
     * skip); "low" = one step after a hitch, then stop; "lowest" = also tries one step further (an extra hitch if it does not hold).
     */
    fun fifoDrainMode(context: Context): Int = when (selected(context, "fifolatency")) { "low" -> 1; "lowest" -> 2; else -> 0 }

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

    /** Gamescope's refresh divisor can round up. Give the renderer the requested ceiling as well. */
    private fun applyApiPacing(context: Context, container: Container, env: EnvVars, dx12: Boolean, target: Int, alias: Boolean): String {
        if (gamescopeOwnsFifoPacing(context, container)) {
            // Gamescope's vblank limiter (sent by GameNativeDrm.setFpsLimit) holds FIFO: no game-side timer on top.
            val explicit = applyLimiter(env, dx12, 0, alias)
            return if (apiLimitIn(env, dx12, alias) > 0) explicit else "Gamescope FIFO: vblank limiter ($target FPS)"
        }
        if (WowDisplayBackend.isGamescope(container) && target > 0) {
            if (dx12) {
                val current = env.get("VKD3D_FRAME_RATE").toDoubleOrNull()
                if (current == null || current !in 1.0..target.toDouble()) env.put("VKD3D_FRAME_RATE", target)
            } else {
                val config = env.get("DXVK_CONFIG")
                val configured = configFrameRate(config, alias)
                val current = if (configured != null) configured.toIntOrNull()?.toDouble()
                    else env.get("DXVK_FRAME_RATE").toDoubleOrNull()
                if (current == null || current !in 1.0..target.toDouble()) {
                    val key = if (alias) "dxvk.maxFrameRate" else "dxgi.maxFrameRate"
                    val prefix = config.trimEnd().trimEnd(';')
                    env.put("DXVK_CONFIG", (if (prefix.isEmpty()) "" else "$prefix;") + "$key=$target")
                }
            }
        }
        return applyLimiter(env, dx12, target, alias)
    }

    /** WowLaunchPolicy switch: DX11 frame latency 1 (on by default since v0.1.113). */
    const val LOW_LATENCY = "lowlatency"
    /** 1.1.7: DX12's own switch (VKD3D swapchain latency 1); until then LOW_LATENCY covered both APIs, so it starts from its value. */
    const val LOW_LATENCY_DX12 = "lowlatency12"

    /**
     * What the game is told about the processor and GPU (Proton r22+ / Vegas r48+): since 1.3.6 always this phone's own SoC
     * and Adreno (user; the laptop/off choices are gone). Typed values in Environment Variables still win.
     */
    const val CPU_VENDOR = "Qualcomm Technologies Inc"

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
    fun applyCpuIdentity(env: EnvVars, saved: EnvVars, socModel: String) {
        val values = mapOf("WINE_CPU_VENDOR" to CPU_VENDOR,
            "WINE_CPU_NAME" to (realCpuName(socModel) ?: "Snapdragon(R) $socModel CPU"), "WINE_CPU_PEAK_MHZ" to "1")
        for ((key, value) in values) if (!saved.has(key)) env.put(key, value)
    }

    /** Saved DXVK_VEGAS_* (Environment Variables) always win: the Qualcomm vendor with this Adreno's own name and id. */
    fun applyGpuIdentity(env: EnvVars, saved: EnvVars, gpuModel: String?) {
        val values = buildMap {
            put("DXVK_VEGAS_PERSONA", "qualcomm")
            realGpuName(gpuModel)?.let { put("DXVK_VEGAS_GPU_NAME", it) }
            realGpuDeviceId(gpuModel)?.let { put("DXVK_VEGAS_GPU_DEVICE", it) }
        }
        for (key in listOf("DXVK_VEGAS_PERSONA", "DXVK_VEGAS_GPU_NAME", "DXVK_VEGAS_GPU_DEVICE")) {
            if (saved.has(key)) continue
            val value = values[key]
            if (value != null) env.put(key, value) else env.remove(key)
        }
    }

    /**
     * WowLaunchPolicy switch (on by default), XServer + DX12 only. The bundled Mesa wrapper's X11 WSI waits for a frame's GPU work
     * before handing the buffer to the X server only in mailbox mode; in FIFO it relies on Linux implicit sync, which Android buffers
     * do not have, so the compositor could read a buffer the GPU was still drawing and show its previous frame (the loading bar
     * jumping back under DX12). The wrapper's own MESA_VK_WSI_DEBUG=forcesync makes vkQueuePresentKHR wait for that frame's fence
     * (wsi_common.c), in FIFO, with no other change (1.1.0 switched the game to mailbox instead). "sw" already waits; a saved
     * MESA_VK_WSI_DEBUG wins.
     */
    const val GAME_GPU_WAIT = "gpuwait"

    fun applyGameGpuWait(env: EnvVars, saved: EnvVars, enabled: Boolean) {
        if (!enabled || saved.has("MESA_VK_WSI_DEBUG")) return
        val flags = env.get("MESA_VK_WSI_DEBUG").split(',').map(String::trim).filter(String::isNotEmpty)
        if ("sw" in flags || "forcesync" in flags) return
        env.put("MESA_VK_WSI_DEBUG", (flags + "forcesync").joinToString(","))
    }

    /** WowLaunchPolicy switch: report the highest-clocked cores as performance cores (Proton r21+, on by default since 2026-11-07). */
    const val CPU_CLASSES = "cpuclasses"

    /**
     * WowLaunchPolicy switch (on by default): Proton r32's event reuse. WoW's Net Queue threads create and close thousands of
     * unnamed events a second after login; reused events skip the wineserver, whose queue otherwise stalls the main thread.
     * OFF sets WINE_EVENT_POOL=0 (exactly the r31 path); a saved WINE_EVENT_POOL wins.
     */
    const val EVENT_POOL = "eventpool"

    /**
     * TurnRIP 1.1.22: under Gamescope the present thread (gn-present) waits for the compositor, so it does not steer the GPU wait
     * boost (1.1.21). DX12 needs it to: its VKD3D waits never steer, and on 1.0.3 DX12 under Gamescope sat at 506 MHz / 39.7 FPS.
     * DX11 and XServer stay without it. A saved TU_KGSL_WAITBOOST_PRESENT_STEERS wins.
     */
    fun applyPresentSteer(env: EnvVars, saved: EnvVars, gamescopeDx12: Boolean) {
        if (saved.has("TU_KGSL_WAITBOOST_PRESENT_STEERS")) return
        if (gamescopeDx12) env.put("TU_KGSL_WAITBOOST_PRESENT_STEERS", "1") else env.remove("TU_KGSL_WAITBOOST_PRESENT_STEERS")
    }

    /**
     * TurnRIP 1.1.24, XServer + DX12 with VKD3D swapchain latency 1: the game cannot prepare a frame ahead, so a VKD3D thread's GPU
     * wait is on every frame's critical path; as a "helper" it never steered the wait boost and the GPU stayed at 500 MHz, 46 FPS
     * (1.1.3 diag, steer_ms "none"). TU_KGSL_WAITBOOST_VKD3D_STEERS=1 lets those waits steer the adaptive boost (no fixed maximum).
     * Only for latency 1; a saved value wins.
     */
    fun applyVkd3dSteer(env: EnvVars, saved: EnvVars, xserverDx12: Boolean) {
        val on = xserverDx12 && env.get("VKD3D_SWAPCHAIN_LATENCY_FRAMES").trim() == "1"
        // TurnRIP 1.1.25: with every frame's GPU wait on the critical path the wait share is high at any clock, so the share
        // controller held ~1200 MHz (1.1.4 diag: boost_level 100 in ~90% of samples, 6.9 W vs 5.2 W DX11). FRAME_STEER=1 steers
        // by frame time instead: +15 at once when a frame comes within 1.5 ms of the 60 FPS period, -5 per 250 ms of 3 ms slack.
        for (name in listOf("TU_KGSL_WAITBOOST_VKD3D_STEERS", "TU_KGSL_WAITBOOST_FRAME_STEER")) {
            if (saved.has(name)) continue
            if (on) env.put(name, "1") else env.remove(name)
        }
    }

    /**
     * VKD3D r11, DX12 only: VKD3D_REPORT_DISCRETE=1 answers the game's D3D12 architecture query with UMA = false. WoW's DX12
     * renderer caps the render scale at 100% on a UMA GPU (1.1.6 diag: DXGI says Discrete, VKD3D "UMA-like topology"); DX11 has no
     * such query. Always on under DX12 since 1.3.6 (user; no setting); a saved value wins.
     */
    fun applyReportDiscrete(env: EnvVars, saved: EnvVars, dx12: Boolean) {
        if (saved.has("VKD3D_REPORT_DISCRETE")) return
        if (dx12) env.put("VKD3D_REPORT_DISCRETE", "1") else env.remove("VKD3D_REPORT_DISCRETE")
    }

    fun applyEventPool(env: EnvVars, saved: EnvVars, enabled: Boolean) {
        if (saved.has("WINE_EVENT_POOL")) return
        if (enabled) env.remove("WINE_EVENT_POOL") else env.put("WINE_EVENT_POOL", "0")
    }

    /** Saved WINE_CPU_EFFICIENCY wins; OFF removes an inherited value. */
    fun applyCpuClasses(env: EnvVars, saved: EnvVars, enabled: Boolean) {
        if (saved.has("WINE_CPU_EFFICIENCY")) return
        if (enabled) env.put("WINE_CPU_EFFICIENCY", "1") else env.remove("WINE_CPU_EFFICIENCY")
    }

    /** The Adreno model text from sysfs ("Adreno (TM) 840"), or null. */
    fun kgslGpuModel(): String? = listOf("/sys/class/kgsl/kgsl-3d0/gpu_model", "/sys/class/kgsl/kgsl-3d0/devfreq/device/gpu_model")
        .firstNotNullOfOrNull { runCatching { java.io.File(it).readText().trim() }.getOrNull()?.takeIf { text -> text.isNotEmpty() } }

    /** WowLaunchPolicy switch: Vegas r49+ real pipeline bind-skip (on by default since 2026-11-07; not in the Quick Menu). */
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
        if (!enabled) return
        if (dx12) {
            // DX12 the same way (1.1.2): VKD3D's swapchain otherwise lets the game queue the driver default (up to three) frames
            // ahead. A saved value wins. 1.1.7: the separate "DX12 maximum latency" choice is gone; its own switch covers it.
            if (!env.has("VKD3D_SWAPCHAIN_LATENCY_FRAMES")) env.put("VKD3D_SWAPCHAIN_LATENCY_FRAMES", "1")
            // 1.3.6 (user): the separate swapchain image count is gone; low latency asks for 2 images, off = VKD3D's default.
            if (!env.has("VKD3D_SWAPCHAIN_IMAGES")) env.put("VKD3D_SWAPCHAIN_IMAGES", "2")
            return
        }
        if (configFrameLatency(env.get("DXVK_CONFIG")) != null) return
        val current = env.get("DXVK_CONFIG").trimEnd().trimEnd(';')
        env.put("DXVK_CONFIG", (if (current.isEmpty()) "" else "$current;") + "dxgi.maxFrameLatency=1")
    }

    fun applyChoices(env: EnvVars, saved: EnvVars, dx12: Boolean, choices: Map<String, String>) {
        for ((key, pair) in options) {
            // DXVK pipeline mode is DX11-only and VKD3D swapchain controls are DX12-only;
            // the game present mode and GPU power mode apply to both APIs.
            if (key == "async" && dx12) continue
            val value = choices[key].orEmpty()
            // The original managed DXVK_ASYNC=1 remains the creation default.
            // Choosing another mode explicitly supersedes that exact default.
            val managedAsync = key == "async" && saved.get(pair.first) == "1"
            if (key == "gpu") {
                // Performance ("1") = PWR_MAX; anything else is Balanced (PWR_MAX off; TurnRIP wait boost and ADPF regulate).
                // Never left unset: TurnRIP's own default is PWR_MAX on. Saved variables (also a typed TU_KGSL_GPU_FLOOR) still win.
                if (!saved.has(pair.first)) env.put(pair.first, if (value == "1") "1" else "0")
                continue
            }
            if (value in pair.second && (!saved.has(pair.first) || managedAsync)) env.put(pair.first, value)
        }
    }

    fun effectiveLimiter(context: Context, container: Container, env: EnvVars): String {
        val saved = EnvVars(container.envVars)
        val dx12 = WowVkd3dComponents.api(container) == WowVkd3dComponents.DX12
        val alias = readsDxvkFrameRateAlias(container)
        val user = if (dx12) saved.has("VKD3D_FRAME_RATE") else configFrameRate(saved.get("DXVK_CONFIG"), alias) != null || saved.has("DXVK_FRAME_RATE")
        val value = if (dx12) env.get("VKD3D_FRAME_RATE") else configFrameRate(env.get("DXVK_CONFIG"), alias) ?: env.get("DXVK_FRAME_RATE")
        if (gamescopeOwnsFifoPacing(context, container)) {
            val target = target(container).takeIf { it > 0 }?.toString() ?: "OFF"
            return "Gamescope FIFO: $target (vblank limiter); ${if (dx12) "VKD3D" else "DXVK"}: ${value.ifBlank { "OFF" }}"
        }
        val owner = when {
            WowDisplayBackend.isGamescope(container) && target(container) > 0 -> "Gamescope launch cap"
            user -> "user"
            else -> "container"
        }
        return "${if (dx12) "VKD3D" else "DXVK"}: ${value.ifBlank { "OFF" }} ($owner)"
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


    /**
     * Gamescope: whether a limit change made in the running game waits for the next launch. Gamescope's limiter takes a lower limit
     * at once; only a higher one, or off, stays under the game-side ceiling set at launch.
     */
    fun gamescopeLimitWaitsForRestart(limit: Int, apiLimit: Int): Boolean = apiLimit > 0 && (limit <= 0 || limit > apiLimit)

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
        if (WowDisplayBackend.isGamescope(container))
            WowLaunchEnvironment.resolvePresentMode(container.envVars, selected(context, "present"), "", "fifo")
        else WowLaunchEnvironment.resolvePresentMode(container.envVars, selected(context, "present"),
            com.winlator.core.KeyValueSet(container.graphicsDriverConfig).get("presentMode"), container.rendererPresentMode)

    /**
     * FIFO under Gamescope: Gamescope's own limiter (debug_set_fps_limit) holds the cap, one commit per limited vblank, the
     * vsync-locked pacing; no game-side DXVK/VKD3D timer then (gamenative-drm icd_shim gamescope_route_pacing: "any FIFO
     * declaration: no gate; gamescope's limiter holds FIFO commits ... and is the cap", hence its "gate=off"). 1044 .. 1047 also
     * dropped Gamescope's limiter (77.6 FPS for 60 on 1.0.2); 1.0.3 stacked the DXVK timer on it (judder). Mailbox/immediate keep the
     * game-side ceiling.
     */
    internal fun gamescopeOwnsFifoPacing(context: Context, container: Container): Boolean =
        WowDisplayBackend.isGamescope(container) && presentMode(context, container).equals("fifo", true) &&
            !EnvVars(container.envVars).has("GN_VKSHIM_NO_PROXY")
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
                error("Hybrid needs the bundled DXVK. Apply it on the Components tab or choose GPL or Async.")
        }
        applyChoices(env, EnvVars(container.envVars), dx12, choices)
        applyFrameLatency(env, dx12, WowLaunchPolicy.enabled(context, if (dx12) LOW_LATENCY_DX12 else LOW_LATENCY))
        applyVkd3dSteer(env, EnvVars(container.envVars), dx12 && !WowDisplayBackend.isGamescope(container))
        // 1.3.6 (user): always report a dedicated GPU under DX12 (no setting); a typed VKD3D_REPORT_DISCRETE still wins.
        applyReportDiscrete(env, EnvVars(container.envVars), dx12)
        applyVegasBindSkip(env, EnvVars(container.envVars), WowLaunchPolicy.enabled(context, VEGAS_BINDSKIP))
        applyHybridGpl(env, EnvVars(container.envVars), WowLaunchPolicy.enabled(context, HYBRID_GPL))
        // 1.3.6 (user): the game always sees this phone's own Snapdragon and Adreno (the choices are gone; typed values still win).
        applyCpuIdentity(env, EnvVars(container.envVars), android.os.Build.SOC_MODEL.orEmpty())
        applyCpuClasses(env, EnvVars(container.envVars), WowLaunchPolicy.enabled(context, CPU_CLASSES))
        applyEventPool(env, EnvVars(container.envVars), WowLaunchPolicy.enabled(context, EVENT_POOL))
        applyPresentSteer(env, EnvVars(container.envVars), WowDisplayBackend.isGamescope(container) && dx12)
        applyGpuIdentity(env, EnvVars(container.envVars), kgslGpuModel())
        // These probes are launch-local and share the existing diagnostic switch. OFF removes
        // inherited paths too, while the saved container environment remains untouched.
        applyDiagnosticStats(env, EnvVars(container.envVars), WowDiagnostics.enabled(context))
        val appOwns = appPacing(context, container)
        val alias = readsDxvkFrameRateAlias(container)
        if (WowDisplayBackend.isGamescope(container)) env.put("MESA_VK_WSI_PRESENT_MODE", presentMode(context, container))
        val notes = if (appOwns) applyAppPacing(env, target(container))
            else applyApiPacing(context, container, env, dx12, target(container), alias)
        if (launch) apiLimitAtLaunch = if (appOwns) 0 else apiLimitIn(env, dx12, alias)
        // 1.1.9: the driver's frame period is the limit the game really runs at. A DXVK/VKD3D limiter (typed into Environment
        // Variables, or the "api" pacing) owns it then, not the app target: at a typed 30 FPS TurnRIP was told 60 and took every
        // frame for a late one.
        val apiLimit = if (appOwns || gamescopeOwnsFifoPacing(context, container)) 0 else apiLimitIn(env, dx12, alias)
        // 1.2.3: never above the panel rate (a 144 chip on the 60 Hz panel told ADPF and TurnRIP 6.9 ms frames).
        val hz = panelHz(context)
        val running = driverFps(if (apiLimit > 0) apiLimit else target(container), hz)
        applyAdpfTarget(env, EnvVars(container.envVars), running)
        // TurnRIP 1.1.28+: the limit changed in the running game reaches the driver through this file (see writeFpsFile).
        if (!EnvVars(container.envVars).has("TU_FPS_FILE")) {
            val file = fpsFile(context)
            env.put("TU_FPS_FILE", file.absolutePath)
            if (launch) writeFpsFile(file, running, hz)
        }
        return notes
    }

    /** The GPU mode ("performance", "balanced", or "floorNN" for a TU_KGSL_GPU_FLOOR typed into Environment Variables) and the frame limit ("off" or FPS) the next launch requests. */
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
            val api = EnvVars(container.envVars).let { applyApiPacing(context, container, it, dx12, target, alias); apiLimitIn(it, dx12, alias) }
            if (gamescopeOwnsFifoPacing(context, container) && target > 0 && (api == 0 || api > target)) target else api
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

    /** TurnRIP 1.1.28 reads the frame limit in force from this file at most once a second ("0" = no limit = 60). */
    fun fpsFile(context: Context) = java.io.File(context.filesDir, "wow-fps-limit")

    /**
     * Written at launch and on every limit change in the running game; atomic, so the driver never reads half a number. TurnRIP
     * 1.1.29 also takes the panel refresh ([hz], 0 = unknown) as a second number: with a limit that is no divisor of it (50 on
     * 60 Hz) frames come on the vsync grid, and the long ones are not late.
     */
    fun writeFpsFile(file: java.io.File, fps: Int, hz: Int = 0) {
        val text = "${fps.coerceIn(0, 1000)}" + (if (hz in 10..1000) " $hz" else "") + "\n"
        runCatching {
            file.parentFile?.mkdirs()
            val temp = java.io.File(file.parentFile, file.name + ".tmp")
            temp.writeText(text)
            if (!temp.renameTo(file)) { file.writeText(text); temp.delete() }
        }
    }

    /** The default display's current refresh rate in whole Hz, 0 when unknown. */
    fun panelHz(context: Context): Int = runCatching {
        val display = context.getSystemService(android.hardware.display.DisplayManager::class.java)
            ?.getDisplay(android.view.Display.DEFAULT_DISPLAY)
        ((display?.refreshRate ?: 0f) + 0.5f).toInt()
    }.getOrDefault(0)

    /** The frame rate the driver is told: the limit, but never above the panel rate (0 = no limit stays 0). */
    fun driverFps(limit: Int, hz: Int): Int = if (hz > 0 && limit > hz) hz else limit

    /** TurnRIP 1.1.2+: CPU frame-time target for the driver's ADPF session; 60 when unlimited. Saved values win. */
    fun applyAdpfTarget(env: EnvVars, saved: EnvVars, target: Int) {
        if (!saved.has("TU_ADPF_TARGET_FPS")) env.put("TU_ADPF_TARGET_FPS", if (target > 0) target else 60)
    }

    fun description(context: Context, container: Container): String =
        runCatching { applyTo(context, container, EnvVars(container.envVars), launch = false) }.getOrElse { it.message ?: "Invalid launch setting" }
}
