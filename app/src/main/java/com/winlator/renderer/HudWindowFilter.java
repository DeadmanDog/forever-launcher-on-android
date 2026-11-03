package com.winlator.renderer;

/**
 * Decides which Present requests the HUD counts. Only the tracked application window should: a second window presenting (a launcher, a
 * tool) must not add its rate to the game's. But the window that presents is often NOT the tracked top-level window - Wine's Vulkan
 * surface is a child of it - and a filter that misses it shows 0 FPS and no frame time or LOW values at all (1.3.20 did exactly that).
 * So the match is "the tracked window or inside it", and when nothing matched for a whole second the filter steps aside and counts
 * everything, as before the filter existed: wrong tracking can cost accuracy, never the readout.
 */
final class HudWindowFilter {
    static final long STEP_ASIDE_NS = 1_000_000_000L;

    /** Time of the last request that matched the tracked window; 0 = none yet. */
    private long lastMatchNs;

    /**
     * @param trackingKnown whether a tracked window exists at all (false: everything counts)
     * @param insideTracked whether the presenting window is the tracked window or lies inside it
     */
    synchronized boolean shouldCount(boolean trackingKnown, boolean insideTracked, long nowNs) {
        if (!trackingKnown || insideTracked) {
            lastMatchNs = nowNs;
            return true;
        }
        return lastMatchNs == 0 || nowNs - lastMatchNs > STEP_ASIDE_NS;
    }

    synchronized void reset() { lastMatchNs = 0; }
}
