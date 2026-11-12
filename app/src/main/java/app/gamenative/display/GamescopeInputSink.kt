package app.gamenative.display

import com.winlator.xserver.Pointer
import com.winlator.xserver.XKeycode
import com.winlator.xserver.XServer

/** Gamescope-mode input retarget: Android-injected input is forwarded to gamescope's wayland seat via the gndrm injector (installed with XServer.setInputEventSink); X11 mode installs none. */
class GamescopeInputSink(
    private val width: Int,
    private val height: Int,
    private val movePointer: (Int, Int) -> Unit = { dx, dy -> GameNativeDrm.nativePointerMotion(dx, dy) },
) : XServer.InputEventSink {
    // The Gamescope seat clips the visible pointer to the guest bounds.
    private var cursorX = 0
    private var cursorY = 0

    /** The seat's real pointer starts where Gamescope puts it (not 0,0): pinned once to the top-left corner first. */
    private var synced = false

    private fun syncOnce() {
        if (synced) return
        synced = true
        // The seat clips at the guest bounds, so a full-size move up-left lands exactly on (0, 0) wherever it started.
        movePointer(-width, -height)
        cursorX = 0
        cursorY = 0
    }

    override fun pointerMove(x: Int, y: Int) {
        syncOnce()
        val nextX = x.coerceIn(0, width - 1)
        val nextY = y.coerceIn(0, height - 1)
        val dx = nextX - cursorX
        val dy = nextY - cursorY
        cursorX = nextX
        cursorY = nextY
        // The bundled Gamescope I1 patch subscribes to wlr_pointer.events.motion,
        // not motion_absolute. Keep guest/surface-local deltas, as wlserver_mousemotion
        // expects them; physical Android Surface pixels are not this coordinate space.
        // Converting an absolute touch to a delta also matches the upstream input sink.
        if (dx != 0 || dy != 0) movePointer(dx, dy)
    }

    override fun pointerMoveDelta(dx: Int, dy: Int) {
        cursorX = (cursorX.toLong() + dx).coerceIn(0, width.toLong() - 1).toInt()
        cursorY = (cursorY.toLong() + dy).coerceIn(0, height.toLong() - 1).toInt()
        // The full relative delta still drives the game's raw mouse/camera input.
        if (dx != 0 || dy != 0) movePointer(dx, dy)
    }

    override fun pointerButton(button: Pointer.Button, pressed: Boolean) {
        when (button) {
            Pointer.Button.BUTTON_LEFT ->
                GameNativeDrm.nativePointerButton(BTN_LEFT, pressed)
            Pointer.Button.BUTTON_MIDDLE ->
                GameNativeDrm.nativePointerButton(BTN_MIDDLE, pressed)
            Pointer.Button.BUTTON_RIGHT ->
                GameNativeDrm.nativePointerButton(BTN_RIGHT, pressed)
            Pointer.Button.BUTTON_SCROLL_UP ->
                if (pressed) GameNativeDrm.nativePointerAxis(AXIS_VERTICAL, -WHEEL_STEP)
            Pointer.Button.BUTTON_SCROLL_DOWN ->
                if (pressed) GameNativeDrm.nativePointerAxis(AXIS_VERTICAL, WHEEL_STEP)
            Pointer.Button.BUTTON_SCROLL_CLICK_LEFT ->
                if (pressed) GameNativeDrm.nativePointerAxis(AXIS_HORIZONTAL, -WHEEL_STEP)
            Pointer.Button.BUTTON_SCROLL_CLICK_RIGHT ->
                if (pressed) GameNativeDrm.nativePointerAxis(AXIS_HORIZONTAL, WHEEL_STEP)
        }
    }

    override fun keyPress(xKeycode: XKeycode, keysym: Int) {
        GameNativeDrm.nativeKey(evdevCode(xKeycode), true)
    }

    override fun keyRelease(xKeycode: XKeycode) {
        GameNativeDrm.nativeKey(evdevCode(xKeycode), false)
    }

    internal companion object {
        /**
         * One wheel notch in wl_pointer axis units. The injector sends the value without a discrete step, and Xwayland turns such
         * smooth scrolling into X wheel clicks at one click per 10 units (DEFAULT_AXIS_STEP_DISTANCE): the old 1.0 per notch
         * needed ten notches for one zoom step in WoW.
         */
        const val WHEEL_STEP = 10f

        // evdev button codes (<linux/input-event-codes.h>)
        const val BTN_LEFT = 0x110
        const val BTN_RIGHT = 0x111
        const val BTN_MIDDLE = 0x112

        const val AXIS_VERTICAL = 0
        const val AXIS_HORIZONTAL = 1

        /** X11 keycode = evdev keycode + 8 (historical Xorg offset). */
        const val X11_KEYCODE_OFFSET = 8

        /** XKeycode.id is a Byte; mask before widening or codes >127 go negative. */
        private fun evdevCode(xKeycode: XKeycode): Int =
            (xKeycode.id.toInt() and 0xFF) - X11_KEYCODE_OFFSET
    }
}
