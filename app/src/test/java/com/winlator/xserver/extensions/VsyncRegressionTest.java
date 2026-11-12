package com.winlator.xserver.extensions;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Field;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

public class VsyncRegressionTest {
    private static final long START_NS = 1_000_000_000L;
    private static final long PERIOD_60_NS = 16_666_667L;
    private static final long PERIOD_120_NS = 8_333_333L;

    @Test public void initialIdleGapDoesNotTurnSixtyHertzIntoThirtyHertz() {
        var core = new VsyncPacingCore<Integer>();
        core.setLimit(60);
        core.schedule(1, 1, false);
        assertEquals(List.of(1), core.onVsync(START_NS));
        // The Android frame callback stops until another Present arrives. The panel keeps
        // refreshing while no callback is posted. Two new pixmaps arrive before it resumes.
        core.idle();
        core.schedule(1, 2, false);
        core.schedule(1, 3, false);
        assertEquals(List.of(2), core.onVsync(START_NS + 2 * PERIOD_60_NS));
        assertEquals(PERIOD_60_NS, core.periodNs());
        assertEquals(List.of(3), core.onVsync(START_NS + 3 * PERIOD_60_NS));
    }

    @Test public void missedCallbacksPreserveThirtyFpsCadenceOnSixtyHertz() {
        assertHalfRefreshCadenceAfterMiss(30, PERIOD_60_NS);
    }

    @Test public void missedCallbacksPreserveSixtyFpsCadenceOnOneTwentyHertz() {
        assertHalfRefreshCadenceAfterMiss(60, PERIOD_120_NS);
    }

    private static void assertHalfRefreshCadenceAfterMiss(int limit, long period) {
        var core = new VsyncPacingCore<Integer>();
        core.setLimit(limit);
        for (int i = 0; i < 12; i++) {
            core.onVsync(START_NS + i * period);
        }
        for (int serial = 1; serial <= 4; serial++) {
            core.schedule(1, serial, false);
        }

        long first = START_NS + 12 * period;
        assertEquals(List.of(1), core.onVsync(first));
        assertTrue(core.onVsync(first + period).isEmpty());
        // The Looper misses two physical refreshes while more pixmaps are queued.
        assertEquals(List.of(2), core.onVsync(first + 4 * period));
        assertEquals("No second release after just one physical refresh", List.of(),
            core.onVsync(first + 5 * period));
        assertEquals(List.of(3), core.onVsync(first + 6 * period));
    }

    @Test public void disablingLimiterReleasesOlderPhaseBatchBeforeNewerCoreBatch() throws Exception {
        List<Integer> delivered = new ArrayList<>();
        var pacer = new VsyncFramePacer<Integer>(delivered::add);
        pacer.setFrameRateLimit(60);
        var deferred = queuePhaseDelayedPixmaps(pacer);

        pacer.setFrameRateLimit(0);

        assertEquals(List.of(10, 11), delivered);
        assertTrue("The posted phase callback can no longer release the drained batch", deferred.isEmpty());
    }

    @Test public void settingSameLimitPreservesThePendingPhaseAndCoreBatches() throws Exception {
        List<Integer> delivered = new ArrayList<>();
        var pacer = new VsyncFramePacer<Integer>(delivered::add);
        pacer.setFrameRateLimit(60);
        var deferred = queuePhaseDelayedPixmaps(pacer);

        pacer.setFrameRateLimit(60);

        assertEquals(List.of(), delivered);
        assertEquals(List.of(10), deferred.peek());
        pacer.releaseAllAndClose();
        assertEquals(List.of(10, 11), delivered);
    }

    @SuppressWarnings("unchecked")
    private static ArrayDeque<List<Integer>> queuePhaseDelayedPixmaps(VsyncFramePacer<Integer> pacer)
            throws ReflectiveOperationException {
        // Prepare the state between doFrame reserving a release and the delayed Handler running.
        // The test avoids sleeps and does not start an Android Looper on the host.
        Field coreField = VsyncFramePacer.class.getDeclaredField("core");
        coreField.setAccessible(true);
        var core = (VsyncPacingCore<Integer>) coreField.get(pacer);
        Field deferredField = VsyncFramePacer.class.getDeclaredField("deferred");
        deferredField.setAccessible(true);
        var deferred = (ArrayDeque<List<Integer>>) deferredField.get(pacer);
        core.schedule(1, 10, false);
        deferred.add(core.onVsync(START_NS));
        core.schedule(1, 11, false);
        return deferred;
    }
}
