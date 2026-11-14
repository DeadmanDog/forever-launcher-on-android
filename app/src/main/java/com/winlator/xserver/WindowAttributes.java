package com.winlator.xserver;

import com.winlator.xconnector.XInputStream;
import com.winlator.xserver.errors.BadValue;

public class WindowAttributes {
    public static final int FLAG_BACKGROUND_PIXMAP = 1<<0;
    public static final int FLAG_BACKGROUND_PIXEL = 1<<1;
    public static final int FLAG_BORDER_PIXMAP = 1<<2;
    public static final int FLAG_BORDER_PIXEL = 1<<3;
    public static final int FLAG_BIT_GRAVITY = 1<<4;
    public static final int FLAG_WIN_GRAVITY = 1<<5;
    public static final int FLAG_BACKING_STORE = 1<<6;
    public static final int FLAG_BACKING_PLANES = 1<<7;
    public static final int FLAG_BACKING_PIXEL = 1<<8;
    public static final int FLAG_OVERRIDE_REDIRECT = 1<<9;
    public static final int FLAG_SAVE_UNDER = 1<<10;
    public static final int FLAG_EVENT_MASK = 1<<11;
    public static final int FLAG_DO_NOT_PROPAGATE_MASK = 1<<12;
    public static final int FLAG_COLORMAP = 1<<13;
    public static final int FLAG_CURSOR = 1<<14;
    private static final int KNOWN_FLAGS = (FLAG_CURSOR << 1) - 1;
    public enum BackingStore {NOT_USEFUL, WHEN_MAPPED, ALWAYS}
    public enum WindowClass {COPY_FROM_PARENT, INPUT_OUTPUT, INPUT_ONLY}
    public enum BitGravity {FORGET, NORTH_WEST, NORTH, NORTH_EAST, WEST, CENTER, EAST, SOUTH_WEST, SOUTH, SOUTH_EAST, STATIC}
    public enum WinGravity {UNMAP, NORTH_WEST, NORTH, NORTH_EAST, WEST, CENTER, EAST, SOUTH_WEST, SOUTH, SOUTH_EAST, STATIC}
    private int backingPixel = 0;
    private int backingPlanes = 1;
    private BackingStore backingStore = BackingStore.NOT_USEFUL;
    private BitGravity bitGravity = BitGravity.CENTER;
    private Cursor cursor;
    private Bitmask doNotPropagateMask = new Bitmask(0);
    private Bitmask eventMask = new Bitmask(0);
    private boolean mapped = false;
    private boolean overrideRedirect = false;
    private boolean saveUnder = false;
    private boolean enabled = true;
    private WinGravity winGravity = WinGravity.CENTER;
    private WindowClass windowClass = WindowClass.INPUT_OUTPUT;
    public final Window window;

    public WindowAttributes(Window window) {
        this.window = window;
    }

    public int getBackingPixel() {
        return backingPixel;
    }

    public int getBackingPlanes() {
        return backingPlanes;
    }

    public BackingStore getBackingStore() {
        return backingStore;
    }

    public BitGravity getBitGravity() {
        return bitGravity;
    }

    public Cursor getCursor() {
        Window parent = window.getParent();
        return cursor == null && parent != null ? parent.attributes.getCursor() : cursor;
    }

    public Bitmask getEventMask() {
        return eventMask;
    }

    public Bitmask getDoNotPropagateMask() {
        return doNotPropagateMask;
    }

    public boolean isMapped() {
        return mapped;
    }

    public void setMapped(boolean mapped) {
        this.mapped = mapped;
    }

    public boolean isOverrideRedirect() {
        return overrideRedirect;
    }

    public boolean isSaveUnder() {
        return saveUnder;
    }

    public WinGravity getWinGravity() {
        return winGravity;
    }

    public WindowClass getWindowClass() {
        return windowClass;
    }

    public void setWindowClass(WindowClass windowClass) {
        this.windowClass = windowClass;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public void update(Bitmask valueMask, XInputStream inputStream, XClient client) throws BadValue {
        // Read and check every value first, then apply them: a bad value (BadValue) leaves the window unchanged. An enum value
        // out of range used to throw ArrayIndexOutOfBounds and drop the client's connection.
        int count = 0;
        for (int index : valueMask) if ((index & KNOWN_FLAGS) != 0) count++;
        int[] flags = new int[count];
        int[] values = new int[count];
        int n = 0;
        for (int index : valueMask) {
            if ((index & KNOWN_FLAGS) == 0) continue; // as before: no value is read for an unknown bit
            flags[n] = index;
            values[n] = inputStream.readInt();
            n++;
        }
        for (int i = 0; i < count; i++) {
            switch (flags[i]) {
                case FLAG_BIT_GRAVITY: checkRange(values[i], BitGravity.values().length); break;
                case FLAG_WIN_GRAVITY: checkRange(values[i], WinGravity.values().length); break;
                case FLAG_BACKING_STORE: checkRange(values[i], BackingStore.values().length); break;
            }
        }

        for (int i = 0; i < count; i++) {
            int value = values[i];
            switch (flags[i]) {
                case FLAG_BACKGROUND_PIXEL:
                    // An InputOnly window has no content to fill (X11 says BadMatch; ignored, it changes nothing visible).
                    Drawable content = window.getContent();
                    if (content != null) content.fillColor(value);
                    break;
                case FLAG_BACKING_PIXEL:
                    backingPixel = value;
                    break;
                case FLAG_BACKING_PLANES:
                    backingPlanes = value;
                    break;
                case FLAG_BIT_GRAVITY:
                    bitGravity = BitGravity.values()[value];
                    break;
                case FLAG_WIN_GRAVITY:
                    winGravity = WinGravity.values()[value];
                    break;
                case FLAG_BACKING_STORE:
                    backingStore = BackingStore.values()[value];
                    break;
                case FLAG_SAVE_UNDER:
                    saveUnder = value == 1;
                    break;
                case FLAG_OVERRIDE_REDIRECT:
                    overrideRedirect = value == 1;
                    break;
                case FLAG_EVENT_MASK:
                    eventMask = new Bitmask(value);
                    break;
                case FLAG_DO_NOT_PROPAGATE_MASK:
                    doNotPropagateMask = new Bitmask(value);
                    break;
                case FLAG_CURSOR:
                    cursor = client.xServer.cursorManager.getCursor(value);
                    break;
                case FLAG_BACKGROUND_PIXMAP:
                case FLAG_BORDER_PIXMAP:
                case FLAG_BORDER_PIXEL:
                case FLAG_COLORMAP:
                    break;
            }
        }

        client.xServer.windowManager.triggerOnUpdateWindowAttributes(window, valueMask);
    }

    private static void checkRange(int value, int size) throws BadValue {
        if (value < 0 || value >= size) throw new BadValue(value);
    }
}