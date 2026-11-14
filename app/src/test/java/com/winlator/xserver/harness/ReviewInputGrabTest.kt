package com.winlator.xserver.harness

import com.winlator.xconnector.ShadowRecordingXConnectorNative
import com.winlator.xserver.Pointer
import com.winlator.xserver.harness.XTestServer.Companion.int
import com.winlator.xserver.harness.XTestServer.Companion.req
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 2026-11-13 X server review, findings 1 (grab part), 2, 8, 9: keyboard/pointer delivery and pointer grabs. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], shadows = [ShadowNoNativeDrawable::class, ShadowRecordingXConnectorNative::class])
class ReviewInputGrabTest {
    private val KEY_PRESS = 1
    private val BUTTON_PRESS = 4
    private val BUTTON_RELEASE = 8
    private val POINTER_MOTION = 0x40
    private val DO_NOT_PROPAGATE = 1 shl 12
    private val BUTTON1 = 0x100

    private lateinit var x: XTestServer

    @Before fun setUp() { x = XTestServer() }

    private fun press(button: Pointer.Button = Pointer.Button.BUTTON_LEFT) = x.server.pointer.setButton(button, true)
    private fun release(button: Pointer.Button = Pointer.Button.BUTTON_LEFT) = x.server.pointer.setButton(button, false)
    private fun moveTo(px: Int, py: Int) = x.server.pointer.setPosition(px, py)
    private fun events(c: XTestServer.TestClient, type: Int) = c.packets().filter { it.type == type }

    // ---- finding 2: key events must not crash the UI thread ----

    @Test fun keyPressOverAChildWithoutKeyListenersDoesNotCrash() {
        val c = x.client()
        val a = c.createWindow(x.root.id, 0, 0, 200, 200)
        val b = c.createWindow(a, 10, 10, 100, 100)
        c.mapWindow(a); c.mapWindow(b)
        x.server.windowManager.setFocus(x.server.windowManager.getWindow(a), com.winlator.xserver.WindowManager.FocusRevertTo.NONE)
        moveTo(20, 20)
        c.packets()
        // No window between the pointer window and the focus selects KeyPress: this was a NullPointerException.
        x.server.inputDeviceManager.onKeyPress(38.toByte(), 0)
        x.server.inputDeviceManager.onKeyRelease(38.toByte())
        assertTrue(events(c, 2).isEmpty())
    }

    @Test fun aDoNotPropagateMaskFallsBackToTheFocusWindow() {
        val c = x.client()
        val a = c.createWindow(x.root.id, 0, 0, 200, 200, eventMask = KEY_PRESS or 2)
        val b = c.createWindow(a, 10, 10, 100, 100, extra = mapOf(DO_NOT_PROPAGATE to (KEY_PRESS or 2)))
        c.mapWindow(a); c.mapWindow(b)
        x.server.windowManager.setFocus(x.server.windowManager.getWindow(a), com.winlator.xserver.WindowManager.FocusRevertTo.NONE)
        moveTo(20, 20)
        c.packets()
        x.server.inputDeviceManager.onKeyPress(38.toByte(), 0)
        x.server.inputDeviceManager.onKeyRelease(38.toByte())
        val sent = c.packets()
        assertEquals(listOf(2, 3), sent.map { it.type })
        assertTrue(sent.all { it.int(12) == a })
    }

    // ---- finding 9: button delivery ----

    @Test fun aNormalClickStillGoesToTheWindowUnderThePointerWithCorrectState() {
        val c = x.client()
        val w = c.createWindow(x.root.id, 0, 0, 300, 300, eventMask = BUTTON_PRESS or BUTTON_RELEASE or POINTER_MOTION)
        c.mapWindow(w)
        moveTo(50, 60)
        c.packets()
        press(); release()
        val sent = c.packets().filter { it.type == 4 || it.type == 5 }
        assertEquals(listOf(4, 5), sent.map { it.type })
        assertTrue(sent.all { it.int(12) == w && it.detail == 1 })
        // The state is the one just before the event: no button on the press, Button1 on its release, and nothing else
        // (the press used to carry PointerMotion = Mod4 and Button-motion bits).
        assertEquals(0, sent[0].short(28))
        assertEquals(BUTTON1, sent[1].short(28))
        assertNull("implicit grab ends with the last button", x.server.grabManager.window)
    }

    @Test fun theReleaseIsRoutedByTheReleaseMaskNotTheMotionMask() {
        val c = x.client()
        val w = c.createWindow(x.root.id, 0, 0, 300, 300, eventMask = BUTTON_PRESS or BUTTON_RELEASE)
        c.mapWindow(w)
        moveTo(50, 60)
        c.packets()
        press(); release()
        assertEquals(listOf(4, 5), c.packets().map { it.type }.filter { it == 4 || it == 5 })
    }

    @Test fun pressStateCarriesModifiersAndHeldButtons() {
        val c = x.client()
        val w = c.createWindow(x.root.id, 0, 0, 300, 300, eventMask = BUTTON_PRESS or BUTTON_RELEASE)
        c.mapWindow(w)
        moveTo(5, 5)
        x.server.keyboard.modifiersMask.set(1L or 4L) // Shift + Control
        c.packets()
        press(Pointer.Button.BUTTON_LEFT)
        press(Pointer.Button.BUTTON_RIGHT)
        val presses = events(c, 4)
        assertEquals(0x5, presses[0].short(28))
        assertEquals(0x5 or BUTTON1, presses[1].short(28))
    }

    @Test fun anExplicitGrabSendsButtonsToTheGrabbingClientRelativeToTheGrabWindow() {
        val owner = x.client()
        val w = owner.createWindow(x.root.id, 0, 0, 300, 300, eventMask = BUTTON_PRESS or BUTTON_RELEASE or POINTER_MOTION)
        owner.mapWindow(w)
        val grabber = x.client()
        val g = grabber.createWindow(x.root.id, 400, 400, 50, 50) // selects nothing itself
        grabber.mapWindow(g)
        moveTo(50, 50)
        assertEquals(0, grabber.grabPointer(g, false, BUTTON_PRESS or BUTTON_RELEASE).detail)
        owner.packets(); grabber.packets()

        press(); release()
        val got = grabber.packets().filter { it.type == 4 || it.type == 5 }
        assertEquals(listOf(4, 5), got.map { it.type })
        assertTrue(got.all { it.int(12) == g })
        assertTrue("the owner of the window under the pointer gets nothing during the grab",
            owner.packets().none { it.type == 4 || it.type == 5 })
    }

    @Test fun theGrabMaskDecidesWhichButtonEventsAreReported() {
        val grabber = x.client()
        val g = grabber.createWindow(x.root.id, 0, 0, 100, 100, eventMask = BUTTON_PRESS or BUTTON_RELEASE)
        grabber.mapWindow(g)
        moveTo(10, 10)
        grabber.grabPointer(g, false, BUTTON_RELEASE) // no ButtonPress in the grab mask
        grabber.packets()
        press(); release()
        assertEquals(listOf(5), grabber.packets().map { it.type }.filter { it == 4 || it == 5 })
    }

    @Test fun ownerEventsReportToTheGrabbingClientsOwnWindowUnderThePointer() {
        val grabber = x.client()
        val g = grabber.createWindow(x.root.id, 400, 400, 50, 50)
        val own = grabber.createWindow(x.root.id, 0, 0, 100, 100, eventMask = BUTTON_PRESS or BUTTON_RELEASE)
        grabber.mapWindow(g); grabber.mapWindow(own)
        moveTo(10, 10)
        grabber.grabPointer(g, true, BUTTON_PRESS or BUTTON_RELEASE)
        grabber.packets()
        press(); release()
        val got = grabber.packets().filter { it.type == 4 || it.type == 5 }
        assertEquals(listOf(4, 5), got.map { it.type })
        assertTrue(got.all { it.int(12) == own })
    }

    // ---- finding 8: only the grab window's own viewability ends a grab ----

    @Test fun unmappingAnotherWindowKeepsTheGrab() {
        val c = x.client()
        val g = c.createWindow(x.root.id, 0, 0, 100, 100)
        val other = c.createWindow(x.root.id, 200, 200, 100, 100)
        c.mapWindow(g); c.mapWindow(other)
        c.grabPointer(g, false, BUTTON_PRESS)
        c.unmapWindow(other)
        assertNotNull(x.server.grabManager.window)
        c.unmapWindow(g)
        assertNull(x.server.grabManager.window)
    }

    @Test fun unmappingTheGrabWindowsParentEndsTheGrab() {
        val c = x.client()
        val parent = c.createWindow(x.root.id, 0, 0, 100, 100)
        val g = c.createWindow(parent, 0, 0, 50, 50)
        c.mapWindow(parent); c.mapWindow(g)
        c.grabPointer(g, false, BUTTON_PRESS)
        c.unmapWindow(parent)
        assertNull(x.server.grabManager.window)
    }

    // ---- finding 1: a disconnecting client's grab and XI2 selections go with it ----

    @Test fun aDisconnectingClientReleasesItsGrabAndItsXi2Selections() {
        val grabber = x.client()
        val other = x.client()
        val g = other.createWindow(x.root.id, 0, 0, 100, 100) // the grab window belongs to another client
        other.mapWindow(g)
        grabber.grabPointer(g, false, BUTTON_PRESS)
        // XISelectEvents(root, RawMotion) for the grabbing client.
        val xi2Opcode = com.winlator.xserver.extensions.XInput2Extension.MAJOR_OPCODE.toInt()
        grabber.send(req(xi2Opcode, 46, int(x.root.id), XTestServer.short(1), XTestServer.short(0),
            XTestServer.short(2), XTestServer.short(1), int(1 shl 17)))
        val xi2 = x.server.getExtension<com.winlator.xserver.extensions.XInput2Extension>(xi2Opcode)
        val selections = xi2.javaClass.getDeclaredField("selections").apply { isAccessible = true }.get(xi2) as List<*>
        assertEquals(1, selections.size)
        assertNotNull(x.server.grabManager.window)

        grabber.output.markClosed()
        grabber.x.freeResources()

        assertNull("the grab of a gone client must not keep routing pointer events to it", x.server.grabManager.window)
        assertTrue(selections.isEmpty())
        // Pointer events reach the remaining clients again.
        val w = other.createWindow(x.root.id, 200, 200, 100, 100, eventMask = BUTTON_PRESS)
        other.mapWindow(w)
        moveTo(210, 210)
        other.packets()
        press()
        assertEquals(1, other.packets().count { it.type == 4 })
    }
}
