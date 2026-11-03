package com.winlator.xconnector

import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer

class XOutputStreamNonBlockingTest {
    /** A socket that takes at most [capacity] bytes per call until [full] is set. */
    private class FakeSocket {
        val wire = ByteArrayOutputStream()
        val fdsSent = mutableListOf<Int>()
        var full = false
        var perCall = Int.MAX_VALUE
        var failWith: IOException? = null
        fun send(data: ByteBuffer, offset: Int, length: Int, fd: Int): Int {
            failWith?.let { throw it }
            if (full) return -2
            val n = minOf(length, perCall)
            val bytes = ByteArray(n)
            val copy = data.duplicate(); copy.position(offset); copy.get(bytes)
            wire.write(bytes)
            if (fd >= 0) fdsSent.add(fd)
            return n
        }
    }

    private fun stream(socket: FakeSocket, onPending: () -> Unit = {}): XOutputStream =
        XOutputStream(64).also { it.enableNonBlocking(socket::send, onPending) }

    private fun write(stream: XOutputStream, vararg bytes: Int, fd: Int = -1) {
        if (fd >= 0) stream.setAncillaryFd(fd)
        stream.lock().use { for (b in bytes) stream.writeByte(b.toByte()) }
    }

    @After fun restoreLimit() { XOutputStream.maxPendingBytes = 64L * 1024 * 1024 }

    @Test fun aSocketThatTakesEverythingNeverQueues() {
        val socket = FakeSocket(); val stream = stream(socket)
        write(stream, 1, 2, 3)
        assertFalse(stream.hasPending())
        assertArrayEquals(byteArrayOf(1, 2, 3), socket.wire.toByteArray())
    }

    @Test fun aFullSocketQueuesInsteadOfBlockingAndTheListenerIsTold() {
        val socket = FakeSocket().also { it.full = true }
        var told = 0
        val stream = stream(socket) { told++ }
        write(stream, 1, 2, 3)
        assertTrue(stream.hasPending()); assertEquals(1, told)
        assertEquals(0, socket.wire.size())
    }

    @Test fun queuedOutputIsDrainedInOrderAndLaterWritesNeverOvertakeIt() {
        val socket = FakeSocket().also { it.full = true }
        val stream = stream(socket)
        write(stream, 1, 2, 3)
        socket.full = false; socket.perCall = 2
        // The socket is writable again, but the queue is not drained yet: new output must still go behind it.
        write(stream, 4, 5)
        assertEquals("nothing may overtake the queued bytes", 0, socket.wire.size())
        assertTrue(stream.drainPending())
        assertFalse(stream.hasPending())
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5), socket.wire.toByteArray())
    }

    @Test fun drainingStopsWhenTheSocketIsFullAgainAndResumesLater() {
        val socket = FakeSocket().also { it.full = true }
        val stream = stream(socket)
        write(stream, 1, 2, 3, 4)
        socket.full = false; socket.perCall = 3
        assertTrue(stream.drainPending())
        socket.wire.reset()
        val again = FakeSocket().also { it.full = true }
        val second = stream(again)
        write(second, 9, 8)
        assertFalse("still full: stays queued", second.drainPending())
        assertTrue(second.hasPending())
        again.full = false
        assertTrue(second.drainPending())
        assertArrayEquals(byteArrayOf(9, 8), again.wire.toByteArray())
    }

    @Test fun theAncillaryDescriptorTravelsExactlyOnceWithItsFirstByte() {
        val socket = FakeSocket().also { it.full = true }
        val stream = stream(socket)
        write(stream, 7, 7, 7, 7, fd = 42)
        socket.full = false; socket.perCall = 1
        assertTrue(stream.drainPending())
        assertEquals(listOf(42), socket.fdsSent)
        assertEquals(4, socket.wire.size())
    }

    @Test fun aDescriptorSentWithAPartialWriteIsNotSentAgain() {
        val socket = FakeSocket().also { it.perCall = 2 }
        val stream = stream(socket)
        write(stream, 1, 2, 3, 4, 5, fd = 11)
        assertEquals(listOf(11), socket.fdsSent)
        assertEquals(5, socket.wire.size())
        assertFalse(stream.hasPending())
    }

    @Test fun aClientThatNeverReadsIsDroppedAtTheQueueLimit() {
        XOutputStream.maxPendingBytes = 8
        val socket = FakeSocket().also { it.full = true }
        val stream = stream(socket)
        write(stream, 1, 2, 3, 4, 5)
        try { write(stream, 6, 7, 8, 9, 10); fail("limit reached") } catch (expected: IOException) { }
        assertFalse("the queue is released with the failure", stream.hasPending())
        try { write(stream, 1); fail("the stream stays failed") } catch (expected: IOException) { }
    }

    @Test fun aTransportFailureIsStickyAndReleasesTheQueue() {
        val socket = FakeSocket().also { it.full = true }
        val stream = stream(socket)
        write(stream, 1, 2)
        socket.full = false; socket.failWith = IOException("broken pipe")
        try { stream.drainPending(); fail("failure expected") } catch (expected: IOException) { }
        assertFalse(stream.hasPending())
    }

    @Test fun theConnectorIsToldOnceWhenTheStreamFailsForGood() {
        XOutputStream.maxPendingBytes = 8
        val socket = FakeSocket().also { it.full = true }
        var failures = 0
        val stream = XOutputStream(64).also { it.enableNonBlocking(socket::send, {}, { failures++ }) }
        write(stream, 1, 2, 3, 4, 5)
        assertEquals(0, failures)
        try { write(stream, 6, 7, 8, 9, 10); fail("limit reached") } catch (expected: IOException) { }
        assertEquals("the connector must close the client", 1, failures)
        try { write(stream, 1); fail("the stream stays failed") } catch (expected: IOException) { }
        assertEquals("told only once", 1, failures)
    }

    @Test fun aSocketErrorOnAFreshWriteAlsoClosesTheClient() {
        val socket = FakeSocket().also { it.failWith = IOException("broken pipe") }
        var failures = 0
        val stream = XOutputStream(64).also { it.enableNonBlocking(socket::send, {}, { failures++ }) }
        try { write(stream, 1); fail("error expected") } catch (expected: IOException) { }
        assertEquals(1, failures)
    }
}
