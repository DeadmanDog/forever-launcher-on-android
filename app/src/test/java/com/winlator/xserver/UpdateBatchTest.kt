package com.winlator.xserver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateBatchTest {
    /** Counts the notifications the way Drawable does: [changed] -> notify now, [end] -> notify once if something was held back. */
    private class Counter {
        val batch = UpdateBatch()
        var notifications = 0
        fun change() { if (batch.changed()) notifications++ }
        fun begin() = batch.begin()
        fun end() { if (batch.end()) notifications++ }
    }

    @Test fun withoutABatchEveryChangeNotifiesAtOnce() {
        val c = Counter()
        repeat(5) { c.change() }
        assertEquals(5, c.notifications)
    }

    @Test fun thirtyTwoRectanglesInOneBatchNotifyOnce() {
        val c = Counter()
        c.begin()
        repeat(32) { c.change() }
        assertEquals("nothing is announced while the request is still drawing", 0, c.notifications)
        c.end()
        assertEquals(1, c.notifications)
    }

    @Test fun anEmptyBatchNeverNotifies() {
        val c = Counter()
        c.begin(); c.end()
        assertEquals(0, c.notifications)
    }

    @Test fun nestedBatchesNotifyOnlyAtTheOutermostEnd() {
        val c = Counter()
        c.begin(); c.change()
        c.begin(); c.change(); c.end()
        assertEquals(0, c.notifications)
        c.end()
        assertEquals(1, c.notifications)
    }

    @Test fun theNextBatchStartsClean() {
        val c = Counter()
        c.begin(); c.change(); c.end()
        c.begin(); c.end()
        assertEquals("the pending mark was consumed by the first batch", 1, c.notifications)
        c.change()
        assertEquals(2, c.notifications)
    }

    @Test fun anUnbalancedEndIsIgnored() {
        val batch = UpdateBatch()
        assertFalse(batch.end())
        assertTrue("and the drawable still notifies normally", batch.changed())
    }
}
