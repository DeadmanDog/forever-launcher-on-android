package app.gamenative.ui.screen.wow

import android.app.Application
import android.content.ContextWrapper
import com.winlator.container.Container
import com.winlator.core.envvars.EnvVars
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [29])
class WowDiagnosticsTest {
    @get:Rule val temp = TemporaryFolder()
    private val files by lazy { temp.newFolder() }
    private val context by lazy { object : ContextWrapper(RuntimeEnvironment.getApplication()) {
        override fun getFilesDir() = files
    } }
    @After fun stop() { WowDiagnostics.save(context,false) { false } }
    @Before fun reset() { context.getSharedPreferences("wow_diagnostics",0).edit().clear().commit() }
    @Test fun defaultOffDoesNotCreateLogStorage() {
        assertFalse(WowDiagnostics.enabled(context))
        val env = EnvVars()
        assertNull(WowDiagnostics.prepare(context, Container("wow_forever"), env))
        WowDiagnostics.rendererFrame()
        WowDiagnostics.presentFrame()
        assertFalse(WowDiagnostics.directory(context).exists())
        assertEquals("none",env.get("DXVK_LOG_LEVEL"))
    }
    @Test fun persistedSwitchDoesNotChangeContainerAndCannotChangeDuringSession() {
        val c = Container("wow_forever").apply { wineVersion="custom"; dxWrapper="custom-dxvk"; envVars="CUSTOM=keep" }
        WowDiagnostics.save(context,true) { false }
        assertTrue(WowDiagnostics.enabled(context))
        assertThrows(IllegalStateException::class.java) { WowDiagnostics.save(context,false) { true } }
        assertTrue(WowDiagnostics.enabled(context))
        val env=EnvVars()
        val session=WowDiagnostics.prepare(context,c,env)!!
        assertTrue(File(session,"stderr.log").isFile)
        assertEquals("custom",c.wineVersion); assertEquals("custom-dxvk",c.dxWrapper); assertEquals("CUSTOM=keep",c.envVars)
        WowDiagnostics.save(context,false) { false }
        assertNull(WowDiagnostics.prepare(context,c,EnvVars()))
        assertTrue(session.exists())
    }
    @Test fun detailedModeIsPersistentAndCanBeFullyDisabled() {
        WowDiagnostics.save(context,true) { false }
        WowDiagnostics.saveDetailed(context,true) { false }
        val env=EnvVars();val session=WowDiagnostics.prepare(context,Container("wow_forever"),env)!!
        assertEquals("debug",env.get("DXVK_LOG_LEVEL"))
        assertTrue(env.get("WINEDEBUG").contains("+loaddll"))
        assertFalse(env.get("WINEDEBUG").contains("+sync"))
        assertFalse(env.get("WINEDEBUG").contains("+unwind"))
        assertTrue(File(session, "session.txt").readText().contains("Active limiter:"))
        assertTrue(File(session, "session.txt").readText().contains("Frame pacing:"))
        WowDiagnostics.finish(session)
        WowDiagnostics.save(context,false) { false }
        assertNull(WowDiagnostics.prepare(context,Container("wow_forever"),EnvVars()))
        assertThrows(IllegalStateException::class.java) { WowDiagnostics.saveDetailed(context,false) { true } }
    }
    @Test fun staleFinishCannotStopTheNextDiagnosticSession() {
        WowDiagnostics.save(context,true) { false }
        val first=WowDiagnostics.prepare(context,Container("wow_forever"),EnvVars())!!
        val next=WowDiagnostics.prepare(context,Container("wow_forever"),EnvVars())!!
        val field=WowDiagnostics::class.java.getDeclaredField("sampler").apply { isAccessible=true }
        val current=field.get(WowDiagnostics)
        WowDiagnostics.finish(first);assertSame(current,field.get(WowDiagnostics))
        WowDiagnostics.finish(next);assertNull(field.get(WowDiagnostics))
    }
    @Test fun javaCrashHandlerDelegatesAndRestoresThePreviousHandler() {
        val original=Thread.getDefaultUncaughtExceptionHandler()
        WowDiagnostics.save(context,false) { false }
        var delegated=0
        val previous=Thread.UncaughtExceptionHandler { _,_ -> delegated++ }
        Thread.setDefaultUncaughtExceptionHandler(previous)
        try {
            app.gamenative.CrashHandler.configure(context,true)
            Thread.getDefaultUncaughtExceptionHandler()!!.uncaughtException(Thread.currentThread(),IllegalStateException("test"))
            assertEquals(1,delegated)
            assertTrue(File(WowDiagnostics.directory(context),"java-crash.txt").isFile)
            app.gamenative.CrashHandler.configure(context,false)
            assertSame(previous,Thread.getDefaultUncaughtExceptionHandler())
        } finally { app.gamenative.CrashHandler.configure(context,false);Thread.setDefaultUncaughtExceptionHandler(original) }
    }
    @Test fun disabledLifecycleCaptureHasNoFileOutput() {
        WowDiagnostics.lifecycle(context,"test")
        assertFalse(WowDiagnostics.directory(context).exists())
    }
    @Test fun loggingOnlyAppliesToWowContainer() {
        WowDiagnostics.save(context,true) { false }
        assertNull(WowDiagnostics.prepare(context,Container("other"),EnvVars()))
        assertFalse(WowDiagnostics.directory(context).exists())
    }
    @Test fun retentionKeepsFiveSessionsAndUnrelatedFiles() {
        val root=temp.newFolder()
        File(root,"keep.txt").writeText("keep")
        repeat(7) { WowDiagnostics.newSession(root) }
        assertEquals(5,root.listFiles()!!.count { it.isDirectory })
        assertEquals("keep",File(root,"keep.txt").readText())
    }
    @Test fun configurableRetentionKeepsTenAndPreservesOtherFiles() {
        WowDiagnostics.saveRetention(context, 10) { false }
        val root = temp.newFolder()
        File(root, "keep.txt").writeText("keep")
        repeat(12) { WowDiagnostics.newSession(root, WowDiagnostics.retention(context)) }
        assertEquals(10, root.listFiles()!!.count { it.isDirectory })
        assertEquals("keep", File(root, "keep.txt").readText())
        assertThrows(IllegalStateException::class.java) { WowDiagnostics.saveRetention(context, 5) { true } }
    }
    @Test fun unavailableLogStorageDoesNotBlockLaunchOrEnableVerboseEnv() {
        WowDiagnostics.save(context,true) { false }
        val root=WowDiagnostics.directory(context);root.parentFile!!.mkdirs();root.writeText("not a directory")
        val env=EnvVars()
        assertNull(WowDiagnostics.prepare(context,Container("wow_forever"),env))
        assertEquals("-all",env.get("WINEDEBUG"))
        assertNotNull(WowDiagnostics.lastError(context))
    }

    @Test fun defaultStatsFilesGoIntoTheSessionAndACustomPathIsKept() {
        // Codex round 10 OPT10-13: the shared ZIP only carries the session folder.
        val session = java.io.File(System.getProperty("java.io.tmpdir"), "wow-diag-session-" + System.nanoTime())
        val env = com.winlator.core.envvars.EnvVars()
        env.put("TU_STATS_FILE", WowRuntimeTuning.STATS_FILE)
        env.put("WINE_REQ_STATS", "/sdcard/custom/req.txt")
        WowDiagnostics.redirectDefaultStats(env, session)
        assertEquals(java.io.File(session, "turnrip-stats.txt").absolutePath, env.get("TU_STATS_FILE"))
        assertEquals("/sdcard/custom/req.txt", env.get("WINE_REQ_STATS"))
        val off = com.winlator.core.envvars.EnvVars()
        WowDiagnostics.redirectDefaultStats(off, session)
        assertFalse(off.has("TU_STATS_FILE"))
    }
}
