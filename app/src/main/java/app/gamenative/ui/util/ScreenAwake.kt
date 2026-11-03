package app.gamenative.ui.util

import android.app.Activity
import android.view.WindowManager

/**
 * Keeps the screen on only while something needs it. The launcher used to hold the flag for as long as it was in front, so an
 * idle launcher never let the screen time out. Reasons ("game", "game-screen", "launcher-busy") are independent: the flag stays while any
 * is set. Each request names its OWNER, and only the owner that set a reason can clear it: an old effect that is disposed after
 * its replacement has already asked again must not take the replacement's request away. Main thread only (Compose effects).
 */
object ScreenAwake {
    private val owners = HashMap<String, Any>()

    /** Records the request; returns whether any reason is still active. Pure bookkeeping, no window access. */
    internal fun update(reason: String, on: Boolean, owner: Any): Boolean {
        if (on) owners[reason] = owner else if (owners[reason] === owner) owners.remove(reason)
        return owners.isNotEmpty()
    }

    internal fun reset() { owners.clear() }

    fun set(activity: Activity?, reason: String, on: Boolean, owner: Any = reason) {
        val active = update(reason, on, owner)
        val window = activity?.window ?: return
        // Applied on every call, not only on a change: a recreated Activity starts without the flag.
        if (active) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
}
