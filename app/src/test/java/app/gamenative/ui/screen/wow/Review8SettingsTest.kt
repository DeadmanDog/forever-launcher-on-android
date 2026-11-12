package app.gamenative.ui.screen.wow

import android.app.Application
import com.winlator.container.Container
import com.winlator.core.envvars.EnvVars
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 1.3.6 (user): no swapchain image choice (DX12 low latency asks for 2), the game always sees the phone's own CPU and GPU,
 * and DX12 always reports a dedicated GPU; none of these is a setting any more. A typed environment value still wins.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class Review8SettingsTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val prefs get() = context.getSharedPreferences(WowLaunchPolicy.PREFS, 0)

    @After fun clear() { prefs.edit().clear().commit() }

    private fun dx12(env: String = "") = Container("wow_forever").apply {
        putExtra(WowVkd3dComponents.API_KEY, WowVkd3dComponents.DX12); envVars = env
    }

    @Test fun dx12LowLatencyAsksForTwoSwapchainImagesOtherwiseVkd3dDecides() {
        val on = EnvVars(); WowRuntimeTuning.applyFrameLatency(on, dx12 = true, enabled = true)
        assertEquals("2", on.get("VKD3D_SWAPCHAIN_IMAGES")); assertEquals("1", on.get("VKD3D_SWAPCHAIN_LATENCY_FRAMES"))
        val off = EnvVars(); WowRuntimeTuning.applyFrameLatency(off, dx12 = true, enabled = false)
        assertFalse(off.has("VKD3D_SWAPCHAIN_IMAGES"))
        val dx11 = EnvVars(); WowRuntimeTuning.applyFrameLatency(dx11, dx12 = false, enabled = true)
        assertFalse(dx11.has("VKD3D_SWAPCHAIN_IMAGES"))
        val typed = EnvVars("VKD3D_SWAPCHAIN_IMAGES=3"); WowRuntimeTuning.applyFrameLatency(typed, dx12 = true, enabled = true)
        assertEquals("3", typed.get("VKD3D_SWAPCHAIN_IMAGES"))
        // An old saved "images" choice no longer does anything.
        check(prefs.edit().putString("images", "4").commit())
        WowLaunchPolicy.save(context, WowRuntimeTuning.LOW_LATENCY_DX12, false)
        val launch = EnvVars(); WowRuntimeTuning.apply(context, dx12(), launch)
        assertFalse(launch.has("VKD3D_SWAPCHAIN_IMAGES"))
    }

    @Test fun dx12AlwaysReportsADedicatedGpuEvenWithTheOldSwitchOff() {
        WowLaunchPolicy.save(context, WowRuntimeTuning.REPORT_DISCRETE, false)
        val env = EnvVars(); WowRuntimeTuning.apply(context, dx12(), env)
        assertEquals("1", env.get("VKD3D_REPORT_DISCRETE"))
        val typed = EnvVars("VKD3D_REPORT_DISCRETE=0"); WowRuntimeTuning.apply(context, dx12("VKD3D_REPORT_DISCRETE=0"), typed)
        assertEquals("0", typed.get("VKD3D_REPORT_DISCRETE"))
    }

    @Test fun theGameAlwaysSeesThePhonesOwnCpuAndGpuWhateverWasSavedBefore() {
        val real = EnvVars(); WowRuntimeTuning.apply(context, dx12(), real)
        check(prefs.edit().putString(WowRuntimeTuning.CPU_PROFILE, "laptop").putString(WowRuntimeTuning.GPU_PROFILE, "off").commit())
        val old = EnvVars(); WowRuntimeTuning.apply(context, dx12(), old)
        for (key in listOf("WINE_CPU_NAME", "WINE_CPU_VENDOR", "DXVK_VEGAS_PERSONA")) assertEquals(key, real.get(key), old.get(key))
        assertEquals("qualcomm", old.get("DXVK_VEGAS_PERSONA"))
        assertFalse(old.get("WINE_CPU_NAME").contains("X Plus"))
    }
}
