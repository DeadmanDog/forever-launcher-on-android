package com.winlator.xserver

import com.winlator.xserver.extensions.PresentIdleHolds
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PresentIdleHoldsTest {
    @Test fun releaseRunsTheActionOnceAndNotBefore() {
        val holds = PresentIdleHolds(60_000)
        val runs = AtomicInteger()
        val token = holds.hold { runs.incrementAndGet() }
        assertNotEquals(0L, token); assertEquals(1, holds.size()); assertEquals(0, runs.get())
        holds.release(token)
        assertEquals(1, runs.get()); assertEquals(0, holds.size())
        holds.release(token)                       // the renderer and the time limit may both report it
        assertEquals(1, runs.get())
    }
    @Test fun anUnknownTokenIsIgnored() {
        val holds = PresentIdleHolds(60_000)
        holds.release(12345L)
        assertEquals(0, holds.size())
    }
    @Test fun theTimeLimitReleasesAStalledHold() {
        val holds = PresentIdleHolds(50)
        val done = CountDownLatch(1)
        val runs = AtomicInteger()
        holds.hold { runs.incrementAndGet(); done.countDown() }
        assertTrue("the client is not left waiting for ever", done.await(5, TimeUnit.SECONDS))
        assertEquals(1, runs.get()); assertEquals(0, holds.size())
    }
    @Test fun aReleaseBeforeTheLimitCancelsTheLimit() {
        val holds = PresentIdleHolds(150)
        val runs = AtomicInteger()
        holds.release(holds.hold { runs.incrementAndGet() })
        Thread.sleep(400)
        assertEquals("the action does not run a second time when the limit passes", 1, runs.get())
    }
    @Test fun releaseAllRunsEveryRemainingAction() {
        val holds = PresentIdleHolds(60_000)
        val runs = AtomicInteger()
        repeat(5) { holds.hold { runs.incrementAndGet() } }
        holds.releaseAll()
        assertEquals(5, runs.get()); assertEquals(0, holds.size())
    }
    @Test fun tokensAreDistinctAndConcurrentUseRunsEachActionOnce() {
        val holds = PresentIdleHolds(60_000)
        val runs = AtomicInteger()
        val tokens = (1..200).map { holds.hold { runs.incrementAndGet() } }
        assertEquals(200, tokens.toSet().size)
        val threads = (0 until 4).map { t -> Thread { tokens.forEach { holds.release(it) } }.also { it.start() } }
        threads.forEach { it.join() }
        assertEquals(200, runs.get())
    }
    @Test fun reachingTheStallLimitIsReportedOnceAndAReleaseIsNot() {
        val expired = AtomicInteger()
        val holds = PresentIdleHolds(60) { expired.incrementAndGet() }
        val runs = AtomicInteger()
        holds.hold { runs.incrementAndGet() }                   // never released: the stall limit ends it
        holds.release(holds.hold { runs.incrementAndGet() })    // released in time: not a stall
        Thread.sleep(400)
        assertEquals(2, runs.get())
        assertEquals("only the stalled hold is reported", 1, expired.get())
    }
}
