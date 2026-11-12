package app.gamenative.display

import app.gamenative.ui.screen.wow.WowRuntimeTuning
import app.gamenative.ui.util.ScreenEffectsConfig
import com.winlator.renderer.FrameTrace
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Third review round (1.0.8): each case failed on 1.0.7 and is fixed with it. */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [29])
class Review3GamescopeTest {
    @After fun off() { FrameTrace.set(false); GamescopeFrameLog.reset() }

    @Test fun g14_anEffectOutputIsNeverSmallerThanTheGame() {
        // A game resolution above the panel's: the "1.5x" output came out smaller than the game (2388x1080 for 2688x1216).
        val out = GamescopeEffects.outputSize(2688 to 1216, 2400, 1080)
        assertTrue("output $out below the game", out.first >= 2688 && out.second >= 1216)
    }

    @Test fun g15_withoutAPanelModeTheUpscalerFallsBackToOneAndAHalfNotThePanel() {
        val fsr = GamescopeEffects(ScreenEffectsConfig.SCALING_MODE_FSR, 3)
        // XServerScreen passes the game size when the display mode cannot be read; the fallback was the whole 2688x1216 surface.
        assertEquals(2016 to 912, fsr.scanoutSize(1344 to 608, 1344 to 608, 2688 to 1216))
    }

    @Test fun g16_aCursorShownBelowOneToOneStillGetsItsReadableSize() {
        // FSR output 2016x912 in portrait (1216 wide): shown at 0.6x, so 48 display pixels need 80 output pixels (was capped at 48).
        assertEquals(80, WowGamescopeRuntime.cursorMinPixels(2016 to 912, 1216 to 2688))
    }

    @Test fun g17_aFullFrameLogKeepsItsNumbersRight() {
        FrameTrace.set(true)
        GamescopeFrameLog.reset()
        for (i in 1..4097) GamescopeFrameLog.record(i * 1000L)
        val rows = GamescopeFrameLog.take()
        for (i in rows.indices step 2) assertEquals("frame number ${rows[i]}", rows[i] * 1000L, rows[i + 1])
    }

    @Test fun g18_aLowerLimitUnderGamescopeHoldsAtOnce() {
        // Gamescope's limiter takes a lower limit live; only a higher one (or off) waits for the game-side ceiling of the launch.
        assertFalse(WowRuntimeTuning.gamescopeLimitWaitsForRestart(30, 60))
        assertTrue(WowRuntimeTuning.gamescopeLimitWaitsForRestart(90, 60))
        assertTrue(WowRuntimeTuning.gamescopeLimitWaitsForRestart(0, 60))
        assertFalse(WowRuntimeTuning.gamescopeLimitWaitsForRestart(30, 0))
    }
}
