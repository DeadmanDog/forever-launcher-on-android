package app.gamenative.ui.screen.wow

import android.app.Application
import app.gamenative.ui.component.dialog.SwitchNeeds
import app.gamenative.ui.component.dialog.switchNeeds
import com.winlator.core.envvars.EnvVars
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 1.1.7: one Low input latency switch per API, and the separate "DX12 maximum latency" choice is gone (the DX12 switch covers it).
 * A saved old choice must not keep acting where the player can no longer see or change it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class Review6LatencyTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val prefs get() = context.getSharedPreferences(WowLaunchPolicy.PREFS, 0)

    @After fun clear() { prefs.edit().clear().commit() }

    @Test fun anOldDx12LatencyChoiceNoLongerActs() {
        val launch = EnvVars()
        WowRuntimeTuning.applyChoices(launch, EnvVars(), true, mapOf("latency" to "3"))
        assertFalse(launch.has("VKD3D_SWAPCHAIN_LATENCY_FRAMES"))
        // A value typed into Environment Variables still wins over the switch.
        val saved = EnvVars("VKD3D_SWAPCHAIN_LATENCY_FRAMES=2"); val typed = EnvVars(saved.toString())
        WowRuntimeTuning.applyFrameLatency(typed, dx12 = true, enabled = true)
        assertEquals("2", typed.get("VKD3D_SWAPCHAIN_LATENCY_FRAMES"))
    }

    @Test fun dx12HasItsOwnSwitchOnByDefault() {
        assertTrue(WowLaunchPolicy.enabled(context, "lowlatency12"))
        assertTrue(WowLaunchPolicy.enabled(context, "lowlatency"))
        WowLaunchPolicy.save(context, "lowlatency12", false)
        assertTrue(WowLaunchPolicy.enabled(context, "lowlatency"))
        assertFalse(WowLaunchPolicy.enabled(context, "lowlatency12"))
    }

    @Test fun aPlayerWhoTurnedTheOldSharedSwitchOffKeepsBothOff() {
        check(prefs.edit().putBoolean("lowlatency", false).commit())   // saved by 1.1.6 or older (no DX12 switch yet)
        assertFalse(WowLaunchPolicy.enabled(context, "lowlatency12"))
    }

    // 1.1.9: the DX12 switch only starts from the old shared value; turning the DX11 one on later must not turn DX12 on too.
    @Test fun turningDx11OnLeavesAnInheritedDx12Off() {
        check(prefs.edit().putBoolean("lowlatency", false).commit())   // the old shared switch, off (saved by 1.1.6 or older)
        WowLaunchPolicy.save(context, "lowlatency", true)           // now the DX11 one is turned on
        assertTrue(WowLaunchPolicy.enabled(context, "lowlatency"))
        assertFalse(WowLaunchPolicy.enabled(context, "lowlatency12"))
    }

    @Test fun eachSwitchIsGreyedOutUnderTheOtherApi() {
        assertEquals(SwitchNeeds.DX11, switchNeeds("lowlatency", dx12 = true, gamescope = false))
        assertNull(switchNeeds("lowlatency", dx12 = false, gamescope = true))
        assertEquals(SwitchNeeds.DX12, switchNeeds("lowlatency12", dx12 = false, gamescope = false))
        assertNull(switchNeeds("lowlatency12", dx12 = true, gamescope = true))
    }

    // VKD3D r11: WoW's DX12 caps render scale at 100% on a UMA GPU; since 1.3.6 DX12 always reports a dedicated GPU (no switch).
    @Test fun dedicatedGpuReportIsDx12OnlyOffByDefaultAndSavedWins() {
        val on = EnvVars(); WowRuntimeTuning.applyReportDiscrete(on, EnvVars(), dx12 = true)
        assertEquals("1", on.get("VKD3D_REPORT_DISCRETE"))
        val dx11 = EnvVars("VKD3D_REPORT_DISCRETE=1"); WowRuntimeTuning.applyReportDiscrete(dx11, EnvVars(), dx12 = false)
        assertFalse(dx11.has("VKD3D_REPORT_DISCRETE"))
        val saved = EnvVars("VKD3D_REPORT_DISCRETE=0"); val typed = EnvVars(saved.toString())
        WowRuntimeTuning.applyReportDiscrete(typed, saved, dx12 = true)
        assertEquals("0", typed.get("VKD3D_REPORT_DISCRETE"))
    }
}
