package com.winlator.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * 1.2.23: the touch mouse-move ticker runs only while a move binding is held. Until 1.2.22 the timer started with the on-screen
 * controls and woke about 62 times a second for the whole session (idleSessionWokeTheTimerEveryRefresh shows the old shape).
 */
class MouseMoveTickerTest {
    private class FakeSource : MouseMoveTicker.Source {
        @Volatile var held = false
        val moves = AtomicInteger()
        val checks = AtomicInteger()
        override fun moving(): Boolean { checks.incrementAndGet(); return held }
        override fun move() { moves.incrementAndGet() }
    }

    private fun waitFor(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val end = System.nanoTime() + timeoutMs * 1_000_000
        while (System.nanoTime() < end) { if (condition()) return true; Thread.sleep(2) }
        return condition()
    }

    @Test
    fun idleSessionWokeTheTimerEveryRefresh() {
        // The 1.2.22 shape: a Timer started at attach that skips zero deltas but never stops.
        val wakes = AtomicInteger()
        val timer = java.util.Timer(true)
        timer.schedule(object : java.util.TimerTask() { override fun run() { wakes.incrementAndGet() } }, 0, MouseMoveTicker.PERIOD_MS)
        Thread.sleep(500)
        timer.cancel()
        assertTrue("old timer woke ${wakes.get()} times in 0.5 s with nothing held", wakes.get() >= 20)
    }

    @Test
    fun withNothingHeldTheTickerStopsAfterOneTick() {
        val source = FakeSource()
        val ticker = MouseMoveTicker(source)
        ticker.start()   // what attach / setXServer do
        assertTrue(waitFor(1000) { !ticker.running() })
        val checks = source.checks.get()
        Thread.sleep(500)
        assertTrue("idle wake-ups in 0.5 s: ${source.checks.get() - checks}", source.checks.get() - checks == 0)
        assertEquals(0, source.moves.get())
    }

    @Test
    fun aHeldBindingMovesAtTheTickRateAndReleaseStopsIt() {
        val source = FakeSource()
        val ticker = MouseMoveTicker(source)
        source.held = true
        ticker.start()
        Thread.sleep(500)
        val moved = source.moves.get()
        assertTrue("moves in 0.5 s: $moved", moved in 20..40)
        source.held = false
        assertTrue(waitFor(1000) { !ticker.running() })
        val after = source.moves.get()
        Thread.sleep(100)
        assertEquals(after, source.moves.get())
        // The next press starts it again.
        source.held = true
        ticker.start()
        assertTrue(waitFor(1000) { source.moves.get() > after + 3 })
        assertTrue(ticker.running())
        ticker.stop()
        assertFalse(ticker.running())
    }

    @Test
    fun aPressWhileTheTickerStopsIsNeverLost() {
        // A press (offset set, then start()) racing the ticker's own stop: afterwards it must be running whenever a binding is held.
        repeat(300) {
            val source = FakeSource()
            val ticker = MouseMoveTicker(source, 1)
            ticker.start()
            Thread.sleep((it % 3).toLong())
            source.held = true
            ticker.start()
            assertTrue("round $it: held but the ticker stopped", waitFor(200) { source.moves.get() > 0 })
            ticker.stop()
        }
    }
}
