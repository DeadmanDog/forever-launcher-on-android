package app.gamenative.ui.screen.wow

import com.winlator.xserver.extensions.PresentExtension
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 1.2.19 / TurnRIP 1.1.47: the driver counts frames up to 250 ms apart, so it must be told the 15 FPS the X limiter runs in the
 * background (else Balanced takes them for a heavy scene and raises the clock while nobody looks).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class BackgroundDriverFpsTest {
    @Test fun everySwitchReachesTheListenerOnce() {
        val extension = PresentExtension()
        val seen = mutableListOf<Boolean>()
        extension.setBackgroundListener { seen += it }
        extension.setBackground(true)
        extension.setBackground(true)
        extension.setBackground(false)
        assertEquals(listOf(true, false), seen)
    }

    @Test fun theBackgroundRateIsTheLimitersRate() {
        assertEquals(PresentExtension.BACKGROUND_FPS, WowRuntimeTuning.backgroundDriverFps(60))
        assertEquals(PresentExtension.BACKGROUND_FPS, WowRuntimeTuning.backgroundDriverFps(0))
        assertEquals(10, WowRuntimeTuning.backgroundDriverFps(10))
    }

    @Test fun theDriverFileCarriesTheBackgroundRateAndTheForegroundOneAfter() {
        val file = java.io.File.createTempFile("wow-fps", ".txt").apply { deleteOnExit() }
        val extension = PresentExtension()
        extension.setBackgroundListener { background ->
            WowRuntimeTuning.writeFpsFile(file, if (background) WowRuntimeTuning.backgroundDriverFps(60) else 60, 120)
        }
        extension.setBackground(true)
        assertEquals("15 120", file.readText().trim())
        extension.setBackground(false)
        assertEquals("60 120", file.readText().trim())
    }
}
