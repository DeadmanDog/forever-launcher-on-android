package com.winlator.inputcontrols

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GamepadStateTest {
    @Test
    fun `copying target stick preserves centered other stick and held controls`() {
        val profileState = GamepadState().apply {
            thumbLX = 0.75f
            thumbLY = -0.5f
        }
        val physicalState = GamepadState().apply {
            thumbLX = 0f
            thumbLY = 0f
            triggerL = 0.6f
            dpad[0] = true
            setPressed(ExternalController.IDX_BUTTON_A.toInt(), true)
        }

        profileState.thumbRX = 0.2f
        profileState.thumbRY = -0.3f
        physicalState.copyThumbstick(profileState, true)

        assertEquals(0f, physicalState.thumbLX, 0f)
        assertEquals(0f, physicalState.thumbLY, 0f)
        assertEquals(0.2f, physicalState.thumbRX, 0f)
        assertEquals(-0.3f, physicalState.thumbRY, 0f)
        assertEquals(0.6f, physicalState.triggerL, 0f)
        assertTrue(physicalState.dpad[0])
        assertTrue(physicalState.isPressed(ExternalController.IDX_BUTTON_A.toInt()))
    }
}
