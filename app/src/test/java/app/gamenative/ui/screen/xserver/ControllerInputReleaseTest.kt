package app.gamenative.ui.screen.xserver

import org.junit.Assert.assertEquals
import org.junit.Test

/** Review 2026-11-13: opening the Quick Menu (or pausing the app) lets go of every controller input the game still holds. */
class ControllerInputReleaseTest {
    private fun actions(log: MutableList<String>, slots: List<Int>, failing: String? = null) = ControllerInputRelease.Actions(
        cleanupBindings = { log += "bindings"; if (failing == "bindings") error("x") },
        releaseKeys = { log += "keys" },
        assignedSlots = { slots },
        refreshControllers = { log += "refresh"; if (failing == "refresh") error("x") },
        writeNeutral = { log += "neutral$it" },
        sendState = { log += "send" },
    )

    @Test fun everyAssignedSlotGetsANeutralStateThenOneSend() {
        val log = mutableListOf<String>()
        ControllerInputRelease.run(actions(log, listOf(0, 2)))
        assertEquals(listOf("bindings", "keys", "refresh", "neutral0", "neutral2", "send"), log)
    }

    @Test fun touchOnlyPlayersOnlyLoseTheirHeldBindingsAndKeys() {
        val log = mutableListOf<String>()
        ControllerInputRelease.run(actions(log, emptyList()))
        assertEquals(listOf("bindings", "keys"), log)
    }

    @Test fun aFailingStepDoesNotStopTheOthers() {
        val log = mutableListOf<String>()
        ControllerInputRelease.run(actions(log, listOf(1), failing = "bindings"))
        assertEquals(listOf("bindings", "keys", "refresh", "neutral1", "send"), log)
        log.clear()
        ControllerInputRelease.run(actions(log, listOf(1), failing = "refresh"))
        assertEquals(listOf("bindings", "keys", "refresh", "neutral1", "send"), log)
    }
}
