package com.winlator.xserver

import com.winlator.xconnector.XInputStream
import com.winlator.xconnector.XOutputStream
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.lang.reflect.InvocationTargetException
import java.nio.ByteBuffer
import java.nio.ByteOrder

class XClientRequestHandlerTest {
    private fun input(bytes: ByteBuffer): XInputStream {
        bytes.flip()
        return XInputStream(32).also { stream ->
            XInputStream::class.java.getDeclaredField("activeBuffer")
                .apply { isAccessible = true }.set(stream, bytes)
        }
    }

    private fun invoke(name: String, stream: XInputStream): Boolean {
        val method = XClientRequestHandler::class.java.getDeclaredMethod(
            name, XClient::class.java, XInputStream::class.java, XOutputStream::class.java,
        ).apply { isAccessible = true }
        try {
            return method.invoke(XClientRequestHandler(), null, stream, XOutputStream(32)) as Boolean
        } catch (e: InvocationTargetException) {
            throw e.targetException
        }
    }

    private fun auth(order: ByteOrder, name: Int, data: Int, supplied: Int): XInputStream =
        input(ByteBuffer.allocate(12 + supplied).order(order).apply {
            put(if (order == ByteOrder.LITTLE_ENDIAN) 108.toByte() else 66.toByte())
            put(0); putShort(11); putShort(0); putShort(name.toShort()); putShort(data.toShort()); putShort(0)
            repeat(supplied) { put(0) }
        })

    @Test fun fragmentedAuthBodyWaitsForBothPaddedFieldsInEitherByteOrder() {
        for (order in listOf(ByteOrder.LITTLE_ENDIAN, ByteOrder.BIG_ENDIAN)) {
            for (supplied in listOf(0, 1, 4, 6, 7)) {
                assertFalse(invoke("handleAuthRequest", auth(order, 1, 3, supplied)))
            }
        }
    }

    @Test fun unsignedAuthLengthsCannotBecomeNegativeOrSkipAuthenticationData() {
        for (order in listOf(ByteOrder.LITTLE_ENDIAN, ByteOrder.BIG_ENDIAN)) {
            assertFalse(invoke("handleAuthRequest", auth(order, 65535, 65535, 0)))
        }
    }

    @Test fun shortAuthHeaderWaitsInsteadOfReadingPastTheBuffer() {
        for (length in 0..11) {
            assertFalse(invoke("handleAuthRequest", input(ByteBuffer.allocate(length).apply {
                repeat(length) { put(0) }
            })))
        }
    }

    @Test fun invalidByteOrderDisconnectsTheClientWithAnIoError() {
        val stream = input(ByteBuffer.allocate(12).apply { repeat(12) { put(0) } })
        assertThrows(IOException::class.java) { invoke("handleAuthRequest", stream) }
    }

    @Test fun unsupportedProtocolVersionIsAnIoErrorRatherThanAnAppCrash() {
        val stream = input(ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN).apply {
            put(108); put(0); putShort(12); putShort(0); putShort(0); putShort(0); putShort(0)
        })
        assertThrows(IOException::class.java) { invoke("handleAuthRequest", stream) }
    }

    private fun big(units: Long): XInputStream = input(ByteBuffer.allocate(8)
        .order(ByteOrder.LITTLE_ENDIAN).apply { put(127); put(0); putShort(0); putInt(units.toInt()) })

    @Test fun invalidBigRequestLengthsCannotOverflowToNegativeOrSmallLengths() {
        for (units in listOf(0L, 1L, 0x20000002L, 0x40000000L, 0xffffffffL)) {
            assertThrows(IOException::class.java) { invoke("handleNormalRequest", big(units)) }
        }
    }

    @Test fun validBigRequestWithMissingBodyWaitsForMoreInput() {
        assertFalse(invoke("handleNormalRequest", big(3)))
        // 2026-11-13: the largest valid length is now the advertised BIG-REQUESTS maximum (finding 20); 0x20000001 is dropped.
        assertFalse(invoke("handleNormalRequest", big(com.winlator.xserver.extensions.BigReqExtension.MAX_REQUEST_LENGTH.toLong())))
    }

    @Test fun incompleteBigRequestHeaderWaitsBeforeReadingExtendedLength() {
        val stream = input(ByteBuffer.allocate(7).order(ByteOrder.LITTLE_ENDIAN).apply {
            put(127); put(0); putShort(0); put(1); put(0); put(0)
        })
        assertFalse(invoke("handleNormalRequest", stream))
    }
}
