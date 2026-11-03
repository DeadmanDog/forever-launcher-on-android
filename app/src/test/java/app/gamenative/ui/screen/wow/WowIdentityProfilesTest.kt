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
        val laptop = EnvVars().also { WowRuntimeTuning.applyCpuIdentity(it, EnvVars(), "laptop", "SM8850") }
        assertEquals("Snapdragon(R) X Plus Oryon(TM) CPU", laptop.get("WINE_CPU_NAME"))
        assertEquals("1", laptop.get("WINE_CPU_PEAK_MHZ"))
        val real = EnvVars().also { WowRuntimeTuning.applyCpuIdentity(it, EnvVars(), "", "SM8850") }
        assertEquals("Snapdragon(R) 8 Elite Gen 5 Oryon(TM) CPU", real.get("WINE_CPU_NAME"))
        val off = EnvVars().also { it.put("WINE_CPU_NAME", "inherited"); WowRuntimeTuning.applyCpuIdentity(it, EnvVars(), "off", "SM8850") }
        assertFalse(off.has("WINE_CPU_NAME")); assertFalse(off.has("WINE_CPU_PEAK_MHZ"))
        val saved = EnvVars().also { WowRuntimeTuning.applyCpuIdentity(it, EnvVars("WINE_CPU_NAME=mine"), "", "SM8850") }
        assertFalse("a saved variable is never overwritten", saved.has("WINE_CPU_NAME"))
    }

    @Test fun gpuProfiles() {
        val laptop = EnvVars().also { WowRuntimeTuning.applyGpuIdentity(it, EnvVars(), "laptop", "Adreno (TM) 840") }
        assertEquals("qualcomm", laptop.get("DXVK_VEGAS_PERSONA")); assertFalse(laptop.has("DXVK_VEGAS_GPU_NAME"))
        val real = EnvVars().also { WowRuntimeTuning.applyGpuIdentity(it, EnvVars(), "", "Adreno (TM) 840") }
        assertEquals("qualcomm", real.get("DXVK_VEGAS_PERSONA"))
        assertEquals("Qualcomm(R) Adreno(TM) 840 GPU", real.get("DXVK_VEGAS_GPU_NAME"))
        assertEquals("44050a31", real.get("DXVK_VEGAS_GPU_DEVICE"))
        val off = EnvVars().also { it.put("DXVK_VEGAS_PERSONA", "qualcomm"); WowRuntimeTuning.applyGpuIdentity(it, EnvVars(), "off", "Adreno (TM) 840") }
        assertFalse(off.has("DXVK_VEGAS_PERSONA"))
    }
}
