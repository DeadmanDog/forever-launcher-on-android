package com.winlator.inputcontrols

import com.winlator.widget.InputControlsView
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * 1.2.26 (input review): a latched toggle (e.g. the FPS profile's L SHIFT) or an L3/R3 kept pressed had no pointer, so nothing
 * released its binding when the controls were hidden or the profile replaced: Shift (or the right mouse button, or W) stayed held
 * in the game. releaseLatchedButton lets go of it; ACTION_CANCEL still keeps the latch (ControlElementCancellationTest).
 */
@RunWith(RobolectricTestRunner::class)
class ControlElementLatchReleaseTest {
    private fun toggle(view: InputControlsView, binding: Binding) = ControlElement(view).apply {
        setX(50); setY(50); setBindingAt(0, binding); setToggleSwitch(true)
    }

    @Test
    fun `a latched toggle is released when its element goes away`() {
        val view = mockk<InputControlsView>(relaxed = true)
        every { view.snappingSize } returns 10
        val element = toggle(view, Binding.KEY_SHIFT_L)
        assertTrue(element.handleTouchDown(1, 50f, 50f))
        element.handleTouchUp(1)
        assertTrue("latched", element.isSelected)
        verify(exactly = 1) { view.handleInputEvent(Binding.KEY_SHIFT_L, true) }
        verify(exactly = 0) { view.handleInputEvent(Binding.KEY_SHIFT_L, false) }

        assertTrue(element.releaseLatchedButton())
        assertFalse(element.isSelected)
        verify(exactly = 1) { view.handleInputEvent(Binding.KEY_SHIFT_L, false) }
        assertFalse("nothing left to release", element.releaseLatchedButton())
    }

    @Test
    fun `an ordinary button and an unlatched toggle are left alone`() {
        val view = mockk<InputControlsView>(relaxed = true)
        every { view.snappingSize } returns 10
        val plain = ControlElement(view).apply { setX(50); setY(50); setBindingAt(0, Binding.KEY_A) }
        assertFalse(plain.releaseLatchedButton())
        assertFalse(toggle(view, Binding.KEY_W).releaseLatchedButton())
        verify(exactly = 0) { view.handleInputEvent(any<Binding>(), any()) }
    }
}
