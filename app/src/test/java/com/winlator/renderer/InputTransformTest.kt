package com.winlator.renderer

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class InputTransformTest {
    private fun guest(t: FloatArray, x: Float, y: Float) = floatArrayOf((x - t[0]) * t[2], (y - t[1]) * t[3])

    // 2688x1216 view, 1280x720 guest: the numbers of the review. Where the picture puts the guest point (320, 180) the pointer must read it back.
    @Test fun stretchGivesBackTheGuestPointItDrew() {
        val t = InputTransform.inverse(InputTransform.STRETCH, 2688, 1216, 1280, 720)
        val g = guest(t, 672.0f, 304.0f)                 // 320 * 2688/1280, 180 * 1216/720
        assertEquals(320f, g[0], 0.01f); assertEquals(180f, g[1], 0.01f)
    }
    @Test fun fillGivesBackTheGuestPointItDrew() {
        val t = InputTransform.inverse(InputTransform.FILL, 2688, 1216, 1280, 720)
        val g = guest(t, 672.0f, 230.0f)                 // scale 2.1, vertical crop (1216 - 720*2.1) / 2
        assertEquals(320f, g[0], 0.01f); assertEquals(180f, g[1], 0.01f)
    }
    @Test fun fitIsTheMappingTheTouchViewAlwaysUsed() {
        val fit = ViewTransformation().apply { update(2688, 1216, 1280, 720) }
        val t = InputTransform.inverse(InputTransform.FIT, 2688, 1216, 1280, 720)
        assertArrayEquals(floatArrayOf(fit.inputOffsetX(2688, 1280), fit.inputOffsetY(1216, 720), 1f / fit.aspect, 1f / fit.aspect), t, 0f)
    }
    @Test fun theHalfSizeRedMagicModeIsExactWhateverOutputModeIsSelected() {
        for (mode in listOf(InputTransform.FIT, InputTransform.STRETCH, InputTransform.FILL)) {
            val t = InputTransform.inverse(mode, 2688, 1216, 1344, 608)
            assertArrayEquals("mode $mode", floatArrayOf(0f, 0f, 0.5f, 0.5f), t, 0f)
        }
    }
    @Test fun cornersOfTheGuestMapToCornersOfTheViewInEveryMode() {
        // stretch: guest (0,0) and (1280,720) are the view's corners; fill: the guest corners lie outside the view by the crop.
        val s = InputTransform.inverse(InputTransform.STRETCH, 2688, 1216, 1280, 720)
        assertEquals(0f, guest(s, 0f, 0f)[0], 0.001f); assertEquals(1280f, guest(s, 2688f, 1216f)[0], 0.01f); assertEquals(720f, guest(s, 2688f, 1216f)[1], 0.01f)
        val f = InputTransform.inverse(InputTransform.FILL, 2688, 1216, 1280, 720)
        assertEquals(640f, guest(f, 1344f, 608f)[0], 0.01f); assertEquals(360f, guest(f, 1344f, 608f)[1], 0.01f)   // the centre stays the centre
    }
    @Test fun anEmptySizeGivesTheIdentity() = assertArrayEquals(floatArrayOf(0f, 0f, 1f, 1f), InputTransform.inverse(InputTransform.FILL, 0, 0, 1280, 720), 0f)
}
