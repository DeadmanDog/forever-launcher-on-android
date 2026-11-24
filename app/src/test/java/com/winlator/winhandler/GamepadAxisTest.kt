package com.winlator.winhandler

import org.junit.Assert.assertEquals
import org.junit.Test

/** 1.2.26: stick values past -1..1 no longer wrap to the opposite direction in the shared gamepad state. */
class GamepadAxisTest {
    private fun old(v: Float) = (v * 32767).toInt().toShort()

    @Test
    fun aStickReportedPastItsRangeKeepsItsDirection() {
        assertEquals("1.2.25 wrapped full right to nearly full left", (-32114).toShort(), old(1.02f))
        assertEquals(32767.toShort(), GamepadAxis.toShort(1.02f))
        assertEquals((32114).toShort(), old(-1.02f))
        assertEquals((-32767).toShort(), GamepadAxis.toShort(-1.02f))
    }

    @Test
    fun inRangeValuesAreUnchanged() {
        for (v in listOf(-1f, -0.5f, -0.01f, 0f, 0.25f, 0.999f, 1f)) {
            assertEquals(v * 32767, GamepadAxis.toShort(v).toFloat(), 1.0f)
        }
        assertEquals(0.toShort(), GamepadAxis.toShort(Float.NaN))
    }
}
