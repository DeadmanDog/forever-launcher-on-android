package com.winlator.xconnector

import org.junit.Assert.*
import org.junit.Test

class ClientRequestGateTest {
    @Test fun lateWakeCannotResumeNewerWaitOrDisconnectedClient() {
        val gate = ClientRequestGate(); val first = gate.suspend()
        assertTrue(gate.resume(first)); val second = gate.suspend()
        assertFalse(gate.resume(first)); assertTrue(gate.isBlocked)
        gate.close(); assertFalse(gate.resume(second)); assertFalse(gate.isBlocked)
    }
}
