package com.winlator.renderer;

import java.util.concurrent.atomic.AtomicLong;

/** Per-second scanout accounting for the optional WoW diagnostics; counters only, no I/O. */
public final class ScanoutStats {
    private ScanoutStats() { }
    private static final AtomicLong scanoutFrames = new AtomicLong();
    private static final AtomicLong compositorFrames = new AtomicLong();
    private static volatile String state = "off";

    public static void scanoutFrame() { scanoutFrames.incrementAndGet(); }
    public static void compositorFrame() { compositorFrames.incrementAndGet(); }
    public static void setState(String value) { state = value; }

    /** Frames since the previous call and the current state, e.g. "12,48,active". */
    public static String take() {
        return scanoutFrames.getAndSet(0) + "," + compositorFrames.getAndSet(0) + "," + state;
    }
    public static void reset() { scanoutFrames.set(0); compositorFrames.set(0); state = "off"; }
}
