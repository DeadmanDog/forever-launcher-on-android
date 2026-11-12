package app.gamenative.ui.screen.wow

import android.app.Application
import app.gamenative.display.GamescopeWindowPolicy
import app.gamenative.ui.component.dialog.SwitchNeeds
import app.gamenative.ui.component.dialog.switchNeeds
import com.winlator.container.Container
import com.winlator.core.envvars.EnvVars
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 1.1.5 settings review: GPU mode is Balanced (TurnRIP adaptive wait boost) or Performance (fixed max clock) only, and settings
 * that no longer act must not silently do something else.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class Review5SettingsTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val prefs get() = context.getSharedPreferences(WowLaunchPolicy.PREFS, 0)

    @After fun clear() { prefs.edit().clear().commit() }

    // TurnRIP reads TU_KGSL_PWR_MAX with default ON: a launch that leaves it unset runs the GPU at its maximum.
    @Test fun anOldRegulatedChoiceRunsAsBalancedNotAtTheDriverDefaultMaximum() {
        check(prefs.edit().putString("gpu", "f65").commit())
        assertEquals("", WowRuntimeTuning.selected(context, "gpu"))
        val launch = EnvVars()
        WowRuntimeTuning.applyChoices(launch, EnvVars(), true, mapOf("gpu" to WowRuntimeTuning.selected(context, "gpu")))
        assertEquals("0", launch.get("TU_KGSL_PWR_MAX"))
        assertFalse(launch.has("TU_KGSL_GPU_FLOOR"))
        assertEquals("balanced", WowQuickSettings.status(context, Container("wow_forever").apply { envVars = "" }).gpuMode)
    }

    @Test fun anUnknownGpuValueNeverFallsBackToTheDriverDefaultMaximum() {
        for (value in listOf("f70", "f80", "2", "max")) for (dx12 in listOf(false, true)) {
            val launch = EnvVars()
            WowRuntimeTuning.applyChoices(launch, EnvVars(), dx12, mapOf("gpu" to value))
            assertEquals(value, "0", launch.get("TU_KGSL_PWR_MAX"))
            assertFalse(value, launch.has("TU_KGSL_GPU_FLOOR"))
        }
    }

    @Test fun gpuModeIsBalancedOrPerformanceOnly() {
        assertThrows(IllegalArgumentException::class.java) { WowRuntimeTuning.save(context, "gpu", "f65") }
        WowRuntimeTuning.save(context, "gpu", "1")
        val performance = EnvVars()
        WowRuntimeTuning.applyChoices(performance, EnvVars(), true, mapOf("gpu" to WowRuntimeTuning.selected(context, "gpu")))
        assertEquals("1", performance.get("TU_KGSL_PWR_MAX"))
        WowRuntimeTuning.save(context, "gpu", "")
        val balanced = EnvVars()
        WowRuntimeTuning.applyChoices(balanced, EnvVars(), false, mapOf("gpu" to WowRuntimeTuning.selected(context, "gpu")))
        assertEquals("0", balanced.get("TU_KGSL_PWR_MAX"))
        // A floor or PWR_MAX typed into Environment Variables still wins (and the launcher card still names it).
        val saved = EnvVars("TU_KGSL_PWR_MAX=0 TU_KGSL_GPU_FLOOR=80")
        val typed = EnvVars(saved.toString())
        WowRuntimeTuning.applyChoices(typed, saved, true, mapOf("gpu" to "1"))
        assertEquals("0", typed.get("TU_KGSL_PWR_MAX")); assertEquals("80", typed.get("TU_KGSL_GPU_FLOOR"))
    }

    // TurnRIP 1.1.25: at VKD3D latency 1 the boost follows frame time (only the clock 60 FPS needs), not the GPU-wait share.
    @Test fun latencyOneOnXServerDx12TurnsFrameSteeringOn() {
        val env = EnvVars()
        WowRuntimeTuning.applyFrameLatency(env, dx12 = true, enabled = true)
        WowRuntimeTuning.applyVkd3dSteer(env, EnvVars(), xserverDx12 = true)
        assertEquals("1", env.get("TU_KGSL_WAITBOOST_VKD3D_STEERS"))
        assertEquals("1", env.get("TU_KGSL_WAITBOOST_FRAME_STEER"))
        val two = EnvVars().apply { put("VKD3D_SWAPCHAIN_LATENCY_FRAMES", "2"); put("TU_KGSL_WAITBOOST_FRAME_STEER", "1") }
        WowRuntimeTuning.applyVkd3dSteer(two, EnvVars(), xserverDx12 = true)
        assertFalse(two.has("TU_KGSL_WAITBOOST_FRAME_STEER"))
        val gamescope = EnvVars().apply { put("VKD3D_SWAPCHAIN_LATENCY_FRAMES", "1") }
        WowRuntimeTuning.applyVkd3dSteer(gamescope, EnvVars(), xserverDx12 = false)
        assertFalse(gamescope.has("TU_KGSL_WAITBOOST_FRAME_STEER"))
        // A saved value wins: FRAME_STEER=0 gives the 1.1.24 share controller back for an A/B test.
        val savedOff = EnvVars("TU_KGSL_WAITBOOST_FRAME_STEER=0")
        val ab = EnvVars(savedOff.toString()).apply { put("VKD3D_SWAPCHAIN_LATENCY_FRAMES", "1") }
        WowRuntimeTuning.applyVkd3dSteer(ab, savedOff, xserverDx12 = true)
        assertEquals("0", ab.get("TU_KGSL_WAITBOOST_FRAME_STEER")); assertEquals("1", ab.get("TU_KGSL_WAITBOOST_VKD3D_STEERS"))
    }

    // The Advanced tab greys out a switch that does nothing with the current API or display backend, and only then.
    @Test fun switchesThatDoNothingNowAreGreyedOutWithTheReason() {
        // The GPU wait acts on XServer + DX12 only (WowLaunchEnvironment): it looked active under DX11 and Gamescope.
        assertEquals(SwitchNeeds.DX12, switchNeeds(WowRuntimeTuning.GAME_GPU_WAIT, dx12 = false, gamescope = false))
        assertEquals(SwitchNeeds.XSERVER, switchNeeds(WowRuntimeTuning.GAME_GPU_WAIT, dx12 = true, gamescope = true))
        assertNull(switchNeeds(WowRuntimeTuning.GAME_GPU_WAIT, dx12 = true, gamescope = false))
        // Gamescope direct scanout is a Gamescope flag: it looked active under XServer.
        assertEquals(SwitchNeeds.GAMESCOPE, switchNeeds(GamescopeWindowPolicy.DIRECT_SCANOUT, dx12 = false, gamescope = false))
        assertNull(switchNeeds(GamescopeWindowPolicy.DIRECT_SCANOUT, dx12 = true, gamescope = true))
        // Unchanged: DXVK-only switches under DX12, XServer-only ones under Gamescope, the rest always.
        assertEquals(SwitchNeeds.DX11, switchNeeds(WowRuntimeTuning.HYBRID_GPL, dx12 = true, gamescope = false))
        assertEquals(SwitchNeeds.DX11, switchNeeds(WowRuntimeTuning.VEGAS_BINDSKIP, dx12 = true, gamescope = true))
        assertEquals(SwitchNeeds.XSERVER, switchNeeds("scanout", dx12 = false, gamescope = true))
        assertNull(switchNeeds("scanout", dx12 = true, gamescope = false))
        assertNull(switchNeeds(WowRuntimeTuning.EVENT_POOL, dx12 = true, gamescope = true))
        assertNull(switchNeeds(WowRuntimeTuning.CPU_CLASSES, dx12 = false, gamescope = false))
    }
}
