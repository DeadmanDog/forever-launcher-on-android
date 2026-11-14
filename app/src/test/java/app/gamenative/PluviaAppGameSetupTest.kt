package app.gamenative

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Review 2026-11-13: an aborted first-boot setup must end before the next one touches the prefix. */
class PluviaAppGameSetupTest {
    @After fun reset() {
        while (PluviaApp.isGameSetupRunning()) PluviaApp.endGameSetup()
        PluviaApp.keepAlive = false
    }

    @Test fun aLaunchWaitsForASetupThatIsStillRunning() {
        PluviaApp.beginGameSetup()
        assertTrue(PluviaApp.isGameSetupRunning())
        assertFalse("a new launch has to wait", PluviaApp.awaitGameSetup(50))
        PluviaApp.endGameSetup()
        assertTrue(PluviaApp.awaitGameSetup(1_000))
        assertFalse(PluviaApp.isGameSetupRunning())
    }

    @Test fun aWaitingLaunchIsWokenWhenTheSetupEnds() {
        PluviaApp.beginGameSetup()
        var woken = false
        val waiter = Thread { woken = PluviaApp.awaitGameSetup(5_000) }
        waiter.start()
        Thread.sleep(50)
        PluviaApp.endGameSetup()
        waiter.join(5_000)
        assertTrue(woken)
    }

    @Test fun theSetupCountNeverTouchesKeepAlive() {
        PluviaApp.keepAlive = true
        PluviaApp.beginGameSetup()
        PluviaApp.endGameSetup()
        assertTrue(PluviaApp.keepAlive)
        PluviaApp.keepAlive = false
        PluviaApp.beginGameSetup()
        PluviaApp.endGameSetup()
        assertFalse(PluviaApp.keepAlive)
    }

    @Test fun anExtraEndDoesNotLetALaterSetupPassUnnoticed() {
        PluviaApp.endGameSetup()
        PluviaApp.beginGameSetup()
        assertTrue(PluviaApp.isGameSetupRunning())
    }

    @Test fun twoSetupsNeverRunAtTheSameTime() {
        val inside = AtomicInteger()
        val most = AtomicInteger()
        val firstIn = CountDownLatch(1)
        val release = CountDownLatch(1)
        val first = Thread {
            PluviaApp.runGameSetupExclusively(5_000) {
                most.accumulateAndGet(inside.incrementAndGet(), ::maxOf)
                firstIn.countDown()
                release.await(5, TimeUnit.SECONDS)
                inside.decrementAndGet()
            }
        }
        first.start()
        assertTrue(firstIn.await(5, TimeUnit.SECONDS))
        var secondRan = false
        val second = Thread {
            secondRan = PluviaApp.runGameSetupExclusively(5_000) { most.accumulateAndGet(inside.incrementAndGet(), ::maxOf); inside.decrementAndGet() }
        }
        second.start()
        Thread.sleep(100)
        release.countDown()
        first.join(5_000)
        second.join(5_000)
        assertTrue(secondRan)
        assertEquals(1, most.get())
    }

    @Test fun aSetupThatCannotGetItsTurnDoesNotRun() {
        val release = CountDownLatch(1)
        val firstIn = CountDownLatch(1)
        val first = Thread { PluviaApp.runGameSetupExclusively(5_000) { firstIn.countDown(); release.await(5, TimeUnit.SECONDS) } }
        first.start()
        assertTrue(firstIn.await(5, TimeUnit.SECONDS))
        var ran = false
        assertFalse(PluviaApp.runGameSetupExclusively(50) { ran = true })
        assertFalse(ran)
        release.countDown()
        first.join(5_000)
        assertTrue("the gate is free again after a failing or finished setup", PluviaApp.runGameSetupExclusively(1_000) { })
    }

    @Test fun aFailingSetupGivesTheTurnBack() {
        runCatching { PluviaApp.runGameSetupExclusively(1_000) { throw IllegalStateException("extract failed") } }
        assertTrue(PluviaApp.runGameSetupExclusively(1_000) { })
    }
}
