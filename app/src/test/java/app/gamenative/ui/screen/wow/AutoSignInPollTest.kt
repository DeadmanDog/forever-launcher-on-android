package app.gamenative.ui.screen.wow

import org.junit.Assert.assertEquals
import org.junit.Test

class AutoSignInPollTest {
    private val fast = 200L
    private val normal = 700L
    private val late = 2500L
    private val off = 3000L
    private val threeMinutes = 3 * 60 * 1000L

    @Test fun offOrNoSavedLoginPollsRarelyAtAnyTime() {
        assertEquals(off, autoPollDelayMs(false, 5, true, 0))
        assertEquals(off, autoPollDelayMs(false, 0, false, threeMinutes + 1))
    }

    @Test fun aColourKindLoginCandidateIsFollowedQuicklyAtAnyTime() {
        assertEquals(fast, autoPollDelayMs(true, 0, true, 1000))
        assertEquals("a login that shows up late is still found fast", fast, autoPollDelayMs(true, 0, true, threeMinutes + 60_000))
    }

    @Test fun aPartialExactMatchHurriesOnlyInTheFirstMinutes() {
        assertEquals(fast, autoPollDelayMs(true, 3, false, threeMinutes))        // exactly at the limit: still the first minutes
        assertEquals("lasting partial match after 3 min: slow, not 200 ms for ten minutes", late, autoPollDelayMs(true, 3, false, threeMinutes + 1))
        assertEquals(late, autoPollDelayMs(true, 5, false, 181_000))
    }

    @Test fun anythingElseIsNormalThenLate() {
        assertEquals(normal, autoPollDelayMs(true, 0, false, 10_000))
        assertEquals(normal, autoPollDelayMs(true, 2, false, 179_000))
        assertEquals(late, autoPollDelayMs(true, 2, false, 181_000))
        assertEquals(late, autoPollDelayMs(true, 0, false, 599_000))
    }
}
