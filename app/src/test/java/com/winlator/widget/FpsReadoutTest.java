package com.winlator.widget;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class FpsReadoutTest {
    @Test public void withoutLimitNothingChanges() {
        assertEquals(71.3f, FpsReadout.forLimit(71.3f, 0), 0f);
    }

    @Test public void burstReadingsSlightlyAboveTheLimitShowTheLimit() {
        assertEquals(60f, FpsReadout.forLimit(66f, 60), 0f);
        assertEquals(60f, FpsReadout.forLimit(60.4f, 60), 0f);
        assertEquals(60f, FpsReadout.forLimit(67.2f, 60), 0f);
        assertEquals(60f, FpsReadout.forLimit(68.5f, 60), 0f);
    }

    @Test public void readingsAtOrBelowTheLimitAreNeverChanged() {
        assertEquals(40f, FpsReadout.forLimit(40f, 60), 0f);
        assertEquals(59.5f, FpsReadout.forLimit(59.5f, 60), 0f);
        assertEquals(60f, FpsReadout.forLimit(60f, 60), 0f);
    }

    @Test public void aLimiterThatIsReallyNotWorkingStaysVisible() {
        assertEquals(90f, FpsReadout.forLimit(90f, 60), 0f);
        assertEquals(75f, FpsReadout.forLimit(75f, 60), 0f);
    }
}
