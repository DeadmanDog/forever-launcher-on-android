package app.gamenative.ui.screen.xserver

import org.junit.Assert.*
import org.junit.Test

class GameSessionLifecycleTest {
    @Test fun secondSessionCanStartBeforeAnyComposeEffectsRun() {
        val first = GameSessionLifecycle()
        assertTrue(first.beginExit())
        val second = GameSessionLifecycle()
        // Worker immediately scheduled by AndroidView factory must see a fresh state.
        assertFalse(second.isExiting)
        assertTrue(first.isExiting)
    }
    @Test fun oldSessionExitCannotCloseNextSession() {
        val first = GameSessionLifecycle()
        val second = GameSessionLifecycle()
        first.beginExit()
        assertFalse(second.isExiting)
        assertTrue(second.beginExit())
        assertFalse(second.beginExit())
    }
    @Test fun workerCallbackWaitsForDispatchAndCannotTouchDisposedSession() {
        val session = GameSessionLifecycle()
        val queued = mutableListOf<Runnable>()
        var called = false
        val worker = Thread { session.dispatchIfActive({ synchronized(queued) { queued.add(it) } }) { called = true } }
        worker.start(); worker.join()
        assertFalse(called)
        session.beginExit()
        queued.single().run()
        assertFalse(called)
    }
    @Test fun activeCallbackExecutesOnDispatchingThread() {
        val session = GameSessionLifecycle()
        val queued = mutableListOf<Runnable>()
        var thread: Thread? = null
        session.dispatchIfActive({ queued.add(it) }) { thread = Thread.currentThread() }
        assertNull(thread)
        queued.single().run()
        assertSame(Thread.currentThread(), thread)
    }
}
