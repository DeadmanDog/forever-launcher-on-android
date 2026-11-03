package com.winlator.xserver.extensions;

import java.util.HashMap;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/** Owns delayed pixmap releases independently of Android's UI/vsync queue. */
final class PresentFramePacer<T> implements IdlePacer<T> {
    private static final class Release<T> implements Comparable<Release<T>> {
        final int windowId;
        final T value;
        final long sequence;
        final long deadline;

        Release(int windowId, T value, long sequence, long deadline) {
            this.windowId = windowId;
            this.value = value;
            this.sequence = sequence;
            this.deadline = deadline;
        }

        @Override public int compareTo(Release<T> other) {
            int result = Long.compare(deadline, other.deadline);
            return result != 0 ? result : Long.compare(sequence, other.sequence);
        }
    }

    private final Object monitor = new Object();
    private final PriorityQueue<Release<T>> pending = new PriorityQueue<>();
    private final Map<Integer, Long> nextDeadlines = new HashMap<>();
    private final Consumer<T> release;
    private final LongSupplier clock;
    private final boolean automatic;
    private Thread worker;
    private int limit;
    private long sequence;
    private boolean closed;

    PresentFramePacer(Consumer<T> release) {
        this(release, System::nanoTime, true);
    }

    // An injected clock and manual drain allow deterministic timing tests without sleeps.
    PresentFramePacer(Consumer<T> release, LongSupplier clock, boolean automatic) {
        this.release = release;
        this.clock = clock;
        this.automatic = automatic;
    }

    @Override public void setFrameRateLimit(int fps) {
        synchronized (monitor) {
            int updated = Math.max(0, fps);
            if (closed || limit == updated) return;
            limit = updated;
            nextDeadlines.clear();
            // Old-limit buffers must not remain held after disabling/changing the cap.
            PriorityQueue<Release<T>> due = new PriorityQueue<>();
            long now = clock.getAsLong();
            for (Release<T> item : pending) {
                due.add(new Release<>(item.windowId, item.value, item.sequence, now));
            }
            pending.clear();
            pending.addAll(due);
            monitor.notifyAll();
        }
    }

    @Override public void schedule(int windowId, T value, boolean eagerRelease) {
        boolean immediate;
        synchronized (monitor) {
            if (closed) return;
            immediate = limit <= 0;
            if (!immediate) {
                long now = clock.getAsLong();
                long period = Math.max(1L, 1_000_000_000L / limit);
                // First/late frames are already due. Do not add a full interval AFTER
                // a slow render; that would throttle a 50 FPS game below a 60 FPS cap.
                Long previous = nextDeadlines.get(windowId);
                long deadline = previous == null ? now : Math.max(now, previous + period);
                nextDeadlines.put(windowId, deadline);
                // Eager/mailbox mode returns EVERY superseded buffer, not just the last one.
                if (eagerRelease) {
                    PriorityQueue<Release<T>> updated = new PriorityQueue<>();
                    for (Release<T> item : pending) {
                        updated.add(item.windowId == windowId
                            ? new Release<>(item.windowId, item.value, item.sequence, now) : item);
                    }
                    pending.clear();
                    pending.addAll(updated);
                }
                pending.add(new Release<>(windowId, value, sequence++, deadline));
                if (automatic && worker == null) {
                    worker = new Thread(this::run, "PresentPacer");
                    worker.setDaemon(true);
                    worker.setPriority(Thread.NORM_PRIORITY);
                    worker.start();
                }
                monitor.notifyAll();
            }
        }
        if (immediate) release.accept(value);
    }

    private void run() {
        com.winlator.core.DisplayThreadPriority.apply();
        try {
            while (true) {
                Release<T> item;
                synchronized (monitor) {
                    while (true) {
                        if (closed) return;
                        item = pending.peek();
                        if (item == null) {
                            monitor.wait();
                        } else {
                            long remaining = item.deadline - clock.getAsLong();
                            if (remaining <= 0L) {
                                pending.remove();
                                break;
                            }
                            monitor.wait(remaining / 1_000_000L, (int) (remaining % 1_000_000L));
                        }
                    }
                }
                // X client IO never holds the scheduler monitor or blocks Android's UI thread.
                release.accept(item.value);
            }
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    void drainDue() {
        if (automatic) throw new IllegalStateException("Manual drain is test-only");
        while (true) {
            Release<T> item;
            synchronized (monitor) {
                item = pending.peek();
                if (closed || item == null || item.deadline > clock.getAsLong()) return;
                pending.remove();
            }
            release.accept(item.value);
        }
    }

    @Override public void releaseAllAndClose() {
        java.util.List<Release<T>> held;
        synchronized (monitor) {
            held = new java.util.ArrayList<>(pending);
            java.util.Collections.sort(held);
            closed = true;
            pending.clear();
            nextDeadlines.clear();
            monitor.notifyAll();
        }
        for (Release<T> item : held) release.accept(item.value);
    }

    @Override public void close() {
        synchronized (monitor) {
            closed = true;
            pending.clear();
            nextDeadlines.clear();
            monitor.notifyAll();
        }
    }
}
