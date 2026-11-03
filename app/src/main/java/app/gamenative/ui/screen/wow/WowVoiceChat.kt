package app.gamenative.ui.screen.wow

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.winlator.container.ContainerManager
import com.winlator.contents.ContentsManager
import com.winlator.xenvironment.components.PulseAudioComponent
import java.io.File

/** Explicit voice controls. No permission grant, download or setting change occurs on Play. Call on IO. */
internal object WowVoiceChat {
    data class Status(val microphone: Boolean, val captureModule: Boolean, val proxy: Boolean, val libraries: Boolean)

    fun load(context: Context): Status {
        val c = ContainerManager(context).getContainerById(WowContainerProfile.CONTAINER_ID)
            ?: error("WoW container has not been created yet")
        val utils = File(WowLaunchEnvironment.gameDirectory(c).host, "Utils")
        return Status(c.getMicEnabled(), PulseAudioComponent.isMicModuleAvailable(context),
            listOf("WowVoiceProxyT.exe", "WowVoiceProxy.exe").any { File(utils, it).isFile },
            File(utils, "vivoxsdk.dll").isFile && (!File(utils, "WowVoiceProxyT.exe").isFile || File(utils, "discord_partner_sdk.dll").isFile))
    }

    /** Raw microphone: no Android echo cancellation / noise suppression (read by the microphone bridge at the next Play). */
    fun rawMic(context: Context): Boolean = context.getSharedPreferences("wow_voice", Context.MODE_PRIVATE).getBoolean("raw_mic", false)

    fun saveRawMic(context: Context, enabled: Boolean, active: () -> Boolean) {
        check(!active()) { "Stop the game before changing microphone capture." }
        check(context.getSharedPreferences("wow_voice", Context.MODE_PRIVATE).edit().putBoolean("raw_mic", enabled).commit())
    }

    fun saveMicrophone(context: Context, enabled: Boolean, active: () -> Boolean) =
        synchronized(ContentsManager.getLocalInstallLock()) {
            check(!active()) { "Stop the game before changing microphone capture." }
            check(!enabled || ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                "Microphone permission is required."
            }
            val c = ContainerManager(context).getContainerById(WowContainerProfile.CONTAINER_ID)
                ?: error("WoW container has not been created yet")
            check(!active()) { "Stop the game before changing microphone capture." }
            c.setMicEnabled(enabled)
            c.saveData()
            check(ContainerManager(context).getContainerById(c.id)?.getMicEnabled() == enabled) { "Unable to save microphone capture." }
        }

    fun repair(context: Context, active: () -> Boolean, translate: (String, String) -> String, onStatus: (String) -> Unit): Int =
        synchronized(ContentsManager.getLocalInstallLock()) {
            check(!active()) { "Stop the game before repairing voice files." }
            val c = ContainerManager(context).getContainerById(WowContainerProfile.CONTAINER_ID)
                ?: error("WoW container has not been created yet")
            val game = WowLaunchEnvironment.gameDirectory(c).host
            require(game.name == WowClientDownloader.FLAVOR_DIR) { "The WoW executable directory is required." }
            WowClientDownloader.repairVoiceFiles(game.parentFile!!, onStatus, active, translate)
        }
}
