package com.winlator.xserver.harness

import com.winlator.xconnector.ShadowRecordingXConnectorNative
import com.winlator.xconnector.XInputStream
import com.winlator.xserver.XClientRequestHandler
import com.winlator.xserver.harness.XTestServer.Companion.int
import com.winlator.xserver.harness.XTestServer.Companion.req
import com.winlator.xserver.harness.XTestServer.Companion.short
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * libXrender's XRenderHasDepths: RENDER counts as present only if pixmaps of depth 1, 4, 8, 24 and 32 can be created. The
 * depths the screen does not list are probed with CreatePixmap; one BadValue and libXrender reports no RENDER, so libXcursor
 * turns every colour cursor into a black and white one (the dark WoW cursor on the phone).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], shadows = [ShadowCursorDrawable::class, ShadowRecordingXConnectorNative::class])
class RenderDepthProbeTest {
    private val required = listOf(1, 4, 8, 24, 32)

    @Test fun libXrenderDepthProbeFindsEveryRequiredDepth() {
        val x = XTestServer()
        val c = x.client()
        val listed = x.server.pixmapManager.supportedVisuals.map { it.depth.toInt() }.toSet()
        val probed = required.filter { it !in listed && it != 1 }
        assertEquals("the probe must actually run", listOf(4, 8, 24), probed)
        for (depth in probed) {
            val pixmap = c.newId()
            c.send(req(53, depth, int(pixmap), int(x.root.id), short(1), short(1)))
            assertNotNull("depth $depth", x.server.pixmapManager.getPixmap(pixmap))
        }
        assertTrue("CreatePixmap errors make libXrender report no RENDER", c.packets().none { it.isError })
    }

    @Test fun connectionSetupStillListsOnlyTheScreenDepths() {
        val x = XTestServer()
        assertEquals(listOf(32, 1), x.server.pixmapManager.supportedVisuals.map { it.depth.toInt() })
    }

    @Test fun depth24PixmapTakesAPutImageLikeItsAdvertisedFormat() {
        val x = XTestServer()
        val c = x.client()
        val pixmap = c.newId()
        val gc = c.newId()
        c.send(req(53, 24, int(pixmap), int(x.root.id), short(2), short(1)))
        c.send(req(55, 0, int(gc), int(pixmap), int(0)))
        val put = req(72, 2, int(pixmap), int(gc), short(2), short(1), short(0), short(0), byteArrayOf(0, 24, 0, 0),
            int(0x00336699), int(0x00ccbbaa))
        // PutImage reads the socket's direct buffer, as in production; XTestServer's sender uses heap storage.
        val wire = ByteBuffer.allocateDirect(put.size).order(ByteOrder.LITTLE_ENDIAN).put(put).flip()
        XInputStream::class.java.getDeclaredField("activeBuffer").apply { isAccessible = true }.set(c.input, wire)
        assertTrue(XClientRequestHandler().handleRequest(c.connector))
        assertTrue(c.packets().none { it.isError })
        val drawable = x.server.pixmapManager.getPixmap(pixmap).drawable
        assertEquals(24, drawable.visual.depth.toInt())
        assertEquals(0x336699, drawable.data.getInt(0) and 0xffffff)
        assertEquals(0xccbbaa, drawable.data.getInt(4) and 0xffffff)
    }

    @Test fun anUnknownDepthIsStillBadValue() {
        val x = XTestServer()
        val c = x.client()
        c.send(req(53, 12, int(c.newId()), int(x.root.id), short(1), short(1)))
        assertEquals(2, c.packets().single().errorCode)
    }
}
