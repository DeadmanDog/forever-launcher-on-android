package app.gamenative.display

import android.content.Context
import app.gamenative.PluviaApp
import com.winlator.container.Container
import com.winlator.container.ContainerManager

/** Only this extra belongs to the backend selector. Existing runtime selections are retained. */
object WowDisplayBackend {
    const val KEY = "wowDisplayBackend"
    const val XSERVER = "xserver"
    const val GAMESCOPE = "gamescope"
    @JvmStatic fun isGamescope(container: Container?): Boolean = container?.getExtra(KEY, XSERVER) == GAMESCOPE
    fun apply(context: Context, value: String, prepare: () -> Unit = {
        WowGamescopePayload.ensure(context)
        try { GameNativeDrm.nativeDisplayInfo() } catch (e: LinkageError) {
            throw IllegalStateException("Gamescope native bridge is unavailable on this device", e)
        }
    }, active: () -> Boolean = { PluviaApp.keepAlive }) =
        synchronized(com.winlator.contents.ContentsManager.getLocalInstallLock()) {
        require(value == XSERVER || value == GAMESCOPE)
        check(!active()) { "Stop WoW before changing the display backend." }
        val manager = ContainerManager(context)
        val container = manager.getContainerById("wow_forever") ?: error("WoW container has not been created yet")
        if (value == GAMESCOPE) prepare()
        check(!active()) { "WoW session started during preparation." }
        container.putExtra(KEY, value)
        container.saveData()
        check(ContainerManager(context).getContainerById("wow_forever")?.getExtra(KEY, XSERVER) == value) { "Display backend was not saved" }
    }
}
