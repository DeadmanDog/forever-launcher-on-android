package app.gamenative.ui.screen.wow

import android.app.Application
import com.winlator.container.Container
import com.winlator.core.envvars.EnvVars
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** 1.1.9 runtime review. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class Review7RuntimeTest {
    private val context get() = RuntimeEnvironment.getApplication()
    @After fun clear() { context.getSharedPreferences(WowLaunchPolicy.PREFS, 0).edit().clear().commit() }

    // TurnRIP's ADPF session and its frame steering take their period from TU_ADPF_TARGET_FPS. With a limiter typed into
    // Environment Variables the game runs at that limit, but the driver was told the app's 60: at 30 FPS every frame was "late".
    @Test fun theDriverIsToldTheLimitTheGameReallyRunsAt() {
        val dx12 = Container("wow_forever").apply {
            putExtra(WowVkd3dComponents.API_KEY, WowVkd3dComponents.DX12); envVars = "VKD3D_FRAME_RATE=30"
        }
        val env = EnvVars(dx12.envVars); WowRuntimeTuning.apply(context, dx12, env)
        assertEquals("30", env.get("TU_ADPF_TARGET_FPS"))
        val dx11 = Container("wow_forever").apply { envVars = "DXVK_CONFIG=dxgi.maxFrameRate=40" }
        val env11 = EnvVars(dx11.envVars); WowRuntimeTuning.apply(context, dx11, env11)
        assertEquals("40", env11.get("TU_ADPF_TARGET_FPS"))
        // The app limiter owns pacing: its target, as before; a saved value still wins.
        val app = Container("wow_forever").apply { putExtra("fpsLimiterTarget", "45") }
        val envApp = EnvVars(); WowRuntimeTuning.apply(context, app, envApp)
        assertEquals("45", envApp.get("TU_ADPF_TARGET_FPS"))
        val saved = Container("wow_forever").apply { envVars = "VKD3D_FRAME_RATE=30 TU_ADPF_TARGET_FPS=50"; putExtra(WowVkd3dComponents.API_KEY, WowVkd3dComponents.DX12) }
        val envSaved = EnvVars(saved.envVars); WowRuntimeTuning.apply(context, saved, envSaved)
        assertEquals("50", envSaved.get("TU_ADPF_TARGET_FPS"))
    }

    // The renderer and the Android surface follow the limit that really holds after a change in the running game.
    @Test fun theRendererIsToldTheLimitInForce() {
        // DXVK/VKD3D own pacing at 60 (launch): a request for 90 cannot lift it before the next launch.
        assertEquals(60, WowRuntimeTuning.engineLimit(apiOwned = true, requested = 90, apiLimit = 60))
        // A typed VKD3D_FRAME_RATE=30 with 20 asked: the app limiter holds 20 on top of it.
        assertEquals(20, WowRuntimeTuning.engineLimit(apiOwned = true, requested = 20, apiLimit = 30))
        // OFF under an API limit: the API limit stays.
        assertEquals(30, WowRuntimeTuning.engineLimit(apiOwned = true, requested = 0, apiLimit = 30))
        // The app limiter owns pacing: the request itself.
        assertEquals(45, WowRuntimeTuning.engineLimit(apiOwned = false, requested = 45, apiLimit = 0))
        // What the renderer was told before 1.2.0 in the first two cases (the request, the saved value): not the limit in force.
        val typed = Container("wow_forever").apply { putExtra(WowVkd3dComponents.API_KEY, WowVkd3dComponents.DX12); envVars = "VKD3D_FRAME_RATE=30" }
        assertEquals(30, WowRuntimeTuning.surfaceTarget(typed, 20))
        assertEquals(90, WowRuntimeTuning.surfaceTarget(Container("wow_forever"), 90))
    }

    // TurnRIP 1.1.28: the launch value and every live change reach the driver through TU_FPS_FILE.
    @Test fun theDriverGetsTheFrameLimitThroughItsFile() {
        val container = Container("wow_forever").apply { putExtra("fpsLimiterTarget", "60") }
        val env = EnvVars(); WowRuntimeTuning.apply(context, container, env)
        val file = java.io.File(env.get("TU_FPS_FILE"))
        assertEquals(WowRuntimeTuning.fpsFile(context).absolutePath, file.absolutePath)
        // 1.2.3: with the panel refresh as the second number (TurnRIP 1.1.29).
        val hz = WowRuntimeTuning.panelHz(context)
        assertEquals(if (hz in 10..1000) "60 $hz\n" else "60\n", file.readText())
        WowRuntimeTuning.writeFpsFile(file, 30)
        assertEquals("30\n", file.readText())
        WowRuntimeTuning.writeFpsFile(file, 0)                    // no limit
        assertEquals("0\n", file.readText())
        // A saved TU_FPS_FILE wins.
        val saved = Container("wow_forever").apply { envVars = "TU_FPS_FILE=/tmp/x" }
        val envSaved = EnvVars(saved.envVars); WowRuntimeTuning.apply(context, saved, envSaved)
        assertEquals("/tmp/x", envSaved.get("TU_FPS_FILE"))
    }

    // 1.2.3 (multi-agent review): a limit above the panel rate (144 on 60 Hz) told ADPF and TurnRIP 6.9 ms frames; the panel shows
    // at most one a refresh. A limit that is no divisor of it carries the refresh for TurnRIP's late-frame test.
    @Test fun theDriverIsNeverToldAFrameRateAboveThePanel() {
        assertEquals(60, WowRuntimeTuning.driverFps(144, 60))
        assertEquals(60, WowRuntimeTuning.driverFps(90, 60))
        assertEquals(50, WowRuntimeTuning.driverFps(50, 60))
        assertEquals(0, WowRuntimeTuning.driverFps(0, 60))          // no limit stays no limit
        assertEquals(144, WowRuntimeTuning.driverFps(144, 0))       // refresh unknown: as before
        val file = java.io.File(context.filesDir, "fps-test")
        WowRuntimeTuning.writeFpsFile(file, 50, 60); assertEquals("50 60\n", file.readText())
        WowRuntimeTuning.writeFpsFile(file, 30, 0); assertEquals("30\n", file.readText())
    }
}
