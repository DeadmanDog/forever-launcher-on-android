package com.winlator.math

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 1.2.25: touch / trackpad / look-through / captured-mouse deltas carry their fraction instead of Mathf.roundPoint on every event.
 * Each test also runs the 1.2.24 rounding on the same input to show what it did.
 */
class SubpixelAccumulatorTest {
    private fun old(deltas: List<Float>) = deltas.map { Mathf.roundPoint(it) }
    private fun new(deltas: List<Float>): List<Int> { val a = SubpixelAccumulator(); return deltas.map { a.take(it) } }

    @Test
    fun aRestingFingersSensorNoiseNoLongerMovesTheCursor() {
        // +-0.3 px back and forth: the finger does not move.
        val noise = List(60) { if (it % 2 == 0) 0.3f else -0.3f }
        val o = old(noise); val n = new(noise)
        assertEquals("1.2.24 jumped a pixel on every event", 60, o.count { it != 0 })
        assertEquals(0, n.count { it != 0 })
        var pos = 0; var maxAway = 0
        for (d in n) { pos += d; maxAway = maxOf(maxAway, kotlin.math.abs(pos)) }
        assertEquals(0, maxAway)
    }

    @Test
    fun theTotalOfASlowDragIsExact() {
        val slow = List(100) { 0.25f }   // 25 px in quarter-pixel steps
        assertEquals("1.2.24 quadrupled it", 100, old(slow).sum())
        assertEquals(25, new(slow).sum())
    }

    @Test
    fun aSensitivityBelowOneWorksForSlowDrags() {
        // 1 px finger steps at sensitivity 0.5: half the distance.
        val steps = List(40) { 1f * 0.5f }
        assertEquals("1.2.24: sensitivity 0.5 moved as far as 1.0", 40, old(steps).sum())
        assertEquals(20, new(steps).sum())
    }

    @Test
    fun fastMovesAndDirectionChangesStayExact() {
        val moves = listOf(7.6f, -3.2f, 0.4f, 12.9f, -0.7f, -25.1f, 0.05f, 0.05f, 9.99f)
        val n = new(moves)
        assertEquals(moves.sum().toDouble(), n.sum().toDouble(), 1.0)
        assertTrue(n.zip(moves).all { (px, d) -> kotlin.math.abs(px - d) < 2.0f })
        assertEquals(0, SubpixelAccumulator().take(Float.NaN))
    }

    @Test
    fun aSmallStickDeflectionMovesTheCursorAtItsSpeed() {
        // Stick / on-screen stick bound to mouse movement: offset x 10 x cursor speed per 60 Hz tick, one second.
        fun oldTicks(perTick: Float) = List(60) { perTick.toInt() }.sum()
        fun newTicks(perTick: Float): Int { val a = SubpixelAccumulator(); return List(60) { a.take(perTick) }.sum() }
        assertEquals("1.2.24: 0.08 deflection did not move at all", 0, oldTicks(0.08f * 10))
        assertTrue(newTicks(0.08f * 10) in 47..48)
        assertEquals("1.2.24: 0.15 deflection moved like 0.10", 60, oldTicks(0.15f * 10))
        assertTrue(newTicks(0.15f * 10) in 89..90)
    }
}
