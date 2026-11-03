package app.gamenative.events

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class EventDispatcherTest {

    @Test fun removalUsesIdentityRatherThanSameListenerDescription() {
        val dispatcher = EventDispatcher()
        val called = AtomicInteger()
        fun listener(): (AndroidEvent.GuestProgramTerminated) -> Unit = object : (AndroidEvent.GuestProgramTerminated) -> Unit {
            override fun invoke(event: AndroidEvent.GuestProgramTerminated) { called.incrementAndGet() }
            override fun toString() = "same description"
        }
        val a = listener(); val b = listener()
        dispatcher.on<AndroidEvent.GuestProgramTerminated, Unit>(a); dispatcher.on<AndroidEvent.GuestProgramTerminated, Unit>(b)
        dispatcher.off<AndroidEvent.GuestProgramTerminated, Unit>(a)
        dispatcher.emit<AndroidEvent.GuestProgramTerminated, Unit>(AndroidEvent.GuestProgramTerminated)
        assertEquals(1, called.get())
    }

    @Test fun recursiveEmissionRunsOneShotOnlyOnce() {
        val dispatcher = EventDispatcher()
        var calls = 0
        dispatcher.once<AndroidEvent.GuestProgramTerminated, Unit> { calls++; dispatcher.emit<AndroidEvent.GuestProgramTerminated, Unit>(AndroidEvent.GuestProgramTerminated) }
        dispatcher.emit<AndroidEvent.GuestProgramTerminated, Unit>(AndroidEvent.GuestProgramTerminated)
        assertEquals(1, calls)
    }

    @Test fun clearingOneEventLeavesOtherType() {
        val dispatcher = EventDispatcher()
        var probe = 0; var other = 0
        dispatcher.on<AndroidEvent.GuestProgramTerminated, Unit> { probe++ }
        dispatcher.on<AndroidEvent.ForceCloseApp, Unit> { other++ }
        dispatcher.clearAllListenersOf<AndroidEvent.GuestProgramTerminated>()
        dispatcher.emit<AndroidEvent.GuestProgramTerminated, Unit>(AndroidEvent.GuestProgramTerminated); dispatcher.emit<AndroidEvent.ForceCloseApp, Unit>(AndroidEvent.ForceCloseApp)
        assertEquals(0, probe); assertEquals(1, other)
    }

    @Test fun parallelSubscriptionAndEmissionAreSafe() {
        val dispatcher = EventDispatcher()
        val failure = AtomicReference<Throwable?>(null)
        val threads = List(4) { Thread {
            try {
                repeat(2000) {
                    val callback: (AndroidEvent.GuestProgramTerminated) -> Unit = {}
                    dispatcher.on<AndroidEvent.GuestProgramTerminated, Unit>(callback)
                    dispatcher.emit<AndroidEvent.GuestProgramTerminated, Unit>(AndroidEvent.GuestProgramTerminated)
                    dispatcher.off<AndroidEvent.GuestProgramTerminated, Unit>(callback)
                }
            } catch (t: Throwable) { failure.compareAndSet(null, t) }
        } }
        threads.forEach { it.start() }; threads.forEach { it.join() }
        assertNull(failure.get())
        assertTrue(dispatcher.listeners[AndroidEvent.GuestProgramTerminated::class]!!.isEmpty())
    }

    @Test fun emitAnyCallsEveryListenerAndReportsIfAnyReturnedTrue() {
        val dispatcher = EventDispatcher()
        val called = AtomicInteger()
        val event = AndroidEvent.MotionEvent(null)
        assertFalse("no listener, nothing dispatched", dispatcher.emitAny(event))
        dispatcher.on<AndroidEvent.MotionEvent, Boolean> { called.incrementAndGet(); false }
        assertFalse(dispatcher.emitAny(event))
        dispatcher.on<AndroidEvent.MotionEvent, Boolean> { called.incrementAndGet(); true }
        dispatcher.on<AndroidEvent.MotionEvent, Boolean> { called.incrementAndGet(); false }
        called.set(0)
        assertTrue(dispatcher.emitAny(event))
        assertEquals("a true result does not skip the other listeners", 3, called.get())
    }

    @Test fun emitAnyRunsAOneShotListenerOnlyOnce() {
        val dispatcher = EventDispatcher()
        var calls = 0
        dispatcher.once<AndroidEvent.MotionEvent, Boolean> { calls++; true }
        val event = AndroidEvent.MotionEvent(null)
        assertTrue(dispatcher.emitAny(event))
        assertFalse(dispatcher.emitAny(event))
        assertEquals(1, calls)
    }

    @Test fun emitAnyMatchesTheAggregatingEmit() {
        val dispatcher = EventDispatcher()
        val results = listOf(false, true, false)
        results.forEach { r -> dispatcher.on<AndroidEvent.MotionEvent, Boolean> { r } }
        val event = AndroidEvent.MotionEvent(null)
        assertEquals(dispatcher.emit(event) { it.any { x -> x } } == true, dispatcher.emitAny(event))
    }
}
