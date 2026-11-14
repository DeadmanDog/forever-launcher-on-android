package com.winlator.xserver.harness

import com.winlator.xconnector.ShadowRecordingXConnectorNative
import com.winlator.xserver.Atom
import com.winlator.xserver.harness.XTestServer.Companion.byte
import com.winlator.xserver.harness.XTestServer.Companion.int
import com.winlator.xserver.harness.XTestServer.Companion.pad
import com.winlator.xserver.harness.XTestServer.Companion.req
import com.winlator.xserver.harness.XTestServer.Companion.short
import com.winlator.xserver.harness.XTestServer.Companion.string8
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

/** 2026-11-13 X server review: core request findings 3-7, 10, 12-14, 16-18, 20. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], shadows = [ShadowNoNativeDrawable::class, ShadowRecordingXConnectorNative::class])
class ReviewCoreRequestTest {
    private lateinit var x: XTestServer
    private lateinit var c: XTestServer.TestClient

    @Before fun setUp() { x = XTestServer(); c = x.client() }

    private fun one(vararg request: ByteArray): Packet {
        c.send(*request)
        return c.packets().single()
    }

    private fun atom(name: String) = Atom.internAtom(name)

    private fun changeProperty(window: Int, property: Int, type: Int, format: Int, data: ByteArray, mode: Int = 0) =
        req(18, mode, int(window), int(property), int(type), byte(format), pad(3), int(data.size / (format / 8)), data)

    private fun getProperty(window: Int, property: Int, offset: Int, length: Int, type: Int = 0, delete: Boolean = false) =
        req(20, if (delete) 1 else 0, int(window), int(property), int(type), int(offset), int(length))

    // ---- 3: QueryKeymap is written under the stream lock (so it is flushed like every reply) ----
    @Test fun queryKeymapReplyIsCompleteAndSentAtOnce() {
        val reply = one(req(44, 0))
        assertTrue(reply.isReply)
        assertEquals(2, reply.length)
        assertEquals(40, reply.bytes.size)
        assertEquals(1, reply.sequence)
    }

    // ---- 4: GetProperty offset/length are unsigned ----
    @Test fun getPropertyWithHugeLengthsReturnsTheWholeValue() {
        val w = c.createWindow(x.root.id, 0, 0, 10, 10)
        val p = atom("REVIEW_PROP")
        c.send(changeProperty(w, p, atom("STRING"), 8, "hello!!!".toByteArray()))
        for (length in listOf(0x7FFFFFFF, -1 /* 0xFFFFFFFF */)) {
            val reply = one(getProperty(w, p, 0, length))
            assertTrue("length $length: ${reply.type}/${reply.errorCode}", reply.isReply)
            assertEquals(8, reply.int(16)) // value length in format units
            assertEquals(0, reply.int(12)) // bytes after
            assertEquals("hello!!!", String(reply.bytes, 32, 8))
        }
    }

    @Test fun getPropertyOffsetPastTheEndIsBadValueAndAHugeOffsetDoesNotCrash() {
        val w = c.createWindow(x.root.id, 0, 0, 10, 10)
        val p = atom("REVIEW_PROP")
        c.send(changeProperty(w, p, atom("STRING"), 8, "abcd".toByteArray()))
        assertEquals(0, one(getProperty(w, p, 1, 1)).int(16)) // offset == length: empty, not an error
        for (offset in listOf(2, 0x40000000, Int.MIN_VALUE)) {
            val reply = one(getProperty(w, p, offset, 1))
            assertTrue(reply.isError)
            assertEquals(2, reply.errorCode)
        }
    }

    @Test fun getPropertyTypeMismatchReportsTheLengthAndNeverDeletes() {
        val w = c.createWindow(x.root.id, 0, 0, 10, 10)
        val p = atom("REVIEW_PROP")
        c.send(changeProperty(w, p, atom("STRING"), 8, "abcd".toByteArray()))
        val reply = one(getProperty(w, p, 0, 10, type = atom("ATOM"), delete = true))
        assertEquals(atom("STRING"), reply.int(8))
        assertEquals(4, reply.int(12))
        assertTrue(x.server.windowManager.getWindow(w).getProperty(p) != null)
    }

    // ---- 5: SendEvent ----
    @Test fun sendEventSetsTheSendEventBitAndTheRecipientsSequence() {
        val receiver = x.client()
        val w = receiver.createWindow(x.root.id, 0, 0, 10, 10, eventMask = 1 shl 17) // StructureNotify
        receiver.send(req(127, 0), req(127, 0)) // the receiver is at sequence 3 now
        receiver.packets()
        val event = byteArrayOf(33, 32, 0x34, 0x12) + ByteArray(28) // ClientMessage with a bogus sequence 0x1234
        c.send(req(25, 0, int(w), int(1 shl 17), event))
        val got = receiver.packets().single()
        assertEquals(33 or 0x80, got.type)
        assertEquals(3, got.sequence)
    }

    @Test fun sendEventWithAnEmptyMaskToTheRootWindowDoesNotDropTheSender() {
        val event = byteArrayOf(33, 32, 0, 0) + ByteArray(28)
        assertEquals(1, c.send(req(25, 0, int(x.root.id), int(0), event))) // root has no creating client: was an NPE
        assertTrue(c.packets().isEmpty())
    }

    @Test fun sendEventWithAnEmptyMaskGoesToTheCreator() {
        val creator = x.client()
        val w = creator.createWindow(x.root.id, 0, 0, 10, 10)
        creator.packets()
        c.send(req(25, 0, int(w), int(0), byteArrayOf(33, 32, 0, 0) + ByteArray(28)))
        assertEquals(33 or 0x80, creator.packets().single().type)
    }

    // ---- 6: ChangeProperty Replace takes the new type ----
    @Test fun replaceWithTheSameFormatTakesTheNewType() {
        val w = c.createWindow(x.root.id, 0, 0, 10, 10)
        val p = atom("REVIEW_PROP")
        c.send(changeProperty(w, p, atom("STRING"), 8, "abcd".toByteArray()))
        c.send(changeProperty(w, p, atom("UTF8_STRING"), 8, "efgh".toByteArray()))
        val reply = one(getProperty(w, p, 0, 10))
        assertEquals(atom("UTF8_STRING"), reply.int(8))
        assertEquals("efgh", String(reply.bytes, 32, 4))
    }

    // ---- 7: atoms ----
    @Test fun internAtomOnlyIfExistsForAnUnknownNameIsNone() {
        val name = "REVIEW_NEVER_INTERNED_" + System.nanoTime()
        val reply = one(req(16, 1, short(name.length), pad(2), string8(name)))
        assertTrue(reply.isReply)
        assertEquals(0, reply.int(8))
    }

    @Test fun getAtomNameOfAnInvalidAtomIsBadAtom() {
        for (id in listOf(0, 0x7FFFFFF0)) {
            val reply = one(req(17, 0, int(id)))
            assertTrue(reply.isError)
            assertEquals(5, reply.errorCode)
            assertEquals(id, reply.int(4))
        }
        assertTrue(one(req(17, 0, int(atom("PRIMARY")))).isReply)
    }

    // ---- 10: reply-bearing requests always get an answer ----
    @Test fun everyCoreRequestWithAReplyGetsOne() {
        val w = c.createWindow(x.root.id, 0, 0, 10, 10)
        val cases = listOf(
            req(21, 0, int(w)),                                                     // ListProperties
            req(31, 0, int(w), int(0), byte(1), byte(1), pad(2)),                   // GrabKeyboard
            req(39, 0, int(w), int(0), int(0)),                                     // GetMotionEvents
            req(50, 0, short(10), short(1), string8("*")),                          // ListFontsWithInfo
            req(52, 0),                                                             // GetFontPath
            req(83, 0, int(w)),                                                     // ListInstalledColormaps
            req(84, 0, int(0), short(0xFFFF), short(0x8000), short(0), pad(2)),     // AllocColor
            req(91, 0, int(0), int(0x00FF8000)),                                    // QueryColors
            req(97, 0, int(w), short(32), short(32)),                               // QueryBestSize
            req(99, 0),                                                             // ListExtensions
            req(103, 0),                                                            // GetKeyboardControl
            req(106, 0),                                                            // GetPointerControl
            req(110, 0),                                                            // ListHosts
            req(116, 3, byteArrayOf(1, 2, 3), pad(1)),                              // SetPointerMapping
            req(118, 1, ByteArray(8)),                                              // SetModifierMapping
        )
        for (request in cases) {
            val opcode = request[0].toInt() and 0xFF
            val reply = one(request)
            assertTrue("opcode $opcode: ${reply.type}", reply.isReply)
            assertEquals(c.x.sequenceNumber.toInt() and 0xFFFF, reply.sequence)
        }
    }

    @Test fun fallbackRepliesCarryUsefulValues() {
        val alloc = one(req(84, 0, int(0), short(0xFFFF), short(0x8000), short(0), pad(2)))
        assertEquals(0xFF8000, alloc.int(16))
        assertEquals(0xFFFF, alloc.short(8))
        val colors = one(req(91, 0, int(0), int(0x00FF8000)))
        assertEquals(2, colors.length)
        assertEquals(1, colors.short(8))
        assertEquals(0xFFFF, colors.short(32))
        val extensions = one(req(99, 0))
        val names = mutableListOf<String>()
        var at = 32
        repeat(extensions.detail) { val n = extensions.bytes[at].toInt(); names += String(extensions.bytes, at + 1, n); at += 1 + n }
        assertTrue(names.containsAll(listOf("BIG-REQUESTS", "MIT-SHM", "DRI3", "Present", "SYNC")))
        assertEquals(7, one(req(50, 0, short(10), short(1), string8("*"))).length)
    }

    @Test fun fontAndColorNameLookupsAnswerWithTheErrorsXlibExpects() {
        val queryFont = one(req(47, 0, int(0x1234)))
        assertTrue(queryFont.isError); assertEquals(7, queryFont.errorCode); assertEquals(0x1234, queryFont.int(4))
        val lookup = one(req(92, 0, int(0), short(3), pad(2), string8("red")))
        assertTrue(lookup.isError); assertEquals(15, lookup.errorCode)
    }

    @Test fun anUnsupportedVoidRequestStaysSilent() {
        assertEquals(2, c.send(req(100, 0, int(0x11111111), int(0x22222222)), req(127, 0)))
        assertTrue(c.packets().isEmpty())
    }

    @Test fun convertSelectionWithoutOwnerAnswersSelectionNotifyWithPropertyNone() {
        val w = c.createWindow(x.root.id, 0, 0, 10, 10)
        c.send(req(24, 0, int(w), int(atom("CLIPBOARD")), int(atom("UTF8_STRING")), int(atom("REVIEW_PROP")), int(77)))
        val notify = c.packets().single()
        assertEquals(31, notify.type)
        assertEquals(w, notify.int(8))
        assertEquals(atom("CLIPBOARD"), notify.int(12))
        assertEquals(0, notify.int(20))
    }

    @Test fun convertSelectionWithAnOwnerIsForwardedToIt() {
        val owner = x.client()
        val ow = owner.createWindow(x.root.id, 0, 0, 10, 10)
        owner.send(req(22, 0, int(ow), int(atom("CLIPBOARD")), int(0)))
        owner.packets()
        val w = c.createWindow(x.root.id, 0, 0, 10, 10)
        c.send(req(24, 0, int(w), int(atom("CLIPBOARD")), int(atom("UTF8_STRING")), int(atom("REVIEW_PROP")), int(77)))
        assertTrue(c.packets().isEmpty())
        val request = owner.packets().single()
        assertEquals(30, request.type)
        assertEquals(ow, request.int(8)); assertEquals(w, request.int(12)); assertEquals(atom("REVIEW_PROP"), request.int(24))
    }

    // ---- 12: X errors instead of dropped connections ----
    @Test fun badClientValuesAreXErrorsNotDroppedConnections() {
        val w = c.createWindow(x.root.id, 0, 0, 10, 10)
        val inputOnly = c.createWindow(x.root.id, 0, 0, 10, 10, windowClass = 2)
        assertEquals(15, one(req(45, 0, int(c.newId()), short(5), pad(2), string8("fixed"))).errorCode) // OpenFont
        assertEquals(2, one(req(12, 0, int(w), short(0x40), pad(2), int(9))).errorCode)            // stack mode 9
        assertEquals(2, one(changeProperty(w, atom("REVIEW_PROP"), atom("STRING"), 8, ByteArray(4), mode = 3)).errorCode)
        assertEquals(2, one(req(18, 0, int(w), int(atom("REVIEW_PROP")), int(atom("STRING")), byte(7), pad(3), int(4), ByteArray(4))).errorCode)
        assertEquals(2, one(req(1, 0, int(c.newId()), int(x.root.id), short(0), short(0), short(5), short(5), short(0),
            short(7), int(0), int(0))).errorCode)                                                      // window class 7
        assertEquals(2, one(req(53, 24, int(c.newId()), int(w), short(4), short(4))).errorCode)      // depth 24: no visual
        assertEquals(8, one(req(73, 1, int(w), short(0), short(0), short(1), short(1), int(-1))).errorCode) // XYPixmap
        assertEquals(2, one(req(2, 0, int(w), int(1 shl 4), int(99))).errorCode)                      // bit gravity 99
        // Background pixel on an InputOnly window: ignored (was a NullPointerException).
        assertEquals(1, c.send(req(2, 0, int(inputOnly), int(1 shl 1), int(0xFF0000))))
        assertTrue(c.packets().isEmpty())
        // The connection still works.
        assertTrue(one(req(43, 0)).isReply)
    }

    // ---- 13: GetKeyboardMapping ----
    @Test fun getKeyboardMappingSendsTwoKeysymsPerKeycode() {
        x.server.keyboard.setKeysyms(38.toByte(), 0x61, 0x41)
        x.server.keyboard.setKeysyms(200.toByte(), 0x62, 0x42)
        val all = one(req(101, 0, byte(8), byte(248), pad(2)))
        assertEquals(2, all.detail)
        assertEquals(496, all.length)
        assertEquals(32 + 496 * 4, all.bytes.size)
        assertEquals(0x61, all.int(32 + (38 - 8) * 8)); assertEquals(0x41, all.int(32 + (38 - 8) * 8 + 4))
        val high = one(req(101, 0, byte(200), byte(1), pad(2)))
        assertEquals(2, high.length)
        assertEquals(0x62, high.int(32)); assertEquals(0x42, high.int(36))
        assertEquals(2, one(req(101, 0, byte(7), byte(1), pad(2))).errorCode)
        assertEquals(2, one(req(101, 0, byte(250), byte(10), pad(2))).errorCode)
    }

    // ---- 14: GrabServer / UngrabServer have no reply ----
    @Test fun grabServerAndUngrabServerSendNothing() {
        assertEquals(2, c.send(req(36, 0), req(37, 0)))
        assertTrue(c.packets().isEmpty())
    }

    // ---- 16: SetInputFocus ----
    @Test fun setInputFocusUsesTheFocusArgumentAndStoresRevertTo() {
        val w = c.createWindow(x.root.id, 0, 0, 10, 10)
        c.send(req(42, 0, int(w), int(0)))  // focus w, revert-to None: used to set the focus to None
        assertSame(x.server.windowManager.getWindow(w), x.server.windowManager.focusedWindow)
        c.send(req(42, 2, int(0), int(0)))  // focus None, revert-to Parent: used to be BadWindow(0)
        assertNull(x.server.windowManager.focusedWindow)
        c.send(req(42, 0, int(1), int(0)))  // PointerRoot
        assertSame(x.root, x.server.windowManager.focusedWindow)
        assertTrue(c.packets().isEmpty())
        assertEquals(2, one(req(42, 5, int(w), int(0))).errorCode)
    }

    // ---- 17: selections ----
    @Test fun setSelectionOwnerNoneReleasesAndTellsTheOldOwner() {
        val w = c.createWindow(x.root.id, 0, 0, 10, 10)
        c.send(req(22, 0, int(w), int(atom("PRIMARY")), int(0)))
        c.send(req(22, 0, int(0), int(atom("PRIMARY")), int(5)))
        val clear = c.packets().single()
        assertEquals(29, clear.type)
        assertEquals(w, clear.int(8))
        assertEquals(0, one(req(23, 0, int(atom("PRIMARY")))).int(8))
    }

    @Test fun selectionClearNamesTheWindowThatLostTheSelection() {
        val first = c.createWindow(x.root.id, 0, 0, 10, 10)
        c.send(req(22, 0, int(first), int(atom("PRIMARY")), int(0)))
        val other = x.client()
        val second = other.createWindow(x.root.id, 0, 0, 10, 10)
        other.send(req(22, 0, int(second), int(atom("PRIMARY")), int(0)))
        assertEquals(first, c.packets().single().int(8))
    }

    // ---- 18: request framing ----
    @Test fun unreadTrailingBytesOfARequestAreSkipped() {
        // GetInputFocus with 4 bytes of extra body: the next request is still read from its own start.
        assertEquals(2, c.send(req(43, 0, int(0x7F7F7F7F)), req(43, 0)))
        assertEquals(listOf(1, 2), c.packets().map { it.sequence })
    }

    @Test fun aRequestShorterThanItsFixedPartDropsTheClient() {
        // InternAtom claiming a 12-byte name in an 8-byte request: the name would be read from the following requests.
        val shortIntern = byteArrayOf(16, 0) + short(2) + short(12) + pad(2)
        assertThrows(IOException::class.java) { c.send(shortIntern, req(127, 0), req(127, 0), req(127, 0), req(127, 0)) }
    }

    // ---- 20: BIG-REQUESTS maximum ----
    @Test fun aBigRequestAboveTheAdvertisedMaximumDropsTheClient() {
        val max = com.winlator.xserver.extensions.BigReqExtension.MAX_REQUEST_LENGTH
        assertThrows(IOException::class.java) { c.send(byteArrayOf(127, 0) + short(0) + int(max + 1)) }
        val atMax = x.client()
        assertEquals(0, atMax.send(byteArrayOf(127, 0) + short(0) + int(max))) // valid: waits for the body
    }
}
