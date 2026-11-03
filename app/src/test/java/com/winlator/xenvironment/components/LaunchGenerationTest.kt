package com.winlator.xenvironment.components

import org.junit.Assert.*
import org.junit.Test

class LaunchGenerationTest {
    @Test fun stoppedWineCallbackCannotAffectSecondLaunch() {
        val launches = LaunchGeneration()
        val first = launches.invalidate()
        launches.invalidate() // stop before the next start
        val second = launches.current()
        assertFalse(launches.isCurrent(first))
        assertTrue(launches.isCurrent(second))
    }
    @Test fun restartingWineInvalidatesOldCallbackButAcceptsCurrentExit() {
        val launches = LaunchGeneration()
        val old = launches.current()
        val restarted = launches.invalidate()
        assertFalse(launches.isCurrent(old))
        assertTrue(launches.isCurrent(restarted))
    }
}
