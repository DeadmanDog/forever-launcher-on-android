package com.winlator.xserver.extensions;

import static com.winlator.xserver.XClientRequestHandler.RESPONSE_CODE_SUCCESS;

import com.winlator.renderer.VulkanRenderer;
import com.winlator.renderer.XServerRenderer;
import com.winlator.xconnector.XInputStream;
import com.winlator.xconnector.XOutputStream;
import com.winlator.xconnector.XStreamLock;
import com.winlator.xserver.Bitmask;
import com.winlator.xserver.Drawable;
import com.winlator.xserver.Pixmap;
import com.winlator.xserver.Window;
import com.winlator.xserver.XClient;
import com.winlator.xserver.XLock;
import com.winlator.xserver.XServer;
import com.winlator.xserver.errors.BadImplementation;
import com.winlator.xserver.errors.BadMatch;
import com.winlator.xserver.errors.BadPixmap;
import com.winlator.xserver.errors.BadWindow;
import com.winlator.xserver.errors.XRequestError;
import com.winlator.xserver.events.PresentCompleteNotify;
import com.winlator.xserver.events.PresentIdleNotify;

import java.io.IOException;

public class PresentExtension implements Extension {
    public static final byte MAJOR_OPCODE = -103;
    public enum Kind { PIXMAP, MSC_NOTIFY }
    public enum Mode { COPY, FLIP, SKIP }

    private final PresentSubscriptions<Window, XClient> events = new PresentSubscriptions<>();
    private SyncExtension syncExtension;
    private byte firstEventId = 0;
    private byte firstErrorId = 0;

    // Target FPS for the back-pressure limiter. 0 disables limiting (notifies fire
    // immediately). Set from XServerScreen when the user toggles the FPS cap.
    private volatile int frameRateLimit = 0;

    // Mailbox semantics for the pacing scheduler: when a new present supersedes a
    // still-pending pixmap on the same window, release the superseded one
    // immediately instead of holding it to the schedule. Armed only while
    // bionic-fg is active — the layer owns base pacing then, and it presents at
    // multiplier x the cap, so releases must track presents or its generated
    // frames starve the swapchain.
    private volatile boolean eagerIdleRelease = false;
    private static class PendingIdle {
        final Window window;
        final Pixmap pixmap;
        final int serial;
        final int idleFence;
        final Object fenceToken;
        PendingIdle(Window window, Pixmap pixmap, int serial, int idleFence, Object fenceToken) {
            this.window = window;
            this.pixmap = pixmap;
            this.serial = serial;
            this.idleFence = idleFence;
            this.fenceToken = fenceToken;
        }
    }

    // Timer pacing until the session selects the mode (setVsyncPacing); host tests use the timer.
    private volatile boolean vsyncPacing = false;
    // While the app is in the background Android may send it no vsync at all: the timer then releases the buffers (setBackground).
    private volatile boolean background = false;
    private volatile IdlePacer<PendingIdle> idlePacer = newIdlePacer();

    private IdlePacer<PendingIdle> newIdlePacer() {
        java.util.function.Consumer<PendingIdle> release = p -> {
            if (p.idleFence != 0 && syncExtension != null) syncExtension.triggerPresentFence(p.idleFence, p.fenceToken);
            sendIdleNotifyEvents(p.window, p.pixmap, p.serial, p.idleFence);
        };
        return vsyncPacing && !background ? new VsyncFramePacer<>(release) : new PresentFramePacer<>(release);
    }

    /**
     * true: buffers are released on the display's vsync (no drift against the panel, v0.1.114+ default).
     * false: the free-running 1/limit timer used up to v0.1.113. Held buffers are released on a switch.
     */
    public synchronized void setVsyncPacing(boolean vsync) {
        if (vsyncPacing == vsync) return;
        vsyncPacing = vsync;
        replacePacer();
    }

    /**
     * The game keeps running while the app is in the background, but the vsync pacer would wait for display callbacks that may
     * not come: a game blocked on its next buffer stops its network loop and the server drops it. In the background the timer
     * releases the buffers; back in the foreground the chosen pacing returns.
     */
    public synchronized void setBackground(boolean value) {
        if (background == value) return;
        boolean vsyncBefore = vsyncPacing && !background;
        background = value;
        if (vsyncBefore != (vsyncPacing && !background)) replacePacer();
    }

    private void replacePacer() {
        IdlePacer<PendingIdle> old = idlePacer;
        idlePacer = newIdlePacer();
        idlePacer.setFrameRateLimit(frameRateLimit);
        old.releaseAllAndClose();
    }

    public synchronized void setFrameRateLimit(int limit) {
        frameRateLimit = Math.max(0, limit);
        idlePacer.setFrameRateLimit(frameRateLimit);
    }

    /** A component may be started again with the same XServer after stopping. */
    public synchronized void resetPacing() {
        idlePacer.close();
        idlePacer = newIdlePacer();
        idlePacer.setFrameRateLimit(frameRateLimit);
    }

    public void close() {
        idlePacer.close();
        events.clear();
    }

    public void onClientDisconnected(XClient client) { events.removeClient(client); }
    public void onWindowDestroyed(Window window) { events.removeWindow(window); }

    private void scheduleIdleNotify(Window window, Pixmap pixmap, int serial, int idleFence) {
        scheduleIdleNotify(window, pixmap, serial, idleFence,
            idleFence != 0 && syncExtension != null ? syncExtension.capturePresentFence(idleFence) : null);
    }

    private void scheduleIdleNotify(Window window, Pixmap pixmap, int serial, int idleFence, Object token) {
        idlePacer.schedule(window.id, new PendingIdle(window, pixmap, serial, idleFence, token), eagerIdleRelease);
    }

    private static abstract class ClientOpcodes {
        static final byte QUERY_VERSION = 0;
        static final byte PRESENT_PIXMAP = 1;
        static final byte SELECT_INPUT = 3;
    }


    @Override
    public String getName() { return "Present"; }

    @Override
    public byte getMajorOpcode() { return MAJOR_OPCODE; }

    @Override
    public int getNumEvents() { return 2; }

    @Override
    public int getNumErrors() { return 0; }

    @Override
    public void setFirstEventId(byte id) { this.firstEventId = id; }

    @Override
    public void setFirstErrorId(byte id) { this.firstErrorId = id; }

    @Override
    public byte getFirstEventId() { return firstEventId; }

    @Override
    public byte getFirstErrorId() { return firstErrorId; }

    /**
     * The renderer is done with the buffer: queue the client's Idle message (never run it on the render thread itself).
     * The FPS limit still decides WHEN the client gets the buffer: it goes through the same pacer as a buffer that was never held, so
     * the Idle leaves at the later of "the frames are done" and "the limiter allows the next frame". (Sending it right here made
     * a held buffer return at the GPU's pace and the limit did nothing, 1.3.19.) The pacer's own release only names the window and
     * pixmap: a window destroyed meanwhile has no event subscriptions left, so a late release reaches nobody.
     */
    private void sendIdleFromRenderer(XClient client, Window window, Pixmap pixmap, int serial, int idleFence, Object token) {
        if (client.connectorClient == null) return;
        client.connectorClient.getConnector().enqueueClientTask(client.connectorClient, () -> {
            try (XLock ignored = client.xServer.lock(XServer.Lockable.WINDOW_MANAGER)) {
                // XIDs can be destroyed and reused while the renderer held the buffer.
                if (client.xServer.windowManager.getWindow(window.id) != window
                        || client.xServer.pixmapManager.getPixmap(pixmap.id) != pixmap) return;
                if (frameRateLimit > 0) scheduleIdleNotify(window, pixmap, serial, idleFence, token);
                else sendCapturedIdleNotify(window, pixmap, serial, idleFence, token);
            }
        });
    }

    private void sendCapturedIdleNotify(Window window, Pixmap pixmap, int serial, int idleFence, Object token) {
        if (idleFence != 0 && syncExtension != null) syncExtension.triggerPresentFence(idleFence, token);
        sendIdleNotifyEvents(window, pixmap, serial, idleFence);
    }

    private void sendIdleNotifyEvents(Window window, Pixmap pixmap, int serial, int idleFence) {
        for (var e : events.snapshot()) {
            if (e.window == window && (e.mask & PresentIdleNotify.getEventMask()) != 0) {
                e.client.sendEvent(new PresentIdleNotify(e.id, window, pixmap, serial, idleFence));
            }
        }
    }

    private void sendCompleteNotify(Window window, int serial, Kind kind, Mode mode, long ust, long msc) {
        for (var e : events.snapshot()) {
            if (e.window == window && (e.mask & PresentCompleteNotify.getEventMask()) != 0) {
                e.client.sendEvent(new PresentCompleteNotify(e.id, window, serial, kind, mode, ust, msc));
            }
        }
    }

    private static void queryVersion(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        inputStream.skip(8);

        try (XStreamLock lock = outputStream.lock()) {
            outputStream.writeByte(RESPONSE_CODE_SUCCESS);
            outputStream.writeByte((byte)0);
            outputStream.writeShort(client.getSequenceNumber());
            outputStream.writeInt(0);
            outputStream.writeInt(1);
            outputStream.writeInt(0);
            outputStream.writePad(16);
        }
    }

    private void presentPixmap(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        int windowId = inputStream.readInt();
        int pixmapId = inputStream.readInt();
        int serial = inputStream.readInt();
        inputStream.skip(8);
        short xOff = inputStream.readShort();
        short yOff = inputStream.readShort();
        inputStream.skip(4); // target_crtc
        int waitFence = inputStream.readInt();
        int idleFence = inputStream.readInt();
        inputStream.skip(client.getRemainingRequestLength());

        final Window window = client.xServer.windowManager.getWindow(windowId);
        if (window == null) throw new BadWindow(windowId);

        final Pixmap pixmap = client.xServer.pixmapManager.getPixmap(pixmapId);
        if (pixmap == null) throw new BadPixmap(pixmapId);

        Drawable content = window.getContent();
        int contentDepth = content.visual.depth;
        int pixmapDepth = pixmap.drawable.visual.depth;
        boolean depthCompat = (contentDepth == pixmapDepth) ||
                ((contentDepth == 24 || contentDepth == 32) && (pixmapDepth == 24 || pixmapDepth == 32));
        if (!depthCompat) throw new BadMatch();

        final Object idleToken = idleFence != 0 && syncExtension != null ? syncExtension.capturePresentFence(idleFence) : null;
        if (waitFence != 0 && syncExtension.afterPresentFence(client, waitFence,
                () -> queueDeferredPresent(client, window, pixmap, serial, xOff, yOff, idleFence, idleToken, false),
                () -> queueDeferredPresent(client, window, pixmap, serial, xOff, yOff, idleFence, idleToken, true))) return;
        submitPixmap(client, window, pixmap, serial, xOff, yOff, idleFence, idleToken);
    }

    private void queueDeferredPresent(XClient client, Window window, Pixmap pixmap, int serial,
            short xOff, short yOff, int idleFence, Object idleToken, boolean cancelled) {
        client.connectorClient.getConnector().enqueueClientTask(client.connectorClient, () -> {
            try (XLock ignored = client.xServer.lock(XServer.Lockable.WINDOW_MANAGER)) {
                // XIDs can be destroyed and reused before a deferred fence fires.
                if (client.xServer.windowManager.getWindow(window.id) != window
                        || client.xServer.pixmapManager.getPixmap(pixmap.id) != pixmap) return;
                if (cancelled) {
                    long ust = System.nanoTime() / 1000;
                    sendCompleteNotify(window, serial, Kind.PIXMAP, Mode.SKIP, ust, ust / 16666);
                    sendCapturedIdleNotify(window, pixmap, serial, idleFence, idleToken);
                } else submitPixmap(client, window, pixmap, serial, xOff, yOff, idleFence, idleToken);
            } catch (IOException ignored) { }
        });
    }

    private void submitPixmap(XClient client, Window window, Pixmap pixmap, int serial,
            short xOff, short yOff, int idleFence, Object idleToken) throws IOException {
        Drawable content = window.getContent();
        final XServerRenderer xr = client.xServer.getRenderer();
        final VulkanRenderer vr = (xr instanceof VulkanRenderer) ? (VulkanRenderer) xr : null;
        final int targetFps = this.frameRateLimit;

        long ust = System.nanoTime() / 1000;
        long msc = ust / (targetFps > 0 ? (1_000_000L / targetFps) : (1_000_000L / 60));

        // Non-GL paths bypass GL's frame callback and WindowManager draw listeners.
        // Observe request intervals only; do not change rendering, fences or pacing.
        if (vr != null && window.attributes.isMapped()) {
            app.gamenative.ui.screen.wow.WowDiagnostics.presentFrame(window, pixmap);
        }

        synchronized (content.renderLock) {
            if (vr != null && window.attributes.isMapped()) {
                sendCompleteNotify(window, serial, Kind.PIXMAP, Mode.COPY, ust, msc);
                // With the experimental hand-back on, the renderer keeps the buffer and gives it back (through the callback) when the
                // frames that may read it are done; otherwise the Idle goes out right away or on the pacer, as always.
                boolean held = vr.onUpdateWindowContentDirect(window, pixmap.drawable, xOff, yOff,
                        () -> sendIdleFromRenderer(client, window, pixmap, serial, idleFence, idleToken));
                if (!held) {
                    if (targetFps > 0) scheduleIdleNotify(window, pixmap, serial, idleFence, idleToken);
                    else sendCapturedIdleNotify(window, pixmap, serial, idleFence, idleToken);
                }
            } else {
                // Unmapped window (or no Vulkan renderer): keep the window content current without compositing.
                content.copyArea((short)0, (short)0, xOff, yOff,
                        pixmap.drawable.width, pixmap.drawable.height, pixmap.drawable);
                sendCompleteNotify(window, serial, Kind.PIXMAP, Mode.COPY, ust, msc);
                if (targetFps > 0) scheduleIdleNotify(window, pixmap, serial, idleFence, idleToken);
                else sendCapturedIdleNotify(window, pixmap, serial, idleFence, idleToken);
            }
        }
    }

    private void selectInput(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        int eventId = inputStream.readInt();
        int windowId = inputStream.readInt();
        Bitmask mask = new Bitmask(inputStream.readInt());

        Window window = client.xServer.windowManager.getWindow(windowId);
        if (window == null) throw new BadWindow(windowId);

        synchronized (events) {
            var event = events.get(eventId);
            if (event != null && (event.window != window || event.client != client)) throw new BadMatch();
            events.put(eventId, window, client, mask.getBits());
        }
    }

    @Override
    public void handleRequest(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        int opcode = client.getRequestData();
        if (syncExtension == null) syncExtension = client.xServer.getExtension(SyncExtension.MAJOR_OPCODE);

        switch (opcode) {
            case ClientOpcodes.QUERY_VERSION:
                queryVersion(client, inputStream, outputStream);
                break;
            case ClientOpcodes.PRESENT_PIXMAP:
                try (XLock lock = client.xServer.lock(XServer.Lockable.WINDOW_MANAGER)) {
                    presentPixmap(client, inputStream, outputStream);
                }
                break;
            case ClientOpcodes.SELECT_INPUT:
                try (XLock lock = client.xServer.lock(XServer.Lockable.WINDOW_MANAGER)) {
                    selectInput(client, inputStream, outputStream);
                }
                break;
            default:
                throw new BadImplementation();
        }
    }
}
