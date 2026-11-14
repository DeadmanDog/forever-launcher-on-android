package com.winlator.xconnector

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** 2026-11-12 review: the replies of one dispatch round leave in one socket write, and always before the round ends. */
class ReplyBatchTest {
    private class Wire : XOutputStream.NoWaitTransport {
        val writes = mutableListOf<Int>()
        val bytes = mutableListOf<Byte>()
        override fun send(data: ByteBuffer, offset: Int, length: Int, ancillaryFd: Int): Int {
            writes += length
            for (i in 0 until length) bytes += data.get(offset + i)
            return length
        }
    }

    private fun setup(requests: Int): Triple<Client, XOutputStream, Wire> {
        val input = XInputStream(64)
        val data = ByteBuffer.allocate(requests * 4).order(ByteOrder.LITTLE_ENDIAN)
        repeat(requests) { data.putInt(it + 1) }; data.flip()
        XInputStream::class.java.getDeclaredField("activeBuffer").apply { isAccessible = true }.set(input, data)
        val output = XOutputStream(64).apply { setByteOrder(ByteOrder.LITTLE_ENDIAN) }
        val wire = Wire()
        output.enableNonBlocking(wire, {}, {})
        val client = object : Client(null, null) {
            override fun getInputStream() = input
            override fun getOutputStream() = output
        }
        return Triple(client, output, wire)
    }

    private fun replying(client: Client, batch: Boolean, onRequest: (Int) -> Unit = {}) = object : RequestHandler {
        override fun handleRequest(c: Client): Boolean {
            val input = client.inputStream
            if (input.available() < 4) return false
            val value = input.readInt()
            onRequest(value)
            client.outputStream.lock().use { client.outputStream.writeInt(value * 10) }
            return true
        }
        override fun batchesReplies() = batch
    }

    @Test fun aRoundOfRepliesIsOneWriteInOrder() {
        val (client, _, wire) = setup(5)
        BufferedRequestDispatcher.drain(client, replying(client, true)) { true }
        assertEquals(listOf(20), wire.writes)
        val values = ByteBuffer.wrap(wire.bytes.toByteArray()).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(listOf(10, 20, 30, 40, 50), List(5) { values.int })
    }

    @Test fun withoutBatchingEachReplyIsItsOwnWriteAsBefore() {
        val (client, _, wire) = setup(3)
        BufferedRequestDispatcher.drain(client, replying(client, false)) { true }
        assertEquals(listOf(4, 4, 4), wire.writes)
    }

    @Test fun aRoundThatFailsStillSendsWhatItReplied() {
        val (client, _, wire) = setup(3)
        val handler = replying(client, true) { if (it == 3) throw IOException("bad request") }
        try { BufferedRequestDispatcher.drain(client, handler) { true } } catch (expected: IOException) { }
        assertEquals(listOf(8), wire.writes)
    }

    @Test fun anotherThreadsEventFlushesTheRoundSoFarInOrder() {
        val (client, output, wire) = setup(2)
        val handler = replying(client, true) { value ->
            if (value == 2) {
                val t = Thread { output.lock().use { output.writeInt(99) } }
                t.start(); t.join()
            }
        }
        BufferedRequestDispatcher.drain(client, handler) { true }
        // Reply 10, then the event (it flushed 10 with it), then reply 20 at the end of the round.
        assertEquals(listOf(8, 4), wire.writes)
        val values = ByteBuffer.wrap(wire.bytes.toByteArray()).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(listOf(10, 99, 20), List(3) { values.int })
        assertTrue(!output.hasPending())
    }
}
