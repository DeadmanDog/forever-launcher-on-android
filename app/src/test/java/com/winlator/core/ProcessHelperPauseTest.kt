package com.winlator.core

import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class ProcessHelperPauseTest {
    private class FakeProcesses : ProcessHelper.ProcessOps {
        val signals = java.util.Collections.synchronizedList(mutableListOf<String>())
        val stoppedPids = java.util.Collections.synchronizedSet(mutableSetOf<Int>())
        @Volatile var pids = listOf("101", "102")
        @Volatile var listGate: CountDownLatch? = null
        @Volatile var listing: CountDownLatch? = null
        override fun list(): List<String> {
            listing?.countDown()
            listGate?.await(5, TimeUnit.SECONDS)
            return pids
        }
        override fun stop(pid: Int) { signals.add("STOP$pid"); stoppedPids.add(pid) }
        override fun cont(pid: Int) { signals.add("CONT$pid"); stoppedPids.remove(pid) }
        override fun stopped(pid: Int) = pid in stoppedPids
    }

    private lateinit var fake: FakeProcesses
    private lateinit var original: ProcessHelper.ProcessOps

    @Before fun install() { original = ProcessHelper.ops; fake = FakeProcesses(); ProcessHelper.ops = fake }
    @After fun restore() {
        ProcessHelper.resumeAllWineProcesses()      // while the fake is still installed: never touch real processes
        Thread.sleep(350)                           // the resume verifier of this call has finished
        ProcessHelper.ops = original
    }

    @Test fun aPauseSweepThatWasOvertakenByAResumeSendsNoStop() {
        // The sweep is held while it reads the process list; the app comes back to the foreground meanwhile.
        fake.listGate = CountDownLatch(1)
        fake.listing = CountDownLatch(1)
        val pause = Thread { ProcessHelper.pauseAllWineProcesses() }
        pause.start()
        assertTrue("the pause reached the process listing", fake.listing!!.await(5, TimeUnit.SECONDS))
        fake.listGate = null
        ProcessHelper.resumeAllWineProcesses()      // SIGCONT for both processes, pause generation moves on
        Thread.sleep(400)                           // the resume verifier has run, too
        fake.signals.clear()
        // Now the old sweep continues with its list.
        val release = Thread { }
        release.start()
        // (the gate was removed above, so the paused thread proceeds as soon as it is scheduled)
        pause.join(5_000)
        Thread.sleep(150)
        assertTrue("no SIGSTOP after the resume", fake.signals.none { it.startsWith("STOP") })
        assertTrue("nothing is left stopped", fake.stoppedPids.isEmpty())
    }

    @Test fun aNormalPauseStopsEveryProcessAndAResumeContinuesThem() {
        ProcessHelper.pauseAllWineProcesses()
        assertEquals(setOf(101, 102), fake.stoppedPids.toSet())
        ProcessHelper.resumeAllWineProcesses()
        assertTrue(fake.stoppedPids.isEmpty())
    }

    @Test fun aNewerPauseTakesOverFromAnOlderOneThatIsStillListing() {
        fake.listGate = CountDownLatch(1)
        fake.listing = CountDownLatch(1)
        val first = Thread { ProcessHelper.pauseAllWineProcesses() }
        first.start()
        assertTrue(fake.listing!!.await(5, TimeUnit.SECONDS))
        fake.listGate = null
        ProcessHelper.resumeAllWineProcesses()
        ProcessHelper.pauseAllWineProcesses()       // the user opened the menu again
        first.join(5_000)
        Thread.sleep(150)
        // Exactly one pause is in force: both processes are stopped once by the newer sweep (the old one stood down).
        assertEquals(setOf(101, 102), fake.stoppedPids.toSet())
        assertEquals(2, fake.signals.count { it.startsWith("STOP") })
    }

    @Test fun theVerifierRetriesADroppedSignalOfTheCurrentSweep() {
        val dropFirst = AtomicBoolean(true)
        val flaky = object : ProcessHelper.ProcessOps by fake {
            override fun stop(pid: Int) { if (pid == 101 && dropFirst.getAndSet(false)) { fake.signals.add("DROP$pid"); return }; fake.stop(pid) }
        }
        ProcessHelper.ops = flaky
        ProcessHelper.pauseAllWineProcesses()
        Thread.sleep(300)
        assertTrue("the dropped STOP was retried", 101 in fake.stoppedPids)
    }
}
