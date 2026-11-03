package app.gamenative.ui.screen.wow

import com.winlator.core.envvars.EnvVars
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class WowVegasBindSkipTest {
    @Test fun switchSetsOrRemovesTheVariable() {
        val on = EnvVars().also { WowRuntimeTuning.applyVegasBindSkip(it, EnvVars(), true) }
        assertEquals("1", on.get("VEGAS_BINDSKIP"))
        val off = EnvVars().also { it.put("VEGAS_BINDSKIP", "1"); WowRuntimeTuning.applyVegasBindSkip(it, EnvVars(), false) }
        assertFalse(off.has("VEGAS_BINDSKIP"))
    }

    @Test fun aSavedVariableWinsInBothDirections() {
        val kept = EnvVars().also { WowRuntimeTuning.applyVegasBindSkip(it, EnvVars("VEGAS_BINDSKIP=0"), true) }
        assertFalse("the saved value is applied by the container environment, not overwritten here", kept.has("VEGAS_BINDSKIP"))
    }

    @Test fun offByDefault() {
        // The policy default set must not contain the switch.
        assertFalse(setOf(WowRuntimeTuning.LOW_LATENCY, WowRuntimeTuning.CPU_CLASSES, "priority").contains(WowRuntimeTuning.VEGAS_BINDSKIP))
    }
}

class WowHybridGplTest {
    @Test fun switchSetsAndClearsTheVariable() {
        val on = EnvVars().also { WowRuntimeTuning.applyHybridGpl(it, EnvVars(), true) }
        assertEquals("1", on.get("DXVK_HYBRID_GPL"))
        val off = EnvVars().also { it.put("DXVK_HYBRID_GPL", "1"); WowRuntimeTuning.applyHybridGpl(it, EnvVars(), false) }
        assertFalse(off.has("DXVK_HYBRID_GPL"))
    }

    @Test fun savedVariableWins() {
        val kept = EnvVars().also { WowRuntimeTuning.applyHybridGpl(it, EnvVars("DXVK_HYBRID_GPL=0"), true) }
        assertFalse(kept.has("DXVK_HYBRID_GPL"))
    }

    @Test fun offByDefault() {
        assertFalse(setOf(WowRuntimeTuning.LOW_LATENCY, WowRuntimeTuning.CPU_CLASSES, "priority").contains(WowRuntimeTuning.HYBRID_GPL))
    }
}
