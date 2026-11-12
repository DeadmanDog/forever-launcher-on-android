package com.winlator.xserver

import org.junit.Assert.*
import org.junit.Test

class PointerButtonStateTest {
    @Test fun routedButtonKeepsThePressedStateSoTouchpadCanReleaseIt() {
        val pointer = Pointer(null)
        var localEvents = 0
        pointer.addOnPointerMotionListener(object : Pointer.OnPointerMotionListener {
            override fun onPointerButtonPress(button: Pointer.Button) { localEvents++ }
            override fun onPointerButtonRelease(button: Pointer.Button) { localEvents++ }
        })
        repeat(3) {
            assertTrue(pointer.updateButtonState(Pointer.Button.BUTTON_LEFT, true))
            assertTrue(pointer.isButtonPressed(Pointer.Button.BUTTON_LEFT))
            // This is the gate in TouchpadView.releasePointerButtonLeft.
            if (pointer.isButtonPressed(Pointer.Button.BUTTON_LEFT))
                assertTrue(pointer.updateButtonState(Pointer.Button.BUTTON_LEFT, false))
            assertFalse(pointer.isButtonPressed(Pointer.Button.BUTTON_LEFT))
        }
        assertEquals(0, localEvents) // no duplicate X11/WinHandler dispatch
    }
    @Test fun ordinaryXserverDispatchAndDuplicateSuppressionRemainIntact() {
        val pointer = Pointer(null)
        var presses = 0
        var releases = 0
        pointer.addOnPointerMotionListener(object : Pointer.OnPointerMotionListener {
            override fun onPointerButtonPress(button: Pointer.Button) { presses++ }
            override fun onPointerButtonRelease(button: Pointer.Button) { releases++ }
        })
        pointer.setButton(Pointer.Button.BUTTON_LEFT, true)
        pointer.setButton(Pointer.Button.BUTTON_LEFT, true)
        pointer.setButton(Pointer.Button.BUTTON_LEFT, false)
        pointer.setButton(Pointer.Button.BUTTON_LEFT, false)
        assertEquals(1, presses)
        assertEquals(1, releases)
    }
}
