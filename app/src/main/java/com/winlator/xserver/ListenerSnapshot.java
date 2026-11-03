package com.winlator.xserver;

import java.util.Arrays;
import java.util.Objects;
import java.util.function.IntFunction;

/** Stable dispatch snapshots; copy only when listeners change, never per input/frame event. */
final class ListenerSnapshot<T> {
    private volatile T[] listeners;

    ListenerSnapshot(IntFunction<T[]> arrayFactory) {
        listeners = arrayFactory.apply(0);
    }

    synchronized void add(T listener) {
        T[] next = Arrays.copyOf(listeners, listeners.length + 1);
        next[next.length - 1] = listener;
        listeners = next;
    }

    synchronized void remove(T listener) {
        T[] current = listeners;
        for (int i = 0; i < current.length; i++) {
            if (Objects.equals(current[i], listener)) {
                T[] next = Arrays.copyOf(current, current.length - 1);
                System.arraycopy(current, i + 1, next, i, next.length - i);
                listeners = next;
                return;
            }
        }
    }

    /** Callers read once and iterate; they must not mutate the returned array. */
    T[] snapshot() {
        return listeners;
    }
}
