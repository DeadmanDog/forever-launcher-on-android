package app.gamenative.ui.screen.wow

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class WowBundledStartupTest {
    @Test fun successfulInstallationDoesNotShowError() = runBlocking {
        assertTrue(WowBundledStartup.install({ fail(it) }) {})
    }
    @Test fun exceptionsAndErrorsBecomeVisibleInstallationFailures() = runBlocking {
        for (error in listOf(IllegalStateException("invalid package"), LinkageError("linker"),
            NoClassDefFoundError("missing class"), OutOfMemoryError("allocation"))) {
            var visible: String? = null
            assertFalse(WowBundledStartup.install({ visible = it }) { throw error })
            assertEquals(error.message, visible)
        }
    }
    @Test fun errorWithoutMessageHasVisibleFallback() = runBlocking {
        var visible: String? = null
        assertFalse(WowBundledStartup.install({ visible = it }) { throw LinkageError() })
        assertEquals("Bundled component installation failed", visible)
    }
    @Test fun cancellationPropagatesWithoutReportingFailure() = runBlocking {
        val cancelled = CancellationException("launcher disposed")
        try {
            WowBundledStartup.install({ fail("Cancellation must not become a UI error") }) { throw cancelled }
            fail("Cancellation must propagate")
        } catch (actual: CancellationException) {
            assertSame(cancelled, actual)
        }
    }
}
