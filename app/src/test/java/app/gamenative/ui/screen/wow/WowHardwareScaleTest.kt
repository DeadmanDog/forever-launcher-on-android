package app.gamenative.ui.screen.wow

import android.app.Application
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [29])
class WowHardwareScaleTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @Test fun redMagicResolutionFitsLandscapeViewOnly() {
        assertTrue(WowHardwareScale.fits(2688, 1216, 1344, 608))
        assertFalse(WowHardwareScale.fits(1216, 2688, 1344, 608))
    }

    @Test fun otherAspectRatiosWouldStretchAndAreRejected() {
        assertFalse(WowHardwareScale.fits(2688, 1216, 1280, 720))
        assertFalse(WowHardwareScale.fits(2688, 1216, 1920, 1080))
        assertTrue(WowHardwareScale.fits(2560, 1440, 1280, 720))
        assertTrue(WowHardwareScale.fits(1344, 608, 1344, 608))
    }

    @Test fun emptySizesNeverFit() {
        assertFalse(WowHardwareScale.fits(0, 0, 1344, 608))
        assertFalse(WowHardwareScale.fits(2688, 1216, 0, 608))
    }

    @Test fun displayHintIgnoresOrientationAndBadValues() {
        assertTrue(WowHardwareScale.fitsDisplay(1216, 2688, "1344x608"))
        assertTrue(WowHardwareScale.fitsDisplay(2688, 1216, "1344x608"))
        assertFalse(WowHardwareScale.fitsDisplay(1216, 2688, "1920x1080"))
        assertFalse(WowHardwareScale.fitsDisplay(1216, 2688, "garbage"))
    }

    @Test fun onByDefaultOffWhenChosenAndScanoutTakesPrecedence() {
        val prefs = context.getSharedPreferences(WowLaunchPolicy.PREFS, 0)
        prefs.edit().clear().commit()
        assertTrue(WowHardwareScale.requested(context)) // v0.1.81: on by default; it only acts when the resolution fits the display
        WowLaunchPolicy.save(context, WowHardwareScale.KEY, false)
        assertFalse(WowHardwareScale.requested(context))
        WowLaunchPolicy.save(context, WowHardwareScale.KEY, true)
        assertTrue(WowHardwareScale.requested(context))
        WowLaunchPolicy.save(context, "scanout", true)
        assertFalse(WowHardwareScale.requested(context))
        prefs.edit().clear().commit()
    }

    @Test fun launchSwitchDefaultsMatchTheValidatedSetup() {
        val prefs = context.getSharedPreferences(WowLaunchPolicy.PREFS, 0)
        prefs.edit().clear().commit()
        assertTrue(WowLaunchPolicy.enabled(context, "priority"))
        assertTrue(WowLaunchPolicy.enabled(context, WowHardwareScale.KEY))
        for (off in listOf("scanout", "adpf")) assertFalse(off, WowLaunchPolicy.enabled(context, off))
        prefs.edit().clear().commit()
    }

    // 1.3.2: one "Game image output" choice instead of the Hardware scaling switch plus the scanout switch.
    @Test fun oneOutputChoiceCoversHardwareScalingAndScanout() {
        val prefs = context.getSharedPreferences(WowLaunchPolicy.PREFS, 0)
        prefs.edit().clear().commit()
        assertEquals(WowLaunchPolicy.DisplayOutput.HARDWARE_SCALE, WowLaunchPolicy.displayOutput(context)) // the default as before
        WowLaunchPolicy.saveDisplayOutput(context, WowLaunchPolicy.DisplayOutput.COMPOSITOR)
        assertFalse(WowHardwareScale.requested(context)); assertFalse(WowLaunchPolicy.enabled(context, "scanout"))
        assertEquals(WowLaunchPolicy.DisplayOutput.COMPOSITOR, WowLaunchPolicy.displayOutput(context))
        WowLaunchPolicy.saveDisplayOutput(context, WowLaunchPolicy.DisplayOutput.SCANOUT)
        assertTrue(WowLaunchPolicy.enabled(context, "scanout")); assertFalse(WowHardwareScale.requested(context))
        assertEquals(WowLaunchPolicy.DisplayOutput.SCANOUT, WowLaunchPolicy.displayOutput(context))
        WowLaunchPolicy.saveDisplayOutput(context, WowLaunchPolicy.DisplayOutput.HARDWARE_SCALE)
        assertFalse(WowLaunchPolicy.enabled(context, "scanout")); assertTrue(WowHardwareScale.requested(context))
        // A player who had both old switches on (scanout wins) sees Scanout selected.
        assertEquals(WowLaunchPolicy.DisplayOutput.SCANOUT, WowLaunchPolicy.displayOutput(scanout = true, hardwareScale = true))
        prefs.edit().clear().commit()
    }
}
