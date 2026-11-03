package com.winlator.renderer;

/** Decides whether a window hides everything below it. */
public final class ScreenCoverage {
    private ScreenCoverage() {}

    /**
     * True when the window rectangle covers the whole screen. Size alone is not enough: a window as large as the screen but
     * moved aside leaves a strip of the windows below visible (the compositor draws opaque windows, so only an exact cover
     * can hide them).
     */
    public static boolean covers(long x, long y, long width, long height, int screenW, int screenH) {
        return x <= 0 && y <= 0 && x + width >= screenW && y + height >= screenH;
    }
}
