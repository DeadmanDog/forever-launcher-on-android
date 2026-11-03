package com.winlator.xserver.extensions

import java.util.concurrent.atomic.AtomicLong
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The 1.3.19 defect in numbers: with "Safe buffer hand-back" on, a buffer came back to the game the moment the renderer was done
 * with it, past the FPS limiter, so a 60 FPS limit showed ~93. The fix hands the buffer to the same pacer a never-held buffer uses
 * (PresentExtension.sendIdleFromRenderer). This models the game (three buffers, 93 frames/s of work), a renderer that finishes a frame
 * 1 ms after the Present, and both ways of giving the buffer back, with the production PresentFramePacer on a manual clock.
 */
class HeldPresentPacingTest {
    private val time = AtomicLong(0)

    private fun idlePerSecond(limit: Int, throughPacer: Boolean): Double {
        time.set(1_000_000_000L)
        var free = 3
        val released = mutableListOf<Long>()
        val pacer = PresentFramePacer<Int>({ free++; released += time.get() }, { time.get() }, false)
        pacer.use { p ->
            p.setFrameRateLimit(limit)
            val gpuDone = ArrayDeque<Long>()
            var nextProduce = time.get()
            val end = time.get() + 30_000_000_000L
            while (time.get() < end) {
                val now = time.get()
                if (free > 0 && now >= nextProduce) {
                    free--; nextProduce = now + 1_000_000_000L / 93
                    gpuDone.addLast(now + 1_000_000L)
                }
                while (gpuDone.isNotEmpty() && gpuDone.first() <= now) {
                    gpuDone.removeFirst()
                    if (throughPacer && limit > 0) p.schedule(1, 0, false) else { free++; released += now }
                }
                p.drainDue()
                time.addAndGet(100_000L)
            }
        }
        val from = 1_000_000_000L + 10_000_000_000L
        return released.count { it >= from } / 20.0
    }

    @Test fun aHeldBufferGivenBackDirectlyIgnoresTheLimit() {
        assertEquals("the 1.3.19 behaviour", 93.0, idlePerSecond(60, throughPacer = false), 2.0)
    }

    @Test fun aHeldBufferGivenBackThroughThePacerKeepsTheLimit() {
        assertEquals(60.0, idlePerSecond(60, throughPacer = true), 1.5)
        assertEquals(30.0, idlePerSecond(30, throughPacer = true), 1.0)
    }

    @Test fun withoutALimitTheBufferComesBackAtOnce() {
        assertEquals(93.0, idlePerSecond(0, throughPacer = true), 2.0)
    }
}
