package com.winlator.widget;

/**
 * HUD FPS readout under an FPS limit. The limiter's long-run rate is exact, but a game that has gathered
 * free buffers during a hitch submits them in one burst, which the display never shows yet a short
 * counting window reads as 65-67 at a 60 limit. A reading slightly above the limit is therefore shown as
 * the limit itself. Nothing is ever lowered, and a reading well above the limit (a limiter that is really
 * not working) is shown unchanged.
 */
final class FpsReadout {
    /** Readings up to this far above the limit are treated as a burst artifact. */
    static final float BURST_TOLERANCE = 1.15f;

    private FpsReadout() {}

    static float forLimit(float measured, int limit) {
        if (limit > 0 && measured > limit && measured <= limit * BURST_TOLERANCE) return limit;
        return measured;
    }
}
