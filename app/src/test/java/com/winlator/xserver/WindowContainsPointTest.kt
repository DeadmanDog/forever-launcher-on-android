package com.winlator.xserver

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.random.Random

/** 2026-11-12 review: containsPoint no longer allocates; it must answer exactly as the array-based version did. */
class WindowContainsPointTest {
    private fun old(w: Window, x: Short, y: Short): Boolean {
        val p = w.rootPointToLocal(x, y)
        return p[0] >= 0 && p[1] >= 0 && p[0] < w.width && p[1] < w.height
    }

    @Test fun sameAnswerAsTheArrayVersionIncludingShortWraparound() {
        val root = Window(1, null, 0, 0, 1344, 608, null)
        val mid = Window(2, null, 100, 50, 800, 400, null).also { root.addChild(it) }
        val leaf = Window(3, null, -20, 30, 300, 200, null).also { mid.addChild(it) }
        val far = Window(4, null, 32000, -32000, 2000, 2000, null).also { root.addChild(it) }
        val rnd = Random(7)
        for (w in listOf(root, mid, leaf, far)) repeat(20_000) {
            val x = rnd.nextInt(Short.MIN_VALUE.toInt(), Short.MAX_VALUE + 1).toShort()
            val y = rnd.nextInt(Short.MIN_VALUE.toInt(), Short.MAX_VALUE + 1).toShort()
            assertEquals("${w.id} $x,$y", old(w, x, y), w.containsPoint(x, y))
        }
        for ((x, y) in listOf(80 to 80, 79 to 79, 80 + 299 to 80 + 199, 80 + 300 to 80)) {
            assertEquals(old(leaf, x.toShort(), y.toShort()), leaf.containsPoint(x.toShort(), y.toShort()))
        }
    }
}
