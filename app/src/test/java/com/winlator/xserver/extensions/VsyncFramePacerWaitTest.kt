package com.winlator.xserver.extensions

import org.junit.Assert.assertTrue
import org.junit.Test

class VsyncFramePacerWaitTest {
    @Test fun aPhaseReleaseWaitsOutTheSubMillisecondRestButNeverLongerThanTheBound() {
        val target = System.nanoTime() + 700_000L
        VsyncFramePacer.waitUntil(target)
        assertTrue(System.nanoTime() >= target)
        val start = System.nanoTime()
        VsyncFramePacer.waitUntil(start + 10_000_000_000L)   // a clock jump: bounded at 2 ms
        assertTrue(System.nanoTime() - start < 200_000_000L)
        val past = System.nanoTime()
        VsyncFramePacer.waitUntil(past - 1_000_000L)
        assertTrue(System.nanoTime() - past < 50_000_000L)
    }
}
