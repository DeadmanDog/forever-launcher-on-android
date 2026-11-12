package app.gamenative.ui.screen.wow

import android.content.Context
import com.winlator.container.Container
import com.winlator.core.envvars.EnvVars
import java.io.File

/** Separate opt-in diagnostic process; failure never changes the game command. */
internal object WowDriverProbe {
    internal fun skipBeforeGame(gamescope: Boolean, display: String): String? = when {
        gamescope -> "Skipped: Gamescope must create Xwayland and assign DISPLAY before any Wine client. " +
            "The pre-launch diagnostic probe must not initialize the shared Wine display headlessly. " +
            "Wine/Wrapper driver-ID probing is unavailable in this pre-launch Gamescope path."
        display.isBlank() -> "Skipped: no DISPLAY is available for the pre-launch Wine diagnostic probe."
        else -> null
    }

    @JvmStatic fun prepare(context: Context, container: Container, env: EnvVars, session: File?): File? {
        if (session == null || container.id != WowContainerProfile.CONTAINER_ID ||
            WowVkd3dComponents.api(container) != WowVkd3dComponents.DX12 || !WowDiagnostics.enabled(context)) return null
        skipBeforeGame(app.gamenative.display.WowDisplayBackend.isGamescope(container), env.get("DISPLAY"))?.let { reason ->
            runCatching { File(session, "driver-probe-skipped.txt").writeText(reason + "\n") }
            return null
        }
        return try {
            val prefix = env.get("WINEPREFIX")
            check(prefix.isNotBlank())
            val executable = File(prefix,"drive_c/windows/temp/wow-driver-probe.exe")
            check(executable.parentFile.isDirectory || executable.parentFile.mkdirs())
            context.assets.open("dx12_probe/wow-driver-probe.exe").use { input ->
                executable.outputStream().use { input.copyTo(it) }
            }
            File(session,"driver-probe").also { check(it.mkdir() || it.isDirectory) }
        } catch (e: Exception) {
            runCatching { File(session,"driver-probe-error.txt").writeText("Driver probe preparation failed: ${e.javaClass.simpleName}") }
            null
        }
    }
}
