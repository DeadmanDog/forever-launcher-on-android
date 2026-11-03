package com.winlator.xconnector;

import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.SocketChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/** Drives the production doEpollIndefinitely (xconnectorpatch.c) on real unix sockets: callbacks reach the right object, also across threads and subclasses. */
public final class CallbackCacheIntegration {
    static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }

    static class Counting extends XConnectorEpoll {
        final AtomicInteger newConnections = new AtomicInteger(), closed = new AtomicInteger(), existing = new AtomicInteger();
        void handleNewConnection(int fd) { newConnections.incrementAndGet(); }
        void handleExistingConnection(int fd) { existing.incrementAndGet(); }
        void handleConnectionClosed(int fd) { closed.incrementAndGet(); }
        void handleWritable(int fd) { }
    }

    static final class Overriding extends Counting {
        final AtomicInteger overridden = new AtomicInteger();
        @Override void handleNewConnection(int fd) { overridden.incrementAndGet(); }
    }

    static final class Server implements AutoCloseable {
        final Path path; final int serverFd, epollFd;
        Server(String name) throws Exception {
            path = Files.createTempDirectory("wow-cb-").resolve(name);
            serverFd = XConnectorEpollNative.createAFUnixSocket(path.toString());
            epollFd = XConnectorEpollNative.createEpollFd();
            check(serverFd >= 0 && epollFd >= 0, "server sockets");
            check(XConnectorEpollNative.addFdToEpoll(epollFd, serverFd), "server in epoll");
        }
        SocketChannel connect() throws Exception { return SocketChannel.open(UnixDomainSocketAddress.of(path)); }
        boolean batch(XConnectorEpoll connector) { return XConnectorEpollNative.doEpollIndefinitely(connector, epollFd, serverFd, true); }
        @Override public void close() { XConnectorEpollNative.closeFd(serverFd); XConnectorEpollNative.closeFd(epollFd); path.toFile().delete(); path.getParent().toFile().delete(); }
    }

    public static void main(String[] args) throws Exception {
        // 1) the same connector class over many batches: every event reaches the callback (ids are cached after the first call)
        try (Server s = new Server("one.sock")) {
            Counting base = new Counting();
            List<SocketChannel> clients = new ArrayList<>();
            for (int i = 0; i < 50; i++) { clients.add(s.connect()); check(s.batch(base), "batch ok"); }
            check(base.newConnections.get() == 50, "every new connection reached handleNewConnection: " + base.newConnections);
            // 2) a subclass: virtual dispatch still reaches the override, not the base implementation
            Overriding sub = new Overriding();
            clients.add(s.connect()); check(s.batch(sub), "batch ok (subclass)");
            check(sub.overridden.get() == 1 && sub.newConnections.get() == 0, "the override is called for a subclass instance");
            // 3) the base class again after the subclass
            clients.add(s.connect()); check(s.batch(base), "batch ok (base again)");
            check(base.newConnections.get() == 51, "base callbacks still resolve after another class was seen");
            // 4) peer closes: handleConnectionClosed
            for (SocketChannel c : clients) c.close();
            int guard = 0;
            while (base.closed.get() < 52 && guard++ < 200) s.batch(base);
            check(base.closed.get() >= 52, "closed peers reach handleConnectionClosed: " + base.closed);
        }
        // 5) first use from several threads at once (the one-time publication of the cache), each with its own sockets
        AtomicInteger failures = new AtomicInteger();
        List<Thread> threads = new ArrayList<>();
        for (int t = 0; t < 6; t++) {
            final int id = t;
            Thread thread = new Thread(() -> {
                try (Server s = new Server("t" + id + ".sock")) {
                    Counting c = new Counting();
                    List<SocketChannel> open = new ArrayList<>();
                    for (int i = 0; i < 40; i++) { open.add(s.connect()); s.batch(c); }
                    if (c.newConnections.get() != 40) failures.incrementAndGet();
                    for (SocketChannel ch : open) ch.close();
                } catch (Throwable e) { e.printStackTrace(); failures.incrementAndGet(); }
            });
            threads.add(thread); thread.start();
        }
        for (Thread t : threads) t.join();
        check(failures.get() == 0, "concurrent first use works for every thread");
        System.out.println("PASS callback cache");
    }
}
