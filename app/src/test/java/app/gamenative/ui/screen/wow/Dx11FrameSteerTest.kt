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
 * TurnRIP 1.1.37 (player report: Balanced held ~720 MHz in Stormwind with a low frame rate): under DX11 the DXVK GPU waits never
 * steer the wait boost, so the launcher turns frame steering on for XServer + DX11 too. A saved value wins.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class Dx11FrameSteerTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @After fun clear() { context.getSharedPreferences(WowLaunchPolicy.PREFS, 0).edit().clear().commit() }

    @Test fun xserverDx11TurnsFrameSteeringOnAndASavedValueWins() {
        val on = EnvVars(); WowRuntimeTuning.applyDx11FrameSteer(on, EnvVars(), xserverDx11 = true)
        assertEquals("1", on.get("TU_KGSL_WAITBOOST_FRAME_STEER"))
        val other = EnvVars(); WowRuntimeTuning.applyDx11FrameSteer(other, EnvVars(), xserverDx11 = false)
        assertFalse(other.has("TU_KGSL_WAITBOOST_FRAME_STEER"))
        val typed = EnvVars("TU_KGSL_WAITBOOST_FRAME_STEER=0")
        WowRuntimeTuning.applyDx11FrameSteer(typed, EnvVars("TU_KGSL_WAITBOOST_FRAME_STEER=0"), xserverDx11 = true)
        assertEquals("0", typed.get("TU_KGSL_WAITBOOST_FRAME_STEER"))
    }

    @Test fun aDx11LaunchHasFrameSteeringAndNoVkd3dSteering() {
        val dx11 = Container("wow_forever").apply { putExtra(WowVkd3dComponents.API_KEY, WowVkd3dComponents.DX11); envVars = "" }
        val env = EnvVars(); WowRuntimeTuning.apply(context, dx11, env)
        assertEquals("1", env.get("TU_KGSL_WAITBOOST_FRAME_STEER"))
        assertFalse(env.has("TU_KGSL_WAITBOOST_VKD3D_STEERS"))
        assertEquals("0", env.get("TU_KGSL_PWR_MAX"))    // Balanced (the default GPU mode)
    }
}
