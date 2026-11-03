package app.gamenative.utils

import com.winlator.core.envvars.EnvVars
import org.junit.Assert.assertEquals
import org.junit.Test

class AppLoggingTest {
    @Test fun disablesDiagnosticOverridesWithoutChangingRuntimeOrCachePaths() {
        val env = EnvVars().apply {
            put("WINEDEBUG", "+all")
            put("DXVK_LOG_LEVEL", "debug")
            put("DXVK_SHADER_CACHE_PATH", "G:\\_forever_")
            put("MESA_SHADER_CACHE_DIR", "/storage/game/_forever_")
            put("WINEDLLPATH", "/data/wine/dlls")
            put("TU_DEBUG", "nolrz")
        }
        AppLogging.quietRuntimeEnv(env)
        assertEquals("-all", env.get("WINEDEBUG"))
        assertEquals("none", env.get("DXVK_LOG_LEVEL"))
        assertEquals("G:\\_forever_", env.get("DXVK_SHADER_CACHE_PATH"))
        assertEquals("/storage/game/_forever_", env.get("MESA_SHADER_CACHE_DIR"))
        assertEquals("/data/wine/dlls", env.get("WINEDLLPATH"))
        assertEquals("nolrz", env.get("TU_DEBUG"))
    }
    @Test fun diagnosticModeEnablesOnlyRuntimeWarningsAndErrors() {
        val env = EnvVars().apply { put("WINEDEBUG", "+all"); put("DXVK_ASYNC", "1"); put("WINENTSYNC", "1"); put("MESA_VK_WSI_PRESENT_MODE", "fifo") }
        AppLogging.runtimeEnv(env, true)
        assertEquals("-all,err+all,warn+seh,+loaddll", env.get("WINEDEBUG"))
        assertEquals("warn", env.get("DXVK_LOG_LEVEL"))
        assertEquals("none", env.get("DXVK_LOG_PATH"))
        assertEquals("0", env.get("WINE_LOG_PEB_DATA"))
        assertEquals("1", env.get("WINENTSYNC"))
        assertEquals("1", env.get("DXVK_ASYNC"))
        assertEquals("fifo", env.get("MESA_VK_WSI_PRESENT_MODE"))
        AppLogging.runtimeEnv(env, false)
        assertEquals("-all", env.get("WINEDEBUG"))
        assertEquals("none", env.get("DXVK_LOG_LEVEL"))
    }
    @Test fun detailedModeIsOptionalAndQuietModeRestoresTheOriginalLevels() {
        val env=EnvVars()
        AppLogging.runtimeEnv(env,true,true)
        assertEquals("debug",env.get("DXVK_LOG_LEVEL"))
        org.junit.Assert.assertFalse(env.get("WINEDEBUG").contains("+sync")); org.junit.Assert.assertFalse(env.get("WINEDEBUG").contains("+unwind"))
        AppLogging.runtimeEnv(env,false,true)
        assertEquals("-all",env.get("WINEDEBUG"));assertEquals("none",env.get("DXVK_LOG_LEVEL"))
    }
    @Test fun quietModeDisablesBothVkd3dChannelsAndDirectDllLogFiles() {
        val env = EnvVars().apply {
            put("VKD3D_DEBUG", "trace"); put("VKD3D_SHADER_DEBUG", "trace")
            put("VKD3D_LOG_FILE", "G:\\unbounded-vkd3d.log"); put("VKD3D_LOG_BUFFERED", "65536")
            put("VKD3D_SHADER_CACHE_PATH", "G:\\_forever_")
        }
        AppLogging.runtimeEnv(env, false)
        assertEquals("none", env.get("VKD3D_DEBUG")); assertEquals("none", env.get("VKD3D_SHADER_DEBUG"))
        org.junit.Assert.assertFalse(env.has("VKD3D_LOG_FILE")); org.junit.Assert.assertFalse(env.has("VKD3D_LOG_BUFFERED"))
        assertEquals("G:\\_forever_", env.get("VKD3D_SHADER_CACHE_PATH"))
    }
    @Test fun basicDiagnosticsEnableBothVkd3dChannelsWithoutFunctionTracing() {
        val env = EnvVars()
        AppLogging.runtimeEnv(env, true, false)
        assertEquals("warn", env.get("VKD3D_DEBUG")); assertEquals("warn", env.get("VKD3D_SHADER_DEBUG"))
        AppLogging.runtimeEnv(env, false)
        assertEquals("none", env.get("VKD3D_DEBUG")); assertEquals("none", env.get("VKD3D_SHADER_DEBUG"))
    }
    @Test fun detailedVkd3dTracingIsOptInAndSwitchingOffClearsIt() {
        val env = EnvVars()
        AppLogging.runtimeEnv(env, true, true)
        assertEquals("trace", env.get("VKD3D_DEBUG")); assertEquals("trace", env.get("VKD3D_SHADER_DEBUG"))
        AppLogging.runtimeEnv(env, false, true)
        assertEquals("none", env.get("VKD3D_DEBUG")); assertEquals("none", env.get("VKD3D_SHADER_DEBUG"))
    }

    @Test fun audioDiagnosticsAddsTheWineAudioChannelsOnlyWhenEnabledAndRequested() {
        val off = EnvVars()
        AppLogging.runtimeEnv(off, true, false, false)
        assertEquals("-all,err+all,warn+seh,+loaddll", off.get("WINEDEBUG"))
        val on = EnvVars()
        AppLogging.runtimeEnv(on, true, false, true)
        assertEquals("-all,err+all,warn+seh,+loaddll,+pulse,+mmdevapi,+timestamp", on.get("WINEDEBUG"))
        val disabled = EnvVars()
        AppLogging.runtimeEnv(disabled, false, false, true)
        assertEquals("-all", disabled.get("WINEDEBUG"))
    }
}
