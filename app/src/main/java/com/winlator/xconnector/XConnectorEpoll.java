package com.winlator.xconnector;

import android.util.SparseArray;
import androidx.annotation.Keep;
import java.io.IOException;

public class XConnectorEpoll implements Runnable {

    private final String connectorLabel;
    private final ConnectionHandler connectionHandler;
    private final int epollFd;
    private Thread epollThread;
    private final RequestHandler requestHandler;
    private final int serverFd;
    private final int shutdownFd;
    private volatile boolean running = false;
    private final Object controlLock = new Object();
    private boolean serverFdsClosed; // guarded by controlLock
    private final java.util.concurrent.ConcurrentLinkedQueue<Runnable> controlTasks = new java.util.concurrent.ConcurrentLinkedQueue<>();
    private boolean canReceiveAncillaryMessages = false;
    private int initialInputBufferCapacity = 128;
    private int initialOutputBufferCapacity = 128;
    private final SparseArray<Client> connectedClients = new SparseArray<>();

    private boolean addFdToEpoll(int epollFd, int fd) {
        return XConnectorEpollNative.addFdToEpoll(epollFd, fd);
    }

    /**
     * Closes a descriptor the caller owns. The old native version only closed descriptors from an ownership
     * table that nothing fills any more, so descriptors passed in by DRI3 and SysV shared memory were never closed.
     */
    public static void closeFd(int fd) {
        if (fd >= 0) XConnectorEpollNative.closeFd(fd);
    }

    private static void closeTrackedFd(int fd) {
        closeFd(fd);
    }

    private int createAFUnixSocket(String path) {
        return XConnectorEpollNative.createAFUnixSocket(path);
    }

    private int createEpollFd() {
        return XConnectorEpollNative.createEpollFd();
    }

    private int createEventFd() {
        return XConnectorEpollNative.createEventFd();
    }

    private boolean doEpollIndefinitely(int epollFd, int serverFd, boolean addClientToEpoll) {
        return XConnectorEpollNative.doEpollIndefinitely(this, epollFd, serverFd, addClientToEpoll);
    }

    private void removeFdFromEpoll(int epollFd, int fd) {
        XConnectorEpollNative.removeFdFromEpoll(epollFd, fd);
    }

    public XConnectorEpoll(UnixSocketConfig socketConfig, ConnectionHandler connectionHandler, RequestHandler requestHandler) {
        this.connectionHandler = connectionHandler;
        this.requestHandler = requestHandler;
        this.connectorLabel = socketConfig.path + " [" + connectionHandler.getClass().getSimpleName() + "/" + requestHandler.getClass().getSimpleName() + "]";
        int createAFUnixSocket = createAFUnixSocket(socketConfig.path);
        this.serverFd = createAFUnixSocket;
        if (createAFUnixSocket < 0) {
            throw new RuntimeException("Failed to create an AF_UNIX socket.");
        }
        int createEpollFd = createEpollFd();
        this.epollFd = createEpollFd;
        if (createEpollFd < 0) {
            closeTrackedFd(createAFUnixSocket);
            throw new RuntimeException("Failed to create epoll fd.");
        }
        if (!addFdToEpoll(createEpollFd, createAFUnixSocket)) {
            closeTrackedFd(createAFUnixSocket);
            closeTrackedFd(createEpollFd);
            throw new RuntimeException("Failed to add server fd to epoll.");
        }
        int createEventFd = createEventFd();
        this.shutdownFd = createEventFd;
        if (!addFdToEpoll(createEpollFd, createEventFd)) {
            closeTrackedFd(createAFUnixSocket);
            closeTrackedFd(createEventFd);
            closeTrackedFd(createEpollFd);
            throw new RuntimeException("Failed to add shutdown fd to epoll.");
        }
        this.epollThread = new Thread(this, "XConnectorEpoll:" + this.connectorLabel);
    }

    public synchronized void start() {
        Thread thread;
        if (!this.running && (thread = this.epollThread) != null) {
            this.running = true;
            try {
                thread.start();
            } catch (RuntimeException | Error e) {
                // No thread will ever run shutdown(): the three descriptors are closed here, once.
                this.running = false;
                this.epollThread = null;
                closeServerFdsOnce();
                throw e;
            }
        }
    }

    public synchronized void stop() {
        if (this.running && this.epollThread != null) {
            this.running = false;
            requestShutdown();
            while (this.epollThread.isAlive()) {
                try {
                    this.epollThread.join();
                } catch (InterruptedException e) {
                }
            }
            this.epollThread = null;
        } else if (!this.running && this.epollThread != null) {
            // Constructed but never started (a start-up that failed in between): nobody else will close the descriptors.
            // Harmless after a loop that already ended by itself: closeServerFdsOnce knows it has run.
            this.epollThread = null;
            closeServerFdsOnce();
        }
    }

    @Override // java.lang.Runnable
    public void run() {
        com.winlator.core.DisplayThreadPriority.apply();
        try {
            while (this.running) {
                if (!doEpollIndefinitely(this.epollFd, this.serverFd, true)) {
                    if (this.running) {
                    }
                    break;
                }
            }
        } finally {
            // Also when the native loop hands a Java exception back (e.g. an OutOfMemoryError from the callback lookup): the
            // clients and the three descriptors are cleaned up either way.
            shutdown();
        }
    }

    @Keep
    private void handleNewConnection(int fd) {
        final Client client = new Client(this, new ClientSocket(fd));
        client.connected = true;
        try {
            this.connectionHandler.handleNewConnection(client);
        } catch (RuntimeException e) {
            // Same rule as for requests: never let an exception escape into native code.
            android.util.Log.e("XConnectorEpoll", "Rejecting a new client: " + e);
            com.winlator.core.FaultLog.record("New X client rejected (" + connectorLabel + ")", e);
            closeTrackedFd(fd);
            return;
        }
        if (nonBlockingOutput) {
            XOutputStream output = client.getOutputStream();
            if (output != null) output.enableNonBlocking(client.clientSocket::sendNoWait, () -> refreshClientInterest(client),
                () -> enqueueClientTask(client, () -> killConnection(client)));
        }
        this.connectedClients.put(fd, client);
    }

    private volatile boolean nonBlockingOutput;

    /** Experimental: a client that does not read its X11 output no longer blocks the thread that writes to it. Set before start(). */
    public void setNonBlockingOutput(boolean enabled) { nonBlockingOutput = enabled; }

    /** The socket of a client with queued output became writable. */
    @Keep
    private void handleWritable(int fd) {
        Client client = connectedClients.get(fd);
        if (client == null || !client.connected) return;
        XOutputStream output = client.getOutputStream();
        if (output == null) return;
        try {
            if (output.drainPending()) refreshClientInterest(client);
        } catch (IOException e) {
            try { killConnection(client); } catch (RuntimeException ignored) { }
        } catch (RuntimeException e) {
            android.util.Log.e("XConnectorEpoll", "Writing queued X output failed: " + e);
            com.winlator.core.FaultLog.record("Queued X output failed (" + connectorLabel + ")", e);
            try { killConnection(client); } catch (RuntimeException ignored) { }
        }
    }

    @Keep
    private void handleExistingConnection(int fd) {
        if (fd == shutdownFd) {
            XConnectorEpollNative.drainEventFd(shutdownFd);
            Runnable task;
            while (running && (task = controlTasks.poll()) != null) {
                try { task.run(); }
                catch (RuntimeException e) {
                    android.util.Log.e("XConnectorEpoll", "Control task failed: " + e);
                    com.winlator.core.FaultLog.record("X control task failed (" + connectorLabel + ")", e);
                }
            }
            return;
        }
        Client client = this.connectedClients.get(fd);
        if (client == null) {
            return;
        }
        XInputStream inputStream = client.getInputStream();
        try {
            if (!running || !client.connected || client.requestGate.isBlocked()) return;
            if (inputStream != null) {
                if (inputStream.readMoreData(this.canReceiveAncillaryMessages) > 0) {
                    BufferedRequestDispatcher.drain(client, requestHandler, () -> running);
                    return;
                }
                killConnection(client);
                return;
            }
            this.requestHandler.handleRequest(client);
        } catch (IOException e) {
            // The close callback of a failing client must not leave this JNI callback with an exception either.
            try { killConnection(client); }
            catch (RuntimeException failed) {
                android.util.Log.e("XConnectorEpoll", "Closing a client after an I/O error failed: " + failed);
                com.winlator.core.FaultLog.record("Closing an X client after an I/O error failed (" + connectorLabel + ")", failed);
            }
        } catch (RuntimeException e) {
            // A request that fails (malformed, or racing a destroyed resource) must cost that client its connection,
            // not the app: an exception leaving this JNI callback makes ART abort on the next JNI call.
            android.util.Log.e("XConnectorEpoll", "Dropping client after a failed request: " + e);
            com.winlator.core.FaultLog.record("X request failed; client dropped (" + connectorLabel + ")", e);
            try { killConnection(client); } catch (RuntimeException ignored) { }
        }
    }

    @Keep
    private void handleConnectionClosed(int fd) {
        Client client = connectedClients.get(fd);
        if (client == null) return;
        try { killConnection(client); }
        catch (RuntimeException e) {
            android.util.Log.e("XConnectorEpoll", "Closing a client failed: " + e);
            com.winlator.core.FaultLog.record("Closing an X client failed (" + connectorLabel + ")", e);
        }
    }

    public void enqueueClientTask(Client client, Runnable task) {
        enqueueControl(() -> {
            if (client.connected && connectedClients.get(client.clientSocket.fd) == client) task.run();
        });
    }

    private void enqueueControl(Runnable task) {
        synchronized (controlLock) {
            if (!running) return;
            controlTasks.add(task);
            XConnectorEpollNative.signalEventFd(shutdownFd);
        }
    }

    private void refreshClientInterest(Client client) {
        if (!running || !client.connected) return;
        synchronized (client) {
            XOutputStream output = client.getOutputStream();
            XConnectorEpollNative.setFdEvents(epollFd, client.clientSocket.fd,
                !client.requestGate.isBlocked() && client.pauseTask == null, output != null && output.hasPending());
        }
    }

    Runnable suspendClientRequests(Client client) {
        final long ticket = client.requestGate.suspend();
        refreshClientInterest(client);
        return () -> enqueueControl(() -> {
            // Object identity also protects against file descriptor reuse.
            if (!client.connected || connectedClients.get(client.clientSocket.fd) != client
                    || !client.requestGate.resume(ticket)) return;
            try {
                XInputStream input = client.getInputStream();
                if (input != null && input.available() > 0)
                    BufferedRequestDispatcher.drain(client, requestHandler, () -> running);
            } catch (IOException e) { killConnection(client); }
            refreshClientInterest(client);
        });
    }

    // Frame-pacing back-pressure: the fd leaves epoll until delayNs elapses, so
    // the client stalls in its own blocking call while the epoll thread stays
    // free.
    // Process-wide daemon scheduler, never shut down: connector teardown must
    // not be involved — stop() is synchronized and joins the epoll thread, so
    // any scheduler cleanup on the shutdown path can deadlock against it.
    private static final class PauseScheduler {
        static final java.util.concurrent.ScheduledExecutorService INSTANCE =
                java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
                    Thread t = new Thread(r, "XConnectorPause");
                    t.setDaemon(true);
                    return t;
                });
    }

    public void pauseClientReads(Client client, long delayNs) {
        if (!client.connected || !this.running || client.pacingExempt) return;
        long deadlineNs = System.nanoTime() + delayNs;
        final java.util.concurrent.ScheduledExecutorService sched = PauseScheduler.INSTANCE;
        synchronized (client) {
            // One pending re-arm per client, keyed to the latest deadline; an
            // earlier task firing first would resume reads before the newest
            // frame's slot.
            if (client.pauseTask != null && !client.pauseTask.isDone()) {
                // Repeated supersedes within one pause window mean the client
                // pipelines frames without a per-frame sync; read-suspension
                // cannot pace such a client smoothly (its loop only feels the
                // brake in whole batches), so it runs uncapped instead.
                if (client.pacedSupersedes++ >= 8) {
                    client.pacingExempt = true;
                    return;
                }
                if (client.pauseDeadlineNs >= deadlineNs) return;
                client.pauseTask.cancel(false);
            }
            XOutputStream output = client.getOutputStream();
            XConnectorEpollNative.setFdEvents(epollFd, client.clientSocket.fd, false, output != null && output.hasPending());
            client.pauseDeadlineNs = deadlineNs;
            client.pauseTask = sched.schedule(() -> {
                synchronized (client) {
                    client.pauseTask = null;
                    client.pacedSupersedes = 0;
                    if (client.connected && this.running) {
                        enqueueControl(() -> refreshClientInterest(client));
                    }
                }
            }, delayNs, java.util.concurrent.TimeUnit.NANOSECONDS);
        }
    }

    private void cancelPendingPause(Client client) {
        synchronized (client) {
            if (client.pauseTask != null) {
                client.pauseTask.cancel(false);
                client.pauseTask = null;
            }
        }
    }

    public void killConnection(Client client) {
        if (!client.connected) return;
        client.connected = false;
        client.requestGate.close();
        cancelPendingPause(client);
        try {
            this.connectionHandler.handleConnectionShutdown(client);
        } finally {
            // A failing resource callback must not leave the socket, its ancillary fds or the table entry behind.
            XOutputStream queued = client.getOutputStream();
            if (queued != null) queued.releasePending();
            removeFdFromEpoll(this.epollFd, client.clientSocket.fd);
            client.clientSocket.closeAncillaryFds();
            closeTrackedFd(client.clientSocket.fd);
            this.connectedClients.remove(client.clientSocket.fd);
        }
    }

    private void shutdown() {
        while (this.connectedClients.size() > 0) {
            Client client = this.connectedClients.valueAt(this.connectedClients.size() - 1);
            try {
                killConnection(client);
            } catch (RuntimeException e) {
                // The entry is already removed; keep shutting down the remaining clients and the server fds.
                android.util.Log.e("XConnectorEpoll", "Client shutdown failed: " + e);
                com.winlator.core.FaultLog.record("X client shutdown failed (" + connectorLabel + ")", e);
            }
        }
        removeFdFromEpoll(this.epollFd, this.serverFd);
        removeFdFromEpoll(this.epollFd, this.shutdownFd);
        synchronized (controlLock) {
            running = false;
            controlTasks.clear();
        }
        closeServerFdsOnce();
    }

    /** The socket, the shutdown eventfd and the epoll fd are closed exactly once, by whichever of shutdown(), stop() or a failed start gets here first. */
    private void closeServerFdsOnce() {
        synchronized (controlLock) {
            if (serverFdsClosed) return;
            serverFdsClosed = true;
            closeTrackedFd(this.serverFd);
            closeTrackedFd(this.shutdownFd);
            closeTrackedFd(this.epollFd);
        }
    }

    public int getInitialInputBufferCapacity() {
        return this.initialInputBufferCapacity;
    }

    public void setInitialInputBufferCapacity(int initialInputBufferCapacity) {
        this.initialInputBufferCapacity = initialInputBufferCapacity;
    }

    public int getInitialOutputBufferCapacity() {
        return this.initialOutputBufferCapacity;
    }

    public void setCanReceiveAncillaryMessages(boolean canReceiveAncillaryMessages) {
        this.canReceiveAncillaryMessages = canReceiveAncillaryMessages;
    }

    private void requestShutdown() {
        XConnectorEpollNative.signalEventFd(shutdownFd);
    }
}
