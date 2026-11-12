package app.gamenative.ui.screen.wow

import android.content.Context
import com.winlator.container.Container
import com.winlator.core.envvars.EnvVars
import app.gamenative.utils.AppLogging
import java.io.File
import java.util.UUID
import com.winlator.core.OneShotTasks
import android.os.SystemClock

/** Diagnostic preference lives outside container/runtime config and defaults off. */
internal object WowDiagnostics {
    const val MAX_SESSIONS = 5
    fun retention(context: Context): Int = context.getSharedPreferences(PREFS, 0).getInt("retention", MAX_SESSIONS).coerceIn(1, 20)
    fun saveRetention(context: Context, count: Int, active: () -> Boolean) {
        require(count in 1..20)
        check(!active()) { "Stop the game before changing diagnostic retention." }
        check(context.getSharedPreferences(PREFS, 0).edit().putInt("retention", count).commit())
    }
    private const val PREFS = "wow_diagnostics"
    @JvmStatic fun enabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("enabled", false)

    @Volatile private var sampler: DiagnosticSampler? = null
    /** The game folder of the running session, for [copyGameLogs] at its end. */
    @Volatile private var gameRoot: File? = null
    @JvmStatic fun detailed(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("detailed", false)

    /** Audio diagnostics: Wine audio trace, PulseAudio server log and the microphone bridge log. Only active with diagnostic logging on. */
    @JvmStatic fun audioDebug(context: Context): Boolean =
        enabled(context) && context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("audio_debug", false)

    fun audioDebugSaved(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("audio_debug", false)

    fun saveAudioDebug(context: Context, value: Boolean, active: () -> Boolean) {
        check(!active()) { "Stop the game before changing diagnostic logging." }
        check(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("audio_debug", value).commit())
    }

    /**
     * GPU profile (TurnRIP 1.1.30+): the driver records what the game asks of it (render passes in GMEM or sysmem, binning,
     * loads/stores, resolves, blits, clears, compute) for 2 frames every [GPU_PROFILE_SAMPLE_MS] into [GPU_TRACE_FILE] of the
     * session. Only active with diagnostic logging on.
     */
    @JvmStatic fun gpuProfile(context: Context): Boolean =
        enabled(context) && context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("gpu_profile", false)

    fun gpuProfileSaved(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("gpu_profile", false)

    fun saveGpuProfile(context: Context, value: Boolean, active: () -> Boolean) {
        check(!active()) { "Stop the game before changing diagnostic logging." }
        check(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("gpu_profile", value).commit())
    }

    const val GPU_TRACE_FILE = "turnrip-gpu-trace.csv"
    /** One 2-frame sample every 10 s keeps a 15-minute session well under the export's per-file cap. */
    const val GPU_PROFILE_SAMPLE_MS = 10_000

    const val SHADER_STATS_DIR = "turnrip-shaders"

    /**
     * TurnRIP 1.1.35: with diagnostic logging the driver writes each compute shader once (statistics + disassembly) into the
     * session, for the DX11/DX12 compute comparison. A value typed into Environment Variables wins.
     */
    fun applyShaderStats(env: EnvVars, session: File) {
        if (env.has("TU_SHADER_STATS_DIR")) return
        val dir = File(session, SHADER_STATS_DIR)
        if (dir.isDirectory || dir.mkdirs()) env.put("TU_SHADER_STATS_DIR", dir.absolutePath)
    }

    /** Values typed into Environment Variables win; with the profile off nothing is added (the driver then traces nothing). */
    fun applyGpuProfile(env: EnvVars, session: File, on: Boolean) {
        if (!on) return
        if (!env.has("MESA_GPU_TRACES")) env.put("MESA_GPU_TRACES", "print_csv")
        if (!env.has("MESA_GPU_TRACEFILE")) env.put("MESA_GPU_TRACEFILE", File(session, GPU_TRACE_FILE).absolutePath)
        if (!env.has("TU_PROFILE_SAMPLE_MS")) env.put("TU_PROFILE_SAMPLE_MS", GPU_PROFILE_SAMPLE_MS.toString())
    }

    fun saveDetailed(context: Context, value: Boolean, active: () -> Boolean) {
        check(!active()) { "Stop the game before changing diagnostic logging." }
        check(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("detailed",value).commit())
        configureApp(context)
    }

    @JvmStatic fun configureApp(context: Context) {
        val on=enabled(context)
        if(!on) synchronized(this) { sampler?.close();sampler=null }
        AppLogging.setNativeEnabled(on,detailed(context))
        app.gamenative.CrashHandler.configure(context,on)
    }
    @JvmStatic fun initialize(context: Context) {
        configureApp(context)
        if(enabled(context)) OneShotTasks.execute { try { DiagnosticSystemCapture.capture(context,directory(context)) } catch (_:Exception) { } }
    }

    @JvmStatic fun rendererFrame() {
        val active=sampler ?: return
        active.frames.frame(SystemClock.elapsedRealtimeNanos())
    }
    /** A frame the Gamescope session produced (the game's own acquire, from the bridge's producer reports). */
    @JvmStatic fun gamescopeFrame() {
        val active=sampler ?: return
        active.frames.frame(SystemClock.elapsedRealtimeNanos(), DiagnosticFrames.Source.GAMESCOPE_PRODUCED)
    }
    @JvmStatic fun presentFrame() {
        val active=sampler ?: return
        active.frames.frame(SystemClock.elapsedRealtimeNanos(), DiagnosticFrames.Source.X_PRESENT_REQUEST)
    }
    @JvmStatic fun presentFrame(window: com.winlator.xserver.Window, pixmap: com.winlator.xserver.Pixmap) {
        val active = sampler ?: return
        active.frames.frame(SystemClock.elapsedRealtimeNanos(), DiagnosticFrames.Source.X_PRESENT_REQUEST, window.id)
        // The window name/class are decoded only when a new geometry line is written, not on every Present (Codex A14-HUD-01:
        // two windows presenting in turn evicted a one-entry label cache on every frame).
        active.display.presented(window.id, pixmap.drawable.width.toInt(), pixmap.drawable.height.toInt()) { "${window.name.orEmpty()} / ${window.className.orEmpty()}" }
    }

    @JvmStatic fun surfaceGeometry(context: Context, width: Int, height: Int, guestWidth: Int, guestHeight: Int) {
        if (!enabled(context)) return
        val bytes = "${System.currentTimeMillis()} android_surface=${width}x$height xserver=${guestWidth}x$guestHeight\n".toByteArray()
        OneShotTasks.execute {
            try {
                val root = directory(context); root.mkdirs()
                WowDisplayGeometry.append(File(root, "display-surface.log"), bytes)
            } catch (_: Exception) { }
        }
    }

    @JvmStatic @Synchronized fun finish(session: File?) {
        val active=sampler ?: return
        if (session != active.directory) return
        sampler=null
        active.close()
        runCatching { copyGameLogs(gameRoot, active.directory) }
    }

    /** Config.wtf keys that describe graphics (no account, character or realm names). */
    private val graphicsKey = Regex("^(gx.*|.*render.*|resample.*|graphics.*|raidgraphics.*|msaa.*|.*shadow.*|.*sharpen.*|ffx.*|vsync.*|maxfps.*|lowlatency.*)$", RegexOption.IGNORE_CASE)

    /**
     * 1.1.6: WoW's own graphics log (Logs/gx.log: the adapter, video memory and feature checks it made) and the graphics lines of
     * Config.wtf go into the session folder at its end, so a game-side limit (DX12 render scale capped at 100%) can be read from
     * the diagnostics. At most the last 1 MiB of gx.log.
     */
    internal fun copyGameLogs(root: File?, session: File) {
        if (root == null || !session.isDirectory) return
        val flavor = File(root, WowClientDownloader.FLAVOR_DIR)
        listOf(File(flavor, "Logs/gx.log"), File(root, "Logs/gx.log")).firstOrNull { it.isFile }?.let { log ->
            java.io.RandomAccessFile(log, "r").use { raf ->
                val keep = minOf(raf.length(), 1L shl 20)
                raf.seek(raf.length() - keep)
                val bytes = ByteArray(keep.toInt()); raf.readFully(bytes)
                File(session, "wow-gx.log").writeBytes(bytes)
            }
        }
        val config = File(flavor, "WTF/Config.wtf")
        if (config.isFile) {
            val setting = Regex("^\\s*SET\\s+(\\S+)\\s.*$", RegexOption.IGNORE_CASE)
            val lines = config.readLines().filter { line -> setting.matchEntire(line)?.groupValues?.get(1)?.let(graphicsKey::matches) == true }
            File(session, "wow-config-graphics.txt").writeText(lines.joinToString("\n", postfix = "\n"))
        }
        File(flavor, "WTF/${WowGameConfig.RENDER_SCALE_MEMORY}").takeIf { it.isFile }?.copyTo(File(session, "wow-renderscale.txt"), true)
    }

    fun save(context: Context, value: Boolean, active: () -> Boolean) {
        check(!active()) { "Stop the game before changing diagnostic logging." }
        check(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("enabled", value).remove("lastError").commit()) {
            "Unable to save diagnostic logging setting."
        }
        configureApp(context)
    }

    fun lifecycle(context: Context, event: String, cause: Throwable? = null) {
        if(!enabled(context)) return
        try {
            val root=directory(context);root.mkdirs()
            val file=File(root,"lifecycle.log")
            synchronized(this) {
                val bytes=("${System.currentTimeMillis()} $event ${cause?.javaClass?.simpleName ?: ""} ${cause?.message?.take(1024) ?: ""}\n").toByteArray()
                // Small lifecycle-only history; keep it independent of noisy runtime streams.
                val previous=if(file.isFile) file.readBytes().takeLast(128*1024).toByteArray() else byteArrayOf()
                com.winlator.core.RuntimeLog(file,256L*1024).use { log -> log.append(previous,previous.size);log.append(bytes,bytes.size) }
            }
        } catch (_:Exception) { }
    }

    fun directory(context: Context) = File(context.filesDir, "diagnostics/wow")

    /**
     * The driver and Wine statistics go into this session's folder, so Share diagnostics carries them and a new session never
     * overwrites the previous one. Only the default Download paths are moved; a path saved by the user is kept as it is.
     */
    fun redirectDefaultStats(env: EnvVars, session: File) {
        for ((key, default, name) in listOf(Triple("TU_STATS_FILE", WowRuntimeTuning.STATS_FILE, "turnrip-stats.txt"),
                Triple("WINE_REQ_STATS", WowRuntimeTuning.REQ_STATS_FILE, "wine-reqstats.txt"))) {
            if (env.get(key) == default) env.put(key, File(session, name).absolutePath)
        }
    }

    /** Runs on launch worker. Logging failure must not stop a working game. */
    @JvmStatic fun prepare(context: Context, container: Container, env: EnvVars): File? {
        AppLogging.runtimeEnv(env, false)
        if (container.id != WowContainerProfile.CONTAINER_ID || !enabled(context)) return null
        return try {
            val session = newSession(directory(context), retention(context))
            gameRoot = runCatching { File(GamePath.load(context)) }.getOrNull()
            redirectDefaultStats(env, session)
            applyGpuProfile(env, session, gpuProfile(context))
            applyShaderStats(env, session)
            File(session, "stdout.log").createNewFile()
            File(session, "stderr.log").createNewFile()
            File(session, "session.txt").writeText(
                "Wine/DXVK diagnostic log; 2 MiB per file with one previous file per output stream.\n" +
                "Started wall_ms: ${System.currentTimeMillis()} elapsed_ms: ${SystemClock.elapsedRealtime()}\nDetailed: ${detailed(context)}\nAudio debug: ${audioDebug(context)}\nAndroid version: ${android.os.Build.VERSION.RELEASE}\nAPK: ${app.gamenative.BuildConfig.VERSION_NAME} (${app.gamenative.BuildConfig.VERSION_CODE})\nWine: ${container.wineVersion}\nDXVK: ${container.dxWrapper}\n" +
                "Display backend: ${container.getExtra(app.gamenative.display.WowDisplayBackend.KEY, app.gamenative.display.WowDisplayBackend.XSERVER)}\nGamescope payload: ${if (app.gamenative.display.WowDisplayBackend.isGamescope(container)) app.gamenative.display.WowGamescopePayload.ARCHIVE else "off"}\n" +
                "Driver: ${container.graphicsDriverVersion}\nRenderer: ${container.displayRenderer}\nVirtual display: ${container.screenSize}\n" +
                "Graphics API: ${WowVkd3dComponents.api(container)}\nVKD3D: ${container.getExtra(WowVkd3dComponents.PROFILE_KEY).ifBlank { "none" }}\n" +
                "Stats files: TU_STATS_FILE=${env.get("TU_STATS_FILE").ifBlank { "off" }} WINE_REQ_STATS=${env.get("WINE_REQ_STATS").ifBlank { "off" }}\n" +
                "Frame source: GL renderer callback or non-GL X Present request; see performance.csv frame_source. Not GPU timestamps.\n" +
                "Present mode: ${env.get("MESA_VK_WSI_PRESENT_MODE")} (game swapchain; the compositor: ${WowRuntimeTuning.presentMode(context, container)}), MESA_VK_WSI_DEBUG: ${env.get("MESA_VK_WSI_DEBUG").ifBlank { "none" }}\n" +
                "WINENTSYNC: ${env.get("WINENTSYNC")}\n" +
                "NTSYNC_WAKE_HANDOFF: ${env.get("NTSYNC_WAKE_HANDOFF").ifBlank { "0 (default)" }}\n" +
                "Low input latency: DX11 (frame latency 1) ${WowLaunchPolicy.enabled(context, WowRuntimeTuning.LOW_LATENCY)}, DX12 (swapchain latency 1) ${WowLaunchPolicy.enabled(context, WowRuntimeTuning.LOW_LATENCY_DX12)}\nScanout: ${WowLaunchPolicy.enabled(context, "scanout")}\nExec shim: libwowexec (own)\nDisplay priority: ${WowLaunchPolicy.enabled(context, "priority")}\nADPF compositor: ${WowLaunchPolicy.enabled(context, "adpf")}\n" +
                "TU_DEBUG: ${env.get("TU_DEBUG")}\n" +
                "Frame pacing: ${if (WowRuntimeTuning.gamescopeOwnsFifoPacing(context, container)) "Gamescope FIFO vblank limiter" else if (!WowRuntimeTuning.appPacing(context, container)) "DXVK/VKD3D API limiter" else if (WowRuntimeTuning.phaseAlign(context)) "app Present limiter (vsync-locked, phase-aligned)" else if (WowRuntimeTuning.vsyncPacing(context)) "app Present limiter (vsync-locked)" else "app Present limiter (timer)"}\n" +
                "Active limiter: ${WowRuntimeTuning.effectiveLimiter(context, container, env)}\n" +
                "FIFO latency: ${when (WowRuntimeTuning.fifoDrainMode(context)) { 1 -> "lower, one step (drain on)"; 2 -> "lowest, probing (drain on)"; else -> "smooth (drain off)" }}\n" +
                "Swapchain latency: ${env.get("VKD3D_SWAPCHAIN_LATENCY_FRAMES").ifBlank { "driver default" }}\nSwapchain images: ${env.get("VKD3D_SWAPCHAIN_IMAGES").ifBlank { "driver default" }}\nDXVK_ASYNC: ${env.get("DXVK_ASYNC")}\n" +
                "GPU power (TU_KGSL_PWR_MAX): ${env.get("TU_KGSL_PWR_MAX").ifBlank { "driver default" }}\n" +
                "GPU sysfs sources: ${app.gamenative.ui.widget.HardwareClockSampler.probe()}\n" +
                "GPU floor (TU_KGSL_GPU_FLOOR): ${env.get("TU_KGSL_GPU_FLOOR").ifBlank { "off" }}\n" +
                "CPU ADPF (TU_ADPF / TU_ADPF_TARGET_FPS): ${env.get("TU_ADPF").ifBlank { "driver default (on, in the game process)" }} / ${env.get("TU_ADPF_TARGET_FPS").ifBlank { "60" }}\n" +
                "GPU wait boost (TU_KGSL_WAITBOOST): ${env.get("TU_KGSL_WAITBOOST").ifBlank { "driver default (on without PWR_MAX, TurnRIP 1.0.9+)" }}, frame tagging (TU_KGSL_EOF): ${env.get("TU_KGSL_EOF").ifBlank { "driver default" }}, present thread steers (TU_KGSL_WAITBOOST_PRESENT_STEERS): ${env.get("TU_KGSL_WAITBOOST_PRESENT_STEERS").ifBlank { "off" }}, VKD3D waits steer (TU_KGSL_WAITBOOST_VKD3D_STEERS): ${env.get("TU_KGSL_WAITBOOST_VKD3D_STEERS").ifBlank { "off" }}, frame-time steering (TU_KGSL_WAITBOOST_FRAME_STEER): ${env.get("TU_KGSL_WAITBOOST_FRAME_STEER").ifBlank { "off" }}\n" +
                "GPU profile (TurnRIP u_trace sampling): ${if (env.has("MESA_GPU_TRACES")) "${env.get("MESA_GPU_TRACES")}, ${env.get("TU_PROFILE_SAMPLE_MS").let { if (it.isBlank()) "every frame" else "2 frames every $it ms" }} -> ${env.get("MESA_GPU_TRACEFILE").ifBlank { "stdout" }.substringAfterLast('/')}" else "off"}\n" +
                "Shader statistics (TU_SHADER_STATS_DIR): ${env.get("TU_SHADER_STATS_DIR").ifBlank { "off" }.substringAfterLast('/')}\n" +
                "DX12 dedicated GPU report (VKD3D_REPORT_DISCRETE): ${env.get("VKD3D_REPORT_DISCRETE").ifBlank { "off" }}\n" +
                "CPU identity: WINE_CPU_NAME='${env.get("WINE_CPU_NAME")}' WINE_CPU_VENDOR='${env.get("WINE_CPU_VENDOR")}' WINE_CPU_EFFICIENCY='${env.get("WINE_CPU_EFFICIENCY")}'\n" +
                "CPU cores (sysfs capacity / max kHz): ${coreSysfs()}\n" +
                "GPU identity: DXVK_VEGAS_PERSONA='${env.get("DXVK_VEGAS_PERSONA")}' DXVK_CONFIG='${env.get("DXVK_CONFIG")}'\n" +
                "Non-blocking X11 output (xnonblock): ${if (WowLaunchPolicy.enabled(context, "xnonblock")) "on" else "off"}\n" +
                "Gamescope direct scanout: ${if (WowLaunchPolicy.enabled(context, app.gamenative.display.GamescopeWindowPolicy.DIRECT_SCANOUT)) "on (no --force-composition)" else "off"}\n" +
                "Event reuse (WINE_EVENT_POOL): ${if (WowLaunchPolicy.enabled(context, WowRuntimeTuning.EVENT_POOL)) "on" else "off (0)"}\n" +
                "Base prefix fallback: ${com.winlator.container.BasePrefix.fallbackNotice() ?: "none"}\n" +
                "Safe Present buffer hand-back (presentidle): ${if (WowLaunchPolicy.enabled(context, "presentidle")) "on" else "off"}\n" +
                "Hybrid GPL (DXVK_HYBRID_GPL): ${env.get("DXVK_HYBRID_GPL").ifBlank { "off" }}, DX11 mode (DXVK_ASYNC): ${env.get("DXVK_ASYNC").ifBlank { "unset" }}\n" +
                "Vegas bind-skip (VEGAS_BINDSKIP): ${env.get("VEGAS_BINDSKIP").ifBlank { "off" }}\n" +
                "Startup: first 512 KiB in startup.log; stdout/stderr tails rotate independently.\n")
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove("lastError").apply()
            AppLogging.runtimeEnv(env, true, detailed(context), audioDebug(context))
            synchronized(this) {
                sampler?.close()
                sampler=DiagnosticSampler(session,detailed(context))
            }
            OneShotTasks.execute { try { DiagnosticSystemCapture.capture(context,File(session,"android-exits")) } catch (_:Exception) { } }
            session
        } catch (e: Exception) {
            AppLogging.runtimeEnv(env,false)
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString("lastError", "Unable to create runtime diagnostic logs.").apply()
            null
        }
    }

    /** What Wine's core-class detection can read: per CPU, cpu_capacity and cpuinfo_max_freq (blank = unreadable). */
    private fun coreSysfs(): String = (0 until Runtime.getRuntime().availableProcessors()).joinToString(" ") { cpu ->
        fun read(name: String) = runCatching { File("/sys/devices/system/cpu/cpu$cpu/$name").readText().trim() }.getOrDefault("")
        "cpu$cpu=${read("cpu_capacity")}/${read("cpufreq/cpuinfo_max_freq")}"
    }

    fun lastError(context: Context): String? = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("lastError", null)

    @Synchronized fun newSession(root: File, keep: Int = MAX_SESSIONS): File {
        require(keep in 1..20)
        check(root.isDirectory || root.mkdirs()) { "Unable to create diagnostics directory." }
        val session = File(root, "session-${System.currentTimeMillis()}-${UUID.randomUUID()}")
        check(session.mkdir()) { "Unable to create diagnostic session." }
        try {
            root.listFiles().orEmpty().filter { it.isDirectory && it.name.startsWith("session-") && it != session }
                .sortedByDescending { it.name }.drop(keep - 1).forEach {
                    check(it.deleteRecursively()) { "Unable to rotate diagnostic logs." }
                }
        } catch (e: Exception) {
            session.deleteRecursively()
            throw e
        }
        return session
    }
}
