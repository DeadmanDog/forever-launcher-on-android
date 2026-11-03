package com.winlator.inputcontrols

import org.junit.Assert.*
import org.junit.Test

class RetiredGyroBindingTest {
    @Test fun oldModifierIsIgnoredAndOrdinaryBindingsRemain() {
        assertEquals(Binding.NONE, Binding.fromString("GYRO_MODIFIER"))
        assertEquals(Binding.GAMEPAD_BUTTON_A, Binding.fromString("GAMEPAD_BUTTON_A"))
        assertEquals(Binding.SHOW_KEYBOARD, Binding.fromString("SHOW_KEYBOARD"))
        assertTrue(ControlElement.Type.names().contains("D-PAD")); assertFalse(ControlElement.Type.names().contains("SHOOTER-MODE"))
    }
}
