package com.winlator.xserver;

/**
 * Coalesces the "content changed" notifications of one drawable. A request that draws many shapes (PolyFillRectangle, PolyLine)
 * opens a batch: every change inside it only marks a pending change, and the last [end] reports it once. Without this each
 * rectangle made the renderer copy the whole window again.
 */
final class UpdateBatch {
    private int depth;
    private boolean pending;

    /** A change happened. Returns true when the caller must notify now, false when the open batch will notify at its end. */
    synchronized boolean changed() {
        if (depth > 0) {
            pending = true;
            return false;
        }
        return true;
    }

    synchronized void begin() {
        depth++;
    }

    /** Closes one batch. Returns true when changes were held back and the caller must notify now (the outermost end only). */
    synchronized boolean end() {
        if (depth == 0) return false;
        depth--;
        if (depth == 0 && pending) {
            pending = false;
            return true;
        }
        return false;
    }
}
