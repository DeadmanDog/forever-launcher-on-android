package com.winlator.xconnector

import java.io.IOException
import java.nio.ByteBuffer
import org.junit.Assert.*
import org.junit.Test

class SocketWritePumpTest {
    @Test fun shortWritesPreserveEveryByteAndAdvanceOffsets() {
        val buffer = ByteBuffer.allocateDirect(100).apply { repeat(100) { put(it.toByte()) }; flip() }
        val received = mutableListOf<Byte>()
        val offsets = mutableListOf<Int>()
        SocketWritePump.writeFully(buffer, -1) { data, offset, length, _ ->
            offsets.add(offset)
            val size = minOf(length, 3)
            repeat(size) { received.add(data.get(offset + it)) }
            size
        }
        assertEquals((0 until 100).map { it.toByte() }, received)
        assertEquals((0 until 100 step 3).toList(), offsets)
        assertFalse(buffer.hasRemaining())
    }

    @Test fun nonzeroPositionAndLimitSendOnlyTheRequestedRange() {
        val b = ByteBuffer.allocateDirect(64).apply { repeat(64) { put(it.toByte()) }; position(7); limit(21) }
        val bytes = mutableListOf<Byte>()
        SocketWritePump.writeFully(b, -1) { data, offset, length, _ ->
            repeat(length) { bytes.add(data.get(offset + it)) }
            length
        }
        assertEquals((7 until 21).map { it.toByte() }, bytes)
        assertEquals(21, b.position())
    }

    @Test fun ancillaryDescriptorIsSentOnceAcrossPartialWrites() {
        val descriptors = mutableListOf<Int>()
        SocketWritePump.writeFully(ByteBuffer.allocateDirect(20), 42) { _, _, length, fd ->
            descriptors.add(fd)
            minOf(3, length)
        }
        assertEquals(listOf(42, -1, -1, -1, -1, -1, -1), descriptors)
    }

    @Test fun healthyFrameWriteUsesOneTransportCallAndOriginalBuffer() {
        val b = ByteBuffer.allocateDirect(40)
        var calls = 0
        SocketWritePump.writeFully(b, -1) { data, offset, length, fd ->
            assertSame(b, data); assertEquals(0, offset); assertEquals(40, length); assertEquals(-1, fd)
            calls++; length
        }
        assertEquals(1, calls)
    }

    @Test fun emptyBufferCreatesNoTransportWork() {
        SocketWritePump.writeFully(ByteBuffer.allocateDirect(0), -1) { _, _, _, _ -> fail("Empty write"); 0 }
    }

    @Test fun zeroNegativeAndOversizedProgressAreRejectedWithoutSpinning() {
        for (bad in listOf(0, -1, 11)) {
            var calls = 0
            val b = ByteBuffer.allocateDirect(10)
            assertThrows(IOException::class.java) {
                SocketWritePump.writeFully(b, -1) { _, _, _, _ -> calls++; bad }
            }
            assertEquals(1, calls); assertEquals(0, b.position())
        }
    }

    @Test fun errorAfterPartialProgressIsPropagatedWithoutReplayingPrefix() {
        val b = ByteBuffer.allocateDirect(10)
        var calls = 0
        assertThrows(IOException::class.java) {
            SocketWritePump.writeFully(b, 42) { _, offset, _, fd ->
                calls++
                if (calls == 1) { assertEquals(42, fd); 3 }
                else { assertEquals(3, offset); assertEquals(-1, fd); throw IOException("Disconnected") }
            }
        }
        assertEquals(3, b.position()); assertEquals(2, calls)
    }
}
