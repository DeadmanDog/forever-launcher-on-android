package com.winlator.widget;

/**
 * Frames per second over a 500 ms window, with the clock handed in so it can be tested on the host.
 *
 * A reading is "no measurement" (0) once frames have stopped for longer than {@link #STALE_MS}: the last number must not stay on
 * the HUD while the game is frozen, and the first frame after a long pause must not be divided by the whole pause (that showed
 * 0.1 FPS right after a resume). A new window starts at that frame instead.
 */
final class FpsCounter {
    static final long WINDOW_MS = 500;
    static final long STALE_MS = 1500;

    private long windowStart;
    private int frames;
    private float fps;
    private long lastFrame;

    /** Counts a frame at [timeMs]; returns true when this frame completed a new reading. */
    boolean onFrame(long timeMs, int limitHint) {
        if (lastFrame != 0 && timeMs - lastFrame > STALE_MS) {
            windowStart = 0;
            frames = 0;
            fps = 0;
        }
        if (windowStart == 0) windowStart = timeMs;
        boolean fresh = false;
        if (timeMs >= windowStart + WINDOW_MS) {
            fps = FpsReadout.forLimit((float) (frames * 1000) / (timeMs - windowStart), limitHint);
            windowStart = timeMs;
            frames = 0;
            fresh = true;
        }
        frames++;
        if (timeMs > lastFrame) lastFrame = timeMs;
        return fresh;
    }

    /** The latest reading, or 0 when no frame arrived for {@link #STALE_MS} before [nowMs]. */
    float fps(long nowMs) {
        return lastFrame != 0 && nowMs - lastFrame > STALE_MS ? 0f : fps;
    }

    void reset() {
        windowStart = 0;
        frames = 0;
        fps = 0;
        lastFrame = 0;
    }
}
