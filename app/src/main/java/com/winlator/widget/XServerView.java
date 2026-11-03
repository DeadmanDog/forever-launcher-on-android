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

    /** WoW: render at the guest size and let the display hardware upscale the layer. */
    public void setHardwareScale(boolean enabled) {
        hardwareScale = enabled;
        updateBufferSize();
    }

    /** UI thread. Upscaling effects and frame generation keep the full-size buffer. */
    public void updateBufferSize() {
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

        if (renderer instanceof VulkanRenderer vkRenderer) {
            vkRenderer.onSurfaceCreated(holder.getSurface());
        }

    }

    @Override
    public void surfaceChanged(@NonNull SurfaceHolder holder, int format, int width, int height) {
        app.gamenative.ui.screen.wow.WowDiagnostics.surfaceGeometry(getContext(), width, height,
            xServer.screenInfo.width, xServer.screenInfo.height);
        if (renderer instanceof VulkanRenderer vkRenderer) {
            vkRenderer.onSurfaceChanged(width, height);
        }
    }

    @Override
    public void surfaceDestroyed(@NonNull SurfaceHolder holder) {
        if (renderer instanceof VulkanRenderer vkRenderer) {
            vkRenderer.onSurfaceDestroyed();
        }
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
        getHolder().removeCallback(this);
        eventExecutor.close();
        if (renderer instanceof VulkanRenderer vkRenderer) vkRenderer.dispose();
    }

    public void onPause() { if (renderer instanceof VulkanRenderer vk) vk.setForeground(false); }
    public void onResume() { if (renderer instanceof VulkanRenderer vk) vk.setForeground(true); }

    public void setFrameRateLimit(int frameRateLimit) {
        this.frameRateLimit = Math.max(0, frameRateLimit);
        applyMainSurfaceFrameRate();
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
