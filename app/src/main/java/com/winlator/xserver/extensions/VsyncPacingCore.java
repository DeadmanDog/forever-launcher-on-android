package com.winlator.xserver.extensions;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Display-locked release schedule: on every vsync each window may release as many held buffers as
 * the FPS limit allows per refresh (60 FPS at 60 Hz = one per vsync, 30 FPS = every second vsync,
 * 45 FPS = three of four, 60 FPS on a 120 Hz panel = every second vsync). The game therefore
 * starts its frames at a fixed phase to the display and never drifts across a vsync, which a free
 * timer at 1/limit does whenever the panel's real rate differs slightly. Not thread-safe; the
 * owner synchronizes. Pure logic so the patterns are host-testable.
 */
final class VsyncPacingCore<T> {
    private static final long DEFAULT_PERIOD_NS = 16_666_667L;
    private static final long MIN_PERIOD_NS = 4_000_000L;
    private static final int PERIOD_SAMPLES = 16;
    /** Callbacks further apart than this (four 60 Hz refreshes) are a restart after a pause, not neighbouring refreshes: no period is learned from them. */
    private static final long MAX_LEARN_GAP_NS = 4 * DEFAULT_PERIOD_NS;

    private static final class WindowState<T> {
        final ArrayDeque<T> pending = new ArrayDeque<>();
        double budget = 1.0;
        int idleVsyncs;
    }
    /** A window with nothing pending for this many refreshes (about 4 s at 60 Hz) is forgotten: destroyed windows and reused XIDs
     *  no longer pile up in the map that every vsync walks. A window that comes back starts over, as after any idle stretch. */
    static final int FORGET_AFTER_VSYNCS = 240;

    private final Map<Integer, WindowState<T>> windows = new HashMap<>();
    private final long[] deltas = new long[PERIOD_SAMPLES];
    private int deltaCount;
    private int deltaIndex;
    private long periodNs = DEFAULT_PERIOD_NS;
    private long lastFrameNs;
    /** The vsync source stopped, so the gap to the next callback can contain unobserved refreshes. */
    private boolean afterIdle;
    private int limit;

    int limit() { return limit; }
    int windowCount() { return windows.size(); }
    long periodNs() { return periodNs; }

    /** Changing the limit releases everything held under the old one (returned to the caller). */
    List<T> setLimit(int fps) {
        int updated = Math.max(0, fps);
        if (updated == limit) return Collections.emptyList();
        limit = updated;
        return releaseAll();
    }

    /** Returns buffers to release immediately: all of them without a limit, or the ones an eager present displaced. */
    List<T> schedule(int windowId, T value, boolean eagerRelease) {
        if (limit <= 0) return Collections.singletonList(value);
        WindowState<T> state = windows.computeIfAbsent(windowId, id -> new WindowState<>());
        // A window that had nothing pending starts over: its first buffer goes on the next vsync,
        // the following ones keep the pattern (no back-to-back release after an idle stretch).
        // 1.2.25: only after a real idle stretch (nothing pending for a whole frame of the limit), for a caller that ticks onVsync
        // on every refresh even with nothing pending (VsyncIdleClampTest). 1.2.26 correction: VsyncFramePacer stops its callback
        // while the queue is empty and counts the skipped refreshes at the next one, so there the cut never removed credit and the
        // 1.2.24 pacer already released 60 FPS on 144 Hz 2,3,2,2,3 apart; the 1.2.25 note claiming 57.6 FPS came from a model
        // that ticked every refresh. Same releases either way.
        double perVsync = perVsync();
        if (state.pending.isEmpty() && state.idleVsyncs * perVsync >= 1.0 - 1e-6)
            state.budget = Math.min(state.budget, Math.max(0.0, 1.0 - perVsync));
        List<T> now = Collections.emptyList();
        if (eagerRelease && !state.pending.isEmpty()) {
            now = new ArrayList<>(state.pending);
            state.pending.clear();
        }
        state.pending.add(value);
        return now;
    }

    boolean hasPending() {
        for (WindowState<T> state : windows.values()) if (!state.pending.isEmpty()) return true;
        return false;
    }

    /** One display refresh; frameTimeNs is the vsync timestamp. Returns the buffers released on it. */
    List<T> onVsync(long frameTimeNs) {
        int elapsed = 1;
        if (lastFrameNs != 0 && frameTimeNs > lastFrameNs) {
            long delta = frameTimeNs - lastFrameNs;
            // The period is learned across an empty queue too: a game that has just one buffer ready per refresh makes the queue empty after
            // every release, and forgetting the previous callback each time meant a faster panel (120 Hz) was never measured and kept
            // the 60 Hz default, which released a buffer on EVERY refresh - the limit did nothing.
            if (delta <= MAX_LEARN_GAP_NS && !isIdleGap(delta)) learnPeriod(delta);
            // Also after an idle stretch: the refreshes that passed while the callback was off count (capped by the budget cap
            // below, so a stretch never turns into a burst). Counting it as one refresh made a limit below the refresh rate
            // (30 on 60 Hz, 60 on 120 Hz) hold every buffer that came to an empty queue for two refreshes instead of one.
            elapsed = (int) Math.max(1, Math.min(8, Math.round((double) delta / periodNs)));
        }
        afterIdle = false;
        lastFrameNs = frameTimeNs;
        if (limit <= 0) return releaseAll();
        // Most refreshes release nothing: the list is made only when something goes (2026-11-12 review: no list per vsync).
        List<T> released = null;
        double perVsync = perVsync();
        // At or above the refresh rate, cap the number of releases per refresh. An exact
        // refresh divisor (such as 60 FPS on 120 Hz) must also drop surplus credit after a late
        // callback, or it can release again on the very next refresh. Other fractional rates
        // carry their credit forward to preserve patterns such as 45 FPS on 60 Hz.
        double refreshesPerRelease = Math.rint(1.0 / perVsync);
        boolean exactDivisor = refreshesPerRelease > 1.0 && perVsync == 1.0 / refreshesPerRelease;
        double cap = perVsync >= 1.0 || exactDivisor ? Math.max(1.0, perVsync) : 1.0 + perVsync;
        for (java.util.Iterator<WindowState<T>> it = windows.values().iterator(); it.hasNext(); ) {
            WindowState<T> state = it.next();
            if (state.pending.isEmpty()) {
                if ((state.idleVsyncs += elapsed) >= FORGET_AFTER_VSYNCS) { it.remove(); continue; }
            } else {
                state.idleVsyncs = 0;
            }
            state.budget = Math.min(cap, state.budget + perVsync * elapsed);
            while (state.budget >= 1.0 - 1e-6 && !state.pending.isEmpty()) {
                if (released == null) released = new ArrayList<>(2);
                released.add(state.pending.poll());
                state.budget -= 1.0;
            }
            if (state.budget < 0) state.budget = 0;
        }
        return released != null ? released : java.util.Collections.emptyList();
    }

    private double perVsync() { return snap(limit * (periodNs / 1e9)); }

    /**
     * A limit within 2% of the refresh rate, a multiple or an even fraction of it (60 on a 60.05 Hz
     * panel, 30 on 60 Hz, 60 on 120 Hz) locks to it exactly. Otherwise the tiny rate difference
     * would skip one refresh every few seconds: the very judder this pacer removes.
     */
    static double snap(double perVsync) {
        for (int k = 1; k <= 4; k++) {
            if (Math.abs(perVsync - k) <= 0.02 * k) return k;
            if (Math.abs(perVsync - 1.0 / k) <= 0.02 / k) return 1.0 / k;
        }
        return perVsync;
    }

    /** The vsync source stopped (nothing pending). Keep the timestamp to count elapsed refreshes. */
    void idle() { afterIdle = true; }

    List<T> releaseAll() {
        List<T> all = new ArrayList<>();
        for (WindowState<T> state : windows.values()) {
            all.addAll(state.pending);
            state.pending.clear();
            state.budget = 1.0;
        }
        return all;
    }

    void clear() { windows.clear(); }

    private boolean isIdleGap(long delta) {
        if (!afterIdle || delta < periodNs + periodNs / 2) return false;
        // Whole multiples of the known period can come from callbacks we did not request.
        // They count towards the release budget but must not teach us a slower panel rate.
        long nearestMultiple = (delta + periodNs / 2) / periodNs * periodNs;
        return Math.abs(delta - nearestMultiple) <= nearestMultiple / 50;
    }

    /**
     * The panel period from the recent vsync deltas. 1.2.21: their MEDIAN, not their minimum: vsync timestamps jitter a little, and
     * the smallest delta is always shorter than the real period. With a limit that does not divide the refresh (60 FPS on the
     * player's 144 Hz panel) the release rate is limit x period, so 50 us of jitter made it 59.4 FPS and 200 us 57.6 (each missing
     * release a longer frame); at 60 Hz 200 us even broke the snap (55.4 FPS). The median ignores the jitter; a missed callback is
     * divided by the refreshes it spans. No allocation: a fixed scratch array.
     */
    private void learnPeriod(long delta) {
        if (delta < MIN_PERIOD_NS) return;
        deltas[deltaIndex] = delta;
        deltaIndex = (deltaIndex + 1) % PERIOD_SAMPLES;
        if (deltaCount < PERIOD_SAMPLES) deltaCount++;
        // Each delta is first taken as whole refreshes of the smallest one (a missed callback spans two or three), then the median.
        long min = Long.MAX_VALUE;
        for (int i = 0; i < deltaCount; i++) min = Math.min(min, deltas[i]);
        for (int i = 0; i < deltaCount; i++) sorted[i] = deltas[i] / Math.max(1, Math.round((double) deltas[i] / min));
        java.util.Arrays.sort(sorted, 0, deltaCount);
        periodNs = deltaCount % 2 == 1 ? sorted[deltaCount / 2] : (sorted[deltaCount / 2 - 1] + sorted[deltaCount / 2]) / 2;
    }
    private final long[] sorted = new long[PERIOD_SAMPLES];
}
