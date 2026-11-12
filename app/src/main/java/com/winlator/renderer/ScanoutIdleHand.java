package com.winlator.renderer;

/**
 * 1.2.1: the Present buffer on the scanout layer. A buffer handed straight to SurfaceFlinger stays on screen until the next one
 * replaces it, so its Idle (the game may draw into it again) waits for that replacement; before, the game was told at once and
 * could draw into the buffer the display was still showing. Holds the PresentIdleHolds token of the buffer shown now.
 */
final class ScanoutIdleHand {
    private long current;

    /** [token] (0 = none) is the buffer just shown; returns the token of the one it replaced (0 = none), to be released now. */
    synchronized long replace(long token) {
        long previous = current;
        current = token;
        return previous;
    }

    /** The scanout layer goes away (or the compositor takes over): returns the token still held (0 = none) and forgets it. */
    synchronized long take() {
        long previous = current;
        current = 0;
        return previous;
    }
}
