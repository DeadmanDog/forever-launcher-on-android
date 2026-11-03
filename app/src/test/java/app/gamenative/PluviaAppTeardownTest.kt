package app.gamenative

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PluviaAppTeardownTest {
    @Before fun alive() { PluviaApp.keepAlive = true }
    @After fun reset() { PluviaApp.awaitTeardown(5_000); PluviaApp.keepAlive = false }

    @Test fun theSessionStaysAliveUntilTheBackgroundWorkIsDone() {
        val release = CountDownLatch(1)
        PluviaApp.runTeardownInBackground { release.await(5, TimeUnit.SECONDS) }
        assertTrue(PluviaApp.isTearingDown())
        assertTrue("still alive while the wineserver is being asked to exit", PluviaApp.keepAlive)
        assertFalse("a new launch has to wait", PluviaApp.awaitTeardown(50))
        release.countDown()
        assertTrue(PluviaApp.awaitTeardown(5_000))
        assertFalse(PluviaApp.isTearingDown())
        assertFalse(PluviaApp.keepAlive)
    }

    @Test fun aFailingTeardownStillReleasesTheSession() {
        PluviaApp.runTeardownInBackground { throw IllegalStateException("wineserver gone") }
        assertTrue(PluviaApp.awaitTeardown(5_000))
        assertFalse(PluviaApp.keepAlive)
    }

    @Test fun overlappingTeardownsReleaseOnlyWhenTheLastOneIsDone() {
        val first = CountDownLatch(1)
        val second = CountDownLatch(1)
        PluviaApp.runTeardownInBackground { first.await(5, TimeUnit.SECONDS) }
        PluviaApp.runTeardownInBackground { second.await(5, TimeUnit.SECONDS) }
        first.countDown()
        assertFalse(PluviaApp.awaitTeardown(100))
        assertTrue(PluviaApp.keepAlive)
        second.countDown()
        assertTrue(PluviaApp.awaitTeardown(5_000))
        assertFalse(PluviaApp.keepAlive)
    }

    @Test fun awaitingWithoutAnyTeardownReturnsAtOnce() {
        val start = System.nanoTime()
        assertTrue(PluviaApp.awaitTeardown(10_000))
        assertEquals(true, (System.nanoTime() - start) < TimeUnit.SECONDS.toNanos(1))
    }

    @Test fun anAwaitingThreadIsWokenWhenTheWorkEnds() {
        val release = CountDownLatch(1)
        PluviaApp.runTeardownInBackground { release.await(5, TimeUnit.SECONDS) }
        var woken = false
        val waiter = Thread { woken = PluviaApp.awaitTeardown(5_000) }
        waiter.start()
        Thread.sleep(50)
        release.countDown()
        waiter.join(5_000)
        assertTrue(woken)
    }
}
