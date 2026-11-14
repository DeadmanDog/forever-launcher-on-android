package com.winlator.xserver;

import android.graphics.Rect;

import com.winlator.xserver.events.Event;
import com.winlator.xserver.events.PointerWindowEvent;

public class GrabManager implements WindowManager.OnWindowModificationListener {
    private Window window;
    private Window confineToWindow;
    private boolean ownerEvents;
    private boolean releaseWithButtons;
    private EventListener eventListener;
    private final XServer xServer;

    public GrabManager(XServer xServer) {
        this.xServer = xServer;
        xServer.windowManager.addOnWindowModificationListener(this);
    }

    @Override
    public void onUnmapWindow(Window window) {
        // X11: the grab ends when its grab window or confine-to window (or an ancestor of them) stops being viewable, not when
        // just any window is unmapped.
        if (this.window == null) return;
        if (this.window.getMapState() != Window.MapState.VIEWABLE
                || (confineToWindow != null && confineToWindow.getMapState() != Window.MapState.VIEWABLE)) {
            deactivatePointerGrab();
        }
    }

    @Override
    public void onDestroyWindow(Window window) {
        if (this.window != null && (window == this.window || window == confineToWindow)) deactivatePointerGrab();
    }

    public Window getWindow() {
        return window;
    }

    public boolean isOwnerEvents() {
        return ownerEvents;
    }

    public boolean isReleaseWithButtons() {
        return releaseWithButtons;
    }

    public EventListener getEventListener() {
        return eventListener;
    }

    public XClient getClient() {
        return eventListener != null ? eventListener.client : null;
    }

    public Rect getConfinementBounds() {
        if (confineToWindow == null) return null;
        return confineToWindow.getAbsoluteBounds();
    }

    public void deactivatePointerGrab() {
        if (window != null) {
            xServer.inputDeviceManager.sendEnterLeaveNotify(window, xServer.inputDeviceManager.getPointWindow(), PointerWindowEvent.Mode.UNGRAB);
            window = null;
            eventListener = null;
            confineToWindow = null;
        }
    }

    private void activatePointerGrab(Window window, EventListener eventListener, boolean ownerEvents, boolean releaseWithButtons, Window confineToWindow) {
        if (this.window == null) {
            xServer.inputDeviceManager.sendEnterLeaveNotify(xServer.inputDeviceManager.getPointWindow(), window, PointerWindowEvent.Mode.GRAB);
        }
        this.window = window;
        this.releaseWithButtons = releaseWithButtons;
        this.ownerEvents = ownerEvents;
        this.eventListener = eventListener;
        this.confineToWindow = confineToWindow;
    }

    public void activatePointerGrab(Window window, boolean ownerEvents, Bitmask eventMask, XClient client, Window confineToWindow) {
        activatePointerGrab(window, new EventListener(client, eventMask), ownerEvents, false, confineToWindow);
    }

    public void activatePointerGrab(Window window) {
        EventListener eventListener = window.getButtonPressListener();
        activatePointerGrab(window, eventListener, eventListener.isInterestedIn(Event.OWNER_GRAB_BUTTON), true, null);
    }
}
