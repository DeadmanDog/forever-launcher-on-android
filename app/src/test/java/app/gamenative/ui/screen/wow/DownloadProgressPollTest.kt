package app.gamenative.ui.screen.wow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 1.2.24: the in-game download line looks up the total once, lists only the data files, and stops once the data is complete. */
class DownloadProgressPollTest {
    @Test
    fun theTotalIsLookedUpOnceItExists() {
        var totalReads = 0
        var listings = 0
        var known: Long? = null
        val poll = DownloadProgressPoll({ totalReads++; known }, { listings++; 500L })
        assertNull(poll.poll())
        assertNull(poll.poll())
        known = 1000L
        repeat(10) { assertEquals(1000L, poll.poll()!!.total) }
        assertEquals("build info + locale read per poll until the total exists, then never", 3, totalReads)
        assertEquals(10, listings)
    }

    @Test
    fun pollingStopsOnceTheDataIsComplete() {
        assertEquals(DownloadProgressPoll.UNKNOWN_TOTAL_MS, DownloadProgressPoll.nextDelayMs(null))
        assertEquals(DownloadProgressPoll.STREAMING_MS, DownloadProgressPoll.nextDelayMs(WowGameData.Progress(400, 1000)))
        assertNull(DownloadProgressPoll.nextDelayMs(WowGameData.Progress(1000, 1000)))
    }

    @Test
    fun aThreeHourSessionAfterTheDownloadReadsNothing() {
        // 1.2.22 shape: every 30 s a poll read .build.info, the locale and listed Data/data, for the whole session.
        val oldPolls = 3 * 3600 / 30
        var newPolls = 0
        var delay: Long? = 0
        val poll = DownloadProgressPoll({ 1000L }, { 1000L })
        var elapsed = 0L
        while (delay != null && elapsed < 3 * 3600_000L) {
            newPolls++
            delay = DownloadProgressPoll.nextDelayMs(poll.poll())
            elapsed += delay ?: 0
        }
        assertEquals(360, oldPolls)
        assertEquals(1, newPolls)
    }
}
