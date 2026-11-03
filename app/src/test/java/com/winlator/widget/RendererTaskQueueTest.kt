package com.winlator.widget

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

class RendererTaskQueueTest {
    @Test fun executesEventsBeforeRelease() {
        val executor = Executors.newSingleThreadExecutor()
        val queue = RendererTaskQueue(executor)
        val ran = CountDownLatch(1)
        try {
            queue.execute { ran.countDown() }
            assertTrue(ran.await(2, TimeUnit.SECONDS))
        } finally { queue.close() }
        assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS))
    }

    @Test fun releaseCancelsQueuedEventsAndShutsDownOwnedThread() {
        val executor = Executors.newSingleThreadExecutor()
        val queue = RendererTaskQueue(executor)
        val started = CountDownLatch(1)
        val finish = CountDownLatch(1)
        val callbacks = AtomicInteger()
        try {
            queue.execute {
                started.countDown()
                while (finish.count > 0) {
                    try { finish.await() } catch (_: InterruptedException) { }
                }
            }
            assertTrue(started.await(2, TimeUnit.SECONDS))
            queue.execute { callbacks.incrementAndGet() }
            queue.close()
            queue.close()
            queue.execute { callbacks.incrementAndGet() }
        } finally {
            finish.countDown()
            queue.close()
        }
        assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS))
        assertEquals(0, callbacks.get())
    }

    @Test fun releasedViewDoesNotAllocateOrRunLateTasks() {
        val executor = Executors.newSingleThreadExecutor()
        val queue = RendererTaskQueue(executor)
        queue.close()
        repeat(1000) { queue.execute { fail("Released renderer event ran") } }
        assertTrue(executor.isShutdown)
        assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS))
    }

    @Test fun cleanupQueuedBehindABlockedTaskStillRunsOnceWhenTheViewIsReleased() {
        val executor = Executors.newSingleThreadExecutor()
        val queue = RendererTaskQueue(executor)
        val started = CountDownLatch(1)
        val finish = CountDownLatch(1)
        val cleanups = AtomicInteger()
        try {
            queue.execute {
                started.countDown()
                while (finish.count > 0) { try { finish.await() } catch (_: InterruptedException) { } }
            }
            assertTrue(started.await(2, TimeUnit.SECONDS))
            queue.executeCleanup { cleanups.incrementAndGet() }
            queue.close()
        } finally { finish.countDown() }
        assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS))
        assertEquals("the pending cleanup ran, exactly once", 1, cleanups.get())
    }

    @Test fun cleanupAfterReleaseRunsOnTheCaller() {
        val queue = RendererTaskQueue(Executors.newSingleThreadExecutor())
        queue.close()
        val cleanups = AtomicInteger()
        queue.executeCleanup { cleanups.incrementAndGet() }
        assertEquals(1, cleanups.get())
    }

    @Test fun aFailingCleanupDoesNotBlockTheNextOne() {
        val queue = RendererTaskQueue(Executors.newSingleThreadExecutor())
        val ran = CountDownLatch(1)
        queue.close()
        queue.executeCleanup { throw IllegalStateException("boom") }
        queue.executeCleanup { ran.countDown() }
        assertTrue(ran.await(1, TimeUnit.SECONDS))
    }
}
