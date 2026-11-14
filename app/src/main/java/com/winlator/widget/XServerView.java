package com.winlator.widget;

import android.annotation.SuppressLint;
import android.content.Context;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import androidx.annotation.NonNull;

import com.winlator.renderer.VulkanRenderer;
import com.winlator.renderer.XServerRenderer;
import com.winlator.xserver.XServer;


@SuppressLint("ViewConstructor")
public class XServerView extends SurfaceView implements SurfaceHolder.Callback, XServerRendererView {
    private XServerRenderer renderer;
    private final XServer xServer;
    private final RendererTaskQueue eventExecutor = new RendererTaskQueue();
    private int frameRateLimit = 0;
    private boolean gamescopeMode;
    public void setGamescopeMode(boolean enabled) { gamescopeMode = enabled; }
    public boolean isGamescopeMode() { return gamescopeMode; }

    /** Gamescope: the panel rate the bridge was last told, so a display mode change (60 -> 120 Hz) reaches its vblank grid. */
    private int gamescopeRefreshHz = 0;
    private android.hardware.display.DisplayManager.DisplayListener refreshListener;

    private boolean hardwareScale = false;
    private boolean fixedBuffer = false;

    public XServerView(Context context, XServer xServer, String selectedRenderer) {
        super(context);
        setLayoutParams(new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        getHolder().addCallback(this);
        this.xServer = xServer;
        // The SurfaceFlinger (ASurfaceRenderer) path was removed; this view always renders with Vulkan.
        renderer = new VulkanRenderer(this, xServer);
    }

    public XServer getxServer() {
        return xServer;
    }

    public XServerRenderer getRenderer() {
        return renderer;
    }

    @Override public void setGuestCursorVisible(boolean visible) {
        if (gamescopeMode) app.gamenative.display.GameNativeDrm.setGuestCursorVisible(visible);
        else XServerRendererView.super.setGuestCursorVisible(visible);
    }

    /** WoW: render at the guest size and let the display hardware upscale the layer. */
    public void setHardwareScale(boolean enabled) {
        hardwareScale = enabled;
        updateBufferSize();
    }

    /** UI thread. Upscaling effects and frame generation keep the full-size buffer. */
    public void updateBufferSize() {
        if (gamescopeMode) return;
        int guestWidth = xServer.screenInfo.width, guestHeight = xServer.screenInfo.height;
        boolean fixed = hardwareScale && renderer instanceof VulkanRenderer vk && !vk.needsFullSizeOutput()
            && app.gamenative.ui.screen.wow.WowHardwareScale.fits(getWidth(), getHeight(), guestWidth, guestHeight);
        if (fixed == fixedBuffer) return;
        fixedBuffer = fixed;
        if (fixed) getHolder().setFixedSize(guestWidth, guestHeight);
        else getHolder().setSizeFromLayout();
    }

    // A fixed buffer size must not shrink the view; only the surface buffer changes.
    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        setMeasuredDimension(getDefaultSize(0, widthMeasureSpec), getDefaultSize(0, heightMeasureSpec));
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        post(this::updateBufferSize);
    }

    @Override
    public void surfaceCreated(@NonNull SurfaceHolder holder) {
        applyMainSurfaceFrameRate();
        if (gamescopeMode) {
            try {
                android.graphics.Rect frame = holder.getSurfaceFrame();
                int width = frame.width() > 0 ? frame.width() : getWidth();
                int height = frame.height() > 0 ? frame.height() : getHeight();
                if (width <= 0 || height <= 0) { width = xServer.screenInfo.width; height = xServer.screenInfo.height; }
                gamescopeRefreshHz = currentRefreshHz();
                app.gamenative.display.WowGamescopeRuntime.surface(holder.getSurface(), width, height,
                    xServer.screenInfo.width, xServer.screenInfo.height, gamescopeRefreshHz);
                watchRefreshRate();
            } catch (RuntimeException | LinkageError error) {
                app.gamenative.display.GameNativeDrm.reportStartFailure(getContext(), error);
            }
            return;
        }
        if (renderer instanceof VulkanRenderer vkRenderer) {
            vkRenderer.onSurfaceCreated(holder.getSurface());
        }

    }

    @Override
    public void surfaceChanged(@NonNull SurfaceHolder holder, int format, int width, int height) {
        if (gamescopeMode) { app.gamenative.display.GameNativeDrm.nativeRetargetSurface(holder.getSurface(),width,height); return; }
        app.gamenative.ui.screen.wow.WowDiagnostics.surfaceGeometry(getContext(), width, height,
            xServer.screenInfo.width, xServer.screenInfo.height);
        if (renderer instanceof VulkanRenderer vkRenderer) {
            vkRenderer.onSurfaceChanged(width, height);
        }
    }

    @Override
    public void surfaceDestroyed(@NonNull SurfaceHolder holder) {
        if (gamescopeMode) return; // Session owns teardown; screen power cycles only retarget the Surface.
        if (renderer instanceof VulkanRenderer vkRenderer) {
            vkRenderer.onSurfaceDestroyed();
        }
    }

    private int currentRefreshHz() {
        android.view.Display display = getDisplay();
        return display == null ? 60 : Math.max(1, Math.round(display.getRefreshRate()));
    }

    /**
     * Gamescope: the panel rate was passed once, when the Surface was created; a later mode change (the system switching
     * 60/120/144 Hz) left the bridge's vblank grid on the old rate. The bridge rebuilds its mode from this value on each connector
     * read, so Gamescope takes it the next time its backend is refreshed.
     */
    private void watchRefreshRate() {
        if (refreshListener != null) return;
        android.hardware.display.DisplayManager dm = getContext().getSystemService(android.hardware.display.DisplayManager.class);
        if (dm == null) return;
        refreshListener = new android.hardware.display.DisplayManager.DisplayListener() {
            @Override public void onDisplayAdded(int displayId) { }
            @Override public void onDisplayRemoved(int displayId) { }
            @Override public void onDisplayChanged(int displayId) {
                android.view.Display display = getDisplay();
                if (!gamescopeMode || display == null || display.getDisplayId() != displayId) return;
                int hz = currentRefreshHz();
                if (hz == gamescopeRefreshHz) return;
                android.util.Log.i("XServerView", "Gamescope panel refresh " + gamescopeRefreshHz + " -> " + hz + " Hz");
                gamescopeRefreshHz = hz;
                try {
                    app.gamenative.display.GameNativeDrm.nativeSetPanelRefreshHz(hz);
                    // Gamescope reads the connector's modes again only when its backend is dirtied.
                    app.gamenative.display.GameNativeDrm.nativeGamescopeCommand("backend_set_dirty");
                }
                catch (RuntimeException | LinkageError ignored) { }
            }
        };
        dm.registerDisplayListener(refreshListener, new android.os.Handler(android.os.Looper.getMainLooper()));
    }

    private void stopWatchingRefreshRate() {
        if (refreshListener == null) return;
        android.hardware.display.DisplayManager dm = getContext().getSystemService(android.hardware.display.DisplayManager.class);
        if (dm != null) dm.unregisterDisplayListener(refreshListener);
        refreshListener = null;
    }

    public void queueEvent(Runnable r) {
        eventExecutor.execute(r);
    }

    @Override
    public void queueCleanup(Runnable r) {
        eventExecutor.executeCleanup(r);
    }

    /** Called only when the AndroidView is permanently released. */
    public void dispose() {
        stopWatchingRefreshRate();
        // Review 2026-11-13: a view rebuilt while the game runs (activity recreation) must not end the Gamescope display, input
        // injector or input sink the session still uses; the session's Gamescope component stops them when the game ends.
        if (gamescopeMode && !app.gamenative.PluviaApp.hasLiveEnvironment()) {
            app.gamenative.display.GameNativeDrm.INSTANCE.setFrameListener(null);
            app.gamenative.display.GameNativeDrm.nativeInjectorStop();
            app.gamenative.display.GameNativeDrm.nativeStop();
            xServer.setInputEventSink(null);
        }
        getHolder().removeCallback(this);
        eventExecutor.close();
        if (renderer instanceof VulkanRenderer vkRenderer) vkRenderer.dispose();
    }

    public void onPause() { if (renderer instanceof VulkanRenderer vk) vk.setForeground(false); }
    public void onResume() { if (renderer instanceof VulkanRenderer vk) vk.setForeground(true); }

    public void setFrameRateLimit(int frameRateLimit) {
        this.frameRateLimit = Math.max(0, frameRateLimit);
        applyMainSurfaceFrameRate();
        if (gamescopeMode) { app.gamenative.display.GameNativeDrm.setFpsLimit(this.frameRateLimit); return; }
        if (renderer instanceof VulkanRenderer vkRenderer) {
            vkRenderer.setFpsLimit(this.frameRateLimit);
        }
    }

    private void applyMainSurfaceFrameRate() {
        if (android.os.Build.VERSION.SDK_INT < 30) return;
        android.view.Surface surface = getHolder().getSurface();
        if (!surface.isValid()) return;
        // A plain hint (DEFAULT, seamless switches only) left a 144 Hz panel at 144 under a 60 limit: a 2/3-refresh beat. The
        // limit is the content's fixed rate, and Android 12+ may switch the panel mode for it. Not a multiple (120 for 60): frames
        // finishing near the 120 Hz latch then flip between one and three refreshes (1.3.32 measurement).
        float rate = frameRateLimit;
        int compatibility = frameRateLimit > 0 ? android.view.Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE
            : android.view.Surface.FRAME_RATE_COMPATIBILITY_DEFAULT;
        try {
            if (android.os.Build.VERSION.SDK_INT >= 31) {
                surface.setFrameRate(rate, compatibility, android.view.Surface.CHANGE_FRAME_RATE_ALWAYS);
            } else {
                surface.setFrameRate(rate, compatibility);
            }
        }
        catch (IllegalStateException | IllegalArgumentException ignored) { }
    }

    @Override
    public void moveCursorTo(short x, short y) {
        if (renderer instanceof VulkanRenderer vkRenderer) vkRenderer.moveCursorTo(x, y);
    }

    public void requestRender() {
        if (renderer instanceof VulkanRenderer vkRenderer) {
            vkRenderer.queueSceneUpdate();
        }
    }
}
