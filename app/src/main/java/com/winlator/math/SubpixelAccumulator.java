package com.winlator.math;

/**
 * 1.2.25: relative pointer movement in whole pixels without losing or inventing any. Touch, trackpad and look-through drags give
 * fractions of a pixel; they used to be rounded away from zero on every event (Mathf.roundPoint), so a resting finger's 0.1-0.5 px
 * sensor noise moved the cursor - and WoW's camera while a mouse button is held - a whole pixel each time, and a sensitivity below
 * 1 did nothing for slow drags (0.5 px became 1). The fraction is now carried to the next event: the total is exact and noise
 * that goes back and forth cancels out.
 */
public final class SubpixelAccumulator {
    private float rest;

    /** The whole pixels to move now for [delta]; the fraction stays for the next call. */
    public int take(float delta) {
        if (Float.isNaN(delta) || Float.isInfinite(delta)) return 0;
        float total = rest + delta;
        int whole = (int) total;   // toward zero
        rest = total - whole;
        return whole;
    }

    public void reset() { rest = 0f; }
}
