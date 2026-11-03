package com.winlator.xserver.extensions;

/** Paces the release (IdleNotify) of presented pixmaps; the client cannot reuse a buffer before it. */
interface IdlePacer<T> extends AutoCloseable {
    void setFrameRateLimit(int fps);
    void schedule(int windowId, T value, boolean eagerRelease);
    /** Releases every held buffer now and stops; used when the pacing mode is switched. */
    void releaseAllAndClose();
    @Override void close();
}
