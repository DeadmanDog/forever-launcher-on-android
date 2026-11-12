package app.gamenative.ui.screen.wow

import com.winlator.core.envvars.EnvVars
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** 1.1.4 / TurnRIP 1.1.24: XServer + DX12 with VKD3D latency 1 lets VKD3D's GPU waits steer the wait boost (1.1.3: 500 MHz, 46 FPS). */
class Vkd3dSteerTest {
    @Test fun latencyOneOnXServerDx12TurnsVkd3dSteeringOn() {
        val env = EnvVars()
        WowRuntimeTuning.applyFrameLatency(env, dx12 = true, enabled = true)
        WowRuntimeTuning.applyVkd3dSteer(env, EnvVars(), xserverDx12 = true)
        assertEquals("1", env.get("TU_KGSL_WAITBOOST_VKD3D_STEERS"))
    }

    @Test fun otherLatenciesOtherBackendsAndASavedValueLeaveItAlone() {
        val two = EnvVars().apply { put("VKD3D_SWAPCHAIN_LATENCY_FRAMES", "2") }
        WowRuntimeTuning.applyVkd3dSteer(two, EnvVars(), xserverDx12 = true)
        assertFalse(two.has("TU_KGSL_WAITBOOST_VKD3D_STEERS"))
        val gamescopeOrDx11 = EnvVars().apply { put("VKD3D_SWAPCHAIN_LATENCY_FRAMES", "1") }
        WowRuntimeTuning.applyVkd3dSteer(gamescopeOrDx11, EnvVars(), xserverDx12 = false)
        assertFalse(gamescopeOrDx11.has("TU_KGSL_WAITBOOST_VKD3D_STEERS"))
        val saved = EnvVars().apply { put("VKD3D_SWAPCHAIN_LATENCY_FRAMES", "1"); put("TU_KGSL_WAITBOOST_VKD3D_STEERS", "0") }
        WowRuntimeTuning.applyVkd3dSteer(saved, EnvVars().apply { put("TU_KGSL_WAITBOOST_VKD3D_STEERS", "0") }, xserverDx12 = true)
        assertEquals("0", saved.get("TU_KGSL_WAITBOOST_VKD3D_STEERS"))
    }
}
