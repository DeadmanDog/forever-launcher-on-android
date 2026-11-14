package com.winlator.xconnector

import com.winlator.xserver.harness.ShadowNoNativeDrawable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 2026-11-13 X server review, findings 1 and 19: nothing is written through a client's descriptor once its connection is closed
 * (the number may already belong to a new connection), and stop() never signals an eventfd that is already closed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], shadows = [ShadowRecordingXConnectorNative::class, ShadowNoNativeDrawable::class])
class ClosedConnectionTest {
    @get:Rule val folder = TemporaryFolder()

    @Before fun reset() = ShadowRecordingXConnectorNative.reset()

    private fun write(output: XOutputStream, value: Int) { output.lock().use { output.writeInt(value) } }

    @Test fun aClosedStreamDropsOutputInsteadOfWritingToTheDescriptor() {
        val output = XOutputStream(ClientSocket(7), 64)
        write(output, 1)
        assertEquals(listOf(7), ShadowRecordingXConnectorNative.writesToFd)
        output.markClosed()
        write(output, 2)
        output.writeSuccessReply(1, 0)
        assertEquals(listOf(7), ShadowRecordingXConnectorNative.writesToFd)
        assertTrue(output.isClosed)
    }

    @Test fun aClosedNonBlockingStreamDropsOutputAndItsQueue() {
        val output = XOutputStream(ClientSocket(8), 64)
        val wire = TestWire.attach(output)
        write(output, 1)
        assertEquals(4, wire.take().size)
        output.markClosed()
        write(output, 2)
        assertEquals(0, wire.take().size)
        assertTrue("nothing left to drain", output.drainPending())
    }

    @Test fun aClosedSocketRefusesTheRestOfAPartialWrite() {
        val socket = ClientSocket(9)
        socket.markClosed()
        try {
            socket.write(java.nio.ByteBuffer.allocateDirect(4))
            throw AssertionError("a write to a closed socket went through")
        } catch (expected: java.io.IOException) { }
        assertTrue(ShadowRecordingXConnectorNative.writesToFd.isEmpty())
    }

    private fun connector(onShutdown: (Client) -> Unit): XConnectorEpoll {
        val handler = object : ConnectionHandler {
            override fun handleNewConnection(client: Client) = client.createIOStreams()
            override fun handleConnectionShutdown(client: Client) = onShutdown(client)
        }
        val requests = object : RequestHandler { override fun handleRequest(client: Client) = false }
        return XConnectorEpoll(UnixSocketConfig.createSocket(folder.root.path, UnixSocketConfig.XSERVER_PATH), handler, requests)
    }

    private fun clientFor(connector: XConnectorEpoll, fd: Int): Client {
        XConnectorEpoll::class.java.getDeclaredMethod("handleNewConnection", Int::class.java).apply { isAccessible = true }.invoke(connector, fd)
        @Suppress("UNCHECKED_CAST")
        val clients = XConnectorEpoll::class.java.getDeclaredField("connectedClients").apply { isAccessible = true }.get(connector) as android.util.SparseArray<Client>
        return clients.get(fd)
    }

    @Test fun eventsSentWhileAClientIsShutDownNeverReachItsDescriptor() {
        var closedDuringShutdown = false
        val connector = connector { client ->
            closedDuringShutdown = client.outputStream.isClosed
            // e.g. releasing the client's pointer grab sends LeaveNotify/EnterNotify, possibly to the client itself
            write(client.outputStream, 0x1234)
        }
        val client = clientFor(connector, 40)
        connector.killConnection(client)
        assertTrue(closedDuringShutdown)
        assertFalse(ShadowRecordingXConnectorNative.writesToFd.contains(40))
        assertTrue(ShadowRecordingXConnectorNative.closed.contains(40))
    }

    @Test fun stopNeverSignalsAnEventFdThatShutdownAlreadyClosed() {
        val connector = connector { }
        val requestShutdown = XConnectorEpoll::class.java.getDeclaredMethod("requestShutdown").apply { isAccessible = true }
        requestShutdown.invoke(connector)
        assertEquals(listOf(902), ShadowRecordingXConnectorNative.signalled)
        // The epoll loop ended by itself and closed its descriptors; stop() then races in with its wake-up.
        XConnectorEpoll::class.java.getDeclaredMethod("shutdown").apply { isAccessible = true }.invoke(connector)
        assertTrue(ShadowRecordingXConnectorNative.closed.contains(902))
        requestShutdown.invoke(connector)
        assertEquals("no write to a closed (maybe reused) eventfd", listOf(902), ShadowRecordingXConnectorNative.signalled)
    }
}
