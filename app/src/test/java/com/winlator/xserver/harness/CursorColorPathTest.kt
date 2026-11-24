package com.winlator.xserver.harness

import com.winlator.xconnector.ShadowRecordingXConnectorNative
import com.winlator.xserver.extensions.RenderExtension
import com.winlator.xserver.XClientRequestHandler
import com.winlator.xconnector.XInputStream
import com.winlator.xserver.harness.XTestServer.Companion.int
import com.winlator.xserver.harness.XTestServer.Companion.req
import com.winlator.xserver.harness.XTestServer.Companion.short
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** RENDER wire request -> real CursorManager -> actual drawable bytes consumed by the Vulkan GPU probe. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], shadows = [ShadowCursorDrawable::class, ShadowRecordingXConnectorNative::class])
class CursorColorPathTest {
    @Test fun corePixmapCursorPreservesGreyForegroundAndBackgroundThroughNativeMask() {
        val x = XTestServer()
        val client = x.client()
        val src = x.server.drawableManager.createDrawable(client.newId(), 2.toShort(), 1.toShort(), 1.toByte())
        val mask = x.server.drawableManager.createDrawable(client.newId(), 2.toShort(), 1.toShort(), 1.toByte())
        x.server.pixmapManager.createPixmap(src)
        x.server.pixmapManager.createPixmap(mask)
        src.data.putInt(0, 0xffffff).putInt(4, 0)
        mask.data.putInt(0, 0xffffff).putInt(4, 0xffffff)
        val id = client.newId()
        client.send(req(93, 0, int(id), int(src.id), int(mask.id),
            short(0x8000), short(0x8000), short(0x8000),
            short(0xc000), short(0x4000), short(0x2000), short(0), short(0)))
        assertTrue(client.packets().isEmpty())
        val cursor = x.server.cursorManager.getCursor(id)
        assertNotNull(cursor)
        assertEquals(0xff808080.toInt(), cursor.cursorImage.data.getInt(0))
        assertEquals(0xffc04020.toInt(), cursor.cursorImage.data.getInt(4))
    }

    @Test fun renderCursorPreservesGreyAndColoredArgbPixelsForNativeUpload() {
        val x = XTestServer()
        val client = x.client()
        val pixmapId = client.newId()
        val pictureId = client.newId()
        val cursorId = client.newId()
        val gcId = client.newId()
        val source = x.server.drawableManager.createDrawable(pixmapId, 8.toShort(), 4.toShort(), 32.toByte())
        x.server.pixmapManager.createPixmap(source)
        val pixels = intArrayOf(0x80404040.toInt(), 0xc0806040.toInt(), 0xff808080.toInt(), 0x00000000)
        x.server.graphicsContextManager.createGraphicsContext(gcId, source)
        val image = ByteBuffer.allocate(128).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until 32) image.putInt(pixels[i % pixels.size])
        val put = req(72, 2, int(pixmapId), int(gcId), short(8), short(4), short(0), short(0),
            byteArrayOf(0, 32, 0, 0), image.array())
        // The socket's real production buffer is direct. XTestServer's convenience sender uses heap storage instead.
        val wire = ByteBuffer.allocateDirect(put.size).order(ByteOrder.LITTLE_ENDIAN).put(put).flip()
        XInputStream::class.java.getDeclaredField("activeBuffer").apply { isAccessible = true }.set(client.input, wire)
        assertTrue(XClientRequestHandler().handleRequest(client.connector))
        for (i in 0 until 32) assertEquals(pixels[i % pixels.size], source.data.getInt(i * 4))
        val opcode = RenderExtension.MAJOR_OPCODE.toInt() and 0xff
        client.send(req(opcode, 4, int(pictureId), int(pixmapId), int(RenderExtension.FORMAT_ARGB32), int(0)))
        client.send(req(opcode, 27, int(cursorId), int(pictureId), short(1), short(1)))
        assertTrue(client.packets().isEmpty())
        val cursor = x.server.cursorManager.getCursor(cursorId)
        assertNotNull(cursor)
        assertTrue(cursor.isVisible)
        assertEquals(1, cursor.hotSpotX)
        assertEquals(1, cursor.hotSpotY)
        val data = cursor.cursorImage.buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        val expected = intArrayOf(0x80808080.toInt(), 0xc0aa8055.toInt(), 0xff808080.toInt(), 0x00000000)
        for (i in 0 until 32) assertEquals("pixel $i", expected[i % expected.size], data.getInt(i * 4))
        val output = File("build/cursor-color-proof/argb-straight.bin")
        output.parentFile!!.mkdirs()
        output.writeBytes(ByteArray(data.capacity()).also { data.position(0); data.get(it) })
    }
}
