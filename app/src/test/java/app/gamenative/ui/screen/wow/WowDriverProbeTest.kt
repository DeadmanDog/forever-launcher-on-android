package app.gamenative.ui.screen.wow

import android.app.Application
import android.content.ContextWrapper
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
    @Suppress("UNUSED_PARAMETER") private fun container(unused: Boolean = false) = Container("wow_forever").apply {
        putExtra(WowVkd3dComponents.API_KEY, WowVkd3dComponents.DX12)
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
    @Test fun absentDisplayCannotSpawnAHeadlessProbe() {
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
