package com.winlator.renderer

import com.winlator.xserver.extensions.PresentIdleHolds
import org.junit.Assert.assertEquals
import org.junit.Test

/** 1.2.1: a buffer on the scanout layer is handed back when the next one replaces it, not when it is handed to the display. */
class ScanoutIdleHandTest {
    @Test fun aScanoutBufferIsFreeOnlyOnceTheNextOneReplacesIt() {
        val holds = PresentIdleHolds(60_000)
        val hand = ScanoutIdleHand()
        val idle = mutableListOf<String>()
        fun present(name: String) {
            val token = holds.hold { idle += name }
            val replaced = hand.replace(token)
            if (replaced != 0L) holds.release(replaced)
        }
        present("A")
        assertEquals(emptyList<String>(), idle)          // A is on screen: the game must not draw into it
        present("B")
        assertEquals(listOf("A"), idle)                  // B replaced A
        present("C")
        assertEquals(listOf("A", "B"), idle)
        holds.release(hand.take())                       // scanout ends: the last one is free too
        assertEquals(listOf("A", "B", "C"), idle)
        assertEquals(0L, hand.take())
    }
}
