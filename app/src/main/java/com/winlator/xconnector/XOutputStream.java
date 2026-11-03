package com.winlator.xconnector;

import com.winlator.xserver.XServer;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayDeque;
import java.util.concurrent.locks.ReentrantLock;

public class XOutputStream {
    private static final byte[] ZERO = new byte[64];
    public ByteBuffer buffer;
    public final ClientSocket clientSocket;
    private final ReentrantLock lock = new ReentrantLock();
    private int ancillaryFd = -1;
    private IOException writeFailure;

    /** One flush that the socket could not take yet: its remaining bytes and the descriptor that travels with its first byte. */
    private static final class Pending {
        final ByteBuffer data;
        int ancillaryFd;
        Pending(ByteBuffer data, int ancillaryFd) { this.data = data; this.ancillaryFd = ancillaryFd; }
    }

    /** Where bytes go when the output must never block (null = the classic blocking write). */
    interface NoWaitTransport { int send(ByteBuffer data, int offset, int length, int ancillaryFd) throws IOException; }

    // Non-blocking mode: a full socket queues the output (in order) instead of stalling the thread that wrote it; the epoll
    // thread drains the queue when the socket becomes writable. A client that stops reading for good is dropped at the limit.
    static volatile long maxPendingBytes = 64L * 1024 * 1024;
    private final ArrayDeque<Pending> pending = new ArrayDeque<>();
    private long pendingBytes;
    // Readable without the stream lock: the connector asks it while holding the client monitor, and the writer asks the connector
    // while holding the stream lock; a lock here would be a lock-order cycle between those two threads.
    private volatile boolean hasPendingOutput;
    private NoWaitTransport noWait;
    private Runnable pendingListener;
    private Runnable failureListener;
    private static final double FP3232_SCALE = 4294967296.0;

    public XOutputStream(int initialCapacity) {
        this(null, initialCapacity);
    }

    public XOutputStream(ClientSocket clientSocket, int initialCapacity) {
        this.clientSocket = clientSocket;
        buffer = ByteBuffer.allocateDirect(initialCapacity);
    }

    public void setByteOrder(ByteOrder byteOrder) {
        buffer.order(byteOrder);
    }

    /** Enables the non-blocking mode with the socket's own no-wait send; [onPending] tells the connector the queue changed. */
    void enableNonBlocking(NoWaitTransport transport, Runnable onPending) {
        enableNonBlocking(transport, onPending, null);
    }

    /** [onFailure] runs once the stream has failed for good (queue limit, socket error): the connector must then close the client. */
    void enableNonBlocking(NoWaitTransport transport, Runnable onPending, Runnable onFailure) {
        lock.lock();
        try { noWait = transport; pendingListener = onPending; failureListener = onFailure; } finally { lock.unlock(); }
    }

    boolean hasPending() {
        return hasPendingOutput;
    }

    public void setAncillaryFd(int ancillaryFd) {
        this.ancillaryFd = ancillaryFd;
    }

    public void writeByte(byte value) {
        ensureSpaceIsAvailable(1);
        buffer.put(value);
    }

    public void writeShort(short value) {
        ensureSpaceIsAvailable(2);
        buffer.putShort(value);
    }

    public void writeInt(int value) {
        ensureSpaceIsAvailable(4);
        buffer.putInt(value);
    }

    public void writeLong(long value) {
        ensureSpaceIsAvailable(8);
        buffer.putLong(value);
    }

    public void writeFP3232(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            throw new IllegalArgumentException("FP3232 value must be finite");
        }

        long fixed = Math.round(value * FP3232_SCALE);

        int integral = (int) (fixed >> 32);
        int frac = (int) fixed;

        // FP3232 is a struct { int32_t integral; uint32_t frac; } in X11.
        writeInt(integral);
        writeInt(frac);
    }

    public void writeString8(String str) {
        byte[] bytes = str.getBytes(XServer.LATIN1_CHARSET);
        int length = -str.length() & 3;
        ensureSpaceIsAvailable(bytes.length + length);
        buffer.put(bytes);
        if (length > 0) writePad(length);
    }

    public void write(byte[] data) {
        write(data, 0, data.length);
    }

    public void write(byte[] data, int offset, int length) {
        ensureSpaceIsAvailable(length);
        buffer.put(data, offset, length);
    }

    public void write(ByteBuffer data) {
        ensureSpaceIsAvailable(data.remaining());
        buffer.put(data);
    }

    public void writePad(int length) {
        write(ZERO, 0, length);
    }

    private void flush() throws IOException {
        if (writeFailure != null) {
            buffer.clear();
            throw writeFailure;
        }
        if (noWait != null) {
            flushNonBlocking();
            return;
        }
        if (buffer.position() != 0) {
            buffer.flip();
            try {
                if (ancillaryFd != -1) {
                    clientSocket.sendAncillaryMsg(buffer, ancillaryFd);
                    ancillaryFd = -1;
                } else {
                    clientSocket.write(buffer);
                }
            } catch (IOException error) {
                // A prefix may already be on the wire. Never replay it on this stream.
                writeFailure = error;
                ancillaryFd = -1;
                buffer.clear();
                throw error;
            }
            buffer.clear();
        }
    }

    /** Caller holds [lock]. Sends what the socket takes now and queues the rest, keeping the byte order. */
    private void flushNonBlocking() throws IOException {
        if (buffer.position() == 0) return;
        buffer.flip();
        int fdToSend = ancillaryFd;
        ancillaryFd = -1;
        try {
            // Anything already queued goes first: later output must never overtake it.
            if (pending.isEmpty()) {
                while (buffer.hasRemaining()) {
                    int sent = noWait.send(buffer, buffer.position(), buffer.remaining(), fdToSend);
                    if (sent == -2) break;
                    if (sent < 0 || sent > buffer.remaining()) throw new IOException("Socket write made invalid progress: " + sent);
                    buffer.position(buffer.position() + sent);
                    if (sent > 0) fdToSend = -1;
                }
            }
            if (buffer.hasRemaining()) {
                long length = buffer.remaining();
                if (pendingBytes + length > maxPendingBytes) throw new IOException("X client is not reading: output queue limit reached");
                ByteBuffer copy = ByteBuffer.allocateDirect(buffer.remaining()).order(buffer.order());
                copy.put(buffer).flip();
                pending.add(new Pending(copy, fdToSend));
                pendingBytes += length;
                hasPendingOutput = true;
                if (pendingListener != null) pendingListener.run();
            }
        } catch (IOException error) {
            // A prefix may already be on the wire. Never replay it on this stream.
            boolean first = writeFailure == null;
            writeFailure = error;
            releasePending();
            // Event senders swallow this exception, and nothing else would ever notice that protocol bytes were dropped: the
            // connector is told to close the client (it only schedules the close, so no lock order is created here).
            if (first && failureListener != null) failureListener.run();
            throw error;
        } finally {
            buffer.clear();
        }
    }

    /**
     * Called on the epoll thread when the socket became writable: sends queued output in order. Returns true once the queue is
     * empty (the writable interest can go), false while the socket is full again.
     */
    boolean drainPending() throws IOException {
        lock.lock();
        try {
            if (writeFailure != null) { releasePending(); throw writeFailure; }
            try {
                while (!pending.isEmpty()) {
                    Pending head = pending.peekFirst();
                    while (head.data.hasRemaining()) {
                        int sent = noWait.send(head.data, head.data.position(), head.data.remaining(), head.ancillaryFd);
                        if (sent == -2) return false;
                        if (sent < 0 || sent > head.data.remaining()) throw new IOException("Socket write made invalid progress: " + sent);
                        head.data.position(head.data.position() + sent);
                        if (sent > 0) head.ancillaryFd = -1;
                    }
                    pendingBytes -= head.data.capacity();
                    pending.removeFirst();
                }
                hasPendingOutput = false;
                return true;
            } catch (IOException error) {
                writeFailure = error;
                releasePending();
                throw error;
            }
        } finally {
            lock.unlock();
        }
    }

    /** Drops queued output (the connection is gone). A descriptor that never travelled stays owned by its sender. */
    void releasePending() {
        lock.lock();
        try { pending.clear(); pendingBytes = 0; hasPendingOutput = false; } finally { lock.unlock(); }
    }

    public XStreamLock lock() {
        return new OutputStreamLock();
    }

    private void ensureSpaceIsAvailable(int length) {
        int position = buffer.position();
        if ((buffer.capacity() - position) >= length) return;
        int required = Math.addExact(position, length);
        long doubled = Math.max(64L, (long)buffer.capacity() * 2L);
        int capacity = (int)Math.min(Integer.MAX_VALUE, Math.max(required, doubled));
        ByteBuffer newBuffer = ByteBuffer.allocateDirect(capacity).order(buffer.order());
        buffer.flip();
        newBuffer.put(buffer);
        buffer = newBuffer;
    }

    public void writeSuccessReply(int sequenceNumber, int replyLength) throws IOException {
        try (XStreamLock lock = lock()) {
            writeByte((byte) 1);
            writeByte((byte) 0);
            writeShort((short) sequenceNumber);
            writeInt(replyLength);
            writePad(24);
        }
    }

    private class OutputStreamLock implements XStreamLock {
        public OutputStreamLock() {
            lock.lock();
        }

        @Override
        public void close() throws IOException {
            try {
                flush();
            }
            finally {
                lock.unlock();
            }
        }
    }
}
