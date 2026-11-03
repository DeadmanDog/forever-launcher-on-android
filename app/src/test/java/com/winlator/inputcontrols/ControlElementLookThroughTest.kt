package com.winlator.inputcontrols

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ControlElementLookThroughTest {
    @Test
    fun `new buttons explicitly disable look-through by default`() {
        val element = ControlElement(null)

        assertEquals(false, element.lookThroughSetting)
        assertFalse(element.isLookThrough)
    }

    @Test
    fun `reset restores the explicit look-through default`() {
        val element = ControlElement(null)
        element.lookThroughSetting = true

        element.setType(ControlElement.Type.BUTTON)

        assertEquals(false, element.lookThroughSetting)
        assertFalse(element.isLookThrough)
    }

    @Test
    fun `profiles that predate look-through load as off`() {
        val element = ControlElement(null)
        element.lookThroughSetting = null

        assertNull(element.lookThroughSetting)
        assertFalse(element.isLookThrough)
    }

    @Test
    fun `explicitly enabled look-through is on`() {
        val element = ControlElement(null)
        element.lookThroughSetting = true

        assertTrue(element.isLookThrough)
    }

    @Test
    fun `radial menu anywhere in combo disables look-through`() {
        val element = ControlElement(null).apply {
            setType(ControlElement.Type.BUTTON)
            lookThroughSetting = true
            setBindingComboAt(
                0,
                BindingCombo.fromBindings(listOf(Binding.OPEN_RADIAL_MENU, Binding.KEY_E)),
            )
        }

        assertFalse(element.isLookThrough)
    }
}
