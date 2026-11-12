package app.gamenative.ui.screen.wow

import android.app.Application
import com.winlator.container.Container
import com.winlator.core.envvars.EnvVars
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [29])
class WowQuickSettingsTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @Test fun languageFollowsExplicitChoiceOrSystemLocale() {
        assertTrue(WowUiLanguage.isHungarian(WowLanguage.HU, "en"))
        assertFalse(WowUiLanguage.isHungarian(WowLanguage.EN, "hu"))
        assertTrue(WowUiLanguage.isHungarian(WowLanguage.SYSTEM, "hu"))
        assertFalse(WowUiLanguage.isHungarian(WowLanguage.SYSTEM, "de"))
    }

    @Test fun savedLanguageRoundTripsAndDefaultsToSystem() {
        context.getSharedPreferences("wow_ui", 0).edit().clear().commit()
        assertEquals(WowLanguage.SYSTEM, WowUiLanguage.saved(context))
        for (language in WowLanguage.entries) {
            WowUiLanguage.save(context, language)
            assertEquals(language, WowUiLanguage.saved(context))
        }
        context.getSharedPreferences("wow_ui", 0).edit().putString("language", "xx").commit()
        assertEquals(WowLanguage.SYSTEM, WowUiLanguage.saved(context))
    }

    @Test fun shortNamesForBundledRuntimesAndForeignNamesUnchanged() {
        assertEquals("Proton 11.0 ARM64EC", WowQuickSettings.shortRuntime("proton-11.0-90639.12-arm64ec-12"))
        assertEquals("Proton 11.0 ARM64EC", WowQuickSettings.shortRuntime("proton-11.0-90639.9-arm64ec-9"))
        assertEquals("other-proton-1", WowQuickSettings.shortRuntime("other-proton-1"))
        assertEquals("DXVK 2.7.3", WowQuickSettings.shortDxvk("dxvk-Vegas-2.7.3-wow-arm64-hybrid-gamenative-r16-16"))
        assertEquals("DXVK 2.7.3", WowQuickSettings.shortDxvk("dxvk-Vegas-2.7.3-wow-arm64-async-gamenative-r13-13"))
        assertEquals("dxvk-custom-1", WowQuickSettings.shortDxvk("dxvk-custom-1"))
        assertEquals("DXVK 3.1.1", WowQuickSettings.shortDxvk("dxvk-Vegas-3.1.1-wow-arm64-hybrid-gamenative-r60-60"))
    }

    @Test fun fpsLimitMatchesInGameQuickMenuDefaults() {
        val c = Container("wow_forever")
        assertEquals(WowQuickSettings.FpsLimit(true, 60), WowQuickSettings.fpsLimit(c))
        c.putExtra("fpsLimiterEnabled", false); c.putExtra("fpsLimiterTarget", 90)
        assertEquals(WowQuickSettings.FpsLimit(false, 90), WowQuickSettings.fpsLimit(c))
        c.putExtra("fpsLimiterTarget", "garbage")
        assertEquals(60, WowQuickSettings.fpsLimit(c).target)
    }

    @Test fun statusSummarisesNextLaunchAndSavedGpuOverrideWins() {
        context.getSharedPreferences(WowLaunchPolicy.PREFS, 0).edit().clear().commit()
        val c = Container("wow_forever").apply {
            wineVersion = "proton-11.0-90639.12-arm64ec-12"
            setDXWrapper("dxvk-Vegas-2.7.3-wow-arm64-hybrid-gamenative-r16-16")
            graphicsDriverConfig = "version=TurnRIP A840 Performance v1.0.6"
            envVars = ""
        }
        var s = WowQuickSettings.status(context, c)
        assertEquals("DX11", s.api); assertEquals("Proton 11.0 ARM64EC", s.runtime); assertEquals("DXVK 2.7.3", s.dxvk)
        assertEquals("TurnRIP A840 Performance v1.0.6", s.driver); assertEquals("balanced", s.gpuMode); assertEquals("60", s.fps)
        context.getSharedPreferences(WowLaunchPolicy.PREFS, 0).edit().putString("gpu", "0").commit()
        assertEquals("balanced", WowQuickSettings.status(context, c).gpuMode)
        c.envVars = "TU_KGSL_PWR_MAX=1"
        assertEquals("performance", WowQuickSettings.status(context, c).gpuMode)
        c.putExtra("fpsLimiterEnabled", false)
        s = WowQuickSettings.status(context, c)
        assertEquals("off", s.fps)
        context.getSharedPreferences(WowLaunchPolicy.PREFS, 0).edit().clear().commit()
    }

    @Test fun aSavedFloorStillActsUnderEitherMenuChoice() {
        // 1.1.5: the menu has no Regulated floors any more (Review5SettingsTest); a TU_KGSL_GPU_FLOOR typed into Environment
        // Variables is still passed through with PWR_MAX off.
        val savedFloor = EnvVars("TU_KGSL_GPU_FLOOR=40"); val launch = EnvVars(savedFloor.toString())
        WowRuntimeTuning.applyChoices(launch, savedFloor, true, mapOf("gpu" to ""))
        assertEquals("40", launch.get("TU_KGSL_GPU_FLOOR")); assertEquals("0", launch.get("TU_KGSL_PWR_MAX"))
        val plain = EnvVars(); WowRuntimeTuning.applyChoices(plain, EnvVars(), true, mapOf("gpu" to "0"))
        assertFalse(plain.has("TU_KGSL_GPU_FLOOR"))
    }

    @Test fun nextLaunchShowsVkd3dForDx12AndDxvkForDx11() {
        // The NEXT LAUNCH card showed the DXVK line even when DX12 was selected.
        val c = Container("wow_forever").apply {
            envVars = ""
            setDXWrapper("dxvk-Vegas-2.7.3-wow-arm64-hybrid-gamenative-r51-51")
            putExtra(WowVkd3dComponents.PROFILE_KEY, "vkd3d-${WowBundledComponents.VKD3D_NAME}-${WowBundledComponents.VKD3D_VERSION_CODE}")
        }
        assertEquals("DXVK 2.7.3", WowQuickSettings.status(context, c).graphicsLayer)
        c.putExtra(WowVkd3dComponents.API_KEY, WowVkd3dComponents.DX12)
        val dx12 = WowQuickSettings.status(context, c)
        assertEquals("DX12", dx12.api)
        assertEquals("VKD3D 3.0.1", dx12.graphicsLayer)
        assertEquals("VKD3D 3.0.1", WowQuickSettings.shortVkd3d("vkd3d-FrontMage-VKD3D-Proton-3.0.1-ARM64-r10-10"))
        assertEquals("vkd3d-Other-1", WowQuickSettings.shortVkd3d("vkd3d-Other-1"))
    }

    @Test fun nextLaunchGpuFollowsTheLaunchPrecedence() {
        // Codex UI16-03: a saved TU_KGSL_GPU_FLOOR (or a saved PWR_MAX=0 under a menu floor) used to show up as Balanced.
        val prefs = context.getSharedPreferences(WowLaunchPolicy.PREFS, 0)
        fun gpu(choice: String?, saved: String): String {
            prefs.edit().clear().apply { if (choice != null) putString("gpu", choice) }.commit()
            return WowQuickSettings.status(context, Container("wow_forever").apply { envVars = saved }).gpuMode
        }
        assertEquals("floor80", gpu(null, "TU_KGSL_PWR_MAX=0 TU_KGSL_GPU_FLOOR=80"))
        assertEquals("balanced", gpu("f65", "TU_KGSL_PWR_MAX=0"))   // an old Regulated choice runs as Balanced (1.1.5)
        assertEquals("floor80", gpu("f50", "TU_KGSL_GPU_FLOOR=80"))
        assertEquals("floor80", gpu("1", "TU_KGSL_PWR_MAX=0 TU_KGSL_GPU_FLOOR=80"))
        assertEquals("floor40", gpu("f80", "TU_KGSL_GPU_FLOOR=40"))
        assertEquals("performance", gpu("f65", "TU_KGSL_PWR_MAX=1"))
        assertEquals("performance", gpu("1", ""))
        assertEquals("balanced", gpu(null, ""))
        assertEquals("balanced", gpu("f50", ""))
        prefs.edit().clear().commit()
    }

    @Test fun nextLaunchFpsFollowsTheLimiterThatOwnsPacing() {
        // Codex UI16-03: a typed DXVK/VKD3D limit owns pacing at launch, but the card showed the container's limit instead.
        val prefs = context.getSharedPreferences(WowLaunchPolicy.PREFS, 0)
        fun fps(dx12: Boolean, saved: String, containerLimit: Int?, wrapper: String = "dxvk-Vegas-2.7.3-wow-arm64-hybrid-gamenative-r51-51"): String {
            val c = Container("wow_forever").apply {
                envVars = saved; setDXWrapper(wrapper)
                if (dx12) putExtra(WowVkd3dComponents.API_KEY, WowVkd3dComponents.DX12)
                if (containerLimit == null) putExtra("fpsLimiterEnabled", false) else putExtra("fpsLimiterTarget", containerLimit)
            }
            val before = WowRuntimeTuning.apiLimitAtLaunch
            return WowQuickSettings.status(context, c).fps.also { assertEquals(before, WowRuntimeTuning.apiLimitAtLaunch) }
        }
        prefs.edit().clear().commit()
        assertEquals("30", fps(true, "VKD3D_FRAME_RATE=30", 60))
        assertEquals("30", fps(true, "VKD3D_FRAME_RATE=30", null))
        assertEquals("30", fps(false, "DXVK_FRAME_RATE=30", 60))
        assertEquals("40", fps(false, "DXVK_CONFIG=dxgi.maxFrameRate=40", 60))
        assertEquals("45", fps(false, "DXVK_CONFIG=dxvk.maxFrameRate=45", 60, "dxvk-Vegas-3.1.1-wow-arm64-hybrid-gamenative-r63-63"))
        // Controls: the app limiter, its OFF state, and the API limiter chosen in the menu without a typed value.
        assertEquals("60", fps(false, "", 60))
        assertEquals("off", fps(true, "", null))
        prefs.edit().putString("pacing", "api").commit()
        assertEquals("72", fps(true, "", 72))
        prefs.edit().clear().commit()
    }

    @Test fun adpfTargetFollowsFpsLimitDefaultsTo60AndSavedWins() {
        val env = EnvVars(); WowRuntimeTuning.applyAdpfTarget(env, EnvVars(), 45); assertEquals("45", env.get("TU_ADPF_TARGET_FPS"))
        val off = EnvVars(); WowRuntimeTuning.applyAdpfTarget(off, EnvVars(), 0); assertEquals("60", off.get("TU_ADPF_TARGET_FPS"))
        val saved = EnvVars("TU_ADPF_TARGET_FPS=30"); val launch = EnvVars(saved.toString())
        WowRuntimeTuning.applyAdpfTarget(launch, saved, 90); assertEquals("30", launch.get("TU_ADPF_TARGET_FPS"))
    }

    @Test fun appVsyncPacingIsDefaultAndInjectsNoApiLimiter() {
        val prefs = context.getSharedPreferences(WowLaunchPolicy.PREFS, 0); prefs.edit().clear().commit()
        val c = Container("wow_forever").apply { envVars = "" }
        assertTrue(WowRuntimeTuning.appPacing(context, c))
        for (dx12 in listOf(false, true)) {
            val env = EnvVars("DXVK_FRAME_RATE=60 VKD3D_FRAME_RATE=60")
            assertTrue(WowRuntimeTuning.applyAppPacing(env, 60).startsWith("App Present limiter: 60 FPS"))
            assertFalse(env.has("DXVK_FRAME_RATE")); assertFalse(env.has("VKD3D_FRAME_RATE"))
            assertNull(WowRuntimeTuning.configFrameRate(env.get("DXVK_CONFIG")))
        }
        WowRuntimeTuning.save(context, "pacing", "api"); assertFalse(WowRuntimeTuning.appPacing(context, c))
        assertThrows(IllegalArgumentException::class.java) { WowRuntimeTuning.save(context, "pacing", "bogus") }
        WowRuntimeTuning.save(context, "pacing", ""); assertTrue(WowRuntimeTuning.appPacing(context, c))
        assertTrue(WowRuntimeTuning.vsyncPacing(context))
        // 1.3.2: two limiters only. The timer and the separate phase choice are gone; an old saved one is the app pacer.
        for (retired in listOf("timer", "phase")) {
            assertThrows(IllegalArgumentException::class.java) { WowRuntimeTuning.save(context, "pacing", retired) }
            check(prefs.edit().putString("pacing", retired).commit())
            assertEquals("", WowRuntimeTuning.selected(context, "pacing"))
            assertTrue(WowRuntimeTuning.appPacing(context, c)); assertTrue(WowRuntimeTuning.vsyncPacing(context))
            assertTrue(WowRuntimeTuning.phaseAlign(context))
        }
        WowRuntimeTuning.save(context, "pacing", "api"); assertFalse(WowRuntimeTuning.phaseAlign(context))
        WowRuntimeTuning.save(context, "pacing", ""); assertTrue(WowRuntimeTuning.phaseAlign(context))
        // A limiter typed by the user keeps the API path.
        c.envVars = "DXVK_CONFIG=dxgi.maxFrameRate=45"; assertFalse(WowRuntimeTuning.appPacing(context, c))
        c.envVars = "VKD3D_FRAME_RATE=50"; assertFalse(WowRuntimeTuning.appPacing(context, c))
        prefs.edit().clear().commit()
    }

    @Test fun handoffChoiceIsLaunchLocalForBothApisAndSavedValueWins() {
        for (dx12 in listOf(false, true)) {
            val launch = EnvVars(); WowRuntimeTuning.applyChoices(launch, EnvVars(), dx12, mapOf("handoff" to "1"))
            assertEquals("1", launch.get("NTSYNC_WAKE_HANDOFF"))
        }
        val saved = EnvVars("NTSYNC_WAKE_HANDOFF=0"); val launch = EnvVars(saved.toString())
        WowRuntimeTuning.applyChoices(launch, saved, false, mapOf("handoff" to "1"))
        assertEquals("0", launch.get("NTSYNC_WAKE_HANDOFF"))
    }
}
