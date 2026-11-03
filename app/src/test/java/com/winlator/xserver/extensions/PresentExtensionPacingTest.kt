package com.winlator.xserver.extensions

import com.winlator.xserver.Pixmap
import com.winlator.xserver.Window
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class PresentExtensionPacingTest {
    @Test fun disablingCapTriggersEveryHeldIdleFenceAndTheExtensionCanRestartAfterClose() {
        val extension = PresentExtension()
        val sync = mock(SyncExtension::class.java)
        val tokens = (1..4).associateWith { Any() }
        doAnswer { tokens[it.getArgument<Int>(0)] }.`when`(sync).capturePresentFence(anyInt())
        val triggered = AtomicInteger()
        val first = CountDownLatch(3)
        val restarted = CountDownLatch(1)
        doAnswer { invocation ->
            triggered.incrementAndGet()
            if (invocation.getArgument<Int>(0) == 4) restarted.countDown() else first.countDown()
            null
        }.`when`(sync).triggerPresentFence(anyInt(), any())
        PresentExtension::class.java.getDeclaredField("syncExtension").apply { isAccessible = true }.set(extension, sync)
        val schedule = PresentExtension::class.java.getDeclaredMethod("scheduleIdleNotify",
            Window::class.java, Pixmap::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            .apply { isAccessible = true }
        val window = Window(7, null, 0, 0, 0, 0, null)
        extension.setFrameRateLimit(1)
        try {
            for (id in 1..3) schedule.invoke(extension, window, null, id, id)
            extension.setFrameRateLimit(0)
            assertTrue(first.await(2, TimeUnit.SECONDS))
            assertEquals(3, triggered.get())
            for (id in 1..3) verify(sync, times(1)).triggerPresentFence(eq(id), same(tokens[id]))
            extension.close()
            extension.resetPacing()
            extension.setFrameRateLimit(60)
            schedule.invoke(extension, window, null, 4, 4)
            assertTrue(restarted.await(2, TimeUnit.SECONDS))
            verify(sync, times(1)).triggerPresentFence(eq(4), same(tokens[4]))
        } finally {
            extension.close()
        }
    }

    @Test fun theBackgroundUsesTheTimerPacerAndTheForegroundGetsVsyncBack() {
        // In the background the display may send the app no vsync: a vsync-paced game would wait for its buffers and lose its server.
        val extension = PresentExtension()
        val pacer = PresentExtension::class.java.getDeclaredField("idlePacer").apply { isAccessible = true }
        try {
            extension.setVsyncPacing(true)
            assertTrue(pacer.get(extension) is VsyncFramePacer<*>)
            extension.setBackground(true)
            assertTrue(pacer.get(extension) is PresentFramePacer<*>)
            extension.setVsyncPacing(false); extension.setVsyncPacing(true)   // a settings change in the background keeps the timer
            assertTrue(pacer.get(extension) is PresentFramePacer<*>)
            extension.setBackground(false)
            assertTrue(pacer.get(extension) is VsyncFramePacer<*>)
        } finally {
            extension.close()
        }
    }

    @Test fun switchingThePacingModeReleasesEveryHeldBufferImmediately() {
        val extension = PresentExtension()
        val sync = mock(SyncExtension::class.java)
        doAnswer { Any() }.`when`(sync).capturePresentFence(anyInt())
        val released = AtomicInteger()
        doAnswer { released.incrementAndGet(); null }.`when`(sync).triggerPresentFence(anyInt(), any())
        PresentExtension::class.java.getDeclaredField("syncExtension").apply { isAccessible = true }.set(extension, sync)
        val schedule = PresentExtension::class.java.getDeclaredMethod("scheduleIdleNotify",
            Window::class.java, Pixmap::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            .apply { isAccessible = true }
        val window = Window(7, null, 0, 0, 0, 0, null)
        extension.setFrameRateLimit(1)
        try {
            for (id in 1..3) schedule.invoke(extension, window, null, id, id)
            // The 1 FPS timer still holds two of them; the switch must hand them back at once.
            extension.setVsyncPacing(true)
            // The timer worker may still be handing out the first (already due) one.
            val deadline = System.nanoTime() + 2_000_000_000L
            while (released.get() < 3 && System.nanoTime() < deadline) Thread.sleep(5)
            assertEquals(3, released.get())
            extension.setVsyncPacing(true)
            extension.setVsyncPacing(false)
            assertEquals(3, released.get())
        } finally {
            extension.close()
        }
    }

    @Test fun actualExtensionDelayedCallbackPreservesFenceIdentityAcrossReuse() {
        val extension = PresentExtension()
        val sync = SyncExtension()
        @Suppress("UNCHECKED_CAST")
        val table = SyncExtension::class.java.getDeclaredField("fences").apply { isAccessible = true }
            .get(sync) as SyncFenceTable<Any>
        PresentExtension::class.java.getDeclaredField("syncExtension").apply { isAccessible = true }.set(extension, sync)
        val pacerField = PresentExtension::class.java.getDeclaredField("idlePacer").apply { isAccessible = true }
        val old = pacerField.get(extension)
        @Suppress("UNCHECKED_CAST")
        val release = PresentFramePacer::class.java.getDeclaredField("release").apply { isAccessible = true }
            .get(old) as java.util.function.Consumer<Any>
        val time = java.util.concurrent.atomic.AtomicLong(1_000_000_000L)
        val manual = PresentFramePacer(release, { time.get() }, false)
        pacerField.set(extension, manual)
        val schedule = PresentExtension::class.java.getDeclaredMethod("scheduleIdleNotify",
            Window::class.java, Pixmap::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            .apply { isAccessible = true }
        val owner = Any()
        val window = Window(7, null, 0, 0, 0, 0, null)
        table.create(1, owner, false)
        extension.setFrameRateLimit(1)
        try {
            schedule.invoke(extension, window, null, 1, 1)
            schedule.invoke(extension, window, null, 2, 1)
            manual.drainDue()
            assertTrue(table.isTriggered(1))
            table.destroy(1)
            table.create(1, owner, false)
            var wakes = 0
            table.await(Any(), intArrayOf(1)) { Runnable { wakes++ } }
            time.addAndGet(1_000_000_000L)
            manual.drainDue()
            assertFalse(table.isTriggered(1))
            assertEquals(0, wakes)
        } finally { extension.close() }
    }

}
