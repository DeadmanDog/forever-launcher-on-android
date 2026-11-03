package com.winlator.xconnector

import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test

class XOutputBufferGrowthTest {
    @Test fun incrementalWritesGrowLogarithmicallyAndPreserveBytes() {
        val out = XOutputStream(1)
        out.setByteOrder(ByteOrder.LITTLE_ENDIAN)
        var allocations = 0
        repeat(10000) { n ->
            val before = out.buffer
            out.writeInt(n)
            if (before !== out.buffer) allocations++
        }
        assertTrue("direct-buffer reallocations=$allocations", allocations <= 12)
        assertEquals(40000, out.buffer.position())
        assertEquals(ByteOrder.LITTLE_ENDIAN, out.buffer.order())
        out.buffer.flip()
        repeat(10000) { assertEquals(it, out.buffer.int) }
    }

    @Test fun growthCopiesOnlyWrittenPrefixAndHandlesLargeRequest() {
        val out = XOutputStream(8)
        out.writeByte(7)
        out.write(ByteArray(10000) { (it % 128).toByte() })
        assertEquals(10001, out.buffer.position())
        assertTrue(out.buffer.capacity() >= 10001)
        out.buffer.flip()
        assertEquals(7.toByte(), out.buffer.get())
        repeat(10000) { assertEquals((it % 128).toByte(), out.buffer.get()) }
    }

    @Test fun frameSizedWritesStayInTheExistingBuffer() {
        val out = XOutputStream(512)
        val b = out.buffer
        repeat(10) { out.writeInt(it) }
        assertSame(b, out.buffer)
    }
}
