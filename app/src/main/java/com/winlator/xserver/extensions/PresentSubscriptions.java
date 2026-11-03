package com.winlator.xserver.extensions;

import java.util.TreeMap;

/** Copy subscriptions on changes; frame dispatch reads an immutable snapshot without locks. */
final class PresentSubscriptions<W, C> {
    static final class Entry<W, C> {
        final int id;
        final W window;
        final C client;
        final long mask;
        Entry(int id, W window, C client, long mask) {
            this.id = id; this.window = window; this.client = client; this.mask = mask;
        }
    }
    private final TreeMap<Integer, Entry<W, C>> entries = new TreeMap<>();
    private volatile Entry<W, C>[] snapshot = empty();

    @SuppressWarnings("unchecked")
    private Entry<W, C>[] empty() { return (Entry<W, C>[]) new Entry<?, ?>[0]; }

    synchronized Entry<W, C> get(int id) { return entries.get(id); }

    synchronized void put(int id, W window, C client, long mask) {
        if (mask == 0) { remove(id); return; }
        Entry<W, C> old = entries.get(id);
        if (old != null && old.window == window && old.client == client && old.mask == mask) return;
        entries.put(id, new Entry<>(id, window, client, mask));
        publish();
    }

    synchronized void remove(int id) {
        if (entries.remove(id) != null) publish();
    }

    synchronized void removeClient(C client) {
        if (entries.values().removeIf(e -> e.client == client)) publish();
    }

    synchronized void removeWindow(W window) {
        if (entries.values().removeIf(e -> e.window == window)) publish();
    }

    synchronized void clear() {
        if (entries.isEmpty()) return;
        entries.clear(); snapshot = empty();
    }

    private void publish() { snapshot = entries.values().toArray(empty()); }

    // Callers must not modify the array. No allocations occur on frame dispatch.
    Entry<W, C>[] snapshot() { return snapshot; }
}
