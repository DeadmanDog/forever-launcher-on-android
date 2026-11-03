package com.winlator.widget;

import android.content.Context;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;


import app.gamenative.R;

import java.util.Locale;

public class FrameRating extends FrameLayout implements Runnable {
    private final java.util.concurrent.atomic.AtomicBoolean uiUpdatePending = new java.util.concurrent.atomic.AtomicBoolean();
    private final FpsCounter counter = new FpsCounter();
    private final TextView textView;

    public FrameRating(Context context) {
        this(context, null);
    }

    public FrameRating(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public FrameRating(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);

        View view = LayoutInflater.from(context).inflate(R.layout.frame_rating, this, false);
        textView = view.findViewById(R.id.TVFPS);
        addView(view);
    }

    // Rolling frame-time statistics for the HUD (frame time, 1% / 0.1% low FPS).
    private final app.gamenative.ui.widget.FrameTimeStats frameStats = new app.gamenative.ui.widget.FrameTimeStats();

    public void update() {
        updateNanos(SystemClock.elapsedRealtimeNanos());
    }

    /** Null while there are too few frames in the window or the game is not presenting. */
    public app.gamenative.ui.widget.FrameTimeReading getFrameStats() {
        return frameStats.reading(SystemClock.elapsedRealtimeNanos());
    }

    private static volatile int limitHint = 0;

    /** The FPS limit in force (0 = none); lets the readout absorb buffer bursts that are never displayed. */
    public static void setLimitHint(int fps) { limitHint = Math.max(0, fps); }

    /** One frame, timestamped with {@link SystemClock#elapsedRealtimeNanos()} (the same clock {@link #update()} uses). */
    public void updateNanos(long timeNs) {
        frameStats.record(timeNs);
        process(timeNs / 1_000_000L);
    }

    /** Millisecond timestamp ({@link SystemClock#elapsedRealtime()}): kept for callers without a finer clock; the low-FPS statistics lose precision. */
    public void update(long time) {
        frameStats.record(time * 1_000_000L);
        process(time);
    }

    private synchronized void process(long time) {
        if (counter.onFrame(time, limitHint) && uiUpdatePending.compareAndSet(false, true) && !post(this)) uiUpdatePending.set(false);
    }

    public synchronized void reset() {
        frameStats.reset();
        counter.reset();
        post(() -> textView.setText(String.format(Locale.ENGLISH, "%.1f", 0f)));
    }

    /** The most recent measured FPS for the active session; 0 while no frame has arrived for a while (a frozen or paused game). */
    public synchronized float getCurrentFPS() {
        return counter.fps(SystemClock.elapsedRealtime());
    }

    @Override
    public void run() {
        uiUpdatePending.set(false);
        if (getVisibility() == GONE) setVisibility(View.VISIBLE);
        textView.setText(String.format(Locale.ENGLISH, "%.1f", getCurrentFPS()));
    }
}
