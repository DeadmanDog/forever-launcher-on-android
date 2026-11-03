package com.winlator.renderer;

import java.util.function.Consumer;

/**
 * Whether the per-frame log (frames.csv) is filled: on while the diagnostics read it. Kept apart from VulkanRenderer so the
 * diagnostics can switch it without loading the native renderer library; the active renderer listens and passes it on.
 */
public final class FrameTrace {
    private FrameTrace() { }
    private static volatile boolean enabled;
    private static volatile Consumer<Boolean> listener;

    public static void set(boolean value) {
        enabled = value;
        Consumer<Boolean> l = listener;
        if (l != null) l.accept(value);
    }
    public static boolean enabled() { return enabled; }
    static void setListener(Consumer<Boolean> value) { listener = value; }
}
