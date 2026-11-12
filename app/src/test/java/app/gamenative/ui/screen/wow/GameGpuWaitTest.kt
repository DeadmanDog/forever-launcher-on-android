package app.gamenative.ui.screen.wow

import com.winlator.core.envvars.EnvVars
import org.junit.Assert.assertEquals
import org.junit.Test

class GameGpuWaitTest {
    @Test fun dx12OnXServerKeepsFifoAndMakesTheWrapperWaitForTheGpu() {
        val env = EnvVars().apply { put("MESA_VK_WSI_PRESENT_MODE", "fifo") }
        WowRuntimeTuning.applyGameGpuWait(env, EnvVars(), true)
        assertEquals("forcesync", env.get("MESA_VK_WSI_DEBUG"))
        assertEquals("fifo", env.get("MESA_VK_WSI_PRESENT_MODE"))
    }

    @Test fun otherFlagsAreKeptAndSwAlreadyWaits() {
        val other = EnvVars().apply { put("MESA_VK_WSI_DEBUG", "noshm") }
        WowRuntimeTuning.applyGameGpuWait(other, EnvVars(), true)
        assertEquals("noshm,forcesync", other.get("MESA_VK_WSI_DEBUG"))
        val sw = EnvVars().apply { put("MESA_VK_WSI_DEBUG", "sw") }
        WowRuntimeTuning.applyGameGpuWait(sw, EnvVars(), true)
        assertEquals("sw", sw.get("MESA_VK_WSI_DEBUG"))
    }

    @Test fun offOrASavedValueChangesNothing() {
        val off = EnvVars()
        WowRuntimeTuning.applyGameGpuWait(off, EnvVars(), false)
        assertEquals("", off.get("MESA_VK_WSI_DEBUG"))
        val saved = EnvVars()
        WowRuntimeTuning.applyGameGpuWait(saved, EnvVars().apply { put("MESA_VK_WSI_DEBUG", "nochain") }, true)
        assertEquals("", saved.get("MESA_VK_WSI_DEBUG"))
    }
}
