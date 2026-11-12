package app.gamenative.display

import com.winlator.renderer.FrameTrace
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class GamescopeFrameLogTest {
    @After fun off() { FrameTrace.set(false); GamescopeFrameLog.reset() }

    @Test fun recordsOnlyWhileTheDiagnosticsReadAndNumbersEveryFrame() {
        GamescopeFrameLog.reset()
        FrameTrace.set(false)
        GamescopeFrameLog.record(1)
        assertEquals(0, GamescopeFrameLog.take().size)
        FrameTrace.set(true)
        GamescopeFrameLog.record(100); GamescopeFrameLog.record(200)
        assertArrayEquals(longArrayOf(1, 100, 2, 200), GamescopeFrameLog.take())
        GamescopeFrameLog.record(300)
        assertArrayEquals(longArrayOf(3, 300), GamescopeFrameLog.take())
        assertEquals(0, GamescopeFrameLog.take().size)
    }
}
