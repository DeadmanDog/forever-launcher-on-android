package app.gamenative.ui.data

import org.junit.Assert.*
import org.junit.Test

/** The first-install HUD profile must stay the CapFrameX list the player chose (a saved choice always wins in the app). */
class HudPrefDefaultsTest {
    private val source = java.io.File("src/main/java/app/gamenative/PrefManager.kt").readText()

    @Test fun styleIsCapFrameX() { assertTrue(source.contains("getPref(PERFORMANCE_HUD_STYLE, \"capframex\")")) }

    @Test fun theShownMetricsAreOnAndTheClockIsOff() {
        assertTrue(source.contains("getPref(PERFORMANCE_HUD_CPU_CLOCK, true)"))
        assertTrue(source.contains("getPref(PERFORMANCE_HUD_SHOW_VRAM_USAGE, true)"))
        assertTrue(source.contains("getPref(PERFORMANCE_HUD_SHOW_CLOCK_TIME, false)"))
        assertTrue(source.contains("getPref(PERFORMANCE_HUD_SHOW_BATTERY_RUNTIME, true)"))
        assertTrue(source.contains("getPref(PERFORMANCE_HUD_SHOW_BATTERY_TEMPERATURE, true)"))
    }

    @Test fun theLowsAndTheFrameTimeAreOnByDefault() {
        assertTrue(source.contains("getPref(PERFORMANCE_HUD_SHOW_FPS_1_LOW, true)"))
        assertTrue(source.contains("getPref(PERFORMANCE_HUD_SHOW_FPS_01_LOW, true)"))
        assertTrue(source.contains("getPref(PERFORMANCE_HUD_SHOW_FRAME_TIME, true)"))
    }

    @Test fun noPanelVerticalListTopLeft() {
        assertTrue(source.contains("getPref(PERFORMANCE_HUD_BACKGROUND_OPACITY, 0f)"))
        assertTrue(source.contains("getPref(PERFORMANCE_HUD_COMPACT_MODE, false)"))
        assertTrue(source.contains("getPref(PERFORMANCE_HUD_X_FRACTION, 0f)"))
        assertTrue(source.contains("getPref(PERFORMANCE_HUD_Y_FRACTION, 0f)"))
    }

    @Test fun theDefaultOrderStringStartsWithTheScreenshotOrder() {
        val line = Regex("DEFAULT_PERFORMANCE_HUD_METRIC_ORDER =\\s*\"([A-Z0-9_,]+)\"").find(source)!!.groupValues[1]
        assertTrue(line.startsWith("FPS,FPS_LOW1,FPS_LOW01,FRAMETIME,CPU_CLOCK,CPU,CPU_TEMP,GPU_CLOCK,VRAM,GPU,GPU_TEMP,RAM,POWER,RUNTIME,BATTERY,BATTERY_TEMP"))
    }
}
