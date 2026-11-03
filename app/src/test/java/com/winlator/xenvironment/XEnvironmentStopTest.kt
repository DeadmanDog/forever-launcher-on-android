package com.winlator.xenvironment

import org.junit.Assert.*
import org.junit.Test

class XEnvironmentStopTest {
    private fun component(stop: () -> Unit) = object : EnvironmentComponent() {
        override fun start() = Unit
        override fun stop() = stop.invoke()
    }

    @Test fun guestStopsBeforeItsDisplayAudioAndSharedMemoryDependencies() {
        val env = XEnvironment(null, null)
        val events = mutableListOf<String>()
        var guestStopped = false
        env.addComponent(component { assertTrue(guestStopped); events += "shared-memory" })
        env.addComponent(component { assertTrue(guestStopped); events += "xserver" })
        env.addComponent(component { assertTrue(guestStopped); events += "audio" })
        env.addComponent(component { guestStopped = true; events += "guest" })
        env.stopEnvironmentComponents()
        assertEquals(listOf("guest", "audio", "xserver", "shared-memory"), events)
    }

    @Test fun oneStopFailureDoesNotSkipRemainingComponents() {
        val env = XEnvironment(null, null)
        val events = mutableListOf<String>()
        val failure = IllegalStateException("guest stop failed")
        env.addComponent(component { events += "xserver" })
        env.addComponent(component { events += "audio" })
        env.addComponent(component { events += "guest"; throw failure })
        try { env.stopEnvironmentComponents(); fail("Stop failure must remain visible") }
        catch (actual: IllegalStateException) { assertSame(failure, actual) }
        assertEquals(listOf("guest", "audio", "xserver"), events)
    }

    @Test fun multipleStopFailuresAreRetainedWithoutAbortingCleanup() {
        val env = XEnvironment(null, null)
        val events = mutableListOf<String>()
        val guestFailure = IllegalStateException("guest")
        val audioFailure = IllegalArgumentException("audio")
        env.addComponent(component { events += "xserver" })
        env.addComponent(component { events += "audio"; throw audioFailure })
        env.addComponent(component { events += "guest"; throw guestFailure })
        try { env.stopEnvironmentComponents(); fail("Stop failures must remain visible") }
        catch (actual: IllegalStateException) {
            assertSame(guestFailure, actual)
            assertEquals(listOf(audioFailure), actual.suppressed.toList())
        }
        assertEquals(listOf("guest", "audio", "xserver"), events)
    }

    @Test fun emptyEnvironmentCanBeStoppedAndRegistrationOrderStaysIntact() {
        XEnvironment(null, null).stopEnvironmentComponents()
        val env = XEnvironment(null, null)
        val first = component { }
        val last = component { }
        env.addComponent(first); env.addComponent(last)
        env.stopEnvironmentComponents()
        assertEquals(listOf(first, last), env.toList())
    }
}
