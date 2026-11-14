package com.winlator.xserver.harness

import com.winlator.xconnector.ShadowRecordingXConnectorNative
import com.winlator.xserver.extensions.DRI3Extension
import com.winlator.xserver.extensions.MITSHMExtension
import com.winlator.xserver.extensions.SyncExtension
import com.winlator.xserver.extensions.XInput2Extension
import com.winlator.xserver.harness.XTestServer.Companion.byte
import com.winlator.xserver.harness.XTestServer.Companion.int
import com.winlator.xserver.harness.XTestServer.Companion.pad
import com.winlator.xserver.harness.XTestServer.Companion.req
import com.winlator.xserver.harness.XTestServer.Companion.short
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 2026-11-13 X server review, findings 11 (descriptors of rejected requests), 15 (SYNC/MIT-SHM error codes), 21 (XI2 masks). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], shadows = [ShadowNoNativeDrawable::class, ShadowRecordingXConnectorNative::class])
class ReviewExtensionTest {
    private lateinit var x: XTestServer

    @Before fun setUp() { x = XTestServer(); ShadowRecordingXConnectorNative.reset() }

    private val dri3 = DRI3Extension.MAJOR_OPCODE.toInt() and 0xFF
    private val shm = MITSHMExtension.MAJOR_OPCODE.toInt() and 0xFF
    private val sync = SyncExtension.MAJOR_OPCODE.toInt() and 0xFF
    private val xi2 = XInput2Extension.MAJOR_OPCODE.toInt() and 0xFF

    // ---- 11 ----
    @Test fun anUnsupportedDri3FenceFromFdClosesItsDescriptor() {
        val c = x.client()
        c.connector.clientSocket.addAncillaryFd(55)
        c.send(req(dri3, 4, int(x.root.id), int(c.newId()), byte(0), pad(3)))
        assertEquals(17, c.packets().single().errorCode) // BadImplementation
        assertFalse("the fence fd must not stay queued for the next import", c.connector.clientSocket.hasAncillaryFds())
        assertTrue(ShadowRecordingXConnectorNative.closed.contains(55))
    }

    @Test fun pixmapFromBuffersConsumesEveryPlaneDescriptor() {
        val c = x.client()
        c.connector.clientSocket.addAncillaryFd(60)
        c.connector.clientSocket.addAncillaryFd(61)
        c.connector.clientSocket.addAncillaryFd(62)
        // Two buffers, for a window that does not exist: BadWindow, and both of its descriptors are gone.
        c.send(req(dri3, 7, int(c.newId()), int(0x7777), byte(2), pad(3), short(16), short(16), ByteArray(32),
            byte(32), byte(32), pad(2), ByteArray(8)))
        assertEquals(3, c.packets().single().errorCode)
        assertEquals(62, c.connector.clientSocket.ancillaryFd) // the next request's descriptor is still first in line
        assertTrue(ShadowRecordingXConnectorNative.closed.containsAll(listOf(60, 61)))
    }

    @Test fun anUnsupportedMitShmAttachFdClosesItsDescriptor() {
        val c = x.client()
        c.connector.clientSocket.addAncillaryFd(70)
        c.send(req(shm, 6, int(c.newId()), byte(0), pad(3)))
        assertEquals(17, c.packets().single().errorCode)
        assertFalse(c.connector.clientSocket.hasAncillaryFds())
        assertTrue(ShadowRecordingXConnectorNative.closed.contains(70))
    }

    // ---- 15 ----
    @Test fun syncRegistersThreeErrorsAndBadFenceIsTheThird() {
        val extension = x.server.getExtension<SyncExtension>(SyncExtension.MAJOR_OPCODE.toInt())
        assertEquals(3, extension.numErrors)
        val c = x.client()
        c.send(req(sync, 15, int(0x4242))) // TriggerFence of a fence that does not exist
        val error = c.packets().single()
        assertTrue(error.isError)
        assertEquals((extension.firstErrorId.toInt() and 0xFF) + 2, error.errorCode)
        assertEquals(0x4242, error.int(4))
        // The extensions after SYNC moved up by one, and say so in QueryExtension.
        val xinput = x.server.getExtension<XInput2Extension>(XInput2Extension.MAJOR_OPCODE.toInt())
        assertTrue((xinput.firstErrorId.toInt() and 0xFF) >= (extension.firstErrorId.toInt() and 0xFF) + 3)
    }

    @Test fun badShmSegmentUsesTheMitShmFirstError() {
        val extension = x.server.getExtension<MITSHMExtension>(MITSHMExtension.MAJOR_OPCODE.toInt())
        val error = com.winlator.xserver.errors.BadSHMSegment(extension.firstErrorId, 9)
        assertEquals(extension.firstErrorId, error.code)
    }

    // ---- 21 ----
    private fun selectXi2(c: XTestServer.TestClient, vararg words: Int) {
        val mask = words.fold(ByteArray(0)) { acc, w -> acc + int(w) }
        c.send(req(xi2, 46, int(x.root.id), short(1), pad(2), short(2), short(words.size), mask))
    }

    @Test fun anXi2MaskWordBeyondTheSecondDoesNotAliasTheFirst() {
        val c = x.client()
        selectXi2(c, 0, 0, 1 shl 17) // word 2: events 81.. (none exist); used to be read as RawMotion (bit 17 of word 0)
        assertTrue(c.packets().isEmpty())
        x.server.getExtension<XInput2Extension>(XInput2Extension.MAJOR_OPCODE.toInt()).emitRawMotion(2, 1.0, 1.0)
        assertTrue(c.packets().isEmpty())

        selectXi2(c, 1 shl 17)
        x.server.getExtension<XInput2Extension>(XInput2Extension.MAJOR_OPCODE.toInt()).emitRawMotion(2, 1.0, 1.0)
        assertEquals(35, c.packets().single().type)
    }
}
