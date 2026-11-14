package app.gamenative.ui.screen.wow

import com.winlator.core.envvars.EnvVars
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class WowIdentityProfilesTest {
    @Test fun realNamesComeFromTheSocAndTheAdrenoModel() {
        assertEquals("Snapdragon(R) 8 Elite Gen 5 Oryon(TM) CPU", WowRuntimeTuning.realCpuName("sm8850"))
        assertNull(WowRuntimeTuning.realCpuName("MT6991"))
        assertEquals("Qualcomm(R) Adreno(TM) 840 GPU", WowRuntimeTuning.realGpuName("Adreno (TM) 840"))
        assertEquals("44050a31", WowRuntimeTuning.realGpuDeviceId("Adreno (TM) 840"))
        assertNull(WowRuntimeTuning.realGpuName(null))
        assertNull(WowRuntimeTuning.realGpuDeviceId("Adreno (TM) 999"))
    }

    @Test fun cpuProfiles() {
        val real = EnvVars().also { WowRuntimeTuning.applyCpuIdentity(it, EnvVars(), "SM8850") }
        assertEquals("Snapdragon(R) 8 Elite Gen 5 Oryon(TM) CPU", real.get("WINE_CPU_NAME"))
        assertEquals("1", real.get("WINE_CPU_PEAK_MHZ"))
        val unknown = EnvVars().also { WowRuntimeTuning.applyCpuIdentity(it, EnvVars(), "SM9999") }
        assertEquals("Snapdragon(R) SM9999 CPU", unknown.get("WINE_CPU_NAME"))
        val saved = EnvVars().also { WowRuntimeTuning.applyCpuIdentity(it, EnvVars("WINE_CPU_NAME=mine"), "SM8850") }
        assertFalse("a saved variable is never overwritten", saved.has("WINE_CPU_NAME"))
    }

    @Test fun gpuProfiles() {
        val real = EnvVars().also { WowRuntimeTuning.applyGpuIdentity(it, EnvVars(), "Adreno (TM) 840") }
        assertEquals("qualcomm", real.get("DXVK_VEGAS_PERSONA"))
        assertEquals("Qualcomm(R) Adreno(TM) 840 GPU", real.get("DXVK_VEGAS_GPU_NAME"))
        assertEquals("44050a31", real.get("DXVK_VEGAS_GPU_DEVICE"))
        // An Adreno without a known device id keeps the vendor and name but drops an inherited id it cannot vouch for.
        val unknown = EnvVars().also { it.put("DXVK_VEGAS_GPU_DEVICE", "inherited"); WowRuntimeTuning.applyGpuIdentity(it, EnvVars(), "Adreno (TM) 999") }
        assertEquals("qualcomm", unknown.get("DXVK_VEGAS_PERSONA")); assertFalse(unknown.has("DXVK_VEGAS_GPU_DEVICE"))
    }
}
