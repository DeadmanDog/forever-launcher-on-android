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

    long offsetNs() { return offsetNs; }

    long update(long window, long meanNs, long concentration, long refreshNs) {
        if (window == lastWindow || refreshNs <= 0) return offsetNs;
        lastWindow = window;
        if (settling) { settling = false; return offsetNs; }
        if (concentration < 500) return offsetNs;
        long error = Math.floorMod(meanNs, refreshNs) - refreshNs / 2;
        if (Math.abs(error) <= refreshNs / 8) return offsetNs;
        offsetNs = Math.floorMod(offsetNs + error, refreshNs);
        settling = true;
        return offsetNs;
    }
}
