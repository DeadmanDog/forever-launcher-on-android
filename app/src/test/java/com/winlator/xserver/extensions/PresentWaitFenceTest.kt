package com.winlator.xserver.extensions

import org.junit.Assert.*
import org.junit.Test
import com.winlator.xserver.errors.BadFence

class PresentWaitFenceTest {
    @Test fun untriggeredPresentWaitsWithoutBlockingOtherClientRequests() {
        val table = SyncFenceTable<Any>(); val client = Any(); var renders = 0
        table.create(1, client, false)
        assertTrue(table.afterTrigger(client, 1, { renders++ }, {}))
        assertEquals(0, renders)
        table.create(2, client, false) // client continues requests, including TriggerFence
        table.trigger(1, false); assertEquals(1, renders)
        table.trigger(1, false); assertEquals(1, renders)
    }
    @Test fun triggeredFenceNeedsNoQueue() {
        val table = SyncFenceTable<Any>(); val client = Any()
        table.create(1, client, true)
        assertFalse(table.afterTrigger(client, 1, { fail("caller submits immediately") }, {}))
    }
    @Test fun missingFenceIsRejected() {
        try { SyncFenceTable<Any>().afterTrigger(Any(), 1, {}, {}); fail() }
        catch (_: BadFence) {}
    }
    @Test fun destroyedFenceCancelsAndReuseDoesNotRenderOldPixmap() {
        val table = SyncFenceTable<Any>(); val client = Any(); var renders = 0; var cancelled = 0
        table.create(1, client, false)
        table.afterTrigger(client, 1, { renders++ }, { cancelled++ })
        table.destroy(1); table.create(1, client, false); table.trigger(1, false)
        assertEquals(0, renders); assertEquals(1, cancelled)
    }
    @Test fun disconnectDiscardsPresentRegistrations() {
        val table = SyncFenceTable<Any>(); val owner = Any(); val client = Any(); var renders = 0
        table.create(1, owner, false); table.afterTrigger(client, 1, { renders++ }, { fail() })
        table.disconnect(client); table.trigger(1, false); assertEquals(0, renders)
    }
}
