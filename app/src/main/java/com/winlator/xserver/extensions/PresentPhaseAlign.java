package com.winlator.xserver.extensions;

/**
 * Release delay from the measured phase: the mean time from a compositor frame being ready to the panel taking it, inside one
 * refresh. Releasing d later makes frames finish d later, which shortens that wait by d; the delay moves the mean to half a
 * refresh. A window measured while the previous shift was taking effect is skipped, a scattered phase (concentration under 0.5)
 * changes nothing, and errors within an eighth of a refresh are left alone. Pure logic; the pacer thread owns it.
 */
final class PresentPhaseAlign {
    private long offsetNs;
    private long lastWindow = -1;
    private boolean settling;

    PresentPhaseAlign() { }

    /**
     * A pacer that takes over (background/foreground, a settings change) continues from the delay in force: starting from 0 added
     * the next error to 0 although that window was measured with the old delay, so the phase was off for seconds after every
     * return to the app. The first window it sees was measured before the takeover and is skipped.
     */
    PresentPhaseAlign(long carriedOffsetNs) {
        offsetNs = Math.max(0, carriedOffsetNs);
        settling = carriedOffsetNs > 0;
    }

    long offsetNs() { return offsetNs; }

    long update(long window, long meanNs, long concentration, long refreshNs) {
        if (window == lastWindow || refreshNs <= 0) return offsetNs;
        lastWindow = window;
        // A delay learned at another refresh rate (60 -> 120 Hz) is folded into the new refresh: more than one refresh pushed every
        // release past a vsync.
        if (offsetNs >= refreshNs) offsetNs = Math.floorMod(offsetNs, refreshNs);
        if (settling) { settling = false; return offsetNs; }
        if (concentration < 500) return offsetNs;
        long error = Math.floorMod(meanNs, refreshNs) - refreshNs / 2;
        if (Math.abs(error) <= refreshNs / 8) return offsetNs;
        long target = offsetNs + error;
        // The delay lives inside one refresh. A correction that crosses its ends used to wrap round (1 ms - 3 ms -> 14.7 ms): the
        // next release then came a whole refresh late or early, one repeated or doubled frame (1.0.9 diag: about one a minute). A
        // correction that only overshoots by up to a quarter refresh stops at the end instead; frames then still sit at least a
        // quarter refresh from the latch. A bigger one, frames too close to the latch, still goes round once.
        long limit = refreshNs / 4;
        long next;
        if (target < 0 && target >= -limit) next = 0;
        else if (target >= refreshNs && target < refreshNs + limit) next = refreshNs - 1;
        else next = Math.floorMod(target, refreshNs);
        if (next == offsetNs) return offsetNs;
        offsetNs = next;
        settling = true;
        return offsetNs;
    }
}
