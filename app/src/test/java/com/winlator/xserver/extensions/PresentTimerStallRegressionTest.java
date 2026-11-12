package com.winlator.xserver.extensions;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.assertEquals;

public class PresentTimerStallRegressionTest {
    private static final long PERIOD_NS = 1_000_000_000L / 60;

    @Test public void overdueBuffersDoNotAllLeaveOnTheSameWakeup() {
        AtomicLong now = new AtomicLong(1_000_000_000L);
        List<Integer> released = new ArrayList<>();
        try (PresentFramePacer<Integer> pacer = new PresentFramePacer<>(released::add, now::get, false)) {
            pacer.setFrameRateLimit(60);
            pacer.schedule(1, 1, false);
            pacer.schedule(1, 2, false);
            pacer.schedule(1, 3, false);
            pacer.schedule(2, 4, false);

            now.addAndGet(3 * PERIOD_NS);
            pacer.drainDue();
            assertEquals("a delayed timer returns at most one paced buffer per window on wakeup",
                    List.of(1, 4), released);

            now.addAndGet(PERIOD_NS - 1);
            pacer.drainDue();
            assertEquals(List.of(1, 4), released);

            now.incrementAndGet();
            pacer.drainDue();
            assertEquals(List.of(1, 4, 2), released);

            now.addAndGet(PERIOD_NS);
            pacer.drainDue();
            assertEquals(List.of(1, 4, 2, 3), released);
        }
    }

    @Test public void overdueBuffersFlushedByACapChangeAreStillReturnedTogether() {
        AtomicLong now = new AtomicLong(1_000_000_000L);
        List<Integer> released = new ArrayList<>();
        try (PresentFramePacer<Integer> pacer = new PresentFramePacer<>(released::add, now::get, false)) {
            pacer.setFrameRateLimit(60);
            pacer.schedule(1, 1, false);
            pacer.schedule(1, 2, false);
            pacer.setFrameRateLimit(30);
            pacer.schedule(1, 3, false);
            pacer.schedule(1, 4, false);

            now.addAndGet(3 * PERIOD_NS);
            pacer.drainDue();
            assertEquals(List.of(1, 2, 3), released);
        }
    }
}
