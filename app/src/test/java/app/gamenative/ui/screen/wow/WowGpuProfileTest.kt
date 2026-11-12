package app.gamenative.ui.screen.wow

import android.app.Application
import com.winlator.core.envvars.EnvVars
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** 1.2.4: the TurnRIP 1.1.30 GPU profile is opt-in, needs diagnostic logging and writes into the diagnostic session. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class WowGpuProfileTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val prefs get() = context.getSharedPreferences("wow_diagnostics", 0)

    @After fun clear() { prefs.edit().clear().commit() }

    @Test fun theProfileIsOffUnlessSavedAndNeedsDiagnosticLogging() {
        assertFalse(WowDiagnostics.gpuProfile(context))
        WowDiagnostics.saveGpuProfile(context, true) { false }
        assertTrue(WowDiagnostics.gpuProfileSaved(context))
        assertFalse("diagnostic logging off", WowDiagnostics.gpuProfile(context))
        check(prefs.edit().putBoolean("enabled", true).commit())
        assertTrue(WowDiagnostics.gpuProfile(context))
    }

    @Test fun itCannotBeChangedWhileTheGameRuns() {
        assertThrows(IllegalStateException::class.java) { WowDiagnostics.saveGpuProfile(context, true) { true } }
        assertFalse(WowDiagnostics.gpuProfileSaved(context))
    }

    @Test fun onItSamplesIntoTheSessionFolder() {
        val session = Files.createTempDirectory("session").toFile()
        val env = EnvVars()
        WowDiagnostics.applyGpuProfile(env, session, true)
        assertEquals("print_csv", env.get("MESA_GPU_TRACES"))
        assertEquals(File(session, WowDiagnostics.GPU_TRACE_FILE).absolutePath, env.get("MESA_GPU_TRACEFILE"))
        // Without the sample interval the driver would trace every frame: always set together.
        assertEquals("10000", env.get("TU_PROFILE_SAMPLE_MS"))
    }

    @Test fun diagnosticsGiveTheDriverAShaderStatsFolderInTheSession() {
        val session = Files.createTempDirectory("session").toFile()
        val env = EnvVars()
        WowDiagnostics.applyShaderStats(env, session)
        val dir = File(session, WowDiagnostics.SHADER_STATS_DIR)
        assertTrue(dir.isDirectory)
        assertEquals(dir.absolutePath, env.get("TU_SHADER_STATS_DIR"))
        val typed = EnvVars("TU_SHADER_STATS_DIR=/sdcard/s")
        WowDiagnostics.applyShaderStats(typed, session)
        assertEquals("/sdcard/s", typed.get("TU_SHADER_STATS_DIR"))
    }

    @Test fun offItAddsNothingAndTypedValuesWin() {
        val session = Files.createTempDirectory("session").toFile()
        val off = EnvVars()
        WowDiagnostics.applyGpuProfile(off, session, false)
        for (key in listOf("MESA_GPU_TRACES", "MESA_GPU_TRACEFILE", "TU_PROFILE_SAMPLE_MS")) assertFalse(key, off.has(key))
        val typed = EnvVars("TU_PROFILE_SAMPLE_MS=2000 MESA_GPU_TRACEFILE=/sdcard/t.csv")
        WowDiagnostics.applyGpuProfile(typed, session, true)
        assertEquals("2000", typed.get("TU_PROFILE_SAMPLE_MS"))
        assertEquals("/sdcard/t.csv", typed.get("MESA_GPU_TRACEFILE"))
        assertEquals("print_csv", typed.get("MESA_GPU_TRACES"))
    }
}
