package com.winlator.widget;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Owns X renderer events for exactly one view lifetime. Scene work may be dropped when the view ends; resource cleanup may
 * not (a dropped destroy would leak the native buffer it was meant to release), so it has its own path.
 */
final class RendererTaskQueue implements AutoCloseable {
    private final ExecutorService executor;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final ConcurrentLinkedQueue<Runnable> cleanups = new ConcurrentLinkedQueue<>();

    RendererTaskQueue() { this(Executors.newSingleThreadExecutor()); }
    RendererTaskQueue(ExecutorService executor) { this.executor = executor; }

    void execute(Runnable task) {
        if (closed.get()) return;
        try {
            executor.execute(() -> { if (!closed.get()) task.run(); });
        } catch (RejectedExecutionException error) {
            if (!closed.get()) throw error;
        }
    }

    /**
     * Cleanup that must run exactly once: on the queue while it lives, otherwise (closed, rejected, or still pending when it
     * closes) on the thread that ends up holding it.
     */
    void executeCleanup(Runnable task) {
        cleanups.add(task);
        if (!closed.get()) {
            try {
                executor.execute(this::drainCleanups);
                return;
            } catch (RejectedExecutionException ignored) { }
        }
        drainCleanups();
    }

    private void drainCleanups() {
        Runnable task;
        while ((task = cleanups.poll()) != null) {
            try { task.run(); } catch (RuntimeException ignored) { }
        }
    }

    @Override public void close() {
        if (closed.compareAndSet(false, true)) {
            executor.shutdownNow();
            drainCleanups();
        }
    }
}
