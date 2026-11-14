package app.gamenative.ui.screen.wow

import android.app.Application
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config

/**
 * Review 2026-11-13: stopService() before the service called startForeground() makes Android end the app. A stop that comes
 * before the service is in the foreground is held back; the service then goes into the foreground and stops itself.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class WowSessionServiceStopTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @After fun clear() {
        // Leaves the stop flag cleared for the next test (a start clears it).
        WowSessionService.sessionChanged(context, true)
        Shadows.shadowOf(context).clearStartedServices()
    }

    @Test fun anEndBeforeTheServiceRanSendsNoStopAndTheServiceStopsItself() {
        assertFalse(WowSessionService.running)
        WowSessionService.sessionChanged(context, true)
        WowSessionService.sessionChanged(context, false)
        assertNull("no stopService before startForeground", Shadows.shadowOf(context).nextStoppedService)
        assertTrue(WowSessionService.stopRequested)

        val controller = Robolectric.buildService(WowSessionService::class.java).create().startCommand(0, 1)
        val shadow = Shadows.shadowOf(controller.get())
        // running is set right after startForeground(): the service did go into the foreground before it left it again.
        assertTrue("startForeground came first", WowSessionService.running)
        assertTrue(shadow.isForegroundStopped)
        assertTrue(shadow.isStoppedBySelf)
        controller.destroy()
        assertFalse(WowSessionService.running)
    }

    @Test fun anEndWhileTheServiceRunsStopsIt() {
        WowSessionService.sessionChanged(context, true)
        val controller = Robolectric.buildService(WowSessionService::class.java).create().startCommand(0, 1)
        assertTrue(WowSessionService.running)
        assertFalse(Shadows.shadowOf(controller.get()).isStoppedBySelf)
        WowSessionService.sessionChanged(context, false)
        assertEquals(WowSessionService::class.java.name, Shadows.shadowOf(context).nextStoppedService.component!!.className)
        controller.destroy()
    }

    @Test fun aNewSessionClearsAnEarlierEnd() {
        WowSessionService.sessionChanged(context, false)
        assertTrue(WowSessionService.stopRequested)
        WowSessionService.sessionChanged(context, true)
        assertFalse(WowSessionService.stopRequested)
        val controller = Robolectric.buildService(WowSessionService::class.java).create().startCommand(0, 1)
        val shadow = Shadows.shadowOf(controller.get())
        assertFalse(shadow.isForegroundStopped)
        assertFalse(shadow.isStoppedBySelf)
        controller.destroy()
    }
}
