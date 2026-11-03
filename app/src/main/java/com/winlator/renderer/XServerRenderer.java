package com.winlator.renderer;

import com.winlator.widget.FrameRating;
import com.winlator.widget.XServerRendererView;

/**
 * Shared surface used by the X server and call sites of the compositor
 * ({@link VulkanRenderer}; the legacy OpenGL renderer was removed in v0.1.117).
 */
public interface XServerRenderer {
    boolean isFullscreen();
    void setCursorVisible(boolean visible);
    String getForceFullscreenWMClass();
    void setForceFullscreenWMClass(String wmClass);
    void setUnviewableWMClasses(String... classes);
    void setOnFrameRenderedListener(Runnable r);
    void setFrameRating(FrameRating fr);
    XServerRendererView getRendererView();
}
