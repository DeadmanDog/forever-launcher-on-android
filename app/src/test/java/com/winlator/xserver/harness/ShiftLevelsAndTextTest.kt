package com.winlator.xserver.harness

import android.view.KeyEvent
import com.winlator.xconnector.ShadowRecordingXConnectorNative
import com.winlator.xserver.WindowManager
import com.winlator.xserver.XKeycode
import com.winlator.xserver.XServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * jaredgei 83cdbc9 / 634d1e9: a hardware key carries both its levels ('1' and '!'), so Shift+1 stays the same key for Wine (action
 * bar keybinds); a character with no US key (Hungarian ő) reaches the game as a Unicode keysym on the spare keycode.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], shadows = [ShadowNoNativeDrawable::class, ShadowRecordingXConnectorNative::class])
class ShiftLevelsAndTextTest {
    private lateinit var x: XTestServer

    @Before fun setUp() {
        x = XTestServer()
        val c = x.client()
        val w = c.createWindow(x.root.id, 0, 0, 200, 200, eventMask = 1 or 2)
        c.mapWindow(w)
        x.server.windowManager.setFocus(x.server.windowManager.getWindow(w), WindowManager.FocusRevertTo.NONE)
        x.server.pointer.setPosition(20, 20)
    }

    private fun keysyms(keycode: Byte): Pair<Int, Int> {
        val i = ((keycode.toInt() and 0xFF) - 8) * 2
        return x.server.keyboard.keysyms[i] to x.server.keyboard.keysyms[i + 1]
    }

    @Test fun aNumberKeyKeepsBothLevelsAlsoWithShiftHeld() {
        x.server.keyboard.onKeyEvent(KeyEvent(0, 0, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_1, 0, KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON))
        assertEquals('1'.code to '!'.code, keysyms(XKeycode.KEY_1.id))
        x.server.keyboard.onKeyEvent(KeyEvent(0, 0, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_1, 0, 0))
        x.server.keyboard.onKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_1))
        assertEquals('1'.code to '!'.code, keysyms(XKeycode.KEY_1.id))
    }

    @Test fun aHungarianLetterGoesAsAUnicodeKeysym() {
        x.server.injectTextCharacter('ő'.code)
        assertEquals(0x01000151 to 0x01000151, keysyms(XServer.TEXT_KEYCODE))
        x.server.injectTextCharacter('é'.code)
        assertEquals(0xe9 to 0xe9, keysyms(XServer.TEXT_KEYCODE))
        assertTrue(XServer.TEXT_KEYCODE.toInt() !in XKeycode.values().map { it.id.toInt() })
    }
}
