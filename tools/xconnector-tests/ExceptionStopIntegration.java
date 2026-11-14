package com.winlator.xconnector;

import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Drives the production doEpollIndefinitely (xconnectorpatch.c): a callback that throws an Error the handlers do not catch must
 * end the batch at once. The rest of the batch is not run with the exception pending (no JNI call may be made then) and the
 * Error reaches the Java caller.
 */
public final class ExceptionStopIntegration {
    static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }

    static final class Throwing extends XConnectorEpoll {
        final AtomicInteger newConnections = new AtomicInteger(), existing = new AtomicInteger();
        volatile boolean fail;
        void handleNewConnection(int fd) { newConnections.incrementAndGet(); }
        void handleExistingConnection(int fd) {
            existing.incrementAndGet();
            if (fail) throw new StackOverflowError("test: uncaught error in a callback");
        }
        void handleConnectionClosed(int fd) { }
        void handleWritable(int fd) { }
    }

    public static void main(String[] args) throws Exception {
        Path path = Files.createTempDirectory("wow-exc-").resolve("x.sock");
        int serverFd = XConnectorEpollNative.createAFUnixSocket(path.toString());
        int epollFd = XConnectorEpollNative.createEpollFd();
        check(serverFd >= 0 && epollFd >= 0 && XConnectorEpollNative.addFdToEpoll(epollFd, serverFd), "server sockets");
        Throwing connector = new Throwing();
        SocketChannel a = SocketChannel.open(UnixDomainSocketAddress.of(path));
        SocketChannel b = SocketChannel.open(UnixDomainSocketAddress.of(path));
        for (int guard = 0; connector.newConnections.get() < 2 && guard < 100; guard++)
            check(XConnectorEpollNative.doEpollIndefinitely(connector, epollFd, serverFd, true), "accept batch");
        check(connector.newConnections.get() == 2, "both clients accepted");
        // Both clients have input: one batch reports two readable descriptors; the first callback throws.
        a.write(ByteBuffer.wrap(new byte[]{1})); b.write(ByteBuffer.wrap(new byte[]{2}));
        Thread.sleep(50);
        connector.fail = true;
        Throwable seen = null;
        try { XConnectorEpollNative.doEpollIndefinitely(connector, epollFd, serverFd, true); }
        catch (StackOverflowError expected) { seen = expected; }
        check(seen != null, "the callback's Error reaches the caller");
        check(connector.existing.get() == 1, "no callback runs after the one that threw: " + connector.existing.get());
        a.close(); b.close();
        XConnectorEpollNative.closeFd(serverFd); XConnectorEpollNative.closeFd(epollFd);
        path.toFile().delete(); path.getParent().toFile().delete();
        System.out.println("PASS exception stop");
    }
}
