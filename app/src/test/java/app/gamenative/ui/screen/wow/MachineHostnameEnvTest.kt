package app.gamenative.ui.screen.wow

import android.app.Application
import com.winlator.container.Container
import com.winlator.core.envvars.EnvVars
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** jaredgei 248d8d2: the launch tells the DNS shim this install's computer name; the same name every launch; a saved value wins. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class MachineHostnameEnvTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @Test fun aLaunchCarriesTheInstallsComputerNameEveryTime() {
        val first = EnvVars(); WowRuntimeTuning.apply(context, Container("wow_forever"), first)
        val name = first.get(WowMachineIdentity.HOSTNAME_ENV)
        assertTrue(name, Regex("DESKTOP-[A-Z0-9]{7}").matches(name))
        val second = EnvVars(); WowRuntimeTuning.apply(context, Container("wow_forever"), second)
        assertEquals(name, second.get(WowMachineIdentity.HOSTNAME_ENV))
        assertEquals(name, WowMachineIdentity.load(context).computerName)
    }

    @Test fun aSavedValueWins() {
        val env = EnvVars("GAMENATIVE_HOSTNAME=MYPC")
        WowRuntimeTuning.applyMachineHostname(env, EnvVars("GAMENATIVE_HOSTNAME=MYPC"), "DESKTOP-AAAAAAA")
        assertEquals("MYPC", env.get(WowMachineIdentity.HOSTNAME_ENV))
    }
}
