package com.winlator.xserver.harness

import com.winlator.xconnector.ShadowRecordingXConnectorNative
import com.winlator.xserver.Atom
import com.winlator.xserver.harness.XTestServer.Companion.int
import com.winlator.xserver.harness.XTestServer.Companion.req
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.ByteOrder

/**
 * 1.2.15: a long paste (a RestedXP guide) goes to the game through an X CLIPBOARD the server owns, read the way Wine's
 * clipboard thread reads it (GetSelectionOwner, ConvertSelection TARGETS, then UTF8_STRING), instead of one key press per
 * character, which froze the phone.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], shadows = [ShadowNoNativeDrawable::class, ShadowRecordingXConnectorNative::class])
class ClipboardPasteTest {
    private lateinit var x: XTestServer
    private lateinit var wine: XTestServer.TestClient
    private var importWindow = 0

    private fun atom(name: String) = Atom.internAtom(name)

    @Before fun setUp() {
        x = XTestServer()
        wine = x.client()
        importWindow = wine.createWindow(x.root.id, 0, 0, 1, 1)
        wine.packets()
    }

    private fun owner(): Int {
        wine.send(req(23, 0, int(atom("CLIPBOARD"))))
        return wine.packets().single().int(8)
    }

    /** ConvertSelection into SELECTION_DATA; returns the SelectionNotify and the property's bytes (null when property None). */
    private fun convert(target: String): Pair<Packet, ByteArray?> {
        wine.send(req(24, 0, int(importWindow), int(atom("CLIPBOARD")), int(atom(target)), int(atom("SELECTION_DATA")), int(0)))
        val notify = wine.packets().single { it.type == 31 }
        val property = x.server.windowManager.getWindow(importWindow).getProperty(atom("SELECTION_DATA"))
        val bytes = property?.data?.let { b -> ByteArray(b.capacity()).also { b.duplicate().apply { position(0) }.get(it) } }
        return notify to (if (notify.int(20) != 0) bytes else null)
    }

    @Test fun wineOwningTheClipboardIsToldAndTheServerAnswersTargets() {
        val wineSelectionWindow = wine.createWindow(x.root.id, 0, 0, 1, 1)
        wine.send(req(22, 0, int(wineSelectionWindow), int(atom("CLIPBOARD")), int(0)))
        wine.packets()

        x.server.setClipboardText("hello")
        val clear = wine.packets().single()
        assertEquals("SelectionClear tells Wine to import at once", 29, clear.type)
        assertEquals(wineSelectionWindow, clear.int(8))
        val serverOwner = owner()
        assertTrue(serverOwner != 0 && serverOwner != wineSelectionWindow)

        val (notify, targets) = convert("TARGETS")
        assertEquals(atom("SELECTION_DATA"), notify.int(20))
        val list = java.nio.ByteBuffer.wrap(targets!!).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(listOf(atom("TARGETS"), atom("UTF8_STRING"), Atom.getId("STRING")), List(3) { list.getInt() })
    }

    @Test fun aWholeGuideArrivesInOnePieceAsUtf8() {
        val line = "Step 75: Hearth to Loch Modan — ő ű á é, Questie {[\"x\"]=1}\n"
        val guide = buildString { while (length < 200_000) append(line) }
        val generation = x.server.setClipboardText(guide)
        assertTrue("not read yet", x.server.selectionManager.servedGeneration < generation)

        convert("TARGETS")
        assertEquals(generation, x.server.selectionManager.servedGeneration)
        val (notify, text) = convert("UTF8_STRING")
        assertEquals(atom("SELECTION_DATA"), notify.int(20))
        assertArrayEquals(guide.toByteArray(Charsets.UTF_8), text)

        // Read back through the wire as Wine does (GetProperty, AnyPropertyType, up to INT_MAX/4 longs).
        wine.send(req(20, 0, int(importWindow), int(atom("SELECTION_DATA")), int(0), int(0), int(Int.MAX_VALUE / 4)))
        val reply = wine.packets().single()
        assertTrue(reply.isReply)
        assertEquals(guide.toByteArray(Charsets.UTF_8).size, reply.int(16))
    }

    @Test fun aNewTextChangesTheOwnerSoWineSeesTheChange() {
        x.server.setClipboardText("first")
        val first = owner()
        x.server.setClipboardText("second")
        val second = owner()
        assertNotEquals(first, second)
        assertArrayEquals("second".toByteArray(), convert("UTF8_STRING").second)
    }

    @Test fun anUnknownTargetGetsPropertyNone() {
        x.server.setClipboardText("text")
        val (notify, data) = convert("image/png")
        assertEquals(0, notify.int(20))
        assertEquals(null, data)
    }

    @Test fun aClientTakingTheClipboardBackEndsTheServerOwnership() {
        x.server.setClipboardText("text")
        val w = wine.createWindow(x.root.id, 0, 0, 1, 1)
        wine.send(req(22, 0, int(w), int(atom("CLIPBOARD")), int(0)))
        wine.packets()
        assertEquals(w, owner())
    }
}
