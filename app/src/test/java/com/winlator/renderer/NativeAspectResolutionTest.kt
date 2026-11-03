package com.winlator.renderer

import com.winlator.math.XForm
import com.winlator.xserver.ScreenInfo
import org.junit.Assert.*
import org.junit.Test

class NativeAspectResolutionTest {
    @Test fun screenInfoKeepsTheActualRequestedPixelSize() {
        val info = ScreenInfo("1344x608")
        assertEquals(1344, info.width.toInt()); assertEquals(608, info.height.toInt())
        assertEquals("1344x608", info.toString())
    }
    @Test fun redmagicIsScaledByExactlyTwoWithNoEdgeAdjustmentAtAll() {
        val v = ViewTransformation().apply { update(2688, 1216, 1344, 608) }
        assertTrue(v.isNativeAspectResolution)
        assertEquals(2f, v.aspect, 0f)
        assertEquals(1f, v.sceneScaleX, 0f); assertEquals(1f, v.sceneScaleY, 0f)
        assertEquals(0f, v.inputOffsetX(2688, 1344), 0f); assertEquals(0f, v.inputOffsetY(1216, 608), 0f)
        assertEquals(2688, v.viewWidth); assertEquals(1216, v.viewHeight)
        assertEquals(0, v.viewOffsetX); assertEquals(0, v.viewOffsetY)
    }
    @Test fun theOldTwoThirdsPresetIsNoLongerSpecial() {
        val v = ViewTransformation().apply { update(2688, 1216, 1792, 810) }
        assertFalse(v.isNativeAspectResolution)
    }
    @Test fun mouseMappingInvertsTheContinuousCompositorAtCenterAndCorners() {
        for ((width, height) in listOf(2688 to 1216, 1920 to 1080, 1216 to 2688)) {
            val v = ViewTransformation().apply { update(width, height, 1344, 608) }
            val tx = v.inputOffsetX(width, 1344); val ty = v.inputOffsetY(height, 608)
            val form = XForm.getInstance()
            XForm.makeTranslation(form, -tx, -ty); XForm.scale(form, 1 / v.aspect, 1 / v.aspect)
            for ((x, y) in listOf(0f to 0f, 672f to 304f, 1343f to 607f)) {
                val point = XForm.transformPoint(form, tx + x * v.aspect, ty + y * v.aspect)
                assertEquals(x, point[0], 0.001f); assertEquals(y, point[1], 0.001f)
            }
        }
    }
    @Test fun otherPhoneShapesStayAspectFitWithoutStretchOrLargeCrop() {
        val v = ViewTransformation().apply { update(1920, 1080, 1344, 608) }
        assertEquals(1920f / 1344, v.aspect, 0.00001f)
        assertEquals(1920, v.viewWidth); assertTrue(v.viewHeight < 1080)
        assertTrue(v.viewOffsetY > 0)
    }
    @Test fun existingResolutionsRetainTheirPreviousTransform() {
        for ((width, height) in listOf(1280 to 720, 1920 to 1080, 1024 to 768)) {
            val v = ViewTransformation().apply { update(2688, 1216, width, height) }
            assertFalse(v.isNativeAspectResolution)
            assertEquals(minOf(2688f / width, 1216f / height), v.aspect, 0.00001f)
        }
    }
}
