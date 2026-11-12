package com.winlator.xserver.extensions;

import java.util.HashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Present buffers the renderer still holds. A client is told "buffer free" (Idle) only when the renderer says its frames are done;
 * each hold also has a STALL limit, so a renderer that never answers can not leave the client waiting for ever. The limit is a
 * safety net, not part of the protocol: it is long (the renderer polls the GPU itself, see VulkanRendererContext::renderLoop)
 * and reaching it is reported through [onExpired]. A hold's action runs exactly once, on whichever of release / stall limit /
 * releaseAll comes first.
 */
public final class PresentIdleHolds {
    static final java.util.concurrent.ScheduledThreadPoolExecutor TIMER = new java.util.concurrent.ScheduledThreadPoolExecutor(1, r -> {
        Thread t = new Thread(r, "PresentIdleHolds");
        t.setDaemon(true);
        return t;
    });
    // A released hold cancels its stall timer; without this the dead task stayed queued for the full limit and woke the timer
    // thread for nothing (about 60 times a second at 60 FPS).
    static { TIMER.setRemoveOnCancelPolicy(true); }

    private static final class Hold {
        final Runnable action;
        ScheduledFuture<?> limit;
        Hold(Runnable action) { this.action = action; }
    }

    private final HashMap<Long, Hold> holds = new HashMap<>();
    private long next = 1;
    private final long limitMs;
    private final Runnable onExpired;

    public PresentIdleHolds(long limitMs) { this(limitMs, null); }

    /** [onExpired] runs (on the timer thread, after the action) each time a hold ended by reaching the stall limit. */
    public PresentIdleHolds(long limitMs, Runnable onExpired) {
        this.limitMs = limitMs;
        this.onExpired = onExpired;
    }

    /** Registers [action] and returns its token (never 0). */
    public long hold(Runnable action) {
        final Hold hold = new Hold(action);
        final long token;
        synchronized (this) {
            token = next++;
            holds.put(token, hold);
        }
        ScheduledFuture<?> limit = TIMER.schedule(() -> { if (runHold(token) && onExpired != null) onExpired.run(); },
            limitMs, TimeUnit.MILLISECONDS);
        synchronized (this) {
            if (holds.get(token) == hold) hold.limit = limit; else limit.cancel(false);   // released meanwhile
        }
        return token;
    }

    /** Runs the action of [token] if it is still held (a second call, or an unknown token, does nothing). */
    public void release(long token) { runHold(token); }

    private boolean runHold(long token) {
        Hold hold;
        synchronized (this) {
            hold = holds.remove(token);
        }
        if (hold == null) return false;
        if (hold.limit != null) hold.limit.cancel(false);
        hold.action.run();
        return true;
    }

    /** Runs every remaining action (the renderer is going away). */
    public void releaseAll() {
        Long[] tokens;
        synchronized (this) {
            tokens = holds.keySet().toArray(new Long[0]);
        }
        for (long token : tokens) release(token);
    }

    public synchronized int size() { return holds.size(); }
}
