package com.winlator.xserver

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RectClipTest {
    // The three failing cases of the review: an 8x2 window.
    @Test fun aRectangleHangingOverTheLeftEdgeKeepsOnlyItsVisiblePart() {
        val r = RectClip.intersect(-2, 0, 3, 1, 8, 2)!!
        assertArrayEquals(intArrayOf(0, 0, 1, 1), r)          // exactly one pixel, not three
    }
    @Test fun aRectangleEntirelyRightOfTheDrawableIsNothing() = assertNull(RectClip.intersect(9, 0, 3, 1, 8, 2))
    @Test fun aRectangleEntirelyAboveTheDrawableIsNothing() = assertNull(RectClip.intersect(0, -3, 2, 2, 8, 2))
    @Test fun aRectangleInsideIsUnchanged() = assertArrayEquals(intArrayOf(2, 1, 5, 2), RectClip.intersect(2, 1, 3, 1, 8, 2))
    @Test fun aRectangleLargerThanTheDrawableIsCutOnAllSides() =
        assertArrayEquals(intArrayOf(0, 0, 8, 2), RectClip.intersect(-100, -100, 1000, 1000, 8, 2))
    @Test fun emptyOrNegativeSizesAndBoundsAreNothing() {
        assertNull(RectClip.intersect(0, 0, 0, 5, 8, 2)); assertNull(RectClip.intersect(0, 0, 5, -1, 8, 2))
        assertNull(RectClip.intersect(0, 0, 5, 5, 0, 2))
    }
    @Test fun hugeValuesDoNotOverflow() {
        assertArrayEquals(intArrayOf(0, 0, 8, 2), RectClip.intersect(Int.MIN_VALUE.toLong(), Int.MIN_VALUE.toLong(), 4_294_967_296L, 4_294_967_296L, 8, 2))
        assertNull(RectClip.intersect(Int.MAX_VALUE.toLong(), 0, Int.MAX_VALUE.toLong(), 1, 8, 2))
    }
    // The MIT-SHM table of the review: a 100x100 image into a 100x100 drawable, y = 0.
    @Test fun visibleAreaCountsTheCutOffLeftPartOfAnImage() {
        assertEquals(10000L, RectClip.visibleArea(0, 0, 100, 100, 100, 100))
        assertEquals(8000L, RectClip.visibleArea(-20, 0, 100, 100, 100, 100))
        assertEquals(5000L, RectClip.visibleArea(-50, 0, 100, 100, 100, 100))
        assertEquals(0L, RectClip.visibleArea(-100, 0, 100, 100, 100, 100))
        assertEquals(0L, RectClip.visibleArea(0, 100, 100, 100, 100, 100))
    }
    @Test fun theEightyPercentRuleSeesTheRealCoverage() {
        fun counts(dstX: Int) = RectClip.visibleArea(dstX.toLong(), 0, 100, 100, 100, 100) * 5 >= 100L * 100 * 4
        assert(counts(0)); assert(counts(-20)); assert(!counts(-50)); assert(!counts(-100))
    }
}
