package com.winlator.xserver;

import android.os.Handler;
import android.os.Looper;
import android.view.ViewConfiguration;

import com.winlator.winhandler.MouseEventFlags;
import com.winlator.winhandler.WinHandler;
import com.winlator.xserver.events.ButtonPress;
import com.winlator.xserver.events.ButtonRelease;
import com.winlator.xserver.events.EnterNotify;
import com.winlator.xserver.events.Event;
import com.winlator.xserver.events.KeyPress;
import com.winlator.xserver.events.KeyRelease;
import com.winlator.xserver.events.LeaveNotify;
import com.winlator.xserver.events.MappingNotify;
import com.winlator.xserver.events.MotionNotify;
import com.winlator.xserver.events.PointerWindowEvent;

public class InputDeviceManager implements Pointer.OnPointerMotionListener, Keyboard.OnKeyboardListener, WindowManager.OnWindowModificationListener, XResourceManager.OnResourceLifecycleListener {
    private static final byte MOUSE_WHEEL_DELTA = 120;
    private Window pointWindow;
    private final XServer xServer;
    private final Handler autoRepeatHandler = new Handler(Looper.getMainLooper());
    private byte currentRepeatingKeycode;
    private int currentRepeatingKeysym;
    private int repeatDelayMs = 500;
    private int repeatRateMs = 50;

    private volatile boolean sessionClosed;

    /** A (re)started environment on this X server: key auto-repeat works again. */
    public void startSessionCallbacks() {
        sessionClosed = false;
    }

    /** The session is over: end the key auto-repeat, which would otherwise re-post itself for a key whose release never arrived. */
    public void stopSessionCallbacks() {
        sessionClosed = true;
        autoRepeatHandler.removeCallbacksAndMessages(null);
        currentRepeatingKeycode = 0;
        currentRepeatingKeysym = 0;
    }

    private final Runnable autoRepeatRunnable = new Runnable() {
        @Override
        public void run() {
            if (!sessionClosed && xServer.keyboard.getPressedKeys().contains(currentRepeatingKeycode)) {
                onKeyPress(currentRepeatingKeycode, currentRepeatingKeysym);
                autoRepeatHandler.postDelayed(this, repeatRateMs);
            }
        }
    };

    public InputDeviceManager(XServer xServer) {
        this.xServer = xServer;
        pointWindow = xServer.windowManager.rootWindow;
        xServer.windowManager.addOnWindowModificationListener(this);
        xServer.windowManager.addOnResourceLifecycleListener(this);
        xServer.pointer.addOnPointerMotionListener(this);
        xServer.keyboard.addOnKeyboardListener(this);

        repeatDelayMs = ViewConfiguration.getKeyRepeatTimeout();
        repeatRateMs = ViewConfiguration.getKeyRepeatDelay();
    }

    @Override
    public void onMapWindow(Window window) {
        updatePointWindow();
    }

    @Override
    public void onUnmapWindow(Window window) {
        updatePointWindow();
    }

    @Override
    public void onChangeWindowZOrder(Window window) {
        updatePointWindow();
    }

    @Override
    public void onUpdateWindowGeometry(Window window, boolean resized) {
        updatePointWindow();
    }

    @Override
    public void onCreateResource(XResource resource) {
        updatePointWindow();
    }

    @Override
    public void onFreeResource(XResource resource) {
        updatePointWindow();
    }

    private void updatePointWindow() {
        Window pointWindow = xServer.windowManager.findPointWindow(xServer.pointer.getClampedX(), xServer.pointer.getClampedY());
        this.pointWindow = pointWindow != null ? pointWindow : xServer.windowManager.rootWindow;
    }

    public Window getPointWindow() {
        return pointWindow;
    }

    private void sendEvent(Window window, int eventId, Event event) {
        Window grabWindow = xServer.grabManager.getWindow();
        if (grabWindow != null && grabWindow.attributes.isEnabled()) {
            EventListener eventListener = xServer.grabManager.getEventListener();
            if (xServer.grabManager.isOwnerEvents() && window != null) {
                window.sendEvent(eventId, event, xServer.grabManager.getClient());
            }
            else if (eventListener.isInterestedIn(eventId)) {
                eventListener.sendEvent(event);
            }
        }
        else if (window != null && window.attributes.isEnabled()) {
            window.sendEvent(eventId, event);
        }
    }

    private void sendEvent(Window window, Bitmask eventMask, Event event) {
        Window grabWindow = xServer.grabManager.getWindow();
        if (grabWindow != null && grabWindow.attributes.isEnabled()) {
            EventListener eventListener = xServer.grabManager.getEventListener();
            if (xServer.grabManager.isOwnerEvents() && window != null) {
                window.sendEvent(eventMask, event, eventListener.client);
            }
            else if (eventListener.isInterestedIn(eventMask)) {
                eventListener.sendEvent(event);
            }
        }
        else if (window != null && window.attributes.isEnabled()) {
            window.sendEvent(eventMask, event);
        }
    }

    public void sendEnterLeaveNotify(Window windowA, Window windowB, PointerWindowEvent.Mode mode) {
        if (windowA == windowB) return;
        short x = xServer.pointer.getX();
        short y = xServer.pointer.getY();

        short[] localPointA = windowA.rootPointToLocal(x, y);
        short[] localPointB = windowB.rootPointToLocal(x, y);

        boolean sameScreenAndFocus = windowB.isAncestorOf(xServer.windowManager.getFocusedWindow());
        PointerWindowEvent.Detail detailA = PointerWindowEvent.Detail.NONLINEAR;
        PointerWindowEvent.Detail detailB = PointerWindowEvent.Detail.NONLINEAR;

        if (windowA.isAncestorOf(windowB)) {
            detailA = PointerWindowEvent.Detail.ANCESTOR;
            detailB = PointerWindowEvent.Detail.INFERIOR;
        }
        else if (windowB.isAncestorOf(windowA)) {
            detailB = PointerWindowEvent.Detail.ANCESTOR;
            detailA = PointerWindowEvent.Detail.INFERIOR;
        }

        Bitmask keyButMask = getKeyButMask();
        sendEvent(windowA, Event.LEAVE_WINDOW, new LeaveNotify(detailA, xServer.windowManager.rootWindow, windowA, null, x, y, localPointA[0], localPointA[1], keyButMask, mode, sameScreenAndFocus));
        sendEvent(windowB, Event.ENTER_WINDOW, new EnterNotify(detailB, xServer.windowManager.rootWindow, windowB, null, x, y, localPointB[0], localPointB[1], keyButMask, mode, sameScreenAndFocus));
    }

    @Override
    public void onPointerButtonPress(Pointer.Button button) {
        if (xServer.isRelativeMouseMovement()) {
            WinHandler winHandler = xServer.getWinHandler();
            int wheelDelta = button == Pointer.Button.BUTTON_SCROLL_UP ? MOUSE_WHEEL_DELTA : (button == Pointer.Button.BUTTON_SCROLL_DOWN ? -MOUSE_WHEEL_DELTA : 0);
            winHandler.mouseEvent(MouseEventFlags.getFlagFor(button, true), 0, 0, wheelDelta);
        }
        else {
            // The state of a button event is the key/button state just before it: this button is not yet part of it.
            Bitmask state = getKeyButMask(button, false);
            if (xServer.grabManager.getWindow() == null) {
                // No grab: the press goes where it would without one and starts the implicit grab there (unchanged behaviour).
                Window grabWindow = pointWindow.getAncestorWithEventId(Event.BUTTON_PRESS);
                if (grabWindow == null) return;
                xServer.grabManager.activatePointerGrab(grabWindow);
                if (grabWindow.attributes.isEnabled()) {
                    grabWindow.sendEvent(Event.BUTTON_PRESS, createButtonEvent(true, button, grabWindow, state));
                }
            }
            else sendGrabbedButtonEvent(true, button, state);
        }
    }

    @Override
    public void onPointerButtonRelease(Pointer.Button button) {
        if (xServer.isRelativeMouseMovement()) {
            WinHandler winHandler = xServer.getWinHandler();
            winHandler.mouseEvent(MouseEventFlags.getFlagFor(button, false), 0, 0, 0);
        }
        else {
            // The state just before the release still holds the button (the pointer has already cleared it).
            Bitmask state = getKeyButMask(button, true);
            if (xServer.grabManager.getWindow() == null) {
                Window window = pointWindow.getAncestorWithEventId(Event.BUTTON_RELEASE);
                if (window != null && window.attributes.isEnabled()) {
                    window.sendEvent(Event.BUTTON_RELEASE, createButtonEvent(false, button, window, state));
                }
            }
            else sendGrabbedButtonEvent(false, button, state);

            if (xServer.pointer.getButtonMask().isEmpty() && xServer.grabManager.isReleaseWithButtons()) {
                xServer.grabManager.deactivatePointerGrab();
            }
        }
    }

    /**
     * A button event while a pointer grab is active (X11 core protocol, GrabPointer): with owner-events, an event that would
     * normally go to the grabbing client goes there as usual; anything else goes to the grabbing client relative to the grab
     * window, and only if the grab's event mask selects it.
     */
    private void sendGrabbedButtonEvent(boolean press, Pointer.Button button, Bitmask state) {
        Window grabWindow = xServer.grabManager.getWindow();
        EventListener grabListener = xServer.grabManager.getEventListener();
        if (grabWindow == null || grabListener == null || !grabWindow.attributes.isEnabled()) return;
        int eventId = press ? Event.BUTTON_PRESS : Event.BUTTON_RELEASE;
        if (xServer.grabManager.isOwnerEvents()) {
            Window window = pointWindow.getAncestorWithEventId(eventId);
            if (window != null && window.hasEventListenerFor(eventId, grabListener.client)) {
                if (window.attributes.isEnabled()) {
                    window.sendEvent(eventId, createButtonEvent(press, button, window, state), grabListener.client);
                }
                return;
            }
        }
        if (grabListener.isInterestedIn(eventId)) grabListener.sendEvent(createButtonEvent(press, button, grabWindow, state));
    }

    private Event createButtonEvent(boolean press, Pointer.Button button, Window eventWindow, Bitmask state) {
        short x = xServer.pointer.getX();
        short y = xServer.pointer.getY();
        short[] localPoint = eventWindow.rootPointToLocal(x, y);
        Window child = eventWindow.isAncestorOf(pointWindow) ? pointWindow : null;
        return press
            ? new ButtonPress(button.code(), xServer.windowManager.rootWindow, eventWindow, child, x, y, localPoint[0], localPoint[1], state)
            : new ButtonRelease(button.code(), xServer.windowManager.rootWindow, eventWindow, child, x, y, localPoint[0], localPoint[1], state);
    }

    @Override
    public void onPointerMove(short x, short y) {
        updatePointWindow();
        Bitmask eventMask = createPointerEventMask();
        Window grabWindow = xServer.grabManager.getWindow();
        Window window = grabWindow == null || xServer.grabManager.isOwnerEvents() ? pointWindow.getAncestorWithEventMask(eventMask) : null;

        if (grabWindow != null || window != null) {
            Window eventWindow = window != null ? window : grabWindow;
            short[] localPoint = eventWindow.rootPointToLocal(x, y);

            Window child = eventWindow.isAncestorOf(pointWindow) ? pointWindow : null;
            sendEvent(window, eventMask, new MotionNotify(false, xServer.windowManager.rootWindow, eventWindow, child, x, y, localPoint[0], localPoint[1], getKeyButMask()));
        }
    }

    @Override
    public void onKeyPress(byte keycode, int keysym) {
        Window focusedWindow = xServer.windowManager.getFocusedWindow();
        if (focusedWindow == null) return;
        updatePointWindow();

        Window eventWindow = null;
        Window child = null;
        if (focusedWindow.isAncestorOf(pointWindow)) {
            eventWindow = pointWindow.getAncestorWithEventId(Event.KEY_PRESS, focusedWindow);
            // No listener between the pointer window and the focus (or a do-not-propagate mask): the focus window itself.
            if (eventWindow != null) child = eventWindow.isAncestorOf(pointWindow) ? pointWindow : null;
        }
        if (eventWindow == null) {
            if (!focusedWindow.hasEventListenerFor(Event.KEY_PRESS)) return;
            eventWindow = focusedWindow;
        }

        if (!eventWindow.attributes.isEnabled()) return;

        Bitmask keyButMask = getKeyButMask();
        short x = xServer.pointer.getX();
        short y = xServer.pointer.getY();
        short[] localPoint = eventWindow.rootPointToLocal(x, y);

        if (keysym != 0 && !xServer.keyboard.hasKeysym(keycode, keysym)) {
            xServer.keyboard.setKeysyms(keycode, keysym, keysym);
            eventWindow.sendEvent(new MappingNotify(MappingNotify.Request.KEYBOARD, keycode, 1));
        }

        eventWindow.sendEvent(Event.KEY_PRESS, new KeyPress(keycode, xServer.windowManager.rootWindow, eventWindow, child, x, y, localPoint[0], localPoint[1], keyButMask));

        autoRepeatHandler.removeCallbacks(autoRepeatRunnable);
        if (sessionClosed) return;
        currentRepeatingKeycode = keycode;
        currentRepeatingKeysym = keysym;
        autoRepeatHandler.postDelayed(autoRepeatRunnable, repeatDelayMs);
    }

    @Override
    public void onKeyRelease(byte keycode) {
        if (keycode == currentRepeatingKeycode) {
            autoRepeatHandler.removeCallbacks(autoRepeatRunnable);
        }

        Window focusedWindow = xServer.windowManager.getFocusedWindow();
        if (focusedWindow == null) return;
        updatePointWindow();

        Window eventWindow = null;
        Window child = null;
        if (focusedWindow.isAncestorOf(pointWindow)) {
            eventWindow = pointWindow.getAncestorWithEventId(Event.KEY_RELEASE, focusedWindow);
            // No listener between the pointer window and the focus (or a do-not-propagate mask): the focus window itself.
            if (eventWindow != null) child = eventWindow.isAncestorOf(pointWindow) ? pointWindow : null;
        }
        if (eventWindow == null) {
            if (!focusedWindow.hasEventListenerFor(Event.KEY_RELEASE)) return;
            eventWindow = focusedWindow;
        }

        if (!eventWindow.attributes.isEnabled()) return;

        Bitmask keyButMask = getKeyButMask();
        short x = xServer.pointer.getX();
        short y = xServer.pointer.getY();
        short[] localPoint = eventWindow.rootPointToLocal(x, y);

        eventWindow.sendEvent(Event.KEY_RELEASE, new KeyRelease(keycode, xServer.windowManager.rootWindow, eventWindow, child, x, y, localPoint[0], localPoint[1], keyButMask));
    }

    private Bitmask createPointerEventMask() {
        Bitmask eventMask = new Bitmask();
        eventMask.set(Event.POINTER_MOTION);

        Bitmask buttonMask = xServer.pointer.getButtonMask();
        if (!buttonMask.isEmpty()) {
            eventMask.set(Event.BUTTON_MOTION);

            if (buttonMask.isSet(Pointer.Button.BUTTON_LEFT.flag())) {
                eventMask.set(Event.BUTTON1_MOTION);
            }
            if (buttonMask.isSet(Pointer.Button.BUTTON_MIDDLE.flag())) {
                eventMask.set(Event.BUTTON2_MOTION);
            }
            if (buttonMask.isSet(Pointer.Button.BUTTON_RIGHT.flag())) {
                eventMask.set(Event.BUTTON3_MOTION);
            }
            if (buttonMask.isSet(Pointer.Button.BUTTON_SCROLL_UP.flag())) {
                eventMask.set(Event.BUTTON4_MOTION);
            }
            if (buttonMask.isSet(Pointer.Button.BUTTON_SCROLL_DOWN.flag())) {
                eventMask.set(Event.BUTTON5_MOTION);
            }
        }
        return eventMask;
    }

    /** The core SETofKEYBUTMASK: modifiers and Button1..Button5 (buttons 6 and 7 have no state bit in the core protocol). */
    private static final long CORE_KEY_BUT_MASK = 0x1FFF;

    private Bitmask getKeyButMask(Pointer.Button button, boolean pressed) {
        Bitmask keyButMask = getKeyButMask();
        keyButMask.set(button.flag(), pressed);
        return new Bitmask(keyButMask.getBits() & CORE_KEY_BUT_MASK);
    }

    public Bitmask getKeyButMask() {
        Bitmask keyButMask = new Bitmask();
        keyButMask.join(xServer.pointer.getButtonMask());
        keyButMask.join(xServer.keyboard.getModifiersMask());
        return keyButMask;
    }
}
