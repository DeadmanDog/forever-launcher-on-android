package com.winlator.renderer

import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * AUDIT13-APP-02: the renderer must register its FrameTrace listener before it reads the current value; otherwise a diagnostics
 * session switching the trace on in between reaches nobody and frames.csv stays empty for the whole session.
 */
class FrameTraceTest {
    @After fun reset() { FrameTrace.setListener(null); FrameTrace.set(false) }

    @Test fun aSwitchBetweenListeningAndReadingIsNotLost() {
        var native = false
        FrameTrace.setListener { native = it }       // VulkanRenderer init, in its order
        FrameTrace.set(true)                          // diagnostics start on another thread right now
        native = FrameTrace.enabled()
        assertTrue(native)
    }

    @Test fun rendererInitListensBeforeReading() {
        val source = File("src/main/java/com/winlator/renderer/VulkanRenderer.java").readText()
        val listen = source.indexOf("FrameTrace.setListener(this::applyFrameTrace);")
        val read = source.indexOf("nativeSetFrameTrace(nativeHandle, FrameTrace.enabled());")
        assertTrue("listener at $listen must come before the read at $read", listen in 0 until read)
    }
}
