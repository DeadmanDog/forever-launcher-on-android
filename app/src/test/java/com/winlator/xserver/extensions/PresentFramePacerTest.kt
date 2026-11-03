package com.winlator.xserver.extensions

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import org.junit.Assert.*
import org.junit.Test

class PresentFramePacerTest {
    private val time = AtomicLong(1_000_000_000L)
    private val released = mutableListOf<Int>()
    private fun pacer() = PresentFramePacer<Int>({ released.add(it) }, { time.get() }, false)

    @Test fun burstReturnsEveryBufferOnceAtTheConfiguredCadence() {
        pacer().use { p ->
            p.setFrameRateLimit(60)
            repeat(3) { p.schedule(1, it, false) }
            p.drainDue()
            assertEquals(listOf(0), released)
            repeat(2) { n ->
                time.addAndGet(1_000_000_000L / 60)
                p.drainDue()
                assertEquals((0..n + 1).toList(), released)
            }
            p.drainDue()
            assertEquals(listOf(0, 1, 2), released)
        }
    }

    @Test fun aLateFrameIsNotDelayedAgainAndTheNextFrameHasAFreshInterval() {
        pacer().use { p ->
            p.setFrameRateLimit(60)
            p.schedule(1, 1, false)
            time.addAndGet(2_000_000_000L)
            p.drainDue()
            p.schedule(1, 2, false)
            p.schedule(1, 3, false)
            p.drainDue()
            assertEquals(listOf(1, 2), released)
            time.addAndGet(1_000_000_000L / 60)
            p.drainDue()
            assertEquals(listOf(1, 2, 3), released)
        }
    }

    @Test fun disablingTheLimiterReleasesAllHeldBuffersWithoutWaitingForOldDeadlines() {
        pacer().use { p ->
            p.setFrameRateLimit(5)
            repeat(10) { p.schedule(1, it, false) }
            p.setFrameRateLimit(0)
            p.drainDue()
            assertEquals((0 until 10).toList(), released)
            p.schedule(1, 10, false)
            assertEquals((0..10).toList(), released)
        }
    }

    @Test fun aGameBelowTheCapIsNotMadeEvenSlowerByTheLimiter() {
        pacer().use { p ->
            p.setFrameRateLimit(60)
            // 20 ms render time = 50 FPS, already below the configured 60 FPS cap.
            repeat(100) { frame ->
                time.addAndGet(20_000_000L)
                p.schedule(1, frame, false)
                p.drainDue()
                assertEquals(frame + 1, released.size)
            }
        }
    }

    @Test fun disabledLimiterAndNegativeInputNeverCreateDelayedWork() {
        pacer().use { p ->
            p.setFrameRateLimit(-10)
            repeat(20) { p.schedule(1, it, false) }
            assertEquals((0 until 20).toList(), released)
        }
    }

    @Test fun changingTheLimitStartsAFreshTimelineAndReturnsOldBuffers() {
        pacer().use { p ->
            p.setFrameRateLimit(5)
            repeat(3) { p.schedule(1, it, false) }
            p.setFrameRateLimit(120)
            p.drainDue()
            assertEquals(listOf(0, 1, 2), released)
            p.schedule(1, 3, false)
            p.schedule(1, 4, false)
            p.drainDue()
            assertEquals(listOf(0, 1, 2, 3), released)
            time.addAndGet(1_000_000_000L / 120)
            p.drainDue()
            assertEquals(listOf(0, 1, 2, 3, 4), released)
        }
    }

    @Test fun eagerModeReturnsSupersededBuffersAndDoesNotAffectAnotherWindow() {
        pacer().use { p ->
            p.setFrameRateLimit(60)
            p.schedule(1, 1, true)
            p.schedule(2, 2, true)
            p.schedule(1, 3, true)
            p.drainDue()
            assertEquals(listOf(1, 2), released)
            time.addAndGet(1_000_000_000L / 60)
            p.drainDue()
            assertEquals(listOf(1, 2, 3), released)
        }
    }

    @Test fun closeCancelsQueuedAndLateSessionReleases() {
        val p = pacer()
        p.setFrameRateLimit(60)
        p.schedule(1, 1, false)
        p.close()
        time.addAndGet(1_000_000_000L)
        p.drainDue()
        p.schedule(1, 2, false)
        assertTrue(released.isEmpty())
    }

    @Test fun callbackDoesNotHoldTheSchedulerMonitor() {
        val finished = CountDownLatch(1)
        lateinit var p: PresentFramePacer<Int>
        p = PresentFramePacer({
            val setter = Thread { p.setFrameRateLimit(0); finished.countDown() }
            setter.start()
            setter.join(1000)
        }, { time.get() }, false)
        p.use {
            p.setFrameRateLimit(60)
            p.schedule(1, 1, false)
            time.addAndGet(1_000_000_000L)
            p.drainDue()
            assertEquals(0L, finished.count)
        }
    }

    @Test fun workerUsesNormalPriorityAndSleepsWhenIdleThenTerminatesOnClose() {
        val delivered = CountDownLatch(1)
        val p = PresentFramePacer<Int>({ delivered.countDown() })
        p.setFrameRateLimit(1000)
        p.schedule(1, 1, false)
        try {
            assertTrue(delivered.await(2, TimeUnit.SECONDS))
            val field = PresentFramePacer::class.java.getDeclaredField("worker").apply { isAccessible = true }
            val worker = field.get(p) as Thread
            assertEquals(Thread.NORM_PRIORITY, worker.priority)
            // Acquire the monitor to ensure the worker has returned from its callback.
            p.setFrameRateLimit(0)
            val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(1)
            while (worker.state != Thread.State.WAITING && System.nanoTime() < until) Thread.yield()
            assertEquals(Thread.State.WAITING, worker.state)
            p.close()
            worker.join(1000)
            assertFalse(worker.isAlive)
        } finally {
            p.close()
        }
    }
}
