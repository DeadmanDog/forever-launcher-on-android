package com.winlator.xserver.extensions;

import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.view.Choreographer;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Releases presented pixmaps on the display's real vsync (Android Choreographer on a dedicated
 * Looper thread; the X request thread has none). The release schedule is {@link VsyncPacingCore}.
 * Callbacks are only requested while buffers are held, so an idle session does not wake 60 times
 * a second. X client IO never runs under the monitor.
 */
final class VsyncFramePacer<T> implements IdlePacer<T> {
    private final Object monitor = new Object();
    private final VsyncPacingCore<T> core = new VsyncPacingCore<>();
    private final Consumer<T> release;
    private HandlerThread thread;
    private Handler handler;
    private Choreographer choreographer;
    private boolean callbackPosted;
    private boolean closed;
    // Phase pacing (experimental): releases held back by the measured delay, still owed to their clients on close.
    private final PresentPhaseAlign align = new PresentPhaseAlign();
    private final ArrayDeque<List<T>> deferred = new ArrayDeque<>();
    private long nextPhaseSampleNs;

    private final Choreographer.FrameCallback frame = new Choreographer.FrameCallback() {
        @Override public void doFrame(long frameTimeNanos) {
            // Phase pacing: an absolute release time on the vsync clock, so the time spent below (sampling, the monitor) does
            // not push it later.
            long releaseAtNs = 0;
            if (PresentPhase.enabled()) {
                long now = System.nanoTime();
                if (now >= nextPhaseSampleNs) {
                    nextPhaseSampleNs = now + 250_000_000L;
                    long[] phase = PresentPhase.sample();   // takes the renderer's lock: outside our monitor
                    PresentPhase.noteSampleCost(System.nanoTime() - now);
                    if (phase != null && phase.length >= 4) {
                        synchronized (monitor) {
                            // A pacer closed meanwhile must not overwrite the phase of the session that replaced it.
                            if (closed) return;
                            PresentPhase.publish(align.update(phase[0], phase[1], phase[2], phase[3]));
                        }
                    }
                }
                releaseAtNs = frameTimeNanos + PresentPhase.offsetNs();
            }
            List<T> released;
            synchronized (monitor) {
                callbackPosted = false;
                if (closed) return;
                released = core.onVsync(frameTimeNanos);
                if (core.hasPending()) {
                    choreographer.postFrameCallback(this);
                    callbackPosted = true;
                } else {
                    core.idle();
                }
                long delayNs = releaseAtNs != 0 ? releaseAtNs - System.nanoTime() : 0;
                if (delayNs > 500_000L && !released.isEmpty()) {
                    final List<T> batch = new ArrayList<>(released);
                    deferred.add(batch);
                    // Choreographer's frame time, System.nanoTime and uptimeMillis share CLOCK_MONOTONIC: the target rounded up
                    // to the next millisecond, never later than the remaining delay allows.
                    long nowMs = SystemClock.uptimeMillis();
                    long atMs = Math.max(nowMs, Math.min((releaseAtNs + 999_999L) / 1_000_000L, nowMs + (delayNs + 999_999L) / 1_000_000L));
                    final long target = releaseAtNs;
                    handler.postAtTime(() -> {
                        synchronized (monitor) { if (!deferred.remove(batch)) return; }
                        PresentPhase.noteRelease(System.nanoTime() - target);
                        for (T value : batch) release.accept(value);
                    }, atMs);
                    return;
                }
                if (releaseAtNs != 0 && !released.isEmpty()) PresentPhase.noteRelease(-delayNs);
            }
            for (T value : released) release.accept(value);
        }
    };

    VsyncFramePacer(Consumer<T> release) {
        this.release = release;
    }

    @Override public void setFrameRateLimit(int fps) {
        List<T> released;
        synchronized (monitor) {
            if (closed) return;
            released = core.setLimit(fps);
        }
        for (T value : released) release.accept(value);
    }

    @Override public void schedule(int windowId, T value, boolean eagerRelease) {
        List<T> released;
        synchronized (monitor) {
            if (closed) return;
            released = core.schedule(windowId, value, eagerRelease);
            if (core.hasPending() && !callbackPosted) {
                callbackPosted = true;
                startLocked();
                handler.post(() -> {
                    synchronized (monitor) {
                        if (closed) return;
                        if (choreographer == null) choreographer = Choreographer.getInstance();
                        choreographer.postFrameCallback(frame);
                    }
                });
            }
        }
        for (T released1 : released) release.accept(released1);
    }

    private void startLocked() {
        if (thread != null) return;
        thread = new HandlerThread("PresentVsync") {
            @Override protected void onLooperPrepared() { com.winlator.core.DisplayThreadPriority.apply(); }
        };
        thread.setDaemon(true);
        thread.start();
        handler = new Handler(thread.getLooper());
    }

    @Override public void releaseAllAndClose() {
        List<T> released;
        synchronized (monitor) {
            released = new ArrayList<>();
            for (List<T> batch : deferred) released.addAll(batch);
            deferred.clear();
            released.addAll(core.releaseAll());
            closeLocked();
        }
        for (T value : released) release.accept(value);
    }

    @Override public void close() {
        synchronized (monitor) { closeLocked(); }
    }

    private void closeLocked() {
        if (closed) return;
        closed = true;
        core.clear();
        if (choreographer != null && handler != null) {
            final Choreographer c = choreographer;
            handler.post(() -> c.removeFrameCallback(frame));
        }
        if (thread != null) thread.quitSafely();
    }
}
