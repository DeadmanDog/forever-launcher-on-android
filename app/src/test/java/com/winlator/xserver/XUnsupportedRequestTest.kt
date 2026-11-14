package com.winlator.xserver

import com.winlator.xconnector.XInputStream
import com.winlator.xconnector.XOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** 2026-11-12 review: an unsupported request's body is consumed, so the next request is read from its own start. */
class XUnsupportedRequestTest {
    private fun handle(client: XClient, stream: XInputStream): Boolean {
        val method = XClientRequestHandler::class.java.getDeclaredMethod(
            "handleNormalRequest", XClient::class.java, XInputStream::class.java, XOutputStream::class.java,
        ).apply { isAccessible = true }
        return method.invoke(XClientRequestHandler(), client, stream, XOutputStream(32)) as Boolean
    }

    /** A client without an XServer (its constructor needs the native library): only the request framing is used. */
    private fun client(stream: XInputStream): XClient {
        val unsafe = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
        val client = unsafe.javaClass.getMethod("allocateInstance", Class::class.java).invoke(unsafe, XClient::class.java) as XClient
        XClient::class.java.getDeclaredField("inputStream").apply { isAccessible = true }.set(client, stream)
        return client
    }

    @Test fun anUnsupportedCoreRequestIsSkippedWhole() {
        val bytes = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN).apply {
            put(100); put(0); putShort(3); putInt(0x11111111); putInt(0x22222222) // ChangeKeyboardMapping: not supported
            put(127); put(0); putShort(1)                                         // NoOperation
        }
        bytes.flip()
        val stream = XInputStream(32).also {
            XInputStream::class.java.getDeclaredField("activeBuffer").apply { isAccessible = true }.set(it, bytes)
        }
        val client = client(stream)
        assertTrue(handle(client, stream))
        assertEquals("only the NoOperation request is left", 4, stream.available())
        assertTrue(handle(client, stream))
        assertEquals(0, stream.available())
    }
}
