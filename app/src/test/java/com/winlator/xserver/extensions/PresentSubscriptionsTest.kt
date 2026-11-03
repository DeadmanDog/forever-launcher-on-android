package com.winlator.xserver.extensions

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class PresentSubscriptionsTest {
    @Test fun unchangedSubscriptionsReuseSnapshotWithoutFrameAllocations() {
        val s = PresentSubscriptions<Any, Any>(); val w = Any(); val c = Any()
        s.put(1, w, c, 3)
        val before = s.snapshot()
        repeat(10000) { assertSame(before, s.snapshot()) }
        s.put(1, w, c, 3)
        assertSame(before, s.snapshot())
    }

    @Test fun changedMaskPublishesImmutableSnapshotAndPreservesEventIdOrder() {
        val s = PresentSubscriptions<Any, Any>(); val w = Any(); val c = Any()
        s.put(9, w, c, 1); s.put(2, w, c, 2)
        val before = s.snapshot(); s.put(9, w, c, 7)
        assertEquals(listOf(2, 9), s.snapshot().map { it.id })
        assertEquals(1L, before.last().mask)
        assertEquals(7L, s.snapshot().last().mask)
    }

    @Test fun disconnectedClientIsRemovedWithoutRemovingOthers() {
        val s = PresentSubscriptions<Any, Any>(); val w = Any(); val gone = Any(); val live = Any()
        s.put(1, w, gone, 1); s.put(2, w, gone, 2); s.put(3, w, live, 3)
        s.removeClient(gone)
        assertNull(s.get(1)); assertNull(s.get(2)); assertSame(live, s.snapshot().single().client)
    }

    @Test fun destroyedWindowReleasesEverySubscriptionForThatWindow() {
        val s = PresentSubscriptions<Any, Any>(); val gone = Any(); val live = Any(); val c = Any()
        s.put(1, gone, c, 1); s.put(2, gone, Any(), 2); s.put(3, live, c, 3)
        s.removeWindow(gone)
        assertEquals(3, s.snapshot().single().id)
        assertNull(s.get(1)); assertNull(s.get(2))
    }

    @Test fun repeatedClientLifetimesDoNotAccumulateStaleEntries() {
        val s = PresentSubscriptions<Any, Any>(); val w = Any()
        repeat(5000) { id -> val c = Any(); s.put(id, w, c, 1); s.removeClient(c); assertEquals(0, s.snapshot().size); assertNull(s.get(id)) }
    }

    @Test fun zeroMaskAndCloseLeaveNoRetainedSubscriptions() {
        val s = PresentSubscriptions<Any, Any>(); val w = Any(); val c = Any()
        s.put(1, w, c, 0); assertTrue(s.snapshot().isEmpty())
        s.put(1, w, c, 1); s.put(1, w, c, 0); assertTrue(s.snapshot().isEmpty())
        s.put(2, w, c, 1); s.clear(); assertTrue(s.snapshot().isEmpty()); assertNull(s.get(2))
    }

    @Test fun blockedDispatchDoesNotHoldTheSubscriptionMutationMonitor() {
        val s = PresentSubscriptions<Any, Any>(); val w = Any(); val c = Any()
        s.put(1, w, c, 1)
        val entered = CountDownLatch(1); val finish = CountDownLatch(1)
        val dispatch = Thread { for (e in s.snapshot()) { entered.countDown(); finish.await(2, TimeUnit.SECONDS) } }
        dispatch.start()
        try {
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            val changed = CountDownLatch(1)
            val writer = Thread { s.removeClient(c); s.put(2, w, Any(), 1); changed.countDown() }
            writer.start()
            assertTrue(changed.await(1, TimeUnit.SECONDS)); writer.join(1000)
            assertEquals(2, s.snapshot().single().id)
        } finally { finish.countDown(); dispatch.join(2000) }
        assertFalse(dispatch.isAlive)
    }
}
