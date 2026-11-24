package com.winlator.xserver.harness

import android.os.Looper
import android.view.KeyEvent
import com.winlator.xconnector.ShadowRecordingXConnectorNative
import com.winlator.xserver.WindowManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.time.Duration

/**
 * jaredgei/wow-forever-android 8607f98: a hardware keyboard repeats a held key itself (Android sends repeated key-downs), so the X
 * server must not start its own repeat for it. Otherwise a held key was repeated twice, and a release that never arrived (focus
 * moved mid-press) repeated it forever. The on-screen keyboard keeps the X server's repeat.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], shadows = [ShadowNoNativeDrawable::class, ShadowRecordingXConnectorNative::class])
class HardwareKeyRepeatTest {
    private val KEY_PRESS = 2
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

    private fun keyPressesAfter(ms: Long): Int {
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
        return c.packets().count { it.type == KEY_PRESS }
    }

    @Test fun aHeldHardwareKeyIsNotRepeatedByTheXServer() {
        x.server.keyboard.onKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A))
        // No release arrives (focus moved away mid-press): the key must not keep typing.
        assertEquals("only the press itself", 1, keyPressesAfter(3000))
    }

    @Test fun androidsOwnRepeatsStillReachTheGame() {
        val down = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A)
        x.server.keyboard.onKeyEvent(down)
        x.server.keyboard.onKeyEvent(KeyEvent.changeTimeRepeat(down, 600, 1))
        x.server.keyboard.onKeyEvent(KeyEvent.changeTimeRepeat(down, 650, 2))
        assertEquals(3, keyPressesAfter(100))
        x.server.keyboard.onKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_A))
        assertEquals(0, keyPressesAfter(2000))
    }

    @Test fun theOnScreenKeyboardKeepsTheXServerRepeat() {
        x.server.keyboard.onVirtualKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A))
        assertTrue("held on-screen key repeats", keyPressesAfter(1500) > 3)
    }
}
