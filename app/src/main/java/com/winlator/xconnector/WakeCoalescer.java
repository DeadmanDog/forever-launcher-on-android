package com.winlator.xconnector;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * One wake-up signal for many queued tasks (2026-11-12 review). A producer adds its task first and then calls {@link #shouldSignal()};
 * the consumer calls {@link #consumed()} after it read the signal and before it drains the queue. A task added after that call
 * signals again; one added before it is drained in the same round, so no task waits without a wake-up.
 */
final class WakeCoalescer {
    private final AtomicBoolean signalled = new AtomicBoolean();

    boolean shouldSignal() { return signalled.compareAndSet(false, true); }

    void consumed() { signalled.set(false); }
}
