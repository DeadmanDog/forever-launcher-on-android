package com.winlator.xserver.harness

import android.view.KeyEvent
import com.winlator.xconnector.ShadowRecordingXConnectorNative
import com.winlator.xserver.WindowManager
import com.winlator.xserver.XKeycode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 1.2.26 (X server core review):
 * - Tab from a soft keyboard passed '\t' (0x09) as its keysym: the server re-mapped Tab to keysym 0x09 and sent MappingNotify, and
 *   Wine's keyboard map then had no VK_TAB for the rest of the session (WoW's "target nearest enemy"), a hardware keyboard too.
 * - Caps Lock's LockMask was set only while the key was held: Wine's update_lock_state saw Lock off on the very next key and turned
 *   Windows' CapsLock back off (with fake CAPSLOCK events). It is a lock now: each new press flips it, the release keeps it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], shadows = [ShadowNoNativeDrawable::class, ShadowRecordingXConnectorNative::class])
class KeyboardLockAndTabTest {
    private val MAPPING_NOTIFY = 34
    private lateinit var x: XTestServer
    private lateinit var c: XTestServer.TestClient

    @Before fun setUp() {
        x = XTestServer()
        c = x.client()
        val w = c.createWindow(x.root.id, 0, 0, 200, 200, eventMask = 1 or 2)
        c.mapWindow(w)
        x.server.windowManager.setFocus(x.server.windowManager.getWindow(w), WindowManager.FocusRevertTo.NONE)
        x.server.pointer.setPosition(20, 20)
        c.packets()
    }

    @Test fun aSoftKeyboardTabKeepsTabsKeysym() {
        val kb = x.server.keyboard
        assertTrue(kb.hasKeysym(XKeycode.KEY_TAB.id, 0xff09))
        kb.onVirtualKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_TAB))
        kb.onVirtualKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_TAB))
        assertTrue("Tab still XK_Tab", kb.hasKeysym(XKeycode.KEY_TAB.id, 0xff09))
        assertFalse("Tab was re-mapped to 0x09", kb.hasKeysym(XKeycode.KEY_TAB.id, 0x09))
        assertEquals("no MappingNotify for Tab", 0, c.packets().count { it.type == MAPPING_NOTIFY })
    }

    @Test fun capsLockStaysOnAfterItsReleaseUntilPressedAgain() {
        val kb = x.server.keyboard
        val lock = 2L
        kb.setKeyPress(XKeycode.KEY_CAPS_LOCK.id, 0xffe5, false)
        kb.setKeyRelease(XKeycode.KEY_CAPS_LOCK.id)
        assertTrue("Lock on after Caps Lock was released", kb.modifiersMask.isSet(lock))
        kb.setKeyPress(XKeycode.KEY_A.id, 'a'.code, false)
        kb.setKeyRelease(XKeycode.KEY_A.id)
        assertTrue("still on for the next key", kb.modifiersMask.isSet(lock))
        kb.setKeyPress(XKeycode.KEY_CAPS_LOCK.id, 0xffe5, false)
        kb.setKeyPress(XKeycode.KEY_CAPS_LOCK.id, 0xffe5, true)   // an auto-repeat of the held key flips nothing
        kb.setKeyRelease(XKeycode.KEY_CAPS_LOCK.id)
        assertFalse("off after the second press", kb.modifiersMask.isSet(lock))
        // Shift is still a plain modifier: on only while held.
        kb.setKeyPress(XKeycode.KEY_SHIFT_L.id, 0xffe1, false); assertTrue(kb.modifiersMask.isSet(1L))
        kb.setKeyRelease(XKeycode.KEY_SHIFT_L.id); assertFalse(kb.modifiersMask.isSet(1L))
    }
}
