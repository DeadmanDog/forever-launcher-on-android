package com.winlator.xserver.extensions

import java.util.concurrent.atomic.AtomicLong
import org.junit.Assert.*
import org.junit.Test

class PresentFenceLifetimeTest {
    @Test fun delayedPresentCannotSignalReusedFenceId() {
        val table = SyncFenceTable<Any>()
        val owner = Any()
        table.create(1, owner, false)
        val captured = table.capture(1)
        table.destroy(1)
        table.create(1, owner, false)
        table.triggerCaptured(1, captured)
        assertFalse(table.isTriggered(1))
    }

    @Test fun delayedSignalReleasesOnlyTheOriginalLiveFence() {
        val table = SyncFenceTable<Any>()
        table.create(1, Any(), false)
        var wakes = 0
        table.await(Any(), intArrayOf(1)) { Runnable { wakes++ } }
        table.triggerCaptured(1, table.capture(1))
        assertTrue(table.isTriggered(1)); assertEquals(1, wakes)
    }

    @Test fun missingCaptureCannotSignalAFenceCreatedLater() {
        val table = SyncFenceTable<Any>()
        val missing = table.capture(1)
        table.create(1, Any(), false)
        table.triggerCaptured(1, missing)
        assertFalse(table.isTriggered(1))
    }

    @Test fun disconnectedOwnerInvalidatesPendingPresentSignals() {
        val table = SyncFenceTable<Any>()
        val oldOwner = Any()
        table.create(1, oldOwner, false)
        val old = table.capture(1)
        table.disconnect(oldOwner)
        table.create(1, Any(), false)
        table.triggerCaptured(1, old)
        assertFalse(table.isTriggered(1))
    }

    @Test fun actualPacerCallbackCannotWakeANewFenceWithTheSameId() {
        val table = SyncFenceTable<Any>()
        val time = AtomicLong(1_000_000_000L)
        table.create(1, Any(), false)
        PresentFramePacer<Any>({ table.triggerCaptured(1, it) }, { time.get() }, false).use { p ->
            p.setFrameRateLimit(60)
            p.schedule(1, table.capture(1), false)
            p.schedule(1, table.capture(1), false)
            p.drainDue()
            table.destroy(1); table.create(1, Any(), false)
            var wakes = 0
            table.await(Any(), intArrayOf(1)) { Runnable { wakes++ } }
            time.addAndGet(1_000_000_000L)
            p.drainDue()
            assertFalse(table.isTriggered(1)); assertEquals(0, wakes); assertEquals(1, table.pendingCount())
            table.trigger(1, false)
            assertEquals(1, wakes)
        }
    }
}
