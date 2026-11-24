package com.winlator.xconnector

import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer

/**
 * 1.2.26 (X connector review, xnonblock on): while the game's X socket was full every 32-byte event got its own queue entry, its own
 * direct buffer, its own pendingListener call (an epoll_ctl on the input thread) and later its own send(): a 500-event hitch was
 * caught up in 500 tiny writes. Output without a descriptor now joins the queued bytes, and the listener is told once per backlog.
 */
class XOutputBacklogCoalesceTest {
    private class CountingSocket {
        val wire = ByteArrayOutputStream()
        val fds = mutableListOf<Int>()
        var full = true
        var sends = 0
        fun send(data: ByteBuffer, offset: Int, length: Int, fd: Int): Int {
            if (full) return -2
            sends++
            val bytes = ByteArray(length)
            val copy = data.duplicate(); copy.position(offset); copy.get(bytes)
            wire.write(bytes)
            if (fd >= 0) fds += fd
            return length
        }
    }

    @After fun restoreLimit() { XOutputStream.maxPendingBytes = 64L * 1024 * 1024 }

    private fun event(stream: XOutputStream, n: Int, fd: Int = -1) {
        if (fd >= 0) stream.setAncillaryFd(fd)
        stream.lock().use { repeat(32) { stream.writeByte((n + it).toByte()) } }
    }

    @Test fun aBackloggedBurstIsToldOnceAndSentInOneWrite() {
        val socket = CountingSocket()
        var told = 0
        val stream = XOutputStream(64).also { it.enableNonBlocking(socket::send, { told++ }) }
        val expected = ByteArrayOutputStream()
        for (i in 0 until 500) { event(stream, i); for (b in 0 until 32) expected.write((i + b) and 0xFF) }
        assertEquals("the writable interest is armed once", 1, told)
        socket.full = false
        assertTrue(stream.drainPending())
        assertEquals("one write for the whole backlog", 1, socket.sends)
        assertArrayEquals(expected.toByteArray(), socket.wire.toByteArray())
        assertFalse(stream.hasPending())
        // The next backlog arms the interest again.
        socket.full = true
        event(stream, 1)
        assertEquals(2, told)
    }

    @Test fun aDescriptorStillTravelsWithItsOwnFirstByte() {
        val socket = CountingSocket()
        val stream = XOutputStream(64).also { it.enableNonBlocking(socket::send, {}) }
        event(stream, 1); event(stream, 2); event(stream, 3, fd = 42); event(stream, 4)
        socket.full = false
        assertTrue(stream.drainPending())
        assertEquals(listOf(42), socket.fds)
        assertEquals("bytes before the descriptor, then the descriptor's chunk with what followed it", 2, socket.sends)
        assertEquals(128, socket.wire.size())
    }

    @Test fun theQueueLimitStillHoldsForAJoinedBacklog() {
        XOutputStream.maxPendingBytes = 100
        val socket = CountingSocket()
        val stream = XOutputStream(64).also { it.enableNonBlocking(socket::send, {}) }
        event(stream, 1); event(stream, 2); event(stream, 3)
        assertThrows(IOException::class.java) { event(stream, 4) }
    }
}
