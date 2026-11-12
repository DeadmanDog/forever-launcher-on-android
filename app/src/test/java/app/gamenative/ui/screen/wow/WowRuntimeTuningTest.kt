package app.gamenative.ui.screen.wow

import app.gamenative.display.WowDisplayBackend
import com.winlator.container.Container
import com.winlator.core.envvars.EnvVars
import org.junit.Assert.*
import org.junit.Test

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(application = android.app.Application::class, sdk = [29])
class WowRuntimeTuningTest {
    @org.robolectric.annotation.Config(sdk = [34])
    @Test fun gamescopeFifoIsCappedByGamescopesVblankLimiterWithoutAGameSideTimer() {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        val container = Container("wow_forever").apply {
            putExtra(WowDisplayBackend.KEY, WowDisplayBackend.GAMESCOPE)
            putExtra(WowVkd3dComponents.API_KEY, WowVkd3dComponents.DX12)
            graphicsDriverConfig = "presentMode=immediate"
        }
        val env = EnvVars().apply { put("MESA_VK_WSI_PRESENT_MODE", "immediate") }
        assertFalse(WowRuntimeTuning.appPacing(context, container))
        WowRuntimeTuning.apply(context, container, env)
        assertEquals("fifo", env.get("MESA_VK_WSI_PRESENT_MODE"))
        // The shim does not gate FIFO ("gate=off"): Gamescope's limiter is the cap, so no VKD3D timer on top (1.0.3 stacked both).
        assertFalse(env.has("VKD3D_FRAME_RATE"))
        assertEquals(0, WowRuntimeTuning.apiLimitAtLaunch)
        assertEquals("60", WowRuntimeTuning.preview(context, container).fps)

        container.putExtra(WowDisplayBackend.KEY, WowDisplayBackend.XSERVER)
        val xserver = EnvVars()
        WowRuntimeTuning.apply(context, container, xserver)
        assertFalse(xserver.has("VKD3D_FRAME_RATE"))
    }

    @org.robolectric.annotation.Config(sdk = [34])
    @Test fun gamescopeDxvkCapsHigherOverridesAndKeepsUnrelatedConfig() {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        val saved = "dxvk.maxFrameRate=90;dxgi.maxFrameLatency=2"
        val container = Container("wow_forever").apply {
            dxWrapper = WowBundledComponents.DXVK_ID
            putExtra(WowDisplayBackend.KEY, WowDisplayBackend.GAMESCOPE)
            envVars = EnvVars().apply {
                put("MESA_VK_WSI_PRESENT_MODE", "immediate")
                put("DXVK_CONFIG", saved)
                put("DXVK_FRAME_RATE", 120)
            }.toString()
        }
        val env = EnvVars(container.envVars)
        repeat(2) { WowRuntimeTuning.apply(context, container, env) }
        assertEquals("$saved;dxvk.maxFrameRate=60", env.get("DXVK_CONFIG"))
        assertFalse(env.has("DXVK_FRAME_RATE"))
        assertEquals(saved, EnvVars(container.envVars).get("DXVK_CONFIG"))
        assertEquals("60", WowRuntimeTuning.preview(context, container).fps)
        assertEquals("immediate", env.get("MESA_VK_WSI_PRESENT_MODE"))
    }

    @org.robolectric.annotation.Config(sdk = [34])
    @Test fun gamescopeRetainsALowerExplicitCapAndOffAddsNoCap() {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        val container = Container("wow_forever").apply {
            putExtra(WowDisplayBackend.KEY, WowDisplayBackend.GAMESCOPE)
            putExtra(WowVkd3dComponents.API_KEY, WowVkd3dComponents.DX12)
            envVars = "VKD3D_FRAME_RATE=48"
        }
        val env = EnvVars(container.envVars)
        WowRuntimeTuning.apply(context, container, env)
        assertEquals("48", env.get("VKD3D_FRAME_RATE"))
        container.envVars = ""
        container.putExtra("fpsLimiterEnabled", false)
        val disabled = EnvVars()
        WowRuntimeTuning.apply(context, container, disabled)
        assertFalse(disabled.has("VKD3D_FRAME_RATE"))
        assertEquals(0, WowRuntimeTuning.apiLimitAtLaunch)
    }

    @org.robolectric.annotation.Config(sdk = [34])
    @Test fun gamescopeDx11FifoLeavesOtherDxvkSettingsAndFallsBackWithoutProxy() {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        val container = Container("wow_forever").apply {
            dxWrapper = WowBundledComponents.DXVK_ID
            putExtra(WowDisplayBackend.KEY, WowDisplayBackend.GAMESCOPE)
            envVars = "DXVK_CONFIG=dxgi.maxFrameLatency=2"
        }
        val env = EnvVars(container.envVars)
        WowRuntimeTuning.apply(context, container, env)
        assertEquals("dxgi.maxFrameLatency=2", env.get("DXVK_CONFIG"))
        assertFalse(env.has("DXVK_FRAME_RATE"))
        container.envVars += " GN_VKSHIM_NO_PROXY=1"
        val withoutProxy = EnvVars(container.envVars)
        WowRuntimeTuning.apply(context, container, withoutProxy)
        assertEquals("60", WowRuntimeTuning.configFrameRate(withoutProxy.get("DXVK_CONFIG"), true))
    }

    @Test fun dx11LimiterMergesWithoutLosingConfig() {
        val env = EnvVars().apply { put("DXVK_CONFIG", "dxgi.maxDeviceMemory=2048;dxvk.enableAsync=True") }
        WowRuntimeTuning.applyLimiter(env, false, 60)
        assertEquals("dxgi.maxDeviceMemory=2048;dxvk.enableAsync=True;dxgi.maxFrameRate=60", env.get("DXVK_CONFIG"))
        assertEquals("", env.get("VKD3D_FRAME_RATE"))
    }
    @Test fun dx11LowLatencyAppendsFrameLatencyOnceAndUserConfigWins() {
        val env = EnvVars().apply { put("DXVK_CONFIG", "dxgi.maxDeviceMemory=2048;") }
        WowRuntimeTuning.applyFrameLatency(env, false, true)
        WowRuntimeTuning.applyFrameLatency(env, false, true)
        assertEquals("dxgi.maxDeviceMemory=2048;dxgi.maxFrameLatency=1", env.get("DXVK_CONFIG"))
        WowRuntimeTuning.applyLimiter(env, false, 60)
        assertEquals("dxgi.maxDeviceMemory=2048;dxgi.maxFrameLatency=1;dxgi.maxFrameRate=60", env.get("DXVK_CONFIG"))
        val user = EnvVars().apply { put("DXVK_CONFIG", "dxgi.maxFrameLatency = 3") }
        WowRuntimeTuning.applyFrameLatency(user, false, true)
        assertEquals("dxgi.maxFrameLatency = 3", user.get("DXVK_CONFIG"))
        val empty = EnvVars()
        WowRuntimeTuning.applyFrameLatency(empty, false, true)
        assertEquals("dxgi.maxFrameLatency=1", empty.get("DXVK_CONFIG"))
        for ((dx12, enabled) in listOf(true to true, false to false)) {
            val untouched = EnvVars()
            WowRuntimeTuning.applyFrameLatency(untouched, dx12, enabled)
            assertFalse(untouched.has("DXVK_CONFIG"))
        }
    }
    @Test fun customConfigIncludingZeroWinsOverContainerAndLegacyEnv() {
        for (value in listOf("0", "48", "75")) {
            val env = EnvVars().apply { put("DXVK_CONFIG", "dxgi.maxFrameRate = $value;dxgi.maxDeviceMemory=1234"); put("DXVK_FRAME_RATE", "120") }
            WowRuntimeTuning.applyLimiter(env, false, 60)
            assertEquals(value, WowRuntimeTuning.configFrameRate(env.get("DXVK_CONFIG")))
            assertFalse(env.has("DXVK_FRAME_RATE"))
        }
    }
    @Test fun repeatedLaunchPreparationIsIdempotent() {
        val env = EnvVars()
        repeat(3) { WowRuntimeTuning.applyLimiter(env, false, 60) }
        assertEquals("dxgi.maxFrameRate=60", env.get("DXVK_CONFIG"))
    }
    @Test fun dx12UsesOnlyVkd3dAndKeepsUserOverride() {
        val env = EnvVars("WINEESYNC=0 TU_DEBUG=noconform,gmem")
        WowRuntimeTuning.applyLimiter(env, true, 60)
        assertEquals("60", env.get("VKD3D_FRAME_RATE")); assertFalse(env.has("DXVK_CONFIG"))
        env.put("VKD3D_FRAME_RATE", 48); WowRuntimeTuning.applyLimiter(env, true, 60)
        assertEquals("48", env.get("VKD3D_FRAME_RATE")); assertEquals("0", env.get("WINEESYNC"))
        assertEquals("noconform,gmem", env.get("TU_DEBUG"))
    }
    @Test fun disabledLimiterAddsNoSettings() {
        val env = EnvVars(); WowRuntimeTuning.applyLimiter(env, false, 0); assertTrue(env.isEmpty)
    }
    @Test fun environmentEditorRoundTripsEqualsSemicolonsAndSpaces() {
        val text = "dxgi.maxFrameRate = 60;dxgi.maxDeviceMemory=2048"
        val serialized = WowEnvironmentEditor.serialize(listOf(WowEnvironmentEditor.Entry("DXVK_CONFIG", text)))
        assertEquals(text, EnvVars(serialized).get("DXVK_CONFIG"))
        assertEquals(text, WowEnvironmentEditor.load(serialized).single().value)
    }
    @Test fun choicesAreLaunchLocalAndSavedOverridesWin() {
        val saved = EnvVars("VKD3D_SWAPCHAIN_IMAGES=4 OTHER=keep")
        val launch = EnvVars(saved.toString())
        WowRuntimeTuning.applyChoices(launch, saved, true, mapOf("images" to "2", "latency" to "1", "present" to "mailbox", "async" to "2"))
        // 1.1.7: "latency" is no choice any more (the DX12 low-latency switch sets it, see Review6LatencyTest).
        assertEquals("4", launch.get("VKD3D_SWAPCHAIN_IMAGES")); assertFalse(launch.has("VKD3D_SWAPCHAIN_LATENCY_FRAMES"))
        assertEquals("mailbox", launch.get("MESA_VK_WSI_PRESENT_MODE")); assertFalse(launch.has("DXVK_ASYNC"))
        assertFalse(saved.has("VKD3D_SWAPCHAIN_LATENCY_FRAMES"))
    }
    @Test fun dx11GetsPresentAndGpuModeButNotDx12SwapchainControls() {
        // Before v0.1.52 every non-async choice was skipped for DX11, so the game present mode never reached DXVK.
        val saved = EnvVars("OTHER=keep"); val launch = EnvVars(saved.toString())
        WowRuntimeTuning.applyChoices(launch, saved, false, mapOf("images" to "2", "latency" to "1", "present" to "mailbox", "async" to "2", "gpu" to "0"))
        assertEquals("mailbox", launch.get("MESA_VK_WSI_PRESENT_MODE")); assertEquals("0", launch.get("TU_KGSL_PWR_MAX"))
        assertEquals("2", launch.get("DXVK_ASYNC"))
        assertFalse(launch.has("VKD3D_SWAPCHAIN_IMAGES")); assertFalse(launch.has("VKD3D_SWAPCHAIN_LATENCY_FRAMES"))
    }
    @Test fun gpuPowerModeAppliesToBothApisAndSavedValueWins() {
        for (dx12 in listOf(false, true)) for (mode in listOf("0", "1")) {
            val launch = EnvVars(); WowRuntimeTuning.applyChoices(launch, EnvVars(), dx12, mapOf("gpu" to mode))
            assertEquals(mode, launch.get("TU_KGSL_PWR_MAX"))
        }
        val saved = EnvVars("TU_KGSL_PWR_MAX=1"); val launch = EnvVars(saved.toString())
        WowRuntimeTuning.applyChoices(launch, saved, true, mapOf("gpu" to "0"))
        assertEquals("1", launch.get("TU_KGSL_PWR_MAX"))
        // v0.1.73+: no choice means Balanced on both APIs; a saved PWR_MAX still wins over the default.
        for (dx12 in listOf(false, true)) {
            val unset = EnvVars(); WowRuntimeTuning.applyChoices(unset, EnvVars(), dx12, mapOf("gpu" to ""))
            assertEquals("0", unset.get("TU_KGSL_PWR_MAX")); assertFalse(unset.has("TU_KGSL_GPU_FLOOR"))
        }
        val savedDefault = EnvVars("TU_KGSL_PWR_MAX=1"); val keep = EnvVars(savedDefault.toString())
        WowRuntimeTuning.applyChoices(keep, savedDefault, false, mapOf("gpu" to ""))
        assertEquals("1", keep.get("TU_KGSL_PWR_MAX"))
    }
    @Test fun diagnosticStatsAreOffEvenWithInheritedOrSavedPaths() {
        val saved = EnvVars("WINE_REQ_STATS=/custom/wine TU_STATS_FILE=/custom/turnrip OTHER=keep")
        val before = saved.toString(); val launch = EnvVars(saved.toString())
        WowRuntimeTuning.applyDiagnosticStats(launch, saved, false)
        assertFalse(launch.has("WINE_REQ_STATS")); assertFalse(launch.has("TU_STATS_FILE"))
        assertEquals("keep", launch.get("OTHER")); assertEquals(before, saved.toString())
    }
    @Test fun diagnosticStatsUseDefaultsOnlyWhenEnabledAndRespectSavedPathsAndOffValues() {
        val launch = EnvVars()
        WowRuntimeTuning.applyDiagnosticStats(launch, EnvVars(), true)
        assertEquals(WowRuntimeTuning.REQ_STATS_FILE, launch.get("WINE_REQ_STATS"))
        assertEquals(WowRuntimeTuning.STATS_FILE, launch.get("TU_STATS_FILE"))
        for (value in listOf("", "/custom/reqstats")) {
            val saved = EnvVars().apply { put("WINE_REQ_STATS", value); put("TU_STATS_FILE", value) }
            WowRuntimeTuning.applyDiagnosticStats(launch, saved, true)
            assertEquals(value, launch.get("WINE_REQ_STATS")); assertEquals(value, launch.get("TU_STATS_FILE"))
        }
    }
    @Test fun dxvkThreeReadsTheNewerFrameRateKeyAndR50DoesNot() {
        assertEquals("40", WowRuntimeTuning.configFrameRate("dxgi.maxFrameRate=60;dxvk.maxFrameRate=40", alias = true))
        assertEquals("60", WowRuntimeTuning.configFrameRate("dxgi.maxFrameRate=60;dxvk.maxFrameRate=40"))
        assertEquals(null, WowRuntimeTuning.configFrameRate("dxvk.maxFrameRate=40"))
        assertEquals("40", WowRuntimeTuning.configFrameRate("dxvk.maxFrameRate=40", alias = true))
        val saved = EnvVars().apply { put("DXVK_CONFIG", "dxvk.maxFrameRate=60") }
        assertTrue(WowRuntimeTuning.hasUserApiLimiter(saved, alias = true))   // r61: the API limiter owns pacing, no second limiter
        assertFalse(WowRuntimeTuning.hasUserApiLimiter(saved))                 // r50 ignores the key: the app limiter stays
        val env = EnvVars().apply { put("DXVK_CONFIG", "dxvk.maxFrameRate=50") }
        assertTrue(WowRuntimeTuning.applyLimiter(env, false, 60, alias = true).startsWith("DXVK user config: 50"))
        assertEquals("dxvk.maxFrameRate=50", env.get("DXVK_CONFIG"))
        // r61: an explicit dxvk.maxFrameRate=0 (no limit) wins over the older key, as in DXVK; a value that is no integer falls back.
        assertEquals("0", WowRuntimeTuning.configFrameRate("dxgi.maxFrameRate=60;dxvk.maxFrameRate=0", alias = true))
        assertEquals("60", WowRuntimeTuning.configFrameRate("dxgi.maxFrameRate=60;dxvk.maxFrameRate=abc", alias = true))
        assertEquals("-5", WowRuntimeTuning.configFrameRate("dxvk.maxFrameRate=-5", alias = true))
        assertEquals(null, WowRuntimeTuning.configFrameRate("dxvk.maxFrameRate=abc", alias = true))
        assertEquals("75", WowRuntimeTuning.configFrameRate("dxvk.maxFrameRate=30;dxvk.maxFrameRate=75", alias = true))
        // The container's DXVK decides: only the Vegas 3.1.1 line reads the newer key.
        val r61 = Container("wow_forever").apply { dxWrapper = "dxvk-Vegas-3.1.1-wow-arm64-hybrid-gamenative-r61-61" }
        val r50 = Container("wow_forever").apply { dxWrapper = "dxvk-Vegas-2.7.3-wow-arm64-hybrid-gamenative-r50-50" }
        assertTrue(WowRuntimeTuning.readsDxvkFrameRateAlias(r61))
        assertFalse(WowRuntimeTuning.readsDxvkFrameRateAlias(r50))
    }
    @Test fun fifoIsThePresentModeDefaultUntilThePlayerChooses() {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        val prefs = context.getSharedPreferences(WowLaunchPolicy.PREFS, 0)
        prefs.edit().clear().commit()
        assertTrue(WowRuntimeTuning.presentIsDefault(context)); assertEquals("fifo", WowRuntimeTuning.selected(context, "present"))
        assertFalse(WowRuntimeTuning.fifoLowLatency(context)) // the smooth FIFO queue unless the player lowers it
        assertEquals(0, WowRuntimeTuning.fifoDrainMode(context))
        prefs.edit().putString("fifolatency", "low").commit(); assertEquals(1, WowRuntimeTuning.fifoDrainMode(context))
        prefs.edit().putString("fifolatency", "lowest").commit(); assertEquals(2, WowRuntimeTuning.fifoDrainMode(context))
        prefs.edit().remove("fifolatency").commit()
        prefs.edit().putString("present", "mailbox").commit() // a player's own choice stays
        assertEquals("mailbox", WowRuntimeTuning.selected(context, "present"))
        prefs.edit().putString("present", "").commit() // the old explicit "Default (FIFO)" entry is the single FIFO entry now
        assertFalse(WowRuntimeTuning.presentIsDefault(context)); assertEquals("fifo", WowRuntimeTuning.selected(context, "present"))
        prefs.edit().putString("handoff", "").commit() // "Default (off)" and "Off" were one choice
        assertEquals("0", WowRuntimeTuning.selected(context, "handoff"))
        prefs.edit().putString("present", "fifo").commit()
        assertEquals("fifo", WowRuntimeTuning.selected(context, "present"))
        prefs.edit().clear().commit()
    }
    @Test fun hybridIsTheDx11ShaderDefaultUntilThePlayerChooses() {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        val prefs = context.getSharedPreferences(WowLaunchPolicy.PREFS, 0)
        prefs.edit().clear().commit()
        assertTrue(WowRuntimeTuning.asyncIsDefault(context)); assertEquals("2", WowRuntimeTuning.selected(context, "async")) // 1.0.1 code1046: Hybrid
        prefs.edit().putString("async", "").commit() // the old "container setting" choice kept DXVK_ASYNC=1: it reads as Async
        assertFalse(WowRuntimeTuning.asyncIsDefault(context)); assertEquals("1", WowRuntimeTuning.selected(context, "async"))
        prefs.edit().putString("async", "0").commit()
        assertEquals("0", WowRuntimeTuning.selected(context, "async"))
        prefs.edit().clear().commit()
    }
    @Test fun explicitBalancedChoiceReadsAsTheDefault() {
        val prefs = org.robolectric.RuntimeEnvironment.getApplication().getSharedPreferences(WowLaunchPolicy.PREFS, 0)
        prefs.edit().clear().putString("gpu", "0").commit()
        assertEquals("", WowRuntimeTuning.selected(org.robolectric.RuntimeEnvironment.getApplication(), "gpu"))
        prefs.edit().putString("gpu", "1").commit()
        assertEquals("1", WowRuntimeTuning.selected(org.robolectric.RuntimeEnvironment.getApplication(), "gpu"))
        prefs.edit().clear().commit()
    }
    @Test fun hybridChangesManagedAsyncDefaultOnly() {
        for (original in listOf("0", "1", "2")) {
            val saved = EnvVars("DXVK_ASYNC=$original"); val launch = EnvVars(saved.toString())
            WowRuntimeTuning.applyChoices(launch, saved, false, mapOf("async" to "2"))
            assertEquals(if (original == "1") "2" else original, launch.get("DXVK_ASYNC"))
            assertEquals(original, saved.get("DXVK_ASYNC"))
        }
    }
    @Test fun priorR14AndR15HybridRemainSupportedWhenR16IsBundled() {
        val c = Container("wow_forever")
        for (version in listOf("Vegas-2.7.3-wow-arm64-hybrid-gamenative-r14-14", "Vegas-2.7.3-wow-arm64-hybrid-gamenative-r15-15", WowBundledComponents.DXVK_VERSION)) {
            c.setDXWrapper("dxvk-$version")
            c.setDXWrapperConfig("version=$version")
            assertTrue(WowRuntimeTuning.supportsHybrid(c))
            val env = EnvVars()
            WowLaunchEnvironment.applyRuntimeDefaults(c, env)
            assertEquals("1", env.get("DXVK_ASYNC"))
            assertEquals("dxvk-$version", c.dxWrapper)
            assertEquals("version=$version", c.dxWrapperConfig)
        }
        c.setDXWrapper("dxvk-Vegas-2.7.3-wow-arm64-async-gamenative-r13-13")
        assertFalse(WowRuntimeTuning.supportsHybrid(c))
    }
    @Test fun futureHybridBuildsQualifyByNameWithoutListEdits() {
        val c = Container("wow_forever")
        for (n in listOf(14, 15, 16, 17, 30)) {
            c.setDXWrapper("dxvk-Vegas-2.7.3-wow-arm64-hybrid-gamenative-r$n-$n")
            assertTrue("r$n", WowRuntimeTuning.supportsHybrid(c))
        }
        c.setDXWrapper("dxvk-Vegas-3.1.1-wow-arm64-hybrid-gamenative-r60-60")
        assertTrue("the experimental Vegas on DXVK 3.1.1", WowRuntimeTuning.supportsHybrid(c))
        for (wrapper in listOf("dxvk-Vegas-2.7.3-wow-arm64-hybrid-gamenative-r13-13", "dxvk-Vegas-2.7.3-wow-arm64-hybrid-gamenative-r16-15",
                "dxvk-Vegas-3.0.2-wow-arm64-hybrid-gamenative-r60-60", "dxvk-Vegas-3.1.1-wow-arm64-hybrid-gamenative-r60-59",
                "dxvk-Vegas-2.7.3-wow-arm64-async-gamenative-r16-16", "dxvk-other-hybrid-r16-16")) {
            c.setDXWrapper(wrapper)
            assertFalse(wrapper, WowRuntimeTuning.supportsHybrid(c))
        }
    }
    @Test fun legacyTuDefaultMigrationIsOnceAndPreservesOtherVariables() {
        val c = Container("wow_forever").apply { envVars = "TU_DEBUG=noconform,sysmem DXVK_ASYNC=1 CUSTOM=value" }
        WowContainerProfile.prepareExistingContainer(c, "/game")
        assertFalse(EnvVars(c.envVars).has("TU_DEBUG")); assertEquals("value", EnvVars(c.envVars).get("CUSTOM"))
        c.envVars = "TU_DEBUG=noconform,sysmem CUSTOM=manual"
        WowContainerProfile.prepareExistingContainer(c, "/game")
        assertEquals("noconform,sysmem", EnvVars(c.envVars).get("TU_DEBUG"))
    }
    @Test fun modifiedTuOverrideSurvivesMigration() {
        val c = Container("wow_forever").apply { envVars = "TU_DEBUG=noconform,sysmem,nolrz" }
        WowContainerProfile.prepareExistingContainer(c, "/game")
        assertEquals("noconform,sysmem,nolrz", EnvVars(c.envVars).get("TU_DEBUG"))
    }

    @Test fun appPacingTakesTheRequestedLimitAsIs() {
        val l = WowRuntimeTuning.live(false, 45, apiLimit = 120)
        assertEquals(45, l.app); assertEquals(45, l.inForce); assertFalse(l.deferred)
        assertEquals(0, WowRuntimeTuning.live(false, 0, apiLimit = 120).app)
    }

    @Test fun whenTheApiOwnsPacingALowerRequestIsEnforcedByTheAppLimiterOnTopOfIt() {
        val l = WowRuntimeTuning.live(true, 60, apiLimit = 120)
        assertEquals(60, l.app); assertEquals(60, l.inForce); assertFalse(l.deferred)
    }

    @Test fun whenTheApiOwnsPacingAHigherRequestOrOffWaitsForTheNextLaunch() {
        val higher = WowRuntimeTuning.live(true, 90, apiLimit = 60)
        assertEquals("the app limiter stays out of the way: two limiters would fight", 0, higher.app)
        assertEquals(60, higher.inForce); assertTrue(higher.deferred)
        val off = WowRuntimeTuning.live(true, 0, apiLimit = 60)
        assertEquals(0, off.app); assertEquals(60, off.inForce); assertTrue(off.deferred)
    }

    @Test fun whenTheApiOwnsPacingWithoutALimitAnyRequestIsEnforcedByTheAppLimiter() {
        val l = WowRuntimeTuning.live(true, 60, apiLimit = 0)
        assertEquals(60, l.app); assertEquals(60, l.inForce); assertFalse(l.deferred)
        val none = WowRuntimeTuning.live(true, 0, apiLimit = 0)
        assertEquals(0, none.app); assertFalse(none.deferred)
    }

    @Test fun theRequestEqualToTheApiLimitNeedsNothing() {
        val l = WowRuntimeTuning.live(true, 60, apiLimit = 60)
        assertEquals(0, l.app); assertEquals(60, l.inForce); assertFalse(l.deferred)
    }

    @Test fun wineAlwaysPreloadsTheOwnExecShimFromTheApk() {
        assertEquals("/apk/lib/libwowexec.so", WowRuntimeTuning.execShim("/apk/lib"))
        // The 1.4.1/1.4.2 test switch is gone: an old saved value is no longer a valid choice.
        assertThrows(RuntimeException::class.java) { WowRuntimeTuning.save(org.robolectric.RuntimeEnvironment.getApplication(), "redirect", "own") }
    }
}
