package app.gamenative.display

import org.junit.Assert.*
import org.junit.Test

class GamescopeFrameLimitTest {
    @Test fun requestBeforeStartupIsRetainedAndReplayedOnTheFirstFrame() {
        val state = GamescopeFrameLimit()
        val sent = mutableListOf<Int>()
        state.beginSession(); state.request(60)
        state.apply(0, false) { sent.add(it); 0 }
        assertTrue(sent.isEmpty())
        assertEquals(60, state.requested())
        state.apply(1, true) { sent.add(it); 0 }
        state.apply(2, true) { sent.add(it); 0 }
        assertEquals(listOf(60), sent)
        assertFalse(state.pending)
    }
    @Test fun anUnavailableInjectorRetriesWithoutSendingOnEveryFrame() {
        val state = GamescopeFrameLimit()
        state.request(60)
        val sent = mutableListOf<Int>()
        state.apply(0, true) { sent.add(it); -2 }
        state.apply(499_999_999, true) { sent.add(it); 0 }
        assertEquals(listOf(60), sent)
        assertTrue(state.pending)
        state.apply(500_000_000, true) { sent.add(it); 0 }
        assertEquals(listOf(60, 60), sent)
        assertFalse(state.pending)
    }
    @Test fun aNewSessionReappliesTheUnchangedLimit() {
        val state = GamescopeFrameLimit()
        val sent = mutableListOf<Int>()
        repeat(2) {
            state.beginSession(); state.request(60)
            state.apply(0, true) { sent.add(it); 0 }
        }
        assertEquals(listOf(60, 60), sent)
    }
    @Test fun changingAndDisablingTheLimitAreExplicitLiveCommands() {
        val state = GamescopeFrameLimit()
        val sent = mutableListOf<Int>()
        for (fps in listOf(60, 40, 0, 60)) {
            state.request(fps)
            state.apply(0, true) { sent.add(it); 0 }
        }
        assertEquals(listOf(60, 40, 0, 60), sent)
        state.request(0)
        assertEquals(0, state.requested())
    }
}

class GamescopeFifoLimitTest {
    @org.junit.Test fun theSessionAlwaysHandsTheCapToGamescope() {
        // 1044 .. 1047 requested 0 here under FIFO, so nothing capped (77.6 FPS for a 60 limit on 1.0.2).
        val limit = GamescopeFrameLimit()
        limit.beginSession(); limit.request(60)
        val sent = mutableListOf<Int>()
        limit.apply(0L, true) { sent.add(it); 0 }
        org.junit.Assert.assertEquals(listOf(60), sent)
    }

    @Test fun theBackgroundHoldsTheGameAt15FpsAtMost() {
        assertEquals(60, GamescopeFrameLimit.effectiveLimit(60, false))
        assertEquals(0, GamescopeFrameLimit.effectiveLimit(0, false))
        assertEquals(15, GamescopeFrameLimit.effectiveLimit(60, true))
        assertEquals(15, GamescopeFrameLimit.effectiveLimit(0, true))    // no limit set: the background still gets one
        assertEquals(10, GamescopeFrameLimit.effectiveLimit(10, true))   // a lower limit of the player's own stays
    }
}
