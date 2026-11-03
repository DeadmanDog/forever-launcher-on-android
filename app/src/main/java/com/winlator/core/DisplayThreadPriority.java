package com.winlator.core;

/** Optional best-effort Android scheduling; no busy loops or process-wide boost. */
public final class DisplayThreadPriority {
    private DisplayThreadPriority() { }
    private static volatile boolean enabled;
    public static void configure(boolean value) { enabled = value; }
    public static void apply() {
        if (!enabled) return;
        try { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_DISPLAY); }
        catch (SecurityException | IllegalArgumentException ignored) { }
    }
}
