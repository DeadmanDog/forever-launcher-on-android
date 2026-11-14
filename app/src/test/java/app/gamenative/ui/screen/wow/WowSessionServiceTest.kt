package app.gamenative.ui.screen.wow

import android.app.Application
import android.content.ComponentName
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import com.winlator.xenvironment.XEnvironment
import com.winlator.xenvironment.components.MicrophoneComponent
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config

/** A game session must survive a short switch to another app: WoW drops the connection when its processes are stopped or frozen. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class WowSessionServiceTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @Test fun sessionStartAndEndStartAndStopTheForegroundService() {
        WowSessionService.sessionChanged(context, true)
        val started = Shadows.shadowOf(context).nextStartedService
        assertEquals(WowSessionService::class.java.name, started.component!!.className)
        // Review 2026-11-13: the stop is sent only to a service that is in the foreground (see WowSessionServiceStopTest).
        val controller = Robolectric.buildService(WowSessionService::class.java).create().startCommand(0, 1)
        WowSessionService.sessionChanged(context, false)
        assertEquals(WowSessionService::class.java.name, Shadows.shadowOf(context).nextStoppedService.component!!.className)
        controller.destroy()
        WowSessionService.sessionChanged(context, true) // clears the stop flag for the other tests
    }

    @Test fun theServiceRunsInTheForegroundWithANotification() {
        val service = Robolectric.buildService(WowSessionService::class.java).create().startCommand(0, 1).get()
        val shadow = Shadows.shadowOf(service)
        assertTrue(shadow.isForegroundStopped.not())
        assertNotNull(shadow.lastForegroundNotification)
    }

    @Test fun theServiceIsDeclaredAsASpecialUseForegroundService() {
        val info = context.packageManager.getServiceInfo(ComponentName(context, WowSessionService::class.java), PackageManager.GET_META_DATA)
        assertFalse(info.exported)
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE, info.foregroundServiceType and ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
    }

    @Test fun leavingTheAppPausesOnlyTheMicrophone() {
        val environment = XEnvironment(context, null)
        val microphone = MicrophoneComponent(null as File?)
        environment.addComponent(microphone)
        environment.onBackground()
        val paused = MicrophoneComponent::class.java.getDeclaredField("paused").apply { isAccessible = true }.get(microphone) as AtomicBoolean
        assertTrue(paused.get())
        environment.onResume()
        assertFalse(paused.get())
    }

    // 1.2.3 (multi-agent review): a start refused while the app was in the background was never retried; the app now starts the
    // service when it comes back to the foreground with a game running and the service not running.
    @Test fun aSessionWithoutItsServiceGetsItWhenTheAppReturns() {
        val controller = Robolectric.buildService(WowSessionService::class.java).create().startCommand(0, 1)
        assertTrue(WowSessionService.running)
        controller.destroy()
        assertFalse(WowSessionService.running)
        Shadows.shadowOf(context).clearStartedServices()
        WowSessionService.ensureRunning(context, sessionAlive = false)
        assertEquals(null, Shadows.shadowOf(context).nextStartedService)
        WowSessionService.ensureRunning(context, sessionAlive = true)
        assertEquals(WowSessionService::class.java.name, Shadows.shadowOf(context).nextStartedService.component!!.className)
        assertTrue(WowSessionService.needsStart(sessionAlive = true, serviceRunning = false))
        assertFalse(WowSessionService.needsStart(sessionAlive = true, serviceRunning = true))
        assertFalse(WowSessionService.needsStart(sessionAlive = false, serviceRunning = false))
    }
}
