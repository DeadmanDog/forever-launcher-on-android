package app.gamenative.ui.screen.wow

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Review 2026-11-13: each new activity takes the "When the app opens" choice again; a live game is never launched over. */
class WowLauncherStartupTest {
    @Test fun aNewActivityFollowsThePlayersChoice() {
        assertTrue(WoWLauncherState.startupAutoLaunch(enabled = true, sessionAlive = false))
        assertFalse(WoWLauncherState.startupAutoLaunch(enabled = false, sessionAlive = false))
    }

    @Test fun aLiveSessionIsNeverLaunchedOver() {
        assertFalse(WoWLauncherState.startupAutoLaunch(enabled = true, sessionAlive = true))
    }
}
