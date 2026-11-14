package com.winlator.xconnector

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** 2026-11-12 review: control tasks share one eventfd write per wake-up, and none is ever left without one. */
class WakeCoalescerTest {
    @Test fun manyTasksBeforeTheConsumerRunsCostOneSignal() {
        val wake = WakeCoalescer()
        assertTrue(wake.shouldSignal())
        repeat(5) { assertFalse(wake.shouldSignal()) }
        wake.consumed()
        assertTrue("a task after the drain started signals again", wake.shouldSignal())
    }

    /** A consumer thread modelled on XConnectorEpoll: wait for a signal, mark it consumed, drain everything. */
    @Test fun noTaskIsLostUnderConcurrentProducers() {
        val wake = WakeCoalescer()
        val queue = ConcurrentLinkedQueue<Int>()
        val signals = Semaphore(0)
        val total = 4 * 20_000
        val seen = java.util.concurrent.atomic.AtomicInteger()
        val done = CountDownLatch(1)
        var signalCount = 0
        val consumer = thread {
            while (seen.get() < total) {
                if (!signals.tryAcquire(2, TimeUnit.SECONDS)) break
                signals.drainPermits()
                wake.consumed()
                while (queue.poll() != null) seen.incrementAndGet()
            }
            done.countDown()
        }
        val producers = (0 until 4).map {
            thread {
                repeat(20_000) { n ->
                    queue.add(n)
                    if (wake.shouldSignal()) { synchronized(this@WakeCoalescerTest) { signalCount++ }; signals.release() }
                }
            }
        }
        producers.forEach { it.join() }
        assertTrue("consumer finished", done.await(10, TimeUnit.SECONDS))
        consumer.join()
        assertEquals(total, seen.get())
        assertTrue("fewer signals than tasks: $signalCount", signalCount < total)
    }
}
