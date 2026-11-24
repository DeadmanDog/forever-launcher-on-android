package com.winlator.xserver.harness

import android.os.Looper
import android.view.KeyEvent
import com.winlator.xconnector.ShadowRecordingXConnectorNative
import com.winlator.xserver.WindowManager
import com.winlator.xserver.XServer
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.time.Duration
import java.util.concurrent.CountDownLatch

/**
 * 1.2.26: the X server's key auto-repeat (an on-screen key held, e.g. W) runs on the main looper. It walked the window tree and the
 * listener lists without the X locks while the X client thread changes them under WINDOW_MANAGER (restack, event mask, create/
 * destroy): ConcurrentModificationException on the main thread ended the app. It now takes the same locks as XServer.inject*:
 * while another thread holds WINDOW_MANAGER, the repeat waits for it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], shadows = [ShadowNoNativeDrawable::class, ShadowRecordingXConnectorNative::class])
class AutoRepeatLockTest {
    @Test fun theAutoRepeatWaitsForTheXServerLock() {
        val x = XTestServer()
        val c = x.client()
        val w = c.createWindow(x.root.id, 0, 0, 200, 200, eventMask = 1 or 2)
        c.mapWindow(w)
        x.server.windowManager.setFocus(x.server.windowManager.getWindow(w), WindowManager.FocusRevertTo.NONE)
        x.server.pointer.setPosition(20, 20)
        x.server.keyboard.onVirtualKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_W))
        c.packets()

        val holding = CountDownLatch(1)
        val holdMs = 300L
        val holder = Thread {
            x.server.lock(XServer.Lockable.WINDOW_MANAGER).use {
                holding.countDown()
                Thread.sleep(holdMs)   // the X client thread changing the window tree
            }
        }
        holder.start()
        holding.await()
        val start = System.nanoTime()
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(700))   // the repeat delay passes: the runnable runs
        val waitedMs = (System.nanoTime() - start) / 1_000_000
        holder.join()
        assertTrue("the repeat ran while the X client thread held WINDOW_MANAGER (waited $waitedMs ms)", waitedMs >= holdMs - 50)
        assertTrue("the held key still repeats", c.packets().count { it.type == 2 } >= 1)
    }
}
