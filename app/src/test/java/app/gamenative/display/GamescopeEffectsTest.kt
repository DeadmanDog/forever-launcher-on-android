package app.gamenative.display

import app.gamenative.ui.util.ScreenEffectsConfig
import com.winlator.container.Container
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [29])
class GamescopeEffectsTest {
    @Test fun oldPanelToggleDoesNotKeepAFullSizeBufferWithoutAnEffect() {
        val container = Container("wow_forever").apply {
            putExtra("gamescopePanelOutput", true)
            putExtra("gamescopeScalingFilter", "linear")
        }
        val effects = GamescopeEffects.load(container)
        assertFalse(effects.needsPanelOutput)
        assertEquals("", effects.launchArgs())
    }

    @Test fun keepsOriginalFsrModesAndSharpnessWithoutChangingXserverSettings() {
        val container = Container("wow_forever").apply {
            screenSize = "1792x810"
            putExtra("screenEffectsScalingMode", ScreenEffectsConfig.SCALING_MODE_DLS)
        }
        GamescopeEffects(ScreenEffectsConfig.SCALING_MODE_FSR, 3).save(container)
        assertEquals(" --scaler stretch --filter linear --sharpness 10", GamescopeEffects.load(container).launchArgs())
        assertEquals("wow_quick_effects 1 50 0 0 0 1000", GamescopeEffects.load(container).shaderCommand())
        GamescopeEffects(ScreenEffectsConfig.SCALING_MODE_FSR_ASPECT, 5).save(container)
        assertEquals(" --scaler fit --filter linear --sharpness 0", GamescopeEffects.load(container).launchArgs())
        assertEquals("1792x810", container.screenSize)
        assertEquals(ScreenEffectsConfig.SCALING_MODE_DLS.toString(), container.getExtra("screenEffectsScalingMode"))
    }

    @Test fun changesTheNativeScalerAndFilterAndReportsRejection() {
        val commands = mutableListOf<String>()
        val original = GamescopeEffects(ScreenEffectsConfig.SCALING_MODE_NEAREST, 3)
        val effects = GamescopeEffects(ScreenEffectsConfig.SCALING_MODE_FSR, 4)
        assertTrue(effects.apply(original, { commands.add("scaler $it"); true }, { commands.add(it); 0 }))
        assertEquals(listOf("scaler 4", "scaling_filter linear", "wow_quick_effects 1 75 0 0 0 1000"), commands)
        assertFalse(effects.apply(original, { false }, { error("filter must wait for scaler") }))
    }

    @Test fun supportsEveryXserverShaderModeAndReservesLiveOutput() {
        assertTrue(GamescopeEffects.supports(ScreenEffectsConfig.SCALING_MODE_DLS))
        assertTrue(GamescopeEffects.supports(ScreenEffectsConfig.SCALING_MODE_NATURAL))
        assertTrue(GamescopeEffects.supports(ScreenEffectsConfig.SCALING_MODE_FSR_ASPECT))
        assertEquals(2688 to 1216, GamescopeEffects.outputSize(1792 to 810, 1216, 2688))   // 1.5x reaches the panel
        assertEquals(2016 to 912, GamescopeEffects.outputSize(1344 to 608, 2688, 1216))    // 1.5x, the panel scales the rest
        assertEquals(2162 to 1216, GamescopeEffects.outputSize(1920 to 1080, 2688, 1216))   // the panel height limits, aspect kept
        assertEquals(1792 to 810, GamescopeEffects.outputSize(1792 to 810, 0, 0))
    }

    @Test fun restoresLegacyNisAndRequiresBothOutputAxesToBeLarger() {
        val guest = 1792 to 810
        val panel = 2688 to 1216
        val container = Container("wow_forever").apply { putExtra("gamescopeScalingFilter", "nis") }
        val effects = GamescopeEffects.load(container)
        assertTrue(effects.launchArgs().contains("--filter nis"))
        assertEquals(panel, effects.scanoutSize(guest, guest, panel))
        assertEquals(1920 to 810, effects.scanoutSize(guest, 1920 to 810, guest))
        assertEquals(panel, effects.scanoutSize(guest, panel, guest))
    }

    @Test fun withoutAnUpscalingEffectTheOutputStaysAtTheGameSize() {
        // A display-sized output with no upscaler only multiplies the composited/published pixels (about 3 W on the RedMagic).
        val guest = 1344 to 608
        val panel = 2688 to 1216
        val effects = GamescopeEffects.load(Container("wow_forever"))
        assertFalse(effects.needsPanelOutput)
        assertEquals(guest, effects.scanoutSize(guest, guest, panel))
        assertEquals("", effects.launchArgs())
    }

    @Test fun combinesOriginalXserverEffectsAndResetsThemInOneCommand() {
        val container = Container("wow_forever")
        val default = GamescopeEffects.load(container)
        val colors = ScreenEffectsConfig(brightness = 15f, contrast = -25f, gamma = 1.4f,
            enableToon = true, enableFXAA = true, enableVivid = true, enableCRT = true, enableNTSC = true)
        val next = GamescopeEffects(ScreenEffectsConfig.SCALING_MODE_DLS, 2, colors = colors)
        val commands = mutableListOf<String>()
        assertTrue(next.apply(default, { error("DLS retains FIT") }, { commands.add(it); 0 }))
        assertEquals(listOf("wow_quick_effects 2 25 31 150 -250 1400"), commands)
        next.save(container)
        assertEquals(next, GamescopeEffects.load(container))
        assertEquals(colors, app.gamenative.ui.util.loadScreenEffectsConfig(container))
        assertTrue(default.apply(next, { error("reset retains FIT") }, { commands.add(it); 0 }))
        assertEquals("wow_quick_effects 0 0 0 0 0 1000", commands.last())
    }

    @Test fun ignoresLegacyDroidDeckPresetAndOnlyPersistsScaling() {
        val container = Container("wow_forever").apply { putExtra("gamescopeDroidDeckLook", "vivid") }
        assertEquals(GamescopePictureLook.OFF, GamescopeEffects.load(container).look)
        GamescopeEffects(ScreenEffectsConfig.SCALING_MODE_NIS, 3, GamescopePictureLook.CLARITY).save(container)
        val effects = GamescopeEffects.load(container)
        assertEquals(GamescopePictureLook.OFF, effects.look)
        assertEquals("vivid", container.getExtra("gamescopeDroidDeckLook"))
        assertEquals(ScreenEffectsConfig.SCALING_MODE_NIS, effects.mode)
        assertEquals(3, effects.sharpness)
        assertTrue(GamescopePictureLook.VIVID.shaderDefines().contains("#define WOW_SATURATION 1.45"))
    }

    @org.robolectric.annotation.Config(sdk = [34])
    @Test fun onlyTheUpscalersGetALargerOutputButEveryScalerKeepsItsArguments() {
        val guest = 1344 to 608; val panel = 2016 to 912
        for (mode in listOf(ScreenEffectsConfig.SCALING_MODE_NEAREST, ScreenEffectsConfig.SCALING_MODE_FILL,
                ScreenEffectsConfig.SCALING_MODE_STRETCH, ScreenEffectsConfig.SCALING_MODE_DLS, ScreenEffectsConfig.SCALING_MODE_NATURAL)) {
            val effects = GamescopeEffects(mode, 3)
            assertFalse("$mode", effects.needsPanelOutput)
            assertEquals("$mode", guest, effects.scanoutSize(guest, guest, panel))
        }
        assertTrue(GamescopeEffects(ScreenEffectsConfig.SCALING_MODE_NEAREST, 3).launchArgs().contains("--filter nearest"))
        assertTrue(GamescopeEffects(ScreenEffectsConfig.SCALING_MODE_FILL, 3).launchArgs().contains("--scaler fill"))
        for (mode in listOf(ScreenEffectsConfig.SCALING_MODE_FSR, ScreenEffectsConfig.SCALING_MODE_FSR_ASPECT, ScreenEffectsConfig.SCALING_MODE_NIS))
            assertTrue("$mode", GamescopeEffects(mode, 3).needsPanelOutput)
    }
}
