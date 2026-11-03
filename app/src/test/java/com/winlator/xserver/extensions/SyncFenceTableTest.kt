package com.winlator.xserver.extensions

import com.winlator.xserver.errors.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class SyncFenceTableTest {
    @Test fun awaitReturnsWithoutBlockingAndAnyFenceResumesOnce() {
        val table = SyncFenceTable<Any>(); val owner = Any(); val client = Any()
        table.create(1, owner, false); table.create(2, owner, false)
        var suspended = 0; var resumed = 0
        table.await(client, intArrayOf(1, 2, 1)) { suspended++; Runnable { resumed++ } }
        assertEquals(1, suspended); assertEquals(0, resumed); assertEquals(1, table.pendingCount())
        table.trigger(2, false); table.trigger(1, false)
        assertEquals(1, resumed); assertEquals(0, table.pendingCount())
    }
    @Test fun readyAndEmptyListsNeverSuspendButEveryIdIsValidated() {
        val t = SyncFenceTable<Any>(); val owner = Any(); t.create(1, owner, true)
        t.await(Any(), intArrayOf(1)) { fail("ready fence suspended"); Runnable {} }
        t.await(Any(), intArrayOf()) { fail("empty list suspended"); Runnable {} }
        try { t.await(Any(), intArrayOf(1, 99)) { fail("invalid list suspended"); Runnable {} }; fail("missing fence accepted") }
        catch (e: BadFence) { assertEquals(99, e.data) }
    }
    @Test fun triggerFromAnotherThreadDoesNotDeadlockAndCallbacksHoldNoTableLock() {
        val t = SyncFenceTable<Any>(); t.create(1, Any(), false)
        val callback = CountDownLatch(1)
        t.await(Any(), intArrayOf(1)) { Runnable {
            val probe = Thread { assertTrue(t.isTriggered(1)); callback.countDown() }
            probe.start(); probe.join(1000)
        } }
        val trigger = Thread { t.trigger(1, false) }; trigger.start(); trigger.join(2000)
        assertFalse(trigger.isAlive); assertTrue(callback.await(1, TimeUnit.SECONDS))
    }
    @Test fun destructionReleasesWaitersAndDisconnectRemovesOwnedFences() {
        val t = SyncFenceTable<Any>(); val owner = Any(); var resumed = 0
        t.create(1, owner, false); t.create(2, owner, false)
        t.await(Any(), intArrayOf(1, 2)) { Runnable { resumed++ } }
        t.destroy(1); assertEquals(1, resumed); assertEquals(0, t.pendingCount())
        t.await(Any(), intArrayOf(2)) { Runnable { resumed++ } }
        t.disconnect(owner); assertEquals(2, resumed)
        try { t.isTriggered(2); fail("owned fence retained") } catch (_: BadFence) {}
    }
    @Test fun disconnectedWaiterNeverReceivesALateWakeAndIdsCanBeReused() {
        val t = SyncFenceTable<Any>(); val owner = Any(); val client = Any()
        t.create(1, owner, false)
        t.await(client, intArrayOf(1)) { Runnable { fail("disconnected waiter resumed") } }
        t.disconnect(client); t.trigger(1, false); t.disconnect(owner)
        t.create(1, Any(), false); assertEquals(0, t.pendingCount())
    }
    @Test fun duplicateIdsAndResetErrorsAreRejectedAndPresentMaySignalRetiredFence() {
        val t = SyncFenceTable<Any>(); t.create(1, Any(), false)
        try { t.create(1, Any(), true); fail("duplicate accepted") } catch (_: BadIdChoice) {}
        try { t.reset(1); fail("untriggered reset accepted") } catch (_: BadMatch) {}
        t.trigger(1, false); t.reset(1); assertFalse(t.isTriggered(1))
        t.trigger(99, true)
        try { t.trigger(99, false); fail("missing trigger accepted") } catch (_: BadFence) {}
    }
    @Test fun repeatedWaitAndDisconnectCyclesDoNotRetainClients() {
        val t = SyncFenceTable<Any>(); val owner = Any(); t.create(1, owner, false)
        repeat(10000) { val c = Any(); t.await(c, intArrayOf(1)) { Runnable {} }; t.disconnect(c) }
        assertEquals(0, t.pendingCount())
    }
}
