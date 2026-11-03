package com.winlator.xconnector

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class BufferedRequestDispatcherTest {
    private fun input(vararg values: Int): XInputStream {
        val stream = XInputStream(128)
        val buffer = ByteBuffer.allocate(values.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        values.forEach(buffer::putInt); buffer.flip()
        XInputStream::class.java.getDeclaredField("activeBuffer").apply { isAccessible = true }.set(stream, buffer)
        return stream
    }
    @Test fun pipelinedRequestsStayBufferedUntilFenceWakeAndAreNotReplayed() {
        val stream = input(10, 20, 30)
        val client = object : Client(null, null) { override fun getInputStream() = stream }
        val handled = mutableListOf<Int>(); var ticket = 0L
        val handler = RequestHandler {
            if (stream.available() < 4) false else {
                val v = stream.readInt(); handled.add(v)
                if (v == 10) ticket = client.requestGate.suspend()
                true
            }
        }
        BufferedRequestDispatcher.drain(client, handler) { true }
        assertEquals(listOf(10), handled); assertEquals(4, stream.activePosition)
        assertTrue(client.requestGate.resume(ticket))
        BufferedRequestDispatcher.drain(client, handler) { true }
        assertEquals(listOf(10, 20, 30), handled); assertEquals(12, stream.activePosition)
    }
    @Test fun incompleteRequestRewindsToItsOwnStartAfterAResume() {
        val stream = input(10, 20, 30)
        stream.activePosition = 4
        val client = object : Client(null, null) { override fun getInputStream() = stream }
        var attempts = 0
        val handler = RequestHandler { attempts++; stream.readInt(); false }
        BufferedRequestDispatcher.drain(client, handler) { true }
        assertEquals(4, stream.activePosition); assertEquals(1, attempts)
        val handler2 = RequestHandler { if (stream.available() < 4) false else { stream.readInt(); true } }
        BufferedRequestDispatcher.drain(client, handler2) { false }
        assertEquals(4, stream.activePosition)
    }
}
