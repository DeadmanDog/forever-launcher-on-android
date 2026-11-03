package app.gamenative.ui.screen.xserver

import android.app.Application
import android.view.KeyEvent
import androidx.activity.OnBackPressedCallback
import androidx.activity.OnBackPressedDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [29])
class GameBackKeyHandlerTest {
    private val handler = GameBackKeyHandler()

    private fun back(action: Int, downTime: Long = 10L, repeats: Int = 0, flags: Int = 0) =
        KeyEvent(downTime, downTime + 100L, action, KeyEvent.KEYCODE_BACK, repeats, 0, -1, 0, flags)

    @Test
    fun openingPressAndRepeatsCannotImmediatelyDismissMenu() {
        val dispatcher = OnBackPressedDispatcher()
        var visible = false
        var dismissals = 0
        val menuBack = object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() {
                visible = false
                dismissals++
                isEnabled = false
            }
        }
        dispatcher.addCallback(object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                visible = true
                menuBack.isEnabled = true
            }
        })
        dispatcher.addCallback(menuBack)
        val dispatch = { dispatcher.onBackPressed() }

        assertTrue(handler.handle(back(KeyEvent.ACTION_DOWN), true, dispatch))
        assertFalse(visible)
        for (repeat in 1..3) {
            assertTrue(handler.handle(back(KeyEvent.ACTION_DOWN, repeats = repeat), true, dispatch))
        }
        assertTrue(handler.handle(back(KeyEvent.ACTION_UP), true, dispatch))
        assertTrue(visible)
        assertEquals(0, dismissals)
        // A duplicate release must not reach the newly enabled menu Back callback.
        assertTrue(handler.handle(back(KeyEvent.ACTION_UP), true, dispatch))
        assertTrue(visible)

        handler.handle(back(KeyEvent.ACTION_DOWN, downTime = 200L), true, dispatch)
        handler.handle(back(KeyEvent.ACTION_UP, downTime = 200L), true, dispatch)
        assertFalse(visible)
        assertEquals(1, dismissals)
    }

    @Test
    fun legacyEventBusReceivesOneActionForEntirePress() {
        var actions = 0
        val dispatch = { actions++; Unit }
        handler.handle(back(KeyEvent.ACTION_DOWN), true, dispatch)
        handler.handle(back(KeyEvent.ACTION_DOWN, repeats = 1), true, dispatch)
        handler.handle(back(KeyEvent.ACTION_UP), true, dispatch)
        assertEquals(1, actions)
    }

    @Test
    fun cancelledGestureDoesNotOpenMenu() {
        var actions = 0
        val dispatch = { actions++; Unit }
        handler.handle(back(KeyEvent.ACTION_DOWN), true, dispatch)
        assertTrue(handler.handle(back(KeyEvent.ACTION_UP, flags = KeyEvent.FLAG_CANCELED), true, dispatch))
        assertEquals(0, actions)
    }

    @Test
    fun unmatchedReleaseAndRepeatDoNotToggleVisibility() {
        var actions = 0
        val dispatch = { actions++; Unit }
        handler.handle(back(KeyEvent.ACTION_DOWN, repeats = 2), true, dispatch)
        handler.handle(back(KeyEvent.ACTION_UP), true, dispatch)
        handler.handle(back(KeyEvent.ACTION_DOWN), true, dispatch)
        handler.handle(back(KeyEvent.ACTION_UP, downTime = 200L), true, dispatch)
        assertEquals(0, actions)
    }

    @Test
    fun leavingGameDiscardsPendingPressAndPreservesNormalBackRouting() {
        var actions = 0
        val dispatch = { actions++; Unit }
        handler.handle(back(KeyEvent.ACTION_DOWN), true, dispatch)
        assertFalse(handler.handle(back(KeyEvent.ACTION_UP), false, dispatch))
        handler.handle(back(KeyEvent.ACTION_UP), true, dispatch)
        assertEquals(0, actions)
    }

    @Test
    fun otherKeysRemainAvailableToGame() {
        assertFalse(handler.handle(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A), true) {
            error("Non-Back key dispatched Back")
        })
    }
}
