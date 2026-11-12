package com.winlator.widget;

import android.content.Context;

import com.winlator.renderer.XServerRenderer;
import com.winlator.xserver.XServer;

/**
 * Renderer-agnostic view methods that the X server and shared infra need.
 * Implemented by {@link XServerView} (Vulkan/SurfaceView).
 */
public interface XServerRendererView {
    Context getContext();
    void queueEvent(Runnable r);
    /** Resource cleanup that must not be lost when the view ends; runs on the renderer queue, or on the caller once it is gone. */
    default void queueCleanup(Runnable r) { queueEvent(r); }
    void requestRender();
    /** A cursor position the game set itself: only the drawn cursor has to move. */
    default void moveCursorTo(short x, short y) { requestRender(); }
    default void setGuestCursorVisible(boolean visible) { getRenderer().setCursorVisible(visible); }
    void setFrameRateLimit(int limit);
    void onResume();
    void onPause();
    XServer getxServer();
    XServerRenderer getRenderer();
}
