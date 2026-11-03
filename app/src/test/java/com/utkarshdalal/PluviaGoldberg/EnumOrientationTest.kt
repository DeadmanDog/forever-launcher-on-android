package app.gamenative

import android.content.pm.ActivityInfo
import app.gamenative.ui.enums.Orientation
import java.util.EnumSet
import junit.framework.TestCase.assertEquals
import junit.framework.TestCase.assertTrue
import org.junit.Test

class EnumOrientationTest {

    @Test
    fun testOrientationFunctions() {
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, Orientation.PORTRAIT.activityInfoValue)

        val orientationSet = EnumSet.of(Orientation.PORTRAIT, Orientation.LANDSCAPE)
        val asInt = Orientation.toInt(orientationSet)
        val backToSet = Orientation.fromInt(asInt)
        assertEquals(orientationSet, backToSet)

        assertTrue(Orientation.PORTRAIT.angleRanges.any { 0 in it })
        assertTrue(Orientation.LANDSCAPE.angleRanges.any { 90 in it })
        assertTrue(Orientation.REVERSE_PORTRAIT.angleRanges.any { 180 in it })
        assertTrue(Orientation.REVERSE_LANDSCAPE.angleRanges.any { 270 in it })
    }
}
