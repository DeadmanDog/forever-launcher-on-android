package app.gamenative.ui.screen.wow

import android.content.Context
import com.winlator.container.Container
import com.winlator.core.envvars.EnvVars
import java.io.File

/** Separate opt-in diagnostic process; failure never changes the game command. */
internal object WowDriverProbe {
    @JvmStatic fun prepare(context: Context, container: Container, env: EnvVars, session: File?): File? {
        if (session == null || container.id != WowContainerProfile.CONTAINER_ID ||
            WowVkd3dComponents.api(container) != WowVkd3dComponents.DX12 || !WowDiagnostics.enabled(context)) return null
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
