package com.winlator.widget;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Point;
import android.graphics.PointF;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewConfiguration;
import android.widget.FrameLayout;
import android.util.SparseBooleanArray;


import com.winlator.inputcontrols.Binding;
import com.winlator.inputcontrols.BindingCombo;
import com.winlator.inputcontrols.ControlElement;
import com.winlator.inputcontrols.ControlsProfile;
import com.winlator.inputcontrols.ExternalController;
import com.winlator.inputcontrols.ExternalControllerBinding;
import com.winlator.inputcontrols.GamepadState;
import com.winlator.math.Mathf;
import com.winlator.winhandler.WinHandler;
import com.winlator.xserver.Pointer;
import com.winlator.xserver.XServer;

import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.EnumMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import java.util.Timer;
import java.util.TimerTask;

public class InputControlsView extends View {
    public static final float DEFAULT_OVERLAY_OPACITY = 0.4f;
    private static final int SEQUENCE_PRESS_MS = 80;
    private boolean editMode = false;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final Point cursor = new Point();
    private boolean readyToDraw = false;
    private boolean moveCursor = false;
    private final Set<BindingCombo> activeSequenceCombos = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Map<Binding, Integer> activeSequenceBindings = new EnumMap<>(Binding.class);
    private final Map<Object, Binding> activeSequenceSources = new IdentityHashMap<>();
    private int sequenceGeneration = 0;
    private int snappingSize;
    private float offsetX;
    private float offsetY;
    private ControlElement selectedElement;
    private ControlsProfile profile;
    // Active controller state survives hiding on-screen controls.
    private ControlsProfile activeProfile;
    private float overlayOpacity = DEFAULT_OVERLAY_OPACITY;
    private TouchpadView touchpadView;
    private XServer xServer;
    private final Bitmap[] icons = new Bitmap[40];
    private Timer mouseMoveTimer;
    private final PointF mouseMoveOffset = new PointF();
    private boolean showTouchscreenControls = true;

    // General look-through owns one movement-only pointer and quarantines background pointers.
    private final LookThroughPointerState lookThroughPointerState = new LookThroughPointerState();
    // Pointers whose DOWN event was delivered to TouchpadView's normal gesture system.
    private final SparseBooleanArray touchpadPointers = new SparseBooleanArray();
    private final int lookThroughTouchSlop;

    private float baseThumbLX;
    private float baseThumbLY;
    private float baseThumbRX;
    private float baseThumbRY;

    // Callback invoked when the SHOW_KEYBOARD binding is triggered
    private Runnable showKeyboardCallback;
    // Tracks whether SHOW_KEYBOARD is currently held, so the callback fires once per press (rising edge only)
    private boolean showKeyboardPressed;
    private RadialMenuListener radialMenuListener;
    private boolean radialMenuTouchActive;
    private int radialMenuTouchPointerId = MotionEvent.INVALID_POINTER_ID;

    public interface RadialMenuListener {
        void onRadialMenuTouchStart(int pointerId, float x, float y);
        void onRadialMenuTouchMove(int pointerId, float x, float y);
        void onRadialMenuTouchEnd(int pointerId, boolean commit);
        void onRadialMenuButtonStateChanged(boolean isDown);
    }

    @SuppressLint("ResourceType")
    public InputControlsView(Context context) {
        super(context);

        lookThroughTouchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        setClickable(true);
        setFocusable(true);
        setFocusableInTouchMode(true);
        setDefaultFocusHighlightEnabled(false);
        setBackgroundColor(0x00000000);
        setLayoutParams(new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    public boolean isEditMode() {
        return editMode;
    }

    public void setEditMode(boolean editMode) {
        if (this.editMode != editMode) cancelTouchRouting();
        this.editMode = editMode;
        invalidate(); // Trigger redraw to show/hide grid background immediately
    }

    public void setShowKeyboardCallback(Runnable callback) {
        this.showKeyboardCallback = callback;
    }

    public void setRadialMenuListener(RadialMenuListener listener) {
        this.radialMenuListener = listener;
    }

    public void triggerShowKeyboard() {
        if (showKeyboardCallback != null) showKeyboardCallback.run();
    }

    public void handleRadialMenuTouchDown(int pointerId, float x, float y) {
        radialMenuTouchActive = true;
        radialMenuTouchPointerId = pointerId;
        if (radialMenuListener != null) radialMenuListener.onRadialMenuTouchStart(pointerId, x, y);
    }

    public void handleRadialMenuTouchMove(int pointerId, float x, float y) {
        if (radialMenuTouchActive && pointerId == radialMenuTouchPointerId && radialMenuListener != null) {
            radialMenuListener.onRadialMenuTouchMove(pointerId, x, y);
        }
    }

    public void handleRadialMenuTouchUp(int pointerId, boolean commit) {
        if (!radialMenuTouchActive) return;
        if (radialMenuTouchPointerId != MotionEvent.INVALID_POINTER_ID && pointerId != radialMenuTouchPointerId) return;
        int finishedPointerId = radialMenuTouchPointerId;
        radialMenuTouchActive = false;
        radialMenuTouchPointerId = MotionEvent.INVALID_POINTER_ID;
        if (radialMenuListener != null) radialMenuListener.onRadialMenuTouchEnd(finishedPointerId, commit);
    }

    private void commitGamepadStateIfNeeded(Binding binding) {
        if (binding == null || !binding.isGamepad()) return;
        commitGamepadState();
    }

    private void commitGamepadState() {
        if (xServer == null || profile == null) return;
        WinHandler winHandler = xServer.getWinHandler();
        if (winHandler != null) {
            GamepadState state = profile.getGamepadState();
            winHandler.sendGamepadState();
            winHandler.sendVirtualGamepadState(state);
        }
    }

    private void cancelTouchRouting() {
        cancelBindingSequences();
        if (radialMenuTouchActive) handleRadialMenuTouchUp(radialMenuTouchPointerId, false);
        if (profile != null) {
            for (ControlElement element : profile.getElements()) element.cancelTouch();
        }
        commitGamepadState();
        if (touchpadView != null) touchpadView.cancelTouchInput();
        touchpadPointers.clear();
        lookThroughPointerState.clear();
    }

    public void setOverlayOpacity(float overlayOpacity) {
        this.overlayOpacity = overlayOpacity;
    }

    public float getOverlayOpacity() {
        return overlayOpacity;
    }

    public int getSnappingSize() {
        return snappingSize;
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (profile != null && profile.isElementsLoaded() && oldw > 0 && w != oldw) {
            cancelTouchRouting();
            profile.loadElements(this);
        }
    }

    @Override
    protected synchronized void onDraw(Canvas canvas) {
        int width = getWidth();
        int height = getHeight();

        if (width == 0 || height == 0) {
            readyToDraw = false;
            return;
        }

        snappingSize = width / 100;
        readyToDraw = true;

        if (editMode) {
            drawGrid(canvas);
            drawCursor(canvas);
        }

        if (profile != null) {
            if (!profile.isElementsLoaded()) profile.loadElements(this);
            if (showTouchscreenControls) {
                for (ControlElement element : profile.getElements()) {
                    element.draw(canvas);
                }
            }
        }

        super.onDraw(canvas);
    }

    private void drawGrid(Canvas canvas) {
        canvas.drawColor(0x80000000);
        paint.setStrokeWidth(snappingSize * 0.0625f);

        paint.setAntiAlias(false);
        paint.setColor(0x80303030);

        int width = getMaxWidth();
        int height = getMaxHeight();

        for (int i = 0; i < width; i += snappingSize) {
            canvas.drawLine(i, 0, i, height, paint);
            canvas.drawLine(0, i, width, i, paint);
        }

        float cx = Mathf.roundTo(width * 0.5f, snappingSize);
        float cy = Mathf.roundTo(height * 0.5f, snappingSize);
        paint.setColor(0x99424242);

        for (int i = 0; i < width; i += snappingSize * 2) {
            canvas.drawLine(cx, i, cx, i + snappingSize, paint);
            canvas.drawLine(i, cy, i + snappingSize, cy, paint);
        }

        paint.setAntiAlias(true);
    }

    private void drawCursor(Canvas canvas) {
        paint.setStyle(Paint.Style.FILL);
        paint.setStrokeWidth(snappingSize * 0.0625f);
        paint.setColor(0xffc62828);

        paint.setAntiAlias(false);
        canvas.drawLine(0, cursor.y, getMaxWidth(), cursor.y, paint);
        canvas.drawLine(cursor.x, 0, cursor.x, getMaxHeight(), paint);

        paint.setAntiAlias(true);
    }

    public synchronized boolean addElement() {
        if (editMode && profile != null) {
            ControlElement element = new ControlElement(this);
            // Calculate center position, snapped to grid
            int centerX = (int)Mathf.roundTo(getMaxWidth() * 0.5f, snappingSize);
            int centerY = (int)Mathf.roundTo(getMaxHeight() * 0.5f, snappingSize);
            element.setX(centerX);
            element.setY(centerY);
            profile.addElement(element);
            profile.save();
            selectElement(element);
            return true;
        }
        else return false;
    }

    public synchronized boolean removeElement() {
        if (editMode && selectedElement != null && profile != null) {
            profile.removeElement(selectedElement);
            selectedElement = null;
            profile.save();
            invalidate();
            return true;
        }
        else return false;
    }

    public ControlElement getSelectedElement() {
        return selectedElement;
    }

    private synchronized void deselectAllElements() {
        selectedElement = null;
        if (profile != null) {
            for (ControlElement element : profile.getElements()) element.setSelected(false);
        }
    }

    private void selectElement(ControlElement element) {
        deselectAllElements();
        if (element != null) {
            selectedElement = element;
            selectedElement.setSelected(true);
        }
        invalidate();
    }

    public synchronized ControlsProfile getProfile() {
        return profile;
    }

    public void setProfile(ControlsProfile profile) {
        setProfileInternal(profile, false);
    }

    /** Replaces the active profile without making hidden on-screen controls visible again. */
    public void setProfilePreservingOverlayVisibility(ControlsProfile profile) {
        setProfileInternal(profile, true);
    }

    private void setProfileInternal(ControlsProfile profile, boolean preserveOverlayVisibility) {
        cancelTouchRouting();

        final boolean profileChanged;
        final boolean overlayProfileVisible;
        synchronized (this) {
            profileChanged = this.activeProfile != profile;
            overlayProfileVisible = this.profile != null;
        }

        synchronized (this) {
            if (profileChanged) resetThumbContributions();
            if (profile != null) {
                this.profile = preserveOverlayVisibility && !overlayProfileVisible ? null : profile;
                this.activeProfile = profile;
                GamepadState state = profile.getGamepadState();
                baseThumbLX = Mathf.clamp(state.thumbLX, -1f, 1f);
                baseThumbLY = Mathf.clamp(state.thumbLY, -1f, 1f);
                baseThumbRX = Mathf.clamp(state.thumbRX, -1f, 1f);
                baseThumbRY = Mathf.clamp(state.thumbRY, -1f, 1f);
                deselectAllElements();
            }
            else {
                this.profile = null;
                this.activeProfile = null;
            }
        }

    }

    /** Hides the on-screen controls without losing the active controller profile. */
    public void hideProfileForOverlay() {
        cancelTouchRouting();
        synchronized (this) {
            this.profile = null;
        }
    }

    public boolean isShowTouchscreenControls() {
        return showTouchscreenControls;
    }

    public void setShowTouchscreenControls(boolean showTouchscreenControls) {
        this.showTouchscreenControls = showTouchscreenControls;
    }

    public int getPrimaryColor() {
        return Color.argb((int)(overlayOpacity * 255), 255, 255, 255);
    }

    public int getSecondaryColor() {
        return Color.argb((int)(overlayOpacity * 255), 2, 119, 189);
    }

    private synchronized ControlElement intersectElement(float x, float y) {
        if (profile != null) {
            for (ControlElement element : profile.getElements()) {
                if (element.containsPoint(x, y)) return element;
            }
        }
        return null;
    }

    public Paint getPaint() {
        return paint;
    }

    public Path getPath() {
        return path;
    }

    public TouchpadView getTouchpadView() {
        return touchpadView;
    }

    public void setTouchpadView(TouchpadView touchpadView) {
        this.touchpadView = touchpadView;
    }

    public XServer getXServer() {
        return xServer;
    }

    public void setXServer(XServer xServer) {
        this.xServer = xServer;
        createMouseMoveTimer();
    }

    public int getMaxWidth() {
        return (int)Mathf.roundTo(getWidth(), snappingSize);
    }

    @Override
    protected void onDetachedFromWindow() {
        cancelTouchRouting();
        if (mouseMoveTimer != null) {
            mouseMoveTimer.cancel();
            mouseMoveTimer = null; // a cancelled Timer cannot be reused; onAttachedToWindow recreates it
        }
        super.onDetachedFromWindow();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (xServer != null) createMouseMoveTimer();
    }

    public int getMaxHeight() {
        return (int)Mathf.roundTo(getHeight(), snappingSize);
    }

    private void createMouseMoveTimer() {
        if (profile != null && mouseMoveTimer == null) {
            final float cursorSpeed = profile.getCursorSpeed();
            mouseMoveTimer = new Timer();
            mouseMoveTimer.schedule(new TimerTask() {
                @Override
                public void run() {
                    // An exception would end the Timer thread for good and freeze touch mouse movement.
                    try {
                        XServer server = xServer;
                        int dx = (int)(mouseMoveOffset.x * 10 * cursorSpeed), dy = (int)(mouseMoveOffset.y * 10 * cursorSpeed);
                        // Nothing to move most of the time: a zero delta still cost Gamescope thread hops 60 times a second.
                        if (server != null && (dx != 0 || dy != 0)) server.injectPointerMoveDelta(dx, dy);
                    } catch (RuntimeException ignored) {
                    }
                }
            }, 0, 1000 / 60);
        }
    }

    private void processJoystickInput(ExternalController controller) {
        ExternalControllerBinding controllerBinding;
        final int[] axes = {MotionEvent.AXIS_X, MotionEvent.AXIS_Y, MotionEvent.AXIS_Z, MotionEvent.AXIS_RZ, MotionEvent.AXIS_HAT_X, MotionEvent.AXIS_HAT_Y};
        final float[] values = {controller.state.thumbLX, controller.state.thumbLY, controller.state.thumbRX, controller.state.thumbRY, controller.state.getDPadX(), controller.state.getDPadY()};

        for (byte i = 0; i < axes.length; i++) {
            if (Math.abs(values[i]) > ControlElement.STICK_DEAD_ZONE) {
                controllerBinding = controller.getControllerBinding(ExternalControllerBinding.getKeyCodeForAxis(axes[i], Mathf.sign(values[i])));
                if (controllerBinding != null) handleInputEvent(controllerBinding.getBindingCombo(), true, values[i]);
            }
            else {
                controllerBinding = controller.getControllerBinding(ExternalControllerBinding.getKeyCodeForAxis(axes[i], (byte) 1));
                if (controllerBinding != null) handleInputEvent(controllerBinding.getBindingCombo(), false, values[i]);
                controllerBinding = controller.getControllerBinding(ExternalControllerBinding.getKeyCodeForAxis(axes[i], (byte)-1));
                if (controllerBinding != null) handleInputEvent(controllerBinding.getBindingCombo(), false, values[i]);
            }
        }
    }


    @Override
    public boolean onGenericMotionEvent(MotionEvent event) {
        if (!editMode && profile != null) {
            ExternalController controller = profile.getController(event.getDeviceId());
            if (controller != null && controller.updateStateFromMotionEvent(event)) {
                ExternalControllerBinding controllerBinding;
                controllerBinding = controller.getControllerBinding(KeyEvent.KEYCODE_BUTTON_L2);
                if (controllerBinding != null) handleInputEvent(controllerBinding.getBindingCombo(), controller.state.isPressed(ExternalController.IDX_BUTTON_L2));

                controllerBinding = controller.getControllerBinding(KeyEvent.KEYCODE_BUTTON_R2);
                if (controllerBinding != null) handleInputEvent(controllerBinding.getBindingCombo(), controller.state.isPressed(ExternalController.IDX_BUTTON_R2));

                processJoystickInput(controller);
                return true;
            }
        }
        return super.onGenericMotionEvent(event);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (editMode && readyToDraw) {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN: {
                    float x = event.getX();
                    float y = event.getY();

                    ControlElement element = intersectElement(x, y);
                    moveCursor = true;
                    if (element != null) {
                        offsetX = x - element.getX();
                        offsetY = y - element.getY();
                        moveCursor = false;
                    }

                    selectElement(element);
                    break;
                }
                case MotionEvent.ACTION_MOVE: {
                    if (selectedElement != null) {
                        selectedElement.setX((int)Mathf.roundTo(event.getX() - offsetX, snappingSize));
                        selectedElement.setY((int)Mathf.roundTo(event.getY() - offsetY, snappingSize));
                        invalidate();
                    }
                    break;
                }
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL: {
                    if (selectedElement != null && profile != null) profile.save();
                    if (moveCursor) cursor.set((int)Mathf.roundTo(event.getX(), snappingSize), (int)Mathf.roundTo(event.getY(), snappingSize));
                    invalidate();
                    break;
                }
            }
        }

        if (!editMode && profile != null) {
            int actionIndex = event.getActionIndex();
            int pointerId = event.getPointerId(actionIndex);
            int actionMasked = event.getActionMasked();
            boolean handled = false;
            boolean touchscreenMode = touchpadView.isTouchscreenMode();
            if (touchscreenMode && lookThroughPointerState.isActive()) {
                cancelTouchRouting();
            }

            switch (actionMasked) {
                case MotionEvent.ACTION_DOWN:
                case MotionEvent.ACTION_POINTER_DOWN: {
                    float x = event.getX(actionIndex);
                    float y = event.getY(actionIndex);

                    touchpadView.setPointerButtonLeftEnabled(true);
                    touchpadView.setPointerButtonRightEnabled(true);
                    boolean lookThroughCandidate = false;
                    for (ControlElement element : profile.getElements()) {
                        if (element.handleTouchDown(pointerId, x, y)) {
                            performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY);
                            handled = true;
                            if (event.getToolType(actionIndex) == MotionEvent.TOOL_TYPE_FINGER
                                    && element.getType() == ControlElement.Type.BUTTON
                                    && !touchscreenMode
                                    && element.isLookThrough()) {
                                lookThroughCandidate = true;
                            }
                        }
                        if (element.getBindingAt(0) == Binding.MOUSE_LEFT_BUTTON) {
                            touchpadView.setPointerButtonLeftEnabled(false);
                        }
                        if (element.getBindingAt(0) == Binding.MOUSE_RIGHT_BUTTON) {
                            touchpadView.setPointerButtonRightEnabled(false);
                        }
                    }
                    if (lookThroughCandidate) {
                        lookThroughPointerState.tryStart(
                                pointerId,
                                x,
                                y,
                                touchpadPointers.size() > 0
                        );
                    }
                    if (!handled) {
                        if (!lookThroughPointerState.isActive()) {
                            touchpadPointers.put(pointerId, true);
                            touchpadView.onTouchEvent(event);
                        }
                    }
                    break;
                }
                case MotionEvent.ACTION_MOVE: {
                    for (byte i = 0, count = (byte)event.getPointerCount(); i < count; i++) {
                        float x = event.getX(i);
                        float y = event.getY(i);

                        handled = false;
                        int pid = event.getPointerId(i);
                        for (ControlElement element : profile.getElements()) {
                            if (element.handleTouchMove(pid, x, y)) handled = true;
                        }
                        if (lookThroughPointerState.owns(pid)) {
                            LookThroughPointerState.Delta delta = lookThroughPointerState.move(
                                    pid,
                                    x,
                                    y,
                                    lookThroughTouchSlop
                            );
                            if (delta != null) {
                                touchpadView.movePointerFromLookThrough(delta.x, delta.y);
                            }
                        }
                    }
                    if (touchpadPointers.size() > 0) {
                        touchpadView.onTouchEvent(event);
                    }
                    break;
                }
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_POINTER_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (actionMasked == MotionEvent.ACTION_CANCEL) {
                        cancelTouchRouting();
                        handled = true;
                        break;
                    }
                    for (ControlElement element : profile.getElements()) if (element.handleTouchUp(pointerId)) handled = true;
                    if (touchpadPointers.get(pointerId)) touchpadView.onTouchEvent(event);
                    lookThroughPointerState.release(pointerId);
                    touchpadPointers.delete(pointerId);
                    break;
            }

            // commit on-screen joystick state
            WinHandler winHandler = xServer != null ? xServer.getWinHandler() : null;
            if (winHandler != null) {
                GamepadState state = profile.getGamepadState();
                winHandler.sendGamepadState();
                winHandler.sendVirtualGamepadState(state);
            }
        }
        return true;
    }

    public boolean onKeyEvent(KeyEvent event) {
        if (profile != null && event.getRepeatCount() == 0) {
            ExternalController controller = profile.getController(event.getDeviceId());
            if (controller != null) {
                ExternalControllerBinding controllerBinding = controller.getControllerBinding(event.getKeyCode());
                if (controllerBinding != null) {
                    int action = event.getAction();
                    if (action == KeyEvent.ACTION_DOWN) {
                        handleInputEvent(controllerBinding.getBindingCombo(), true);
                    }
                    else if (action == KeyEvent.ACTION_UP) {
                        handleInputEvent(controllerBinding.getBindingCombo(), false);
                    }
                    return true;
                }
            }
        }
        return false;
    }

    public void handleInputEvent(Binding binding, boolean isActionDown) {
        handleInputEvent(binding, isActionDown, 0, binding);
    }

    public void handleInputEvent(BindingCombo bindingCombo, boolean isActionDown) {
        handleInputEvent(bindingCombo, isActionDown, 0, bindingCombo);
    }

    public void handleInputEvent(BindingCombo bindingCombo, boolean isActionDown, float offset) {
        handleInputEvent(bindingCombo, isActionDown, offset, bindingCombo);
    }

    public void handleInputEvent(BindingCombo bindingCombo, boolean isActionDown, float offset, Object source) {
        if (bindingCombo == null || bindingCombo.isEmpty()) return;
        if (bindingCombo.isSequence()) {
            if (isActionDown) {
                if (activeSequenceCombos.add(bindingCombo)) performBindingSequence(bindingCombo, offset);
            }
            else {
                activeSequenceCombos.remove(bindingCombo);
            }
            return;
        }

        if (isActionDown) {
            for (Binding binding : bindingCombo.getBindings()) {
                handleInputEvent(binding, true, offset, source);
            }
        }
        else {
            java.util.List<Binding> bindings = bindingCombo.getBindings();
            for (int i = bindings.size() - 1; i >= 0; i--) {
                handleInputEvent(bindings.get(i), false, offset, source);
            }
        }
    }

    private void performBindingSequence(BindingCombo bindingCombo, float offset) {
        final int generation = sequenceGeneration;
        final int pressDurationMs = Math.min(
                SEQUENCE_PRESS_MS,
                Math.max(1, bindingCombo.getSequenceDelayMs() - 1));
        long delay = 0;
        for (Binding binding : bindingCombo.getBindings()) {
            final Object sequenceSource = new Object();
            postDelayed(() -> {
                if (generation != sequenceGeneration) return;
                handleInputEvent(binding, true, offset, sequenceSource);
                activeSequenceBindings.merge(binding, 1, Integer::sum);
                activeSequenceSources.put(sequenceSource, binding);
                commitGamepadStateIfNeeded(binding);
                postDelayed(() -> {
                    if (generation != sequenceGeneration) return;
                    releaseActiveSequenceBinding(binding, sequenceSource);
                }, pressDurationMs);
            }, delay);
            delay += bindingCombo.getSequenceDelayMs();
        }
    }

    private void releaseActiveSequenceBinding(Binding binding, Object source) {
        Integer count = activeSequenceBindings.get(binding);
        if (count == null) return;
        activeSequenceSources.remove(source);

        if (count > 1) {
            activeSequenceBindings.put(binding, count - 1);
            return;
        }
        activeSequenceBindings.remove(binding);
        handleInputEvent(binding, false, 0, source);
        commitGamepadStateIfNeeded(binding);
    }

    private void cancelBindingSequences() {
        sequenceGeneration++;
        activeSequenceCombos.clear();
        if (activeSequenceBindings.isEmpty()) {
            activeSequenceSources.clear();
            return;
        }

        boolean gamepadStateChanged = false;
        Binding[] bindings = activeSequenceBindings.keySet().toArray(new Binding[0]);
        for (int i = bindings.length - 1; i >= 0; i--) {
            Binding binding = bindings[i];
            handleInputEvent(binding, false, 0, binding);
            gamepadStateChanged |= binding.isGamepad();
        }
        activeSequenceBindings.clear();
        activeSequenceSources.clear();
        if (gamepadStateChanged) commitGamepadState();
    }

    public void handleInputEvent(Binding binding, boolean isActionDown, float offset) {
        handleInputEvent(binding, isActionDown, offset, binding);
    }

    public void handleInputEvent(Binding binding, boolean isActionDown, float offset, Object source) {
        if (binding == null || binding == Binding.NONE) return;

        if (binding == Binding.OPEN_RADIAL_MENU) {
            if (radialMenuListener != null) radialMenuListener.onRadialMenuButtonStateChanged(isActionDown);
            return;
        }

        if (binding.isGamepad()) {
            if (profile == null) return;
            WinHandler winHandler = xServer != null ? xServer.getWinHandler() : null;
            GamepadState state = profile.getGamepadState();

            int buttonIdx = binding.ordinal() - Binding.GAMEPAD_BUTTON_A.ordinal();
            if (buttonIdx <= ExternalController.IDX_BUTTON_R2) {
                if (buttonIdx == ExternalController.IDX_BUTTON_L2) {
                    state.triggerL = isActionDown ? 1.0f : 0f;
                    state.setPressed(ExternalController.IDX_BUTTON_L2, isActionDown);
                } else if (buttonIdx == ExternalController.IDX_BUTTON_R2) {
                    state.triggerR = isActionDown ? 1.0f : 0f;
                    state.setPressed(ExternalController.IDX_BUTTON_R2, isActionDown);
                } else
                    state.setPressed(buttonIdx, isActionDown);
            }
            else if (binding == Binding.GAMEPAD_LEFT_THUMB_UP || binding == Binding.GAMEPAD_LEFT_THUMB_DOWN) {
                state.thumbLY = updateBaseStickValue(binding, isActionDown, offset);
            }
            else if (binding == Binding.GAMEPAD_LEFT_THUMB_LEFT || binding == Binding.GAMEPAD_LEFT_THUMB_RIGHT) {
                state.thumbLX = updateBaseStickValue(binding, isActionDown, offset);
            }
            else if (binding == Binding.GAMEPAD_RIGHT_THUMB_UP || binding == Binding.GAMEPAD_RIGHT_THUMB_DOWN) {
                state.thumbRY = updateBaseStickValue(binding, isActionDown, offset);
            }
            else if (binding == Binding.GAMEPAD_RIGHT_THUMB_LEFT || binding == Binding.GAMEPAD_RIGHT_THUMB_RIGHT) {
                state.thumbRX = updateBaseStickValue(binding, isActionDown, offset);
            }
            else if (binding == Binding.GAMEPAD_DPAD_UP || binding == Binding.GAMEPAD_DPAD_RIGHT ||
                     binding == Binding.GAMEPAD_DPAD_DOWN || binding == Binding.GAMEPAD_DPAD_LEFT) {
                state.dpad[binding.ordinal() - Binding.GAMEPAD_DPAD_UP.ordinal()] = isActionDown;
            }

            if (winHandler != null) {
                ExternalController controller = winHandler.getCurrentController();
                if (controller != null) controller.state.copy(state);
            }
        }
        else {
            if (binding == Binding.SHOW_KEYBOARD) {
                if (isActionDown) {
                    if (!showKeyboardPressed) {
                        showKeyboardPressed = true;
                        if (showKeyboardCallback != null) showKeyboardCallback.run();
                    }
                } else {
                    showKeyboardPressed = false;
                }
                return;
            }
            else if (binding == Binding.ALT_ENTER) {
                if (isActionDown) {
                    xServer.injectKeyPress(Binding.KEY_ALT_L.keycode);
                    xServer.injectKeyPress(Binding.KEY_ENTER.keycode);
                }
                else {
                    xServer.injectKeyRelease(Binding.KEY_ENTER.keycode);
                    xServer.injectKeyRelease(Binding.KEY_ALT_L.keycode);
                }
                return;
            }
            else if (binding == Binding.MOUSE_MOVE_LEFT || binding == Binding.MOUSE_MOVE_RIGHT) {
                mouseMoveOffset.x = isActionDown ? (offset != 0 ? offset : (binding == Binding.MOUSE_MOVE_LEFT ? -1 : 1)) : 0;
                if (isActionDown) createMouseMoveTimer();
            }
            else if (binding == Binding.MOUSE_MOVE_DOWN || binding == Binding.MOUSE_MOVE_UP) {
                mouseMoveOffset.y = isActionDown ? (offset != 0 ? offset : (binding == Binding.MOUSE_MOVE_UP ? -1 : 1)) : 0;
                if (isActionDown) createMouseMoveTimer();
            }
            else {
                Pointer.Button pointerButton = binding.getPointerButton();
                if (isActionDown) {
                    if (pointerButton != null) {
                        xServer.injectPointerButtonPress(pointerButton);
                    }
                    else binding.inject(xServer, true);
                }
                else {
                    if (pointerButton != null) {
                        xServer.injectPointerButtonRelease(pointerButton);
                    }
                    else binding.inject(xServer, false);
                }
            }
        }
    }

    /** Updates and returns the active stick contribution. */
    public synchronized float updateBaseStickValue(Binding binding, boolean isActionDown, float offset) {
        float base;
        switch (binding) {
            case GAMEPAD_LEFT_THUMB_UP:
            case GAMEPAD_LEFT_THUMB_DOWN:
                baseThumbLY = updateBaseAxis(baseThumbLY, binding == Binding.GAMEPAD_LEFT_THUMB_UP,
                        isActionDown, offset);
                base = baseThumbLY;
                break;
            case GAMEPAD_LEFT_THUMB_LEFT:
            case GAMEPAD_LEFT_THUMB_RIGHT:
                baseThumbLX = updateBaseAxis(baseThumbLX, binding == Binding.GAMEPAD_LEFT_THUMB_LEFT,
                        isActionDown, offset);
                base = baseThumbLX;
                break;
            case GAMEPAD_RIGHT_THUMB_UP:
            case GAMEPAD_RIGHT_THUMB_DOWN:
                baseThumbRY = updateBaseAxis(baseThumbRY, binding == Binding.GAMEPAD_RIGHT_THUMB_UP,
                        isActionDown, offset);
                base = baseThumbRY;
                break;
            case GAMEPAD_RIGHT_THUMB_LEFT:
            case GAMEPAD_RIGHT_THUMB_RIGHT:
                baseThumbRX = updateBaseAxis(baseThumbRX, binding == Binding.GAMEPAD_RIGHT_THUMB_LEFT,
                        isActionDown, offset);
                base = baseThumbRX;
                break;
            default:
                return 0f;
        }
        return base;
    }

    /** Records a physical stick contribution using the source axis' raw direction. */
    public synchronized float updatePhysicalStickValue(
            Binding binding, boolean isActionDown, float offset, int sourceKeyCode) {
        float base;
        switch (binding) {
            case GAMEPAD_LEFT_THUMB_UP:
            case GAMEPAD_LEFT_THUMB_DOWN:
                baseThumbLY = updatePhysicalBaseAxis(baseThumbLY, isActionDown, offset, sourceKeyCode);
                base = baseThumbLY;
                break;
            case GAMEPAD_LEFT_THUMB_LEFT:
            case GAMEPAD_LEFT_THUMB_RIGHT:
                baseThumbLX = updatePhysicalBaseAxis(baseThumbLX, isActionDown, offset, sourceKeyCode);
                base = baseThumbLX;
                break;
            case GAMEPAD_RIGHT_THUMB_UP:
            case GAMEPAD_RIGHT_THUMB_DOWN:
                baseThumbRY = updatePhysicalBaseAxis(baseThumbRY, isActionDown, offset, sourceKeyCode);
                base = baseThumbRY;
                break;
            case GAMEPAD_RIGHT_THUMB_LEFT:
            case GAMEPAD_RIGHT_THUMB_RIGHT:
                baseThumbRX = updatePhysicalBaseAxis(baseThumbRX, isActionDown, offset, sourceKeyCode);
                base = baseThumbRX;
                break;
            default:
                return 0f;
        }
        return base;
    }

    private static float updateBaseAxis(float current, boolean negativeDirection, boolean isActionDown, float offset) {
        if (isActionDown) return offset;
        if (current == 0f) return 0f;
        boolean currentIsNegative = current < 0f;
        return currentIsNegative == negativeDirection ? 0f : current;
    }

    public static float updatePhysicalBaseAxis(float current, boolean isActionDown, float offset, int sourceKeyCode) {
        if (isActionDown) return offset;
        if (current == 0f) return 0f;
        int sourceDirection = getPhysicalSourceDirection(sourceKeyCode);
        if (sourceDirection == 0) return 0f;
        return (current < 0f) == (sourceDirection < 0) ? 0f : current;
    }

    private static int getPhysicalSourceDirection(int sourceKeyCode) {
        switch (sourceKeyCode) {
            case ExternalControllerBinding.AXIS_X_NEGATIVE:
            case ExternalControllerBinding.AXIS_Y_POSITIVE:
            case ExternalControllerBinding.AXIS_Z_NEGATIVE:
            case ExternalControllerBinding.AXIS_RZ_POSITIVE:
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_UP:
                return -1;
            case ExternalControllerBinding.AXIS_X_POSITIVE:
            case ExternalControllerBinding.AXIS_Y_NEGATIVE:
            case ExternalControllerBinding.AXIS_Z_POSITIVE:
            case ExternalControllerBinding.AXIS_RZ_NEGATIVE:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_DPAD_DOWN:
                return 1;
            default:
                return 0;
        }
    }

    private synchronized void resetThumbContributions() {
        ControlsProfile outgoingProfile = activeProfile;
        baseThumbLX = 0f;
        baseThumbLY = 0f;
        baseThumbRX = 0f;
        baseThumbRY = 0f;

        if (outgoingProfile == null) return;
        GamepadState state = outgoingProfile.getGamepadState();
        state.thumbLX = 0f;
        state.thumbLY = 0f;
        state.thumbRX = 0f;
        state.thumbRY = 0f;

        WinHandler winHandler = xServer != null ? xServer.getWinHandler() : null;
        if (winHandler != null) {
            winHandler.sendGamepadState();
            winHandler.sendVirtualGamepadState(state);
        }
    }

    public Bitmap getIcon(byte id) {
        if (icons[id] == null) {
            Context context = getContext();
            try (InputStream is = context.getAssets().open("inputcontrols/icons/"+id+".png")) {
                icons[id] = BitmapFactory.decodeStream(is);
            }
            catch (IOException e) {}
        }
        return icons[id];
    }
}
