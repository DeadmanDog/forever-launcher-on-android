package app.gamenative.ui.screen.wow

import android.app.Application
import android.content.ContextWrapper
import app.gamenative.display.WowDisplayBackend
import com.winlator.container.Container
import com.winlator.core.envvars.EnvVars
import java.io.File
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [29])
class WowDriverProbeTest {
    @get:Rule val temp = TemporaryFolder()
    private val context by lazy { ContextWrapper(RuntimeEnvironment.getApplication()) }
    @Before fun reset() { context.getSharedPreferences("wow_diagnostics", 0).edit().clear().commit() }
    @After fun stop() { WowDiagnostics.save(context, false) { false } }
    private fun container(gamescope: Boolean) = Container("wow_forever").apply {
        putExtra(WowVkd3dComponents.API_KEY, WowVkd3dComponents.DX12)
        if (gamescope) putExtra(WowDisplayBackend.KEY, WowDisplayBackend.GAMESCOPE)
    }

    @Test fun gamescopeCannotStartWineBeforeItsDisplayEvenWithAStaleDisplayVariable() {
        WowDiagnostics.save(context, true) { false }
        for (display in listOf("", ":0")) {
            val prefix = temp.newFolder()
            val session = temp.newFolder()
            val env = EnvVars().apply { put("WINEPREFIX", prefix.path); if (display.isNotEmpty()) put("DISPLAY", display) }
            assertNull(WowDriverProbe.prepare(context, container(true), env, session))
            assertTrue(File(session, "driver-probe-skipped.txt").readText().contains("headlessly"))
            assertFalse(File(session, "driver-probe").exists())
            assertTrue(prefix.listFiles()!!.isEmpty()) // no PE extraction, no prefix mutation
            assertEquals(display, env.get("DISPLAY"))
        }
    }
    @Test fun xserverWithItsExistingDisplayRetainsTheDiagnosticProbe() {
        WowDiagnostics.save(context, true) { false }
        val prefix = temp.newFolder()
        val session = temp.newFolder()
        val env = EnvVars().apply { put("WINEPREFIX", prefix.path); put("DISPLAY", ":0") }
        assertEquals(File(session, "driver-probe"), WowDriverProbe.prepare(context, container(false), env, session))
        assertTrue(File(prefix, "drive_c/windows/temp/wow-driver-probe.exe").length() > 0)
        assertFalse(File(session, "driver-probe-skipped.txt").exists())
    }
    @Test fun absentDisplayCannotSpawnAHeadlessProbeOnEitherBackend() {
        WowDiagnostics.save(context, true) { false }
        val prefix = temp.newFolder()
        val session = temp.newFolder()
        val env = EnvVars().apply { put("WINEPREFIX", prefix.path) }
        assertNull(WowDriverProbe.prepare(context, container(false), env, session))
        assertTrue(File(session, "driver-probe-skipped.txt").readText().contains("no DISPLAY"))
        assertTrue(prefix.listFiles()!!.isEmpty())
    }
    @Test fun diagnosticsOffDoesNotTouchThePrefixOrSession() {
        val prefix = temp.newFolder()
        val session = temp.newFolder()
        val env = EnvVars().apply { put("WINEPREFIX", prefix.path) }
        assertNull(WowDriverProbe.prepare(context, container(true), env, session))
        assertTrue(prefix.listFiles()!!.isEmpty())
        assertTrue(session.listFiles()!!.isEmpty())
    }
}
