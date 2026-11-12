package app.gamenative.display

import android.content.Context
import android.view.Surface
import com.winlator.container.Container
import com.winlator.core.envvars.EnvVars
import com.winlator.core.FileUtils
import com.winlator.xenvironment.EnvironmentComponent
import java.io.File

object WowGamescopeRuntime {
    @JvmStatic fun surface(surface: Surface, width: Int, height: Int, guestWidth: Int, guestHeight: Int, refresh: Int) {
        val actual = GamescopeWindowPolicy.surfaceSize(width, height, guestWidth, guestHeight)
        GameNativeDrm.nativeSetPanelRefreshHz(refresh.coerceAtLeast(1))
        if (GameNativeDrm.nativeRetargetSurface(surface, actual.first, actual.second) == GameNativeDrm.E_NO_SESSION) {
            val guest = guestWidth to guestHeight
            val requested = GameNativeDrm.outputSize ?: guest
            val output = GameNativeDrm.activeEffects?.scanoutSize(guest, requested, actual) ?: requested
            GameNativeDrm.outputSize = output
            GameNativeDrm.nativeSetScanoutGeometry(output.first, output.second)
            check(GameNativeDrm.nativeStart("gndrm", surface, actual.first, actual.second) >= 0) { "Gamescope Android display could not start" }
        }
    }
    /** Called only for the game command, never for shell/wineserver cleanup. */
    @JvmStatic fun wrap(context: Context, container: Container, root: File, env: EnvVars, command: String): String {
        val dir = WowGamescopePayload.directory(context)
        check(WowGamescopePayload.isReady(context)) { "Gamescope runtime needs preparation before launch" }
        val run = File(root, "tmp/xdg-run").apply { mkdirs(); FileUtils.chmod(this, 448) }
        val socketDir = File(root, "usr/tmp").apply { mkdirs() }
        val preload = env.get("LD_PRELOAD")
        env.put("LD_PRELOAD", (if (preload.isEmpty()) "" else "$preload:") + File(dir, "lib/libgndrm_shim.so").path)
        env.put("LD_LIBRARY_PATH", File(dir,"lib").path + ":" + env.get("LD_LIBRARY_PATH"))
        env.put("PATH", File(dir,"bin").path + ":" + env.get("PATH"))
        env.remove("DISPLAY")
        env.put("XDG_RUNTIME_DIR", run.path)
        env.put("GAMENATIVE_DRM_SOCKET", "gndrm")
        env.put("GNDRM_SCANOUT_LAYOUT", GameNativeDrm.nativeScanoutLayout())
        env.put("XKB_CONFIG_ROOT", File(dir,"share/xkeyboard-config-2").path)
        env.put("GAMESCOPE_PNP_IDS_PATH", File(dir,"share/hwdata/pnp.ids").path)
        env.put("__EGL_VENDOR_LIBRARY_DIRS", File(dir,"share/glvnd/egl_vendor.d").path)
        env.put("LIBGL_DRIVERS_PATH", File(dir,"lib/dri").path)
        env.put("MESA_LOADER_DRIVER_OVERRIDE", "zink")
        env.put("WLR_XWAYLAND", File(dir,"bin/Xwayland").path)
        env.put("WLR_X11_SOCKET_DIR", socketDir.path)
        env.put("GAMESCOPE_X11_DISPLAY", File(socketDir,".X11-unix/X0").path)
        env.put("VK_ICD_FILENAMES", File(dir,"share/vulkan/icd.d/adrenotools_gamescope.aarch64.json").path)
        env.put("GNDRM_DRIVER_TYPE", container.graphicsDriver)
        val size = geometry(container.screenSize)
        val output = GameNativeDrm.outputSize ?: size
        env.put("GNDRM_SCANOUT", "${output.first}x${output.second}")
        env.put("GNDRM_SCANOUT_NESTED", container.screenSize)
        // Gamescope patch 0119: the cursor's 48-pixel readable minimum in output pixels, so the panel's enlargement of a game-sized
        // output does not make it 1.5x the XServer cursor (1344x608 on 2688x1216 -> 24).
        // The window in its current orientation: in portrait the game sits in a panel-wide strip, so it is shown smaller, not 2x.
        val window = runCatching {
            val wm = context.getSystemService(android.view.WindowManager::class.java)
            if (android.os.Build.VERSION.SDK_INT >= 30) wm.maximumWindowMetrics.bounds.let { it.width() to it.height() }
            else context.resources.displayMetrics.let { it.widthPixels to it.heightPixels }
        }.getOrNull()
        env.put("GNDRM_CURSOR_MIN_PX", cursorMinPixels(output, window))
        // The old XServer compositor's effects/layers cannot run inside the Gamescope process.
        val layers = env.get("VK_INSTANCE_LAYERS").split(":").filter { it.isNotEmpty() && !it.contains("lsfg", ignoreCase = true) }
        if (layers.isEmpty()) env.remove("VK_INSTANCE_LAYERS") else env.put("VK_INSTANCE_LAYERS", layers.joinToString(":"))
        val gameFlags = if (GamescopeWindowPolicy.gameLaunch(true, container.executablePath))
            GamescopeWindowPolicy.gameDisplayFlags(app.gamenative.ui.screen.wow.WowLaunchPolicy.enabled(context, GamescopeWindowPolicy.DIRECT_SCANOUT)) else ""
        val effects = GameNativeDrm.activeEffects ?: GamescopeEffects.load(container)
        return File(dir,"bin/gamescope").path + gameFlags + effects.launchArgs() +
            " --backend drm --expose-wayland -w ${size.first} -h ${size.second} -W ${output.first} -H ${output.second} -- " + command
    }
    /**
     * The cursor's readable minimum (48 display pixels) in Gamescope output pixels: 48 divided by how much the output is enlarged
     * in the window (fit). 48 when the window is unknown. Landscape 1344x608 on 2688x1216 -> 2x -> 24; portrait (1216 wide) shows
     * the output at 0.9x -> 48 (the panel's short side alone gave 24 there, a cursor of about 22 display pixels).
     */
    internal fun cursorMinPixels(output: Pair<Int, Int>, window: Pair<Int, Int>?): Int {
        if (window == null || window.first <= 0 || window.second <= 0 || output.first <= 0 || output.second <= 0) return 48
        val scale = minOf(window.first.toDouble() / output.first, window.second.toDouble() / output.second)
        // Up to 128: an output shown below 1:1 needs more than 48 output pixels (the cursor layer takes up to 256).
        return kotlin.math.round(48.0 / scale).toInt().coerceIn(1, 128)
    }

    internal fun geometry(value: String): Pair<Int, Int> {
        val m = Regex("([0-9]{2,5})x([0-9]{2,5})").matchEntire(value) ?: error("Invalid virtual display size")
        val w = m.groupValues[1].toInt(); val h = m.groupValues[2].toInt()
        require(w in 64..16384 && h in 64..16384)
        return w to h
    }
}

class WowGamescopeComponent(private val root: File, private val waitForGame: Boolean) : EnvironmentComponent() {
    @Volatile private var stopped = false
    private var watcher: Thread? = null
    override fun start() {
        val run = File(root,"tmp/xdg-run").apply { mkdirs(); FileUtils.chmod(this,448) }
        check(GameNativeDrm.nativeInjectorStart(run.path,"gamescope-0") >= 0) { "Gamescope input injector failed" }
        if (waitForGame) {
            val token = GameNativeDrm.sessionEpoch()
            val context = environment.context.applicationContext
            watcher = Thread({
                val dir = WowGamescopePayload.directory(context)
                val env = EnvVars().apply { put("LD_LIBRARY_PATH", File(dir,"lib").path + ":/system/lib64") }
                var lastTree = ""
                try {
                    // Once a second for the first minute, then every 3 s (each probe is a process start); after ~1 min the splash comes
                    // down so a dialog is visible, after ~10 min the watcher gives up. The session is never ended from here: a
                    // missing window used to stop the game after 3 min (a slow first start or a WoW error dialog behind the splash).
                    var revealed = false
                    for (attempt in 0 until 240) {
                        if (stopped || token != GameNativeDrm.sessionEpoch()) return@Thread
                        val probe = File(dir,"bin/gn-xtree-probe").path + " " + File(root,"usr/tmp/.X11-unix/X0").path
                        lastTree = com.winlator.core.ProcessHelper.execWithOutput(probe, env.toStringArray(), dir, true, 2)
                        var mappedGame = false
                        for (id in GamescopeWindowPolicy.gameWindowIds(lastTree)) {
                            if (stopped || token != GameNativeDrm.sessionEpoch()) return@Thread
                            val details = com.winlator.core.ProcessHelper.execWithOutput(probe + " " + id,
                                env.toStringArray(), dir, true, 2)
                            lastTree += "\n" + details
                            if (GamescopeWindowPolicy.hasMappedGameWindow(details)) { mappedGame = true; break }
                        }
                        if (mappedGame) {
                            if (!stopped && token == GameNativeDrm.sessionEpoch()) {
                                runCatching {
                                    if (app.gamenative.ui.screen.wow.WowDiagnostics.enabled(context))
                                        File(app.gamenative.ui.screen.wow.WowDiagnostics.directory(context),"gamescope-window-tree.txt").writeText(lastTree.take(32768))
                                }
                                GameNativeDrm.gameWindowDetected(token)
                            }
                            return@Thread
                        }
                        if (!revealed && attempt >= 60) {
                            revealed = true
                            GameNativeDrm.revealWithoutGameWindow(token)
                        }
                        Thread.sleep(if (attempt < 60) 1000 else 3000)
                    }
                    if (!stopped && token == GameNativeDrm.sessionEpoch()) {
                        GameNativeDrm.revealWithoutGameWindow(token)
                        android.util.Log.w("WowGamescope", "WoW window not detected in Gamescope after ~10 min; watcher stopped")
                    }
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                } catch (error: Exception) {
                    if (!stopped && token == GameNativeDrm.sessionEpoch()) GameNativeDrm.reportStartFailure(context,error)
                } finally {
                    runCatching {
                        if (lastTree.isNotEmpty() && app.gamenative.ui.screen.wow.WowDiagnostics.enabled(context))
                            File(app.gamenative.ui.screen.wow.WowDiagnostics.directory(context), "gamescope-window-tree.txt").writeText(lastTree.take(32768))
                    }
                }
            }, "GamescopeWindowReady").apply { isDaemon = true; start() }
        }
    }
    override fun stop() {
        stopped = true; watcher?.interrupt(); watcher = null
        GameNativeDrm.cancelReadiness()
        GameNativeDrm.frameListener = null
        GameNativeDrm.nativeInjectorStop()
        GameNativeDrm.nativeStop()
    }
}
