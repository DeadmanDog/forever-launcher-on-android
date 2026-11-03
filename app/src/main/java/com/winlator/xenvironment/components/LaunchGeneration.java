package com.winlator.xenvironment.components;

import java.util.concurrent.atomic.AtomicLong;

/** A terminated process can only affect the launch generation that created it. */
public final class LaunchGeneration {
    private final AtomicLong generation = new AtomicLong();
    public long invalidate() { return generation.incrementAndGet(); }
    public long current() { return generation.get(); }
    public boolean isCurrent(long token) { return generation.get() == token; }
}
