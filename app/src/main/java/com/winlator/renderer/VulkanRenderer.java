package com.winlator.renderer;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.view.Surface;

import app.gamenative.R;
import com.winlator.widget.FrameRating;
import com.winlator.widget.XServerRendererView;
import com.winlator.widget.XServerView;
import com.winlator.xserver.Bitmask;
import com.winlator.xserver.Cursor;
import com.winlator.xserver.Drawable;
import com.winlator.xserver.Pointer;
import com.winlator.xserver.Window;
import com.winlator.xserver.WindowAttributes;
import com.winlator.xserver.WindowManager;
import com.winlator.xserver.XLock;
import com.winlator.xserver.XServer;

import java.util.ArrayList;

public class VulkanRenderer implements WindowManager.OnWindowModificationListener,
                                       Pointer.OnPointerMotionListener,
                                       XServerRenderer {

    static { System.loadLibrary("vulkan_renderer"); }
    public static final int EFFECT_NONE = 0;
    public static final int EFFECT_FSR = 1;
    public static final int EFFECT_DLS = 2;
    public static final int EFFECT_NATURAL = 5;
    public static final int SCALE_FIT = 0;
    public static final int SCALE_STRETCH = 1;
    public static final int SCALE_FILL = 2;
    public static final int EFFECT_MASK_TOON = 1;
    public static final int EFFECT_MASK_FXAA = 1 << 1;
    public static final int EFFECT_MASK_VIVID = 1 << 2;
    public static final int EFFECT_MASK_CRT = 1 << 3;
    public static final int EFFECT_MASK_NTSC = 1 << 4;

    public final XServerView xServerView;
    private final XServer xServer;
    private volatile long nativeHandle = 0;
    private final Object lock = new Object();
    // Uploads may arrive while already holding a Drawable.renderLock. Keep their
    // lifetime gate separate so they never acquire the scene/cursor monitor.
    private final Object nativeLifetimeLock = new Object();

    public final ViewTransformation viewTransformation = new ViewTransformation();
    private boolean fullscreen = false;
    public int surfaceWidth;
    public int surfaceHeight;
    private String[] unviewableWMClasses = null;
    private String forceFullscreenWMClass = null;
    private boolean cursorVisible = false;
    private volatile boolean nativeMode = false;
    private volatile boolean experimentalScanout;
    private volatile boolean scanoutBlocked = true;
    private volatile String scanoutBlockReason = "ui";
    private volatile boolean foreground = true;
    private volatile Drawable scanoutCandidate;
    private final java.util.concurrent.atomic.AtomicLong scanoutEpoch = new java.util.concurrent.atomic.AtomicLong();
    /** blockReason names the UI elements that keep the compositor in use; empty means none. */
    public void setExperimentalScanout(boolean requested, String blockReason) {
        boolean blocked = blockReason != null && !blockReason.isEmpty();
        experimentalScanout = requested; scanoutBlocked = blocked;
        scanoutBlockReason = blocked ? blockReason : "";
        if (!requested || blocked) setNativeMode(false);
        queueSceneUpdate();
    }
    public void setForeground(boolean value) {
        foreground = value;
        if (!value) setNativeMode(false);
        queueSceneUpdate();
    }
    private String driverPath = null;
    private int outputScalingMode = SCALE_FIT;
    private java.util.concurrent.ExecutorService initExecutor = null;
    private volatile boolean initComplete = false;

    // ---- Present buffer hand-back (experimental switch "presentidle", default off) ----
    private volatile boolean presentIdleTracking = false;
    /**
     * Stall limit of one hold. The render thread hands a buffer back as soon as the GPU is done with it (it polls the fences by
     * itself while holds exist), so a hold normally lasts a frame or two; this only frees a client when the renderer never answers.
     * It is no longer a 250 ms "hand it back anyway": that returned buffers a slow GPU frame or a still-visible picture could be
     * reading.
     */
    static final long HOLD_STALL_LIMIT_MS = 2000;
    private final java.util.concurrent.atomic.AtomicInteger holdStalls = new java.util.concurrent.atomic.AtomicInteger();
    private final ScanoutIdleHand scanoutIdle = new ScanoutIdleHand();
    private final com.winlator.xserver.extensions.PresentIdleHolds idleHolds =
        new com.winlator.xserver.extensions.PresentIdleHolds(HOLD_STALL_LIMIT_MS, this::onHoldStalled);

    private void onHoldStalled() {
        int n = holdStalls.incrementAndGet();
        if (n == 1 || n % 100 == 0) android.util.Log.w("VulkanRenderer", "Present hand-back: a buffer was returned after the "
            + HOLD_STALL_LIMIT_MS + " ms stall limit (count " + n + ")");
    }

    /**
     * On: a Present buffer is handed back to its client only when the frames that may read it are done (see onUpdateWindowContentDirect).
     * Meant to be set before the first frame of a session; switching it OFF while frames are in flight returns the buffers held so far
     * at once, without waiting for the GPU.
     */
    public void setPresentIdleTracking(boolean on) {
        presentIdleTracking = on;
        synchronized (lock) { if (nativeHandle != 0) nativeSetPresentIdle(nativeHandle, on); }
        if (!on) idleHolds.releaseAll();
    }

    /** Called by the native render thread. Only queues the client's Idle message, never blocks. */
    @androidx.annotation.Keep
    private void onPixmapIdle(long token) {
        idleHolds.release(token);
    }
    /** Counts the surfaces: a context whose creation outlives its surface (destroyed meanwhile) is thrown away when it is done. */
    private final java.util.concurrent.atomic.AtomicInteger surfaceGeneration = new java.util.concurrent.atomic.AtomicInteger();
    /** How long the UI thread waits for a creation still running when the surface changes; longer ones are discarded by generation. */
    private static final long SURFACE_INIT_WAIT_MS = 1000;
    private volatile boolean disposed = false;
    private final java.util.concurrent.atomic.AtomicBoolean releaseStarted = new java.util.concurrent.atomic.AtomicBoolean();
    private String driverLibraryName = null;
    private String nativeLibDir = null;
    private Drawable rootCursorDrawable;
    private Cursor lastCursor = null;
    /** Set when a new native context was created: it knows no cursor image yet, so the next pointer event sends the current one. */
    private boolean cursorResend = false;
    private boolean xRenderingPausedForScanout = false;

    private volatile ArrayList<RenderableWindow> renderableWindows = new ArrayList<>();
    private static final java.util.concurrent.atomic.AtomicLong ID_GEN =
        new java.util.concurrent.atomic.AtomicLong(1);
    // Retain identities until their native imports have explicitly retired.
    // A weak key can disappear before we send nativeRemoveWindow on resize.
    private final java.util.IdentityHashMap<Drawable, Long> drawableIds =
        new java.util.IdentityHashMap<>();
    private final java.util.concurrent.atomic.AtomicBoolean scenePending =
        new java.util.concurrent.atomic.AtomicBoolean(false);
    private android.view.SurfaceControl scanoutGameSC;
    private android.view.SurfaceControl scanoutCursorSC;
    private android.view.Surface        scanoutGameSurface;
    private android.view.Surface        scanoutCursorSurface;

    public VulkanRenderer(XServerView xServerView, XServer xServer) {
        this.xServerView = xServerView;
        this.xServer = xServer;
        rootCursorDrawable = createRootCursorDrawable();
        xServer.windowManager.addOnWindowModificationListener(this);
        xServer.pointer.addOnPointerMotionListener(this);
    }

    private Drawable createRootCursorDrawable() {
        try {
            Context context = xServerView.getContext();
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inScaled = false;
            Bitmap bitmap = BitmapFactory.decodeResource(context.getResources(), R.drawable.cursor, options);
            return Drawable.fromBitmap(bitmap);
        } catch (Exception e) { return null; }
    }

    private native long nativeInit(Surface surface, int screenWidth, int screenHeight, String driverPath, String libraryName, String nativeLibDir);
    private native void nativeResize(long handle, int width, int height);
    private native void nativeDestroy(long handle);
    // offX/offY: where the picture's top-left corner sits inside the window (Present xOff/yOff; 0 for a window's own content);
    // winW/winH: the window's size, the picture is cut to it.
    private native void nativeUpdateWindowContent(long handle, long id, java.nio.ByteBuffer pixels,
        short width, short height, short stride, int offX, int offY, int winW, int winH);
    // idleToken: 0, or a hold of the Present buffer (see PresentIdleHolds) that the renderer hands back through onPixmapIdle
    private native void nativeUpdateWindowContentAHB(long handle, long id, long ahbPtr,
        short width, short height, int offX, int offY, int winW, int winH, long idleToken);
    private native void nativeSetPresentIdle(long handle, boolean on);
    private native void nativeSetTransform(long handle, float ox, float oy, float sx, float sy);
    private native void nativeSetPointerPos(long handle, short x, short y);
    private native void nativeSetCursorVisible(long handle, boolean visible);
    private native void nativeUpdateCursorImage(long handle, java.nio.ByteBuffer pixels,
        short width, short height, short hotX, short hotY);
    private native void nativeSetRenderList(long handle, long[] ids, int[] xs, int[] ys, int count);
    private native void nativeRemoveWindow(long handle, long id);

    private native void nativeInitScanout(long handle);
    private native void nativeDetachSurface(long handle);
    private native boolean nativeReattachSurface(long handle, android.view.Surface surface);
    private native void nativeDestroyScanout(long handle);
    private native void nativeScanoutSetBuffer(long handle, long ahbPtr, int x, int y, int w, int h, int fenceFd);
    private native void nativeScanoutSetCursorImage(long handle, java.nio.ByteBuffer pixels, short w, short h, short stride);
    private native void nativeScanoutSetCursorPos(long handle, short x, short y, short hotX, short hotY);
    private native void nativeSetSchedulingHints(long handle, boolean priority, boolean adpf, long targetNs);
    private volatile boolean priorityHint;
    private volatile boolean adpfHint;
    public void setSchedulingHints(boolean priority, boolean adpf) {
        priorityHint = priority; adpfHint = adpf;
        synchronized (lock) { applySchedulingHints(); }
    }
    private void applySchedulingHints() {
        if (nativeHandle == 0) return;
        nativeSetSchedulingHints(nativeHandle, priorityHint, adpfHint,
            1_000_000_000L / (fpsLimit > 0 ? fpsLimit : 60));
        nativeSetFrameInterval(nativeHandle, fpsLimit > 0 ? 1_000_000_000L / fpsLimit : 0);
    }
    private native void nativeSetFrameInterval(long handle, long intervalNs);
    private native long[] nativeTakeDisplayStats(long handle);
    private native long[] nativePhaseStats(long handle);
    private native long[] nativeTakeFrameLog(long handle);
    private native void nativeSetFrameTrace(long handle, boolean enabled);

    private void applyFrameTrace(boolean enabled) {
        synchronized (lock) { if (nativeHandle != 0) nativeSetFrameTrace(nativeHandle, enabled); }
    }

    private static final java.util.concurrent.atomic.AtomicReference<VulkanRenderer> current = new java.util.concurrent.atomic.AtomicReference<>();
    private static final String[] WINDOW_STATES = {"none", "clean", "dirty", "slow", "hold", "settle", "refilled"};
    /** Columns of [takeDisplayStats], for the diagnostics CSV header. */
    public static final String DISPLAY_STATS_HEADER = "disp_timing,disp_shown,disp_dropped,disp_late,disp_max_ms,refresh_hz,disp_latency_ms,"
        + "disp_drains,disp_phase_ms,disp_phase_conc,release_offset_ms,disp_missing,disp_drain_replays,drain_floor_ms,drain_window_ms,drain_state";
    private static final String DISPLAY_STATS_OFF = "off,0,0,0,0,0,0,0,0,0,0,0,0,0,0,none";

    private long[] phaseStats() {
        synchronized (lock) { return nativeHandle != 0 ? nativePhaseStats(nativeHandle) : null; }
    }

    /** What the panel really showed since the previous call, for the diagnostics CSV ([DISPLAY_STATS_HEADER]). */
    public static String takeDisplayStats() {
        VulkanRenderer renderer = current.get();
        long[] v = null;
        if (renderer != null) {
            synchronized (renderer.lock) {
                if (renderer.nativeHandle != 0) v = renderer.nativeTakeDisplayStats(renderer.nativeHandle);
            }
        }
        if (v == null || v.length < 13 || v[0] == 0) return DISPLAY_STATS_OFF;
        long[] phase = renderer.phaseStats();
        return "on," + v[1] + "," + v[2] + "," + v[3] + "," + String.format(java.util.Locale.ROOT, "%.1f", v[4] / 1000.0)
            + "," + (v[5] > 0 ? Math.round(1_000_000.0 / v[5]) : 0)
            + "," + String.format(java.util.Locale.ROOT, "%.1f", v[6] / 1000.0) + "," + v[7]
            + "," + String.format(java.util.Locale.ROOT, "%.1f", phase != null && phase.length >= 4 ? phase[1] / 1e6 : 0.0)
            + "," + (phase != null && phase.length >= 4 ? phase[2] : 0)
            + "," + String.format(java.util.Locale.ROOT, "%.1f", com.winlator.xserver.extensions.PresentPhase.offsetNs() / 1e6)
            + "," + v[8] + "," + v[9]
            + "," + String.format(java.util.Locale.ROOT, "%.1f", v[10] / 1000.0)
            + "," + String.format(java.util.Locale.ROOT, "%.1f", v[11] / 1000.0)
            + "," + (v[12] >= 0 && v[12] < WINDOW_STATES.length ? WINDOW_STATES[(int) v[12]] : "?");
    }

    /** Per game frame since the previous call, 8 values a row (see VulkanRendererContext::takeFrameLog); empty without a renderer. */
    public static long[] takeFrameLog() {
        VulkanRenderer renderer = current.get();
        if (renderer == null) return new long[0];
        synchronized (renderer.lock) {
            long[] rows = renderer.nativeHandle != 0 ? renderer.nativeTakeFrameLog(renderer.nativeHandle) : null;
            return rows != null ? rows : new long[0];
        }
    }
    private native boolean nativeIsScanoutActive(long handle);
    private native boolean nativeIsGameFrameDelivered(long handle);
    private native void nativeSetScanoutWindow(long handle, android.view.Surface game, android.view.Surface cursor);
    private native void nativeScanoutSetDst(long handle, int x, int y, int w, int h);
    private native void nativeSetVerboseLog(long handle, boolean v);
    private native void nativeDumpRendererInfo(long handle);
    private native void nativeSetFilterMode(long handle, int mode);
    private native void nativeSetSwapRB(long handle, boolean enabled);
    private native void nativeSetPresentMode(long handle, int mode);
    private native void nativeSetEffect(long handle, int effectId, float sharpness,
        int effectMask, float brightness, float contrast, float gamma);


    private long did(Drawable d) {
        synchronized (drawableIds) {
            return drawableIds.computeIfAbsent(d, k -> ID_GEN.getAndIncrement());
        }
    }

    public void queueSceneUpdate() {
        if (disposed) return;
        if (scenePending.compareAndSet(false, true)) {
            xServerView.queueEvent(() -> {
                scenePending.set(false);
                updateScene();
            });
        }
    }

    public void onSurfaceCreated(Surface surface) {
        if (disposed) return;
        final int generation = surfaceGeneration.incrementAndGet();
        if (initExecutor != null) {
            initExecutor.shutdownNow();
            try { initExecutor.awaitTermination(SURFACE_INIT_WAIT_MS, java.util.concurrent.TimeUnit.MILLISECONDS); }
            catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
        }
        initExecutor = java.util.concurrent.Executors.newSingleThreadExecutor();
        initExecutor.execute(() -> {
            // 1. A context that already exists is attached to the new surface (short; under the locks as before).
            synchronized (lock) {
                synchronized (nativeLifetimeLock) {
                    // A worker that was slow to start (the UI thread only waits SURFACE_INIT_WAIT_MS for it) must not attach a surface that
                    // was destroyed or replaced meanwhile: its generation is no longer the current one.
                    if (disposed || generation != surfaceGeneration.get()) return;
                    if (nativeHandle != 0) {
                        boolean ok = nativeReattachSurface(nativeHandle, surface);
                        if (!ok) {
                            nativeDestroy(nativeHandle);
                            nativeHandle = 0;
                        } else {
                            initComplete = true;
                            xServerView.queueEvent(this::updateScene);
                            return;
                        }
                    }
                }
            }
            // 2. A new context is created WITHOUT holding either lock: this is the long part, and a surface callback on the UI
            //    thread must never have to wait for it (it used to queue up behind the lock for as long as the driver needed).
            final long created = nativeInit(surface, xServer.screenInfo.width, xServer.screenInfo.height, driverPath, driverLibraryName, nativeLibDir);
            // 3. Published under the locks, only if this surface is still the current one; otherwise it is thrown away.
            synchronized (lock) {
                synchronized (nativeLifetimeLock) {
                    if (disposed || generation != surfaceGeneration.get() || nativeHandle != 0) {
                        if (created != 0) nativeDestroy(created);
                        return;
                    }
                    nativeHandle = created;
                    if (nativeHandle != 0) {
                        current.set(this);
                        com.winlator.xserver.extensions.PresentPhase.setSource(this::phaseStats);
                        cursorResend = true;   // sent from onPointerMove, under `lock` only (no render-lock inversion here)
                        if (presentIdleTracking) nativeSetPresentIdle(nativeHandle, true);
                        nativeSetPresentMode(nativeHandle, pendingPresentMode);
                        nativeSetFifoDrain(nativeHandle, pendingFifoDrain);
                        // Listen first, then apply the current value: a diagnostics session switching the trace on in between
                        // reaches the listener (which waits for `lock`) instead of nobody (AUDIT13-APP-02).
                        FrameTrace.setListener(this::applyFrameTrace);
                        nativeSetFrameTrace(nativeHandle, FrameTrace.enabled());
                        nativeSetFilterMode(nativeHandle, pendingFilterMode);
                        nativeSetSwapRB(nativeHandle, false);
                        nativeSetEffect(nativeHandle, pendingEffectId, pendingSharpness,
                            pendingEffectMask, pendingBrightness, pendingContrast, pendingGamma);
                        applySchedulingHints();
                        updateTransform();
                        nativeSetCursorVisible(nativeHandle, cursorVisible);
                        if (nativeMode && !effectsRequireCompositor) {
                            xServerView.post(() -> {
                                if (disposed || !nativeMode || !foreground) return;
                                releaseScanoutSurfaces();
                                if (android.os.Build.VERSION.SDK_INT >= 29) {
                                    try {
                                        android.view.SurfaceControl xsc = xServerView.getSurfaceControl();
                                        scanoutGameSC = new android.view.SurfaceControl.Builder()
                                            .setParent(xsc).setName("winlator_game").setOpaque(true).build();
                                        scanoutGameSurface = new android.view.Surface(scanoutGameSC);
                                        scanoutCursorSC = new android.view.SurfaceControl.Builder()
                                            .setParent(xsc).setName("winlator_cursor").setFormat(1).build();
                                        scanoutCursorSurface = new android.view.Surface(scanoutCursorSC);
                                        try (android.view.SurfaceControl.Transaction setup = new android.view.SurfaceControl.Transaction()) {
                                            setup.setLayer(scanoutGameSC,   1)
                                                .setLayer(scanoutCursorSC, 2)
                                                .setVisibility(scanoutGameSC,   true)
                                                .setVisibility(scanoutCursorSC, true)
                                                .apply();
                                        }
                                        applyScanoutFrameRateHint();
                                        applyScanoutSwapTransform();
                                        synchronized (lock) {
                                            if (nativeHandle != 0) {
                                                nativeSetScanoutWindow(nativeHandle, scanoutGameSurface, scanoutCursorSurface);
                                                updateTransform();
                                            }
                                        }
                                    } catch (Exception e) {
                                        synchronized (lock) {
                                            if (nativeHandle != 0) nativeInitScanout(nativeHandle);
                                        }
                                    }
                                } else {
                                    synchronized (lock) { if (nativeHandle != 0) nativeInitScanout(nativeHandle); }
                                }
                            });
                        }
                    }
                }
            }
            synchronized (lock) {
                if (nativeHandle != 0) {
                    nativeSetVerboseLog(nativeHandle, true);
                    nativeDumpRendererInfo(nativeHandle);
                }
            }
            if (!disposed && generation == surfaceGeneration.get()) {
                initComplete = true;
                xServerView.queueEvent(this::updateScene);
            }
        });
    }

    public void onSurfaceChanged(int width, int height) {
        if (disposed) return;
        surfaceWidth = width; surfaceHeight = height;
        viewTransformation.update(width, height, xServer.screenInfo.width, xServer.screenInfo.height);
        synchronized (lock) {
            if (nativeHandle != 0) {
                nativeResize(nativeHandle, width, height);
                updateTransform();
            }
        }
    }

    public void onSurfaceDestroyed() {
        scanoutEpoch.incrementAndGet();
        // A context still being created for this surface is thrown away by its worker when it is done (see onSurfaceCreated).
        surfaceGeneration.incrementAndGet();
        if (scanoutCandidate != null) scanoutCandidate.setDirectScanout(false);
        scanoutCandidate = null;
        initComplete = false;
        if (initExecutor != null) {
            initExecutor.shutdownNow();
            try { initExecutor.awaitTermination(SURFACE_INIT_WAIT_MS, java.util.concurrent.TimeUnit.MILLISECONDS); }
            catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
            initExecutor = null;
        }
        synchronized (lock) {
            synchronized (nativeLifetimeLock) {
                if (nativeHandle != 0) {
                    if (nativeMode) {
                        nativeDestroyScanout(nativeHandle);
                        nativeDestroy(nativeHandle);
                        nativeHandle = 0;
                    } else {
                        nativeDetachSurface(nativeHandle);
                    }
                }
            }
        }
        idleHolds.releaseAll();   // nothing is drawn any more: no client has to wait for a frame that will not come
        if (nativeMode) xServerView.post(this::releaseScanoutSurfaces);
    }

    /** Permanent release, distinct from a temporary surface detach/reattach. */
    public void dispose() {
        if (!releaseStarted.compareAndSet(false, true)) return;
        disposed = true;
        if (current.compareAndSet(this, null)) {
            com.winlator.xserver.extensions.PresentPhase.setSource(null);
            FrameTrace.setListener(null);
        }
        initComplete = false;
        scenePending.set(false);
        xServer.windowManager.removeOnWindowModificationListener(this);
        xServer.pointer.removeOnPointerMotionListener(this);
        java.util.concurrent.ExecutorService initializing = initExecutor;
        initExecutor = null;
        if (initializing != null) initializing.shutdownNow();
        // Uploads retain the native lifetime gate until their JNI call completes.
        // An in-flight initialization finishes before its context is destroyed.
        synchronized (lock) {
            synchronized (nativeLifetimeLock) {
                long handle = nativeHandle;
                nativeHandle = 0;
                if (handle != 0) {
                    nativeDestroyScanout(handle);
                    nativeDestroy(handle);
                }
                renderableWindows = new ArrayList<>();
                synchronized (drawableIds) { drawableIds.clear(); }
                hudRef = null;
            }
        }
        idleHolds.releaseAll();
        xServerView.post(this::releaseScanoutSurfaces);
    }

    private void releaseScanoutSurfaces() {
        if (scanoutGameSurface   != null) { scanoutGameSurface.release();   scanoutGameSurface   = null; }
        if (scanoutCursorSurface != null) { scanoutCursorSurface.release(); scanoutCursorSurface = null; }
        if (scanoutGameSC        != null) { scanoutGameSC.release();        scanoutGameSC        = null; }
        if (scanoutCursorSC      != null) { scanoutCursorSC.release();      scanoutCursorSC      = null; }
    }

    private void applyScanoutSwapTransform() {
        if (scanoutGameSC == null || android.os.Build.VERSION.SDK_INT < 29) return;
        try {
            float[] matrix = new float[]{1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f};
            float[] translation = new float[]{0f, 0f, 0f};
            java.lang.reflect.Method setColorTransform = android.view.SurfaceControl.Transaction.class.getMethod(
                "setColorTransform",
                android.view.SurfaceControl.class,
                float[].class,
                float[].class
            );
            // Closed on every path, including the failure of the reflective call.
            try (android.view.SurfaceControl.Transaction txn = new android.view.SurfaceControl.Transaction()) {
                setColorTransform.invoke(txn, scanoutGameSC, matrix, translation);
                txn.apply();
            }
        } catch (Exception e) {
        }
    }

    private void updateTransform() {
        if (nativeHandle == 0) return;
        if (viewTransformation.isNativeAspectResolution()) {
            nativeSetTransform(nativeHandle, viewTransformation.sceneOffsetX, viewTransformation.sceneOffsetY,
                viewTransformation.sceneScaleX, viewTransformation.sceneScaleY);
            nativeScanoutSetDst(nativeHandle, viewTransformation.viewOffsetX, viewTransformation.viewOffsetY,
                viewTransformation.viewWidth, viewTransformation.viewHeight);
        } else if (fullscreen || outputScalingMode == SCALE_STRETCH) {
            nativeSetTransform(nativeHandle, 0, 0, 1.0f, 1.0f);
            nativeScanoutSetDst(nativeHandle,
                0,
                0,
                surfaceWidth,
                surfaceHeight);
        } else if (outputScalingMode == SCALE_FILL && surfaceWidth > 0 && surfaceHeight > 0) {
            float scale = Math.max(
                (float) surfaceWidth / (float) xServer.screenInfo.width,
                (float) surfaceHeight / (float) xServer.screenInfo.height
            );
            float sceneScaleX = (xServer.screenInfo.width * scale) / surfaceWidth;
            float sceneScaleY = (xServer.screenInfo.height * scale) / surfaceHeight;
            float sceneOffsetX = (xServer.screenInfo.width - xServer.screenInfo.width * sceneScaleX) * 0.5f;
            float sceneOffsetY = (xServer.screenInfo.height - xServer.screenInfo.height * sceneScaleY) * 0.5f;
            nativeSetTransform(nativeHandle, sceneOffsetX, sceneOffsetY, sceneScaleX, sceneScaleY);

            int dstW = Math.round(xServer.screenInfo.width * scale);
            int dstH = Math.round(xServer.screenInfo.height * scale);
            nativeScanoutSetDst(nativeHandle,
                Math.round((surfaceWidth - dstW) * 0.5f),
                Math.round((surfaceHeight - dstH) * 0.5f),
                dstW,
                dstH);
        } else {
            nativeSetTransform(nativeHandle,
                viewTransformation.sceneOffsetX,
                viewTransformation.sceneOffsetY,
                viewTransformation.sceneScaleX,
                viewTransformation.sceneScaleY);
            nativeScanoutSetDst(nativeHandle,
                viewTransformation.viewOffsetX,
                viewTransformation.viewOffsetY,
                viewTransformation.viewWidth,
                viewTransformation.viewHeight);
        }
    }

    public void updateScene() {
        if (disposed) return;
        ArrayList<RenderableWindow> newList = new ArrayList<>();
        ArrayList<Long> retiredIds = new ArrayList<>();
        try (XLock xl = xServer.lock(XServer.Lockable.WINDOW_MANAGER, XServer.Lockable.DRAWABLE_MANAGER)) {
            collectWindows(newList, xServer.windowManager.rootWindow,
                xServer.windowManager.rootWindow.getX(),
                xServer.windowManager.rootWindow.getY());
            collectStaleDrawableIds(retiredIds);
        }
        synchronized (lock) {
            synchronized (nativeLifetimeLock) {
                if (nativeHandle != 0)
                    for (long id : retiredIds) nativeRemoveWindow(nativeHandle, id);
            }
            renderableWindows = newList;
            pushRenderList(newList);
        }
    }

    // Called while DRAWABLE_MANAGER is locked. A window keeps its X id when
    // resized, but WindowManager replaces its Drawable object and storage.
    private void collectStaleDrawableIds(ArrayList<Long> retiredIds) {
        synchronized (drawableIds) {
            var iterator = drawableIds.entrySet().iterator();
            while (iterator.hasNext()) {
                var entry = iterator.next();
                Drawable drawable = entry.getKey();
                if (drawable == null || xServer.drawableManager.getDrawable(drawable.id) != drawable) {
                    retiredIds.add(entry.getValue());
                    iterator.remove();
                }
            }
        }
    }

    private void collectWindows(ArrayList<RenderableWindow> list, Window window, int x, int y) {
        if (!window.attributes.isMapped()) return;
        if (window != xServer.windowManager.rootWindow) {
            boolean viewable = true;
            if (unviewableWMClasses != null) {
                String wc = window.getClassName();
                for (String cls : unviewableWMClasses) {
                    if (wc.contains(cls)) {
                        if (window.attributes.isEnabled()) window.disableAllDescendants();
                        viewable = false; break;
                    }
                }
            }
            if (viewable && window.getContent() != null)
                list.add(new RenderableWindow(window.getContent(), x, y));
        }
        for (Window child : window.getChildren())
            collectWindows(list, child, child.getX() + x, child.getY() + y);
    }

    private void pushRenderList(ArrayList<RenderableWindow> list) {
        if (nativeHandle == 0) return;
        int screenW = xServer.screenInfo.width, screenH = xServer.screenInfo.height;

        int start = 0;
        for (int i = list.size() - 1; i >= 0; i--) {
            RenderableWindow rw = list.get(i);
            if (rw.content != null && ScreenCoverage.covers(rw.rootX, rw.rootY, rw.content.width, rw.content.height, screenW, screenH)) {
                start = i; break;
            }
        }

        Drawable candidate = null;
        String reason = experimentalScanout ? "windows" : "off";
        if (list.size() - start == 1) {
            RenderableWindow rw = list.get(start);
            boolean ahb = rw.content.getTexture() instanceof GPUImage
                && ((GPUImage)rw.content.getTexture()).getHardwareBufferPtr() != 0;
            reason = ScanoutEligibility.reason(experimentalScanout, scanoutBlocked,
                    effectsRequireCompositor, hudRef != null && hudRef.getVisibility() == android.view.View.VISIBLE, foreground,
                    1, rw.rootX, rw.rootY, rw.content.width, rw.content.height,
                    screenW, screenH, ahb);
            if (reason == null) candidate = rw.content;
        }
        ScanoutStats.setState(reason == null ? "eligible" : "ui".equals(reason) ? "ui:" + scanoutBlockReason : reason);
        if (scanoutCandidate != candidate) {
            setNativeMode(false);
            if (scanoutCandidate != null) scanoutCandidate.setDirectScanout(false);
            scanoutCandidate = candidate;
            if (candidate != null) candidate.setDirectScanout(true);
        }
        setNativeMode(candidate != null);
        boolean delivered;
        synchronized (nativeLifetimeLock) {
            delivered = nativeMode && nativeHandle != 0 && nativeIsGameFrameDelivered(nativeHandle);
        }
        if (nativeMode && delivered) {
            ArrayList<RenderableWindow> ns = new ArrayList<>();
            for (int i = start; i < list.size(); i++) {
                RenderableWindow rw = list.get(i);
                if (rw.content != null && (effectsRequireCompositor || !rw.content.isDirectScanout())) ns.add(rw);
            }
            int n = ns.size();
            long[] ids = new long[n]; int[] xs = new int[n]; int[] ys = new int[n];
            for (int i = 0; i < n; i++) {
                ids[i] = did(ns.get(i).content); xs[i] = ns.get(i).rootX; ys[i] = ns.get(i).rootY;
            }
            nativeSetRenderList(nativeHandle, ids, xs, ys, n);
            return;
        }
        if (isFullscreen()) {
            int n = list.size() - start;
            if (n <= 0) { nativeSetRenderList(nativeHandle, new long[0], new int[0], new int[0], 0); return; }
            long[] ids = new long[n]; int[] xs = new int[n]; int[] ys = new int[n];
            for (int i = 0; i < n; i++) {
                RenderableWindow rw = list.get(start + i);
                ids[i] = did(rw.content); xs[i] = rw.rootX; ys[i] = rw.rootY;
            }
            nativeSetRenderList(nativeHandle, ids, xs, ys, n);
            return;
        }

        int n = list.size() - start;
        long[] ids = new long[n]; int[] xs = new int[n]; int[] ys = new int[n];
        for (int i = 0; i < n; i++) {
            RenderableWindow rw = list.get(start + i);
            ids[i] = did(rw.content); xs[i] = rw.rootX; ys[i] = rw.rootY;
        }
        nativeSetRenderList(nativeHandle, ids, xs, ys, n);
    }

    private void sendCursorToNative(Cursor cursor) {
        if (nativeHandle == 0) return;
        Drawable cd; short hotX = 0, hotY = 0;
        boolean effVis = cursorVisible;
        if (cursor != null) {
            if (!cursor.isVisible()) effVis = false;
            cd = cursor.cursorImage; hotX = (short)cursor.hotSpotX; hotY = (short)cursor.hotSpotY;
        } else { cd = rootCursorDrawable; }
        nativeSetCursorVisible(nativeHandle, effVis);
        if (effVis && cd != null && cd.getBuffer() != null) {
            synchronized (cd.renderLock) {
                nativeUpdateCursorImage(nativeHandle, cd.getBuffer(), cd.width, cd.height, hotX, hotY);
                if (nativeMode) {
                    java.nio.ByteBuffer buf = cd.getBuffer();
                    short stride = (short)(buf.capacity() / (cd.height * 4));
                    nativeScanoutSetCursorImage(nativeHandle, buf, cd.width, cd.height, stride);
                    // A new image brings its own hotspot and size: place it now, not at the next pointer move (a window's cursor
                    // changing under a still pointer kept the old hotspot and geometry until then).
                    nativeScanoutSetCursorPos(nativeHandle, xServer.pointer.getX(), xServer.pointer.getY(), hotX, hotY);
                }
            }
        }
    }

    public void onUpdateWindowContentDirect(Window window, Drawable pixmap, short xOff, short yOff) {
        onUpdateWindowContentDirect(window, pixmap, xOff, yOff, null);
    }

    /**
     * @param idleRelease what to do when the picture's buffer may be reused by its client. With the experimental hand-back on and the
     * picture going through the compositor, the renderer keeps it and runs it later (from the render thread - it must only queue
     * work) and returns true; otherwise it returns false and the caller releases the buffer itself, as before.
     */
    public boolean onUpdateWindowContentDirect(Window window, Drawable pixmap, short xOff, short yOff, Runnable idleRelease) {
        if (disposed) return false;
        // Only the tracked application window (or a window inside it: the Vulkan surface is a child) counts for the HUD; see HudWindowFilter.
        // Scanout frames count as well: the HUD stays on screen over scanout and must not read 0 FPS there.
        if (hudRef != null) {
            final int tracked = hudWindowId;
            boolean inside = false;
            if (tracked != -1) {
                int depth = 0;
                for (Window w = window; w != null && depth < 16; w = w.getParent(), depth++) {
                    if (w.id == tracked) { inside = true; break; }
                }
            }
            if (hudFilter.shouldCount(tracked != -1, inside, android.os.SystemClock.elapsedRealtimeNanos())) hudRef.update();
        }
        if (nativeHandle == 0 || pixmap == null) return false;
        Drawable targetDrawable = window.getContent();
        long targetId = did(targetDrawable);
        int rx = window.getRootX() + xOff;
        int ry = window.getRootY() + yOff;
        synchronized (pixmap.renderLock) {
            synchronized (nativeLifetimeLock) {
                if (disposed || nativeHandle == 0) return false;
                Texture texture = pixmap.getTexture();
                if (texture instanceof GPUImage) {
                    GPUImage g = (GPUImage) texture;
                    long ahbPtr = g.getHardwareBufferPtr();
                    if (ahbPtr != 0) {
                        if (nativeMode && targetDrawable == scanoutCandidate && xOff == 0 && yOff == 0
                            && pixmap.width == xServer.screenInfo.width && pixmap.height == xServer.screenInfo.height
                            && nativeIsScanoutActive(nativeHandle)) {
                            boolean wasDelivered = nativeIsGameFrameDelivered(nativeHandle);
                            boolean cpuMapped = g.getVirtualData() != null;
                            int fenceFd = g.unlock();
                            // 1.2.1: this buffer stays on screen until the next one replaces it; only then may the game draw into it
                            // again (it used to be told at once, see ScanoutIdleHand).
                            long idleToken = (idleRelease != null && presentIdleTracking && initComplete) ? idleHolds.hold(idleRelease) : 0;
                            nativeScanoutSetBuffer(nativeHandle, ahbPtr,
                                rx, ry, pixmap.width, pixmap.height, fenceFd);
                            if (cpuMapped) g.lock();
                            long replaced = scanoutIdle.replace(idleToken);
                            if (replaced != 0) idleHolds.release(replaced);
                            ScanoutStats.scanoutFrame();
                            // The first direct buffer changes what the compositor draws.
                            if (!wasDelivered && nativeIsGameFrameDelivered(nativeHandle)) queueSceneUpdate();
                            if (idleToken != 0) return true;
                        } else {
                            // The game window went back to the compositor (or scanout is off): the last direct buffer is no longer
                            // shown and is free. Other windows presenting meanwhile do not touch it.
                            if (!nativeMode || targetDrawable == scanoutCandidate) {
                                long scanoutHeld = scanoutIdle.take();
                                if (scanoutHeld != 0) idleHolds.release(scanoutHeld);
                            }
                            // The hold is registered first: the render thread may hand the buffer back at any moment after the call.
                            // Only while a surface is attached: without one (the app in the background) no frame is drawn, so nothing
                            // would ever hand the buffer back and each one waited out the 2 s stall limit - the game ran at 1-2 FPS.
                            long idleToken = (idleRelease != null && presentIdleTracking && initComplete) ? idleHolds.hold(idleRelease) : 0;
                            nativeUpdateWindowContentAHB(nativeHandle, targetId, ahbPtr,
                                pixmap.width, pixmap.height, xOff, yOff, targetDrawable.width, targetDrawable.height, idleToken);
                            if (pixmap.width == xServer.screenInfo.width && pixmap.height == xServer.screenInfo.height)
                                ScanoutStats.compositorFrame();
                            return idleToken != 0;
                        }
                        return false;
                    }
                    java.nio.ByteBuffer vd = g.getVirtualData();
                    if (vd != null) {
                        short s = g.getStride() > 0 ? g.getStride() : pixmap.width;
                        nativeUpdateWindowContent(nativeHandle, targetId, vd,
                            pixmap.width, pixmap.height, s, xOff, yOff, targetDrawable.width, targetDrawable.height);
                        return false;
                    }
                }
                java.nio.ByteBuffer buf = pixmap.getBuffer();
                if (buf == null) return false;
                short stride = (short)(buf.capacity() / (pixmap.height * 4));
                nativeUpdateWindowContent(nativeHandle, targetId, buf,
                    pixmap.width, pixmap.height, stride, xOff, yOff, targetDrawable.width, targetDrawable.height);
            }
        }
        return false;
    }

    @Override
    public void onUpdateWindowContent(Window window) {
        if (disposed) return;
        // No HUD/FPS count here: the same content change also reaches XServerScreen's window listener, which counts the tracked
        // application window once. Counting in both made a CPU-drawn window show twice its real frame rate. (The direct Present
        // path never reaches that listener, so onUpdateWindowContentDirect keeps its own count.)

        Drawable drawable = window.getContent();
        if (drawable == null || !window.attributes.isMapped()) return;
        if (unviewableWMClasses != null) {
            String wc = window.getClassName();
            for (String cls : unviewableWMClasses) if (wc.contains(cls)) return;
        }
        int rx = window.getRootX();
        int ry = window.getRootY();
        long drawableId = did(drawable);

        synchronized (drawable.renderLock) {
            synchronized (nativeLifetimeLock) {
                final long handle = nativeHandle;
                if (disposed || handle == 0) return;
                if (drawable.getTexture() instanceof GPUImage) {
                    GPUImage g = (GPUImage) drawable.getTexture();
                    long ahbPtr = g.getHardwareBufferPtr();
                    if (ahbPtr != 0) {
                        boolean scanoutNow = nativeMode && nativeIsScanoutActive(handle);
                        if (nativeMode && drawable.isDirectScanout() && scanoutNow) {
                            boolean wasDelivered = nativeIsGameFrameDelivered(handle);
                            boolean cpuMapped = g.getVirtualData() != null;
                            int fenceFd = g.unlock();
                            nativeScanoutSetBuffer(handle, ahbPtr,
                                rx, ry, drawable.width, drawable.height, fenceFd);
                            if (cpuMapped) g.lock();
                            boolean delivered = nativeIsGameFrameDelivered(handle);
                            if (!xRenderingPausedForScanout && !wasDelivered && delivered) {
                                xServer.setRenderingEnabled(false);
                                xRenderingPausedForScanout = true;
                            }
                        } else {
                            nativeUpdateWindowContentAHB(handle, drawableId, ahbPtr,
                                drawable.width, drawable.height, 0, 0, drawable.width, drawable.height, 0L);
                        }
                        return;
                    }
                    java.nio.ByteBuffer vd = g.getVirtualData();
                    if (vd != null) {
                        short s = g.getStride() > 0 ? g.getStride() : drawable.width;
                        nativeUpdateWindowContent(handle, drawableId, vd,
                            drawable.width, drawable.height, s, 0, 0, drawable.width, drawable.height);
                        return;
                    }
                }
                java.nio.ByteBuffer buf = drawable.getBuffer();
                if (buf == null) return;
                short stride = (short)(buf.capacity() / (drawable.height * 4));
                nativeUpdateWindowContent(handle, drawableId, buf,
                    drawable.width, drawable.height, stride, 0, 0, drawable.width, drawable.height);
            }
        }
    }

    /** A position the game set itself (WinHandler feedback): only the drawn cursor moves; no scene rebuild, no X locks. */
    public void moveCursorTo(short x, short y) {
        if (disposed) return;
        synchronized (lock) {
            if (nativeHandle == 0) return;
            nativeSetPointerPos(nativeHandle, x, y);
            if (nativeMode) {
                Cursor cursor = lastCursor;
                nativeScanoutSetCursorPos(nativeHandle, x, y,
                    cursor != null ? (short)cursor.hotSpotX : 0, cursor != null ? (short)cursor.hotSpotY : 0);
            }
        }
    }

    @Override
    public void onPointerMove(short x, short y) {
        if (disposed) return;
        synchronized (lock) {
            if (nativeHandle == 0) return;
            nativeSetPointerPos(nativeHandle, x, y);
            Window pw = xServer.inputDeviceManager.getPointWindow();
            Cursor cursor = pw != null ? pw.attributes.getCursor() : null;
            if (cursor != lastCursor || cursorResend) { lastCursor = cursor; cursorResend = false; sendCursorToNative(cursor); }
            if (nativeMode) {
                short hotX = 0, hotY = 0;
                if (cursor != null) { hotX = (short)cursor.hotSpotX; hotY = (short)cursor.hotSpotY; }
                nativeScanoutSetCursorPos(nativeHandle, x, y, hotX, hotY);
            }
        }
    }

    @Override
    public void onDestroyWindow(Window window) {
        if (disposed) return;
        Drawable content = window.getContent();
        if (content == null) { queueSceneUpdate(); return; }
        final long id = did(content);
        xServerView.queueEvent(() -> {
            synchronized (lock) { if (nativeHandle != 0) nativeRemoveWindow(nativeHandle, id); }
            queueSceneUpdate();
        });
    }

    @Override public void onMapWindow(Window window) {
        queueSceneUpdate();
    }

    @Override
    public void onUnmapWindow(Window window) {
        if (disposed) return;
        // Unmapping hides the window; its last content remains valid for remap.
        // Destroy/Drawable replacement own native resource retirement.
        queueSceneUpdate();
    }

    @Override public void onChangeWindowZOrder(Window window) {
        queueSceneUpdate();
    }

    @Override
    public void onUpdateWindowGeometry(Window window, boolean resized) {
        queueSceneUpdate();
    }

    @Override
    public void onUpdateWindowAttributes(Window window, Bitmask mask) {
        if (mask.isSet(WindowAttributes.FLAG_CURSOR)) {
            synchronized (lock) {
                Window pw = xServer.inputDeviceManager.getPointWindow();
                if (pw == window) { lastCursor = window.attributes.getCursor(); sendCursorToNative(lastCursor); }
            }
        }
    }

    public void setCursorVisible(boolean visible) {
        cursorVisible = visible;
        synchronized (lock) {
            if (nativeHandle != 0) { nativeSetCursorVisible(nativeHandle, visible); if (visible) sendCursorToNative(lastCursor); }
        }
    }

    public void setNativeMode(boolean mode) {
        if (this.nativeMode == mode) return;
        this.nativeMode = mode;
        scanoutEpoch.incrementAndGet();
        xRenderingPausedForScanout = false;
        if (mode) {
            // Only stand up the zero-copy scanout path when no compositor-only
            // effect is active; otherwise keep X rendering through the compositor.
            if (!effectsRequireCompositor) establishScanout();
            else xServer.setRenderingEnabled(true);
        } else {
            tearDownScanout();
        }
        xServerView.queueEvent(this::updateScene);

    }

    // Stands up the SurfaceControl layers and hands them to native for the
    // zero-copy scanout fast-path. Safe to call only when nativeMode is on.
    private void establishScanout() {
        xRenderingPausedForScanout = false;
        xServer.setRenderingEnabled(true);
        final long epoch = scanoutEpoch.get();
        xServerView.post(() -> {
            if (disposed || !nativeMode || scanoutEpoch.get() != epoch || !foreground) return;
            if (android.os.Build.VERSION.SDK_INT >= 29) {
                try {
                    releaseScanoutSurfaces();
                    android.view.SurfaceControl xsc = xServerView.getSurfaceControl();
                    scanoutGameSC = new android.view.SurfaceControl.Builder()
                        .setParent(xsc).setName("winlator_game").setOpaque(true).build();
                    scanoutGameSurface = new android.view.Surface(scanoutGameSC);
                    scanoutCursorSC = new android.view.SurfaceControl.Builder()
                        .setParent(xsc).setName("winlator_cursor").setFormat(1).build();
                    scanoutCursorSurface = new android.view.Surface(scanoutCursorSC);
                    try (android.view.SurfaceControl.Transaction scTxn = new android.view.SurfaceControl.Transaction()) {
                        scTxn.setLayer(scanoutGameSC,   1)
                            .setLayer(scanoutCursorSC, 2)
                            .setVisibility(scanoutGameSC,   true)
                            .setVisibility(scanoutCursorSC, true);
                        scTxn.apply();
                    }
                    applyScanoutFrameRateHint();
                    applyScanoutSwapTransform();
                    synchronized (lock) {
                        if (nativeHandle != 0) {
                            nativeSetScanoutWindow(nativeHandle,
                                scanoutGameSurface, scanoutCursorSurface);
                            updateTransform();
                        }
                    }
                } catch (Exception e) {
                    synchronized (lock) {
                        if (nativeHandle != 0) nativeInitScanout(nativeHandle);
                    }
                }
            } else {
                synchronized (lock) { if (nativeHandle != 0) nativeInitScanout(nativeHandle); }
            }
        });
    }

    // Tears down the scanout path and resumes compositor (X) rendering so that
    // window.frag-based effects and scaling take effect again.
    private void tearDownScanout() {
        xRenderingPausedForScanout = false;
        long scanoutHeld = scanoutIdle.take();
        if (scanoutHeld != 0) idleHolds.release(scanoutHeld);
        synchronized (lock) {
            synchronized (nativeLifetimeLock) {
                if (nativeHandle != 0) nativeDestroyScanout(nativeHandle);
            }
        }
        final long epoch = scanoutEpoch.get();
        xServerView.post(() -> {
            if (scanoutEpoch.get() != epoch || nativeMode) return;
            xServer.setRenderingEnabled(true);
            releaseScanoutSurfaces();
        });
    }

    /** Pointer inverse of the current output mode, see InputTransform. */
    public float[] getInputTransform(int outerW, int outerH, int innerW, int innerH) {
        int mode = fullscreen ? InputTransform.STRETCH
            : outputScalingMode == SCALE_STRETCH ? InputTransform.STRETCH
            : outputScalingMode == SCALE_FILL ? InputTransform.FILL : InputTransform.FIT;
        return InputTransform.inverse(mode, outerW, outerH, innerW, innerH);
    }

    private volatile Runnable outputMappingListener = null;

    /** Called (on the UI thread) after the output mode changed, so the touch view can recompute its pointer mapping. */
    public void setOutputMappingListener(Runnable listener) { outputMappingListener = listener; }

    /** Gamescope owns the picture; this changes only the Android touch transform. */
    public void setGamescopeInputScaling(int mode) {
        if (outputScalingMode == mode) return;
        outputScalingMode = mode;
        Runnable listener = outputMappingListener;
        if (listener != null) listener.run();
    }

    public void setFilterMode(int mode) {
        // The same value again (every colour/gamma slider step calls this) changes nothing: no JNI call, no native wake-up and
        // no buffer-size post. A fresh native context gets the pending value in onSurfaceCreated, and setEffect posts the
        // buffer-size update itself.
        if (mode == pendingFilterMode) return;
        pendingFilterMode = mode;
        synchronized (lock) { if (nativeHandle != 0) nativeSetFilterMode(nativeHandle, mode); }
        xServerView.post(xServerView::updateBufferSize);
    }

    public void setEffect(int effectId, float sharpness, int scalingMode,
                          int effectMask, float brightness, float contrast, float gamma) {
        pendingEffectId = Math.max(EFFECT_NONE, Math.min(EFFECT_NATURAL, effectId));
        pendingSharpness = Math.max(0.0f, Math.min(1.0f, sharpness));
        pendingEffectMask = Math.max(0, effectMask);
        pendingBrightness = Math.max(-1.0f, Math.min(1.0f, brightness));
        pendingContrast = Math.max(-1.0f, Math.min(1.0f, contrast));
        pendingGamma = Math.max(0.1f, Math.min(4.0f, gamma));
        outputScalingMode = Math.max(SCALE_FIT, Math.min(SCALE_FILL, scalingMode));
        boolean wasRequireCompositor = effectsRequireCompositor;
        effectsRequireCompositor = computeEffectsRequireCompositor();
        synchronized (lock) {
            if (nativeHandle != 0) {
                nativeSetEffect(nativeHandle, pendingEffectId, pendingSharpness,
                    pendingEffectMask, pendingBrightness, pendingContrast, pendingGamma);
                updateTransform();
            }
        }
        // If the effect state crossed the compositor/scanout boundary, switch
        // presentation paths so shader work actually reaches the screen.
        if (nativeMode && wasRequireCompositor != effectsRequireCompositor) {
            if (effectsRequireCompositor) tearDownScanout();
            else establishScanout();
        }
        xServerView.post(xServerView::updateBufferSize);
        Runnable mapping = outputMappingListener;
        if (mapping != null) xServerView.post(mapping);
    }

    /** Upscalers, nearest filtering and effect masks need the full-size swapchain. */
    public boolean needsFullSizeOutput() {
        return pendingEffectId != EFFECT_NONE || pendingEffectMask != 0 || pendingFilterMode != 0;
    }

    // Returns true if any active effect, filter, or color adjustment needs the
    // compositor (window.frag). Scanout bypasses the shader, so these can only
    // take visible effect when content is routed through the textured-quad path.
    private boolean computeEffectsRequireCompositor() {
        return pendingEffectId != EFFECT_NONE
            || pendingEffectMask != 0
            || pendingBrightness != 0.0f
            || pendingContrast != 0.0f
            || Math.abs(pendingGamma - 1.0f) > 1e-3f
            || pendingFilterMode != 0;
    }


    public void setVkPresentMode(int mode) {
        pendingPresentMode = mode;
        synchronized (lock) { if (nativeHandle != 0) nativeSetPresentMode(nativeHandle, mode); }
    }

    private volatile int pendingFifoDrain = 0;
    private native void nativeSetFifoDrain(long handle, int mode);
    /** FIFO: 0 keeps the queue as it fills; 1 takes queued frames out once after a hitch and stops; 2 also tries one step lower. */
    public void setFifoLatencyDrain(int mode) {
        pendingFifoDrain = mode;
        synchronized (lock) { if (nativeHandle != 0) nativeSetFifoDrain(nativeHandle, mode); }
    }

    private FrameRating hudRef = null;
    /** The application window the HUD follows (XServerScreen keeps it current); -1 = none yet. */
    private volatile int hudWindowId = -1;
    private final HudWindowFilter hudFilter = new HudWindowFilter();
    public void setHudWindowId(int windowId) { hudWindowId = windowId; hudFilter.reset(); }

    @Override
    public void setFrameRating(FrameRating fr) {
        hudRef = fr;
    }

    @Override
    public void setForceFullscreenWMClass(String wmClass) { this.forceFullscreenWMClass = wmClass; }

    @Override
    public void setOnFrameRenderedListener(Runnable r) { /* no-op: Vulkan compositor does not expose per-frame callbacks */ }

    @Override
    public XServerRendererView getRendererView() { return xServerView; }

    @Override
    public boolean isFullscreen() { return fullscreen && !viewTransformation.isNativeAspectResolution(); }
    public void setUnviewableWMClasses(String... classes) { this.unviewableWMClasses = classes; }
    private int fpsLimit = 0;
    private int     pendingPresentMode    = 2;
    private int     pendingFilterMode     = 0;
    private int     pendingEffectId       = EFFECT_NONE;
    private float   pendingSharpness      = 1.0f;
    private int     pendingEffectMask     = 0;
    private float   pendingBrightness     = 0.0f;
    private float   pendingContrast       = 0.0f;
    private float   pendingGamma          = 1.0f;
    // When any screen effect / filter / color adjustment is active we must route
    // fullscreen content through the compositor (window.frag) instead of the
    // zero-copy scanout fast-path, because scanout bypasses the shader entirely.
    private volatile boolean effectsRequireCompositor = false;
    public void setFpsLimit(int limit) {
        this.fpsLimit = limit;
        synchronized (lock) { applySchedulingHints(); }
        applyScanoutFrameRateHint();
    }

    private void applyScanoutFrameRateHint() {
        if (android.os.Build.VERSION.SDK_INT < 30 || scanoutGameSC == null) return;
        float targetFps = ScanoutEligibility.frameRateTarget(fpsLimit, 0,
            xServerView.getDisplay() != null ? xServerView.getDisplay().getRefreshRate() : 60f);
        int compatibility = fpsLimit > 0 ? android.view.Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE
            : android.view.Surface.FRAME_RATE_COMPATIBILITY_DEFAULT;
        try (android.view.SurfaceControl.Transaction hint = new android.view.SurfaceControl.Transaction()) {
            if (android.os.Build.VERSION.SDK_INT >= 31) {
                hint.setFrameRate(scanoutGameSC, targetFps, compatibility, android.view.Surface.CHANGE_FRAME_RATE_ALWAYS);
            } else {
                hint.setFrameRate(scanoutGameSC, targetFps, compatibility);
            }
            hint.apply();
        }
    }
    private static class RenderableWindow {
        public final Drawable content; public int rootX, rootY;
        public RenderableWindow(Drawable c, int x, int y) { content=c; rootX=x; rootY=y; }
    }

    @Override
    public String getForceFullscreenWMClass() { return forceFullscreenWMClass; }
}
