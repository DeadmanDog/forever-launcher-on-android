package app.gamenative.ui.screen.wow

import com.winlator.core.envvars.EnvVars
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Proton r32's event reuse switch: on by default, OFF = WINE_EVENT_POOL=0 (the r31 path), a saved value always wins. */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [29])
class WowEventPoolTest {
    @Test fun onRemovesAnInheritedOffAndOffSetsZero() {
        val on = EnvVars("WINE_EVENT_POOL=0"); WowRuntimeTuning.applyEventPool(on, EnvVars(""), true)
        assertFalse(on.has("WINE_EVENT_POOL"))
        val off = EnvVars(""); WowRuntimeTuning.applyEventPool(off, EnvVars(""), false)
        assertEquals("0", off.get("WINE_EVENT_POOL"))
    }

    @Test fun aSavedValueWins() {
        val env = EnvVars("WINE_EVENT_POOL=1"); WowRuntimeTuning.applyEventPool(env, EnvVars("WINE_EVENT_POOL=1"), false)
        assertEquals("1", env.get("WINE_EVENT_POOL"))
        val env2 = EnvVars("WINE_EVENT_POOL=0"); WowRuntimeTuning.applyEventPool(env2, EnvVars("WINE_EVENT_POOL=0"), true)
        assertEquals("0", env2.get("WINE_EVENT_POOL"))
    }

    @Test fun theSwitchIsOnByDefault() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences(WowLaunchPolicy.PREFS, 0).edit().clear().commit()
        assertTrue(WowLaunchPolicy.enabled(context, WowRuntimeTuning.EVENT_POOL))
        context.getSharedPreferences(WowLaunchPolicy.PREFS, 0).edit().putBoolean(WowRuntimeTuning.EVENT_POOL, false).commit()
        assertFalse(WowLaunchPolicy.enabled(context, WowRuntimeTuning.EVENT_POOL))
    }
}
