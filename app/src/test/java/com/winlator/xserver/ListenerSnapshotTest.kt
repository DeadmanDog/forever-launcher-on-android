package com.winlator.xserver

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class ListenerSnapshotTest {
    private fun listeners() = ListenerSnapshot<String> { size -> arrayOfNulls<String>(size) }

    @Test fun dispatchRetainsReverseOrderAndStableSnapshotDuringRemoval() {
        val listeners = listeners()
        listeners.add("first")
        listeners.add("second")
        val inFlight = listeners.snapshot()
        listeners.remove("second")
        assertArrayEquals(arrayOf("second", "first"), inFlight.reversedArray())
        assertArrayEquals(arrayOf("first"), listeners.snapshot())
    }

    @Test fun inputAndFrameEventsReuseSnapshotWithoutAllocating() {
        val listeners = listeners()
        listeners.add("renderer")
        val snapshot = listeners.snapshot()
        repeat(10000) { assertSame(snapshot, listeners.snapshot()) }
    }

    @Test fun removalPreservesArrayListDuplicateSemantics() {
        val listeners = listeners()
        listeners.add("renderer")
        listeners.add("other")
        listeners.add("renderer")
        listeners.remove("renderer")
        assertArrayEquals(arrayOf("other", "renderer"), listeners.snapshot())
        val snapshot = listeners.snapshot()
        listeners.remove("absent")
        assertSame(snapshot, listeners.snapshot())
    }

    @Test fun releaseAndDispatchCanRunConcurrently() {
        val listeners = listeners()
        listeners.add("permanent")
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val updates = executor.submit {
                start.await()
                repeat(20000) { listeners.add("renderer"); listeners.remove("renderer") }
            }
            val dispatch = executor.submit {
                start.await()
                repeat(20000) {
                    val snapshot = listeners.snapshot()
                    for (i in snapshot.size - 1 downTo 0) assertNotNull(snapshot[i])
                    assertEquals("permanent", snapshot[0])
                }
            }
            start.countDown()
            updates.get(5, TimeUnit.SECONDS)
            dispatch.get(5, TimeUnit.SECONDS)
        } finally { executor.shutdownNow() }
        assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS))
    }
}
