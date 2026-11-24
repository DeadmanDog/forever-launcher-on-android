package com.winlator.widget;

import java.util.Timer;
import java.util.TimerTask;

/**
 * 1.2.23: the on-screen controls' mouse-move ticker (a stick or button bound to mouse movement moves the pointer 60 times a
 * second). Until 1.2.22 its timer started when the controls were attached and ran for the whole session, waking a thread about
 * 62 times a second even when nothing was bound or held. It now runs only while there is movement: it stops on the first tick
 * without movement, and the next press starts it again. The check before stopping is made under the same lock as start(), so a
 * press that comes while it stops is never lost.
 */
final class MouseMoveTicker {
    interface Source {
        /** True while a mouse-move binding is held (offset not zero). */
        boolean moving();
        /** One step of movement. */
        void move();
    }

    static final long PERIOD_MS = 1000 / 60;

    private final Object lock = new Object();
    private final Source source;
    private final long periodMs;
    private Timer timer;
    private int starts;

    MouseMoveTicker(Source source) { this(source, PERIOD_MS); }

    MouseMoveTicker(Source source, long periodMs) {
        this.source = source;
        this.periodMs = periodMs;
    }

    /** Called after the offset was set (a press): starts the ticker unless it already runs. */
    void start() {
        synchronized (lock) {
            if (timer != null) return;
            final Timer t = new Timer("TouchMouseMove", true);
            timer = t;
            starts++;
            t.schedule(new TimerTask() {
                @Override public void run() {
                    // An exception would end the Timer thread for good and freeze touch mouse movement.
                    try {
                        if (source.moving()) { source.move(); return; }
                    } catch (RuntimeException ignored) {
                        return;
                    }
                    synchronized (lock) {
                        if (timer != t || source.moving()) return;
                        t.cancel();
                        timer = null;
                    }
                }
            }, 0, periodMs);
        }
    }

    void stop() {
        synchronized (lock) {
            if (timer == null) return;
            timer.cancel();
            timer = null;
        }
    }

    boolean running() { synchronized (lock) { return timer != null; } }

    int starts() { synchronized (lock) { return starts; } }
}
