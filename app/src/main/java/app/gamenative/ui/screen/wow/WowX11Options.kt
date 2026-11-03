package app.gamenative.ui.screen.wow

import com.winlator.core.WineRegistryEditor
import java.io.File

/**
 * Wine X11 driver options the WoW prefix needs, written to user.reg before Wine starts.
 * The X server implements only the cursor part of RENDER (RenderExtension), so Wine's own RENDER drawing of GDI text and
 * blends must stay off: ClientSideWithRender=N. libXcursor still uses RENDER for full-colour cursors.
 */
internal object WowX11Options {
    const val KEY = "Software\\Wine\\X11 Driver"
    const val CLIENT_SIDE_WITH_RENDER = "ClientSideWithRender"

    /** Returns whether user.reg was changed; a prefix without user.reg yet is left alone (Wine creates it on first boot). */
    @JvmStatic
    fun ensure(userReg: File): Boolean {
        if (!userReg.isFile || hasValue(userReg.readText(), "N")) return false
        WineRegistryEditor(userReg).use { it.setStringValue(KEY, CLIENT_SIDE_WITH_RENDER, "N") }
        return true
    }

    internal fun hasValue(reg: String, value: String): Boolean {
        val header = "[" + KEY.replace("\\", "\\\\") + "]"
        var inKey = false
        for (line in reg.lineSequence()) {
            if (line.startsWith("[")) inKey = line.startsWith(header)
            else if (inKey && line.trim() == "\"$CLIENT_SIDE_WITH_RENDER\"=\"$value\"") return true
        }
        return false
    }
}
