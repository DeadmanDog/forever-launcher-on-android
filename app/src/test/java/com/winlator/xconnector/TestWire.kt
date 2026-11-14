package com.winlator.xconnector

import java.nio.ByteBuffer

/** Test helper: everything an XOutputStream sends, in order (the stream's no-wait mode with a socket that takes it all). */
class TestWire : XOutputStream.NoWaitTransport {
    val bytes = mutableListOf<Byte>()

    override fun send(data: ByteBuffer, offset: Int, length: Int, ancillaryFd: Int): Int {
        synchronized(bytes) { for (i in 0 until length) bytes += data.get(offset + i) }
        return length
    }

    fun take(): ByteArray = synchronized(bytes) { bytes.toByteArray().also { bytes.clear() } }

    companion object {
        fun attach(output: XOutputStream): TestWire = TestWire().also { output.enableNonBlocking(it, {}, {}) }
    }
}
