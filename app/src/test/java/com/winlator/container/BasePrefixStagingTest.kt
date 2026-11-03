package com.winlator.container

import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BasePrefixStagingTest {
    @Test fun aFailedSharedFileExtractionIsNoCompleteBase() {
        val calls = AtomicInteger()
        assertFalse(BasePrefix.stagingReady(false) { calls.incrementAndGet(); true })
        assertEquals("nothing else is tried on a staged prefix that is already unusable", 0, calls.get())
    }

    @Test fun aSuccessfulTidyUpMakesTheStagedPrefixReady() {
        assertTrue(BasePrefix.stagingReady(true) { true })
    }

    @Test fun theTidyUpGetsOneSecondTryBeforeItCountsAsFailed() {
        val calls = AtomicInteger()
        assertTrue(BasePrefix.stagingReady(true) { calls.incrementAndGet() == 2 })
        assertEquals(2, calls.get())
    }

    @Test fun aTidyUpThatFailsTwiceIsNoCompleteBase() {
        val calls = AtomicInteger()
        assertFalse(BasePrefix.stagingReady(true) { calls.incrementAndGet(); false })
        assertEquals(2, calls.get())
    }
}
