package com.winlator.xserver.extensions;

import java.util.function.Supplier;

/**
 * Release phase for the vsync pacer (experimental "phase" pacing). The compositor measures where game frames finish inside a
 * refresh (VK_GOOGLE_display_timing); {@link PresentPhaseAlign} turns that into a release delay so they land mid-refresh, away
 * from the edge where a little jitter moves them to the neighbouring refresh.
 */
public final class PresentPhase {
    private PresentPhase() { }
    private static volatile Supplier<long[]> source;
    private static volatile boolean enabled;
    private static volatile long offsetNs;

    /** {window number, mean ns, concentration 0..1000, refresh ns} of the latest measured window, or null. */
    public static void setSource(Supplier<long[]> value) { source = value; }
    public static void setEnabled(boolean value) {
        enabled = value;
        if (!value) offsetNs = 0;
    }
    public static boolean enabled() { return enabled; }
    /** The release delay in force (0 when off), for the diagnostics. */
    public static long offsetNs() { return offsetNs; }

    static void publish(long value) { offsetNs = enabled ? value : 0; }

    // How close releases came to their target and what a phase sample cost, since the previous take (diagnostics). One short lock
    // keeps count, sum and maximum of the same period together (about 60 updates a second, a take once a second).
    private static final Object statsLock = new Object();
    private static long releases, releaseErrorSumUs, releaseErrorMaxUs, sampleMaxUs;

    /** A release on the phase path: how late (positive) or early (negative) it ran against its target. */
    static void noteRelease(long errorNs) {
        long us = Math.abs(errorNs) / 1000;
        synchronized (statsLock) {
            releases++;
            releaseErrorSumUs += us;
            releaseErrorMaxUs = Math.max(releaseErrorMaxUs, us);
        }
    }
    static void noteSampleCost(long ns) {
        synchronized (statsLock) { sampleMaxUs = Math.max(sampleMaxUs, ns / 1000); }
    }

    /** Columns of [takeReleaseStats]. */
    public static final String RELEASE_STATS_HEADER = "phase_releases,release_err_avg_us,release_err_max_us,phase_sample_max_us";
    /** "releases,average |error| us,max |error| us,max sample cost us" since the previous call. */
    public static String takeReleaseStats() {
        long count, sum, max, sample;
        synchronized (statsLock) {
            count = releases; sum = releaseErrorSumUs; max = releaseErrorMaxUs; sample = sampleMaxUs;
            releases = releaseErrorSumUs = releaseErrorMaxUs = sampleMaxUs = 0;
        }
        return count + "," + (count > 0 ? sum / count : 0) + "," + max + "," + sample;
    }
    static long[] sample() {
        Supplier<long[]> s = source;
        return s != null ? s.get() : null;
    }
}
