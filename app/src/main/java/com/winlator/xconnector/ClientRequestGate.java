package com.winlator.xconnector;

/** Per-connection suspension; a late wake cannot resume a newer wait or a closed client. */
final class ClientRequestGate {
    private volatile boolean blocked;
    private boolean closed;
    private long generation;

    synchronized long suspend() {
        if (closed || blocked) throw new IllegalStateException("Client cannot be suspended");
        blocked = true;
        return ++generation;
    }

    synchronized boolean resume(long ticket) {
        if (closed || !blocked || generation != ticket) return false;
        blocked = false;
        return true;
    }

    boolean isBlocked() { return blocked; }

    synchronized void close() {
        closed = true;
        blocked = false;
        ++generation;
    }
}
