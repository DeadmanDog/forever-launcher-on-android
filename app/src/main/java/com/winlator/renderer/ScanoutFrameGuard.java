package com.winlator.renderer;

/**
 * Review 3 (3): while a window is shown by direct scanout, a frame it presents with an offset or at another size goes to the
 * compositor, but the scanout layer stays on top with the last direct buffer: the picture froze. Such a frame takes the window out
 * of scanout until it has presented {@link #FULL_FRAMES_TO_RETURN} full-screen frames in a row again (no flip-flop per frame).
 * Called from the present path (under the pixmap's render lock) and from the scene update; both are serialized by the caller.
 */
final class ScanoutFrameGuard {
    static final int FULL_FRAMES_TO_RETURN = 120;

    private Object rejected;
    private int fullFrames;

    /**
     * A frame that went to the compositor. Returns true when the scene must be re-evaluated (the window leaves scanout, or may
     * come back to it).
     */
    synchronized boolean compositorFrame(Object drawable, boolean nativeMode, boolean isCandidate, boolean fullFrame) {
        if (nativeMode && isCandidate && !fullFrame) {
            boolean changed = rejected != drawable;
            rejected = drawable;
            fullFrames = 0;
            return changed;
        }
        if (drawable != rejected) return false;
        if (!fullFrame) {
            fullFrames = 0;
            return false;
        }
        if (++fullFrames < FULL_FRAMES_TO_RETURN) return false;
        rejected = null;
        fullFrames = 0;
        return true;
    }

    /** Whether [candidate] must stay on the compositor for now. */
    synchronized boolean blocks(Object candidate) {
        return candidate != null && candidate == rejected;
    }

    synchronized void reset() {
        rejected = null;
        fullFrames = 0;
    }
}
