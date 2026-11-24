package com.winlator.xserver

import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

class WindowPackedPointTest {
    @Test fun packedCoordinatesMatchLegacySignedShortSemantics() {
        val root = Window(1, null, -32000, 31000, 1344, 608, null)
        val mid = Window(2, null, 32000, -31000, 800, 400, null).also { root.addChild(it) }
        val leaf = Window(3, null, 100, -200, 300, 200, null).also { mid.addChild(it) }
        val random = Random(291)
        for (window in listOf(root, mid, leaf)) repeat(20000) {
            val x = random.nextInt().toShort()
            val y = random.nextInt().toShort()
            var expectedX = x
            var expectedY = y
            var current: Window? = window
            while (current != null) {
                expectedX = (expectedX - current.x).toShort()
                expectedY = (expectedY - current.y).toShort()
                current = current.parent
            }
            val packed = window.rootPointToLocalPacked(x, y)
            assertEquals(expectedX, (packed ushr 16).toShort())
            assertEquals(expectedY, packed.toShort())
            assertArrayEquals(shortArrayOf(expectedX, expectedY), window.rootPointToLocal(x, y))
        }
    }

    @Test fun legacyArrayCallersKeepIndependentMutableResults() {
        val window = Window(1, null, 10, -20, 300, 200, null)
        val a = window.rootPointToLocal(50, 60)
        a[0] = 999
        assertArrayEquals(shortArrayOf(40, 80), window.rootPointToLocal(50, 60))
        val packed = window.rootPointToLocalPacked(50, 60)
        assertEquals(40, (packed ushr 16).toShort().toInt())
        assertEquals(80, packed.toShort().toInt())
    }
}
