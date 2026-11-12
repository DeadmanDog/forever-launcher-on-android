package com.winlator.xserver

import android.app.Application
import android.view.KeyEvent
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 1.2.3 (multi-agent review): keys a player holds on a physical keyboard. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class KeyboardHeldKeysTest {
    private fun shiftDown() = KeyEvent(0, 0, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SHIFT_LEFT, 0,
        KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON)   // Android sets the key's own modifier on its down event

    // The Shift key's own down event made a synthetic Shift_L press, the real press and the synthetic release: the game saw Shift up
    // while it was held, so Shift+click (item links, splitting a stack) became a plain click.
    @Test fun aHeldLeftShiftIsNotReleasedByItsOwnKeyDown() {
        val xServer = mockk<XServer>(relaxed = true)
        val keyboard = Keyboard(xServer)
        keyboard.onKeyEvent(shiftDown())
        verify(exactly = 1) { xServer.injectKeyPress(XKeycode.KEY_SHIFT_L, any()) }
        verify(exactly = 0) { xServer.injectKeyRelease(XKeycode.KEY_SHIFT_L) }
    }

    // Gamescope: keys go to the input sink and never reached pressedKeys; the Quick Menu's release found nothing to release.
    @Test fun keysHeldThroughTheSinkAreReleasedByTheQuickMenu() {
        val xServer = mockk<XServer>(relaxed = true)
        val keyboard = Keyboard(xServer)
        keyboard.trackSinkKey(XKeycode.KEY_W.id, true)
        keyboard.trackSinkKey(XKeycode.KEY_SHIFT_L.id, true)
        assertEquals(true, keyboard.modifiersMask.isSet(Keyboard.getModifierFlag(XKeycode.KEY_SHIFT_L.id).toLong()))
        keyboard.releaseAllKeys()
        verify(exactly = 1) { xServer.injectKeyRelease(XKeycode.KEY_W) }
        verify(exactly = 1) { xServer.injectKeyRelease(XKeycode.KEY_SHIFT_L) }
    }
}
