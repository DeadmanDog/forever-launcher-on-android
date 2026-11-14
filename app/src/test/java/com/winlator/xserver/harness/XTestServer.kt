package com.winlator.xserver.harness

import com.winlator.xconnector.Client
import com.winlator.xconnector.ClientSocket
import com.winlator.xconnector.TestWire
import com.winlator.xconnector.XInputStream
import com.winlator.xconnector.XOutputStream
import com.winlator.xserver.ScreenInfo
import com.winlator.xserver.XClient
import com.winlator.xserver.XClientRequestHandler
import com.winlator.xserver.XServer
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * A real XServer for JVM tests (run under Robolectric with ShadowNoNativeDrawable): clients send encoded requests through the
 * real request handler, and everything the server writes to them is captured and split into replies, events and errors.
 */
class XTestServer(width: Int = 800, height: Int = 600) {
    val server = XServer(ScreenInfo(width, height), false)
    val root get() = server.windowManager.rootWindow
    private val handler = XClientRequestHandler()

    inner class TestClient(socket: ClientSocket) {
        val input = XInputStream(socket, 64)
        val output = XOutputStream(64).apply { setByteOrder(ByteOrder.LITTLE_ENDIAN) }
        val wire = TestWire.attach(output)
        val x = XClient(server, input, output).apply { setAuthenticated(true) }
        val connector = object : Client(null, socket) {
            override fun getInputStream() = input
            override fun getOutputStream() = output
        }.apply { setTag(x) }
        private var nextId = 1

        fun newId(): Int = x.resourceIDBase + nextId++

        /** Sends one or more encoded requests; returns how many the handler processed. Throws what the handler throws. */
        fun send(vararg requests: ByteArray): Int {
            val all = requests.fold(ByteArray(0)) { acc, r -> acc + r }
            val buffer = ByteBuffer.allocate(all.size).order(ByteOrder.LITTLE_ENDIAN).put(all)
            buffer.flip()
            XInputStream::class.java.getDeclaredField("activeBuffer").apply { isAccessible = true }.set(input, buffer)
            var handled = 0
            while (input.available() > 0 && handler.handleRequest(connector)) handled++
            return handled
        }

        fun packets(): List<Packet> = Packet.split(wire.take())

        // ---- request builders (little endian) ----
        fun createWindow(parent: Int, x: Int, y: Int, w: Int, h: Int, eventMask: Int = 0, windowClass: Int = 1, extra: Map<Int, Int> = emptyMap()): Int {
            val id = newId()
            val values = (if (eventMask != 0) extra + (1 shl 11 to eventMask) else extra).toSortedMap()
            var mask = 0; values.keys.forEach { mask = mask or it }
            send(req(1, 0, int(id), int(parent), short(x), short(y), short(w), short(h), short(0), short(windowClass), int(0), int(mask),
                *values.values.map { int(it) }.toTypedArray()))
            return id
        }

        fun mapWindow(id: Int) { send(req(8, 0, int(id))) }
        fun unmapWindow(id: Int) { send(req(10, 0, int(id))) }

        fun grabPointer(window: Int, ownerEvents: Boolean, mask: Int): Packet {
            send(req(26, if (ownerEvents) 1 else 0, int(window), short(mask), byte(1), byte(1), int(0), int(0), int(0)))
            return packets().single()
        }
    }

    fun client(fd: Int = -1) = TestClient(ClientSocket(fd))

    companion object {
        fun byte(v: Int) = byteArrayOf(v.toByte())
        fun short(v: Int) = ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(v.toShort()).array()
        fun int(v: Int) = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array()
        fun pad(n: Int) = ByteArray(n)
        fun string8(s: String): ByteArray = s.toByteArray(Charsets.ISO_8859_1).let { it + ByteArray(-it.size and 3) }

        /** A core request: opcode, data byte, length in 4-byte units (computed), then the body (padded to 4). */
        fun req(opcode: Int, data: Int, vararg body: ByteArray): ByteArray {
            var payload = body.fold(ByteArray(0)) { acc, b -> acc + b }
            payload += ByteArray(-payload.size and 3)
            val units = (payload.size + 4) / 4
            return byteArrayOf(opcode.toByte(), data.toByte()) + short(units) + payload
        }
    }
}

/** One thing the server sent: a reply (type 1, with its extra data), an error (type 0) or an event (2..127, |0x80 if sent). */
class Packet(val bytes: ByteArray) {
    private val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    val type get() = bytes[0].toInt() and 0xFF
    val isReply get() = type == 1
    val isError get() = type == 0
    val errorCode get() = bytes[1].toInt() and 0xFF
    val detail get() = bytes[1].toInt() and 0xFF
    val sequence get() = buffer.getShort(2).toInt() and 0xFFFF
    val length get() = buffer.getInt(4)
    fun int(offset: Int) = buffer.getInt(offset)
    fun short(offset: Int) = buffer.getShort(offset).toInt() and 0xFFFF

    companion object {
        fun split(data: ByteArray): List<Packet> {
            val result = mutableListOf<Packet>()
            var at = 0
            while (at < data.size) {
                var size = 32
                val type = data[at].toInt() and 0x7F
                // Replies and GenericEvents (35) carry extra data after their 32 bytes.
                if (type == 1 || type == 35) size += ByteBuffer.wrap(data, at + 4, 4).order(ByteOrder.LITTLE_ENDIAN).int * 4
                result += Packet(data.copyOfRange(at, at + size))
                at += size
            }
            return result
        }
    }
}
