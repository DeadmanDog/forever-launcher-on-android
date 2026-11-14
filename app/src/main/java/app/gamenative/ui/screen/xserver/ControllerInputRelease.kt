package app.gamenative.ui.screen.xserver

import com.winlator.inputcontrols.ControllerManager
import com.winlator.inputcontrols.GamepadState
import com.winlator.winhandler.WinHandler

/**
 * Review 2026-11-13: while the Quick Menu is open, controller input goes to the menu, so the game never sees the release of a
 * button, stick or trigger held when it opened (and the stick-as-mouse timer kept moving the cursor). When the menu opens and when
 * the app is paused, everything the game still holds is let go: the profile bindings ([PhysicalControllerHandler.cleanup]), the X
 * keyboard, and the external controllers' state (fresh controller objects, a neutral state written to each assigned slot, then
 * sent once to the game's XInput clients).
 */
internal object ControllerInputRelease {
    const val MAX_SLOTS = 4

    /** The parts, so the order can be tested without a running X server. */
    internal class Actions(
        val cleanupBindings: () -> Unit,
        val releaseKeys: () -> Unit,
        val assignedSlots: () -> List<Int>,
        val refreshControllers: () -> Unit,
        val writeNeutral: (Int) -> Unit,
        val sendState: () -> Unit,
    )

    internal fun run(actions: Actions) {
        runCatching(actions.cleanupBindings)
        runCatching(actions.releaseKeys)
        val slots = runCatching(actions.assignedSlots).getOrDefault(emptyList())
        // A player without an external controller (touch controls only) is not touched at all.
        if (slots.isEmpty()) return
        runCatching(actions.refreshControllers)
        slots.forEach { slot -> runCatching { actions.writeNeutral(slot) } }
        runCatching(actions.sendState)
    }

    fun release(handler: PhysicalControllerHandler?, winHandler: WinHandler?, releaseKeys: () -> Unit) {
        val manager = ControllerManager.getInstance()
        run(Actions(
            cleanupBindings = { handler?.cleanup() },
            releaseKeys = releaseKeys,
            assignedSlots = { if (winHandler == null) emptyList() else (0 until MAX_SLOTS).filter { manager.getAssignedDeviceForSlot(it) != null } },
            // New controller objects for every slot: their state starts neutral, so a held button is not sent again with the
            // next input from that controller.
            refreshControllers = { winHandler?.refreshControllerMappings() },
            writeNeutral = { slot -> winHandler?.sendVirtualGamepadState(GamepadState(), slot) },
            sendState = { winHandler?.sendGamepadState() },
        ))
    }
}
