package com.winlator.xserver.extensions;

import org.junit.Test;
import org.mockito.MockedConstruction;

import java.lang.reflect.Field;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.verify;

public class PresentPacerPublicationRegressionTest {
    private static Field activePacer() throws NoSuchFieldException {
        Field field = PresentExtension.class.getDeclaredField("idlePacer");
        field.setAccessible(true);
        return field;
    }

    @Test public void switchingPacingModeDoesNotPublishAnUncappedPacer() throws Exception {
        PresentExtension extension = new PresentExtension();
        try {
            extension.setFrameRateLimit(60);
            Field active = activePacer();
            Object old = active.get(extension);
            try (MockedConstruction<VsyncFramePacer> created = mockConstruction(VsyncFramePacer.class,
                    (pacer, context) -> doAnswer(call -> {
                        assertEquals(60, (int) call.getArgument(0));
                        assertSame("a Present racing with the switch still sees the capped old pacer",
                                old, active.get(extension));
                        return null;
                    }).when(pacer).setFrameRateLimit(anyInt()))) {
                extension.setVsyncPacing(true);
                VsyncFramePacer<?> replacement = created.constructed().get(0);
                verify(replacement).setFrameRateLimit(60);
                assertSame(replacement, active.get(extension));
            }
        } finally {
            extension.close();
        }
    }

    @Test public void resettingPacingDoesNotPublishAnUncappedPacer() throws Exception {
        PresentExtension extension = new PresentExtension();
        try {
            extension.setFrameRateLimit(60);
            Field active = activePacer();
            Object old = active.get(extension);
            try (MockedConstruction<PresentFramePacer> created = mockConstruction(PresentFramePacer.class,
                    (pacer, context) -> doAnswer(call -> {
                        assertEquals(60, (int) call.getArgument(0));
                        assertSame(old, active.get(extension));
                        return null;
                    }).when(pacer).setFrameRateLimit(anyInt()))) {
                extension.resetPacing();
                PresentFramePacer<?> replacement = created.constructed().get(0);
                verify(replacement).setFrameRateLimit(60);
                assertSame(replacement, active.get(extension));
            }
        } finally {
            extension.close();
        }
    }
}
