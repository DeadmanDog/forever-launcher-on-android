package app.gamenative.ui.util

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ScreenAwakeTest {
    @Before @After fun clean() = ScreenAwake.reset()

    @Test fun nothingRequestedMeansTheScreenMayTimeOut() {
        assertFalse(ScreenAwake.update("launcher-busy", false, Any()))
    }

    @Test fun independentReasonsKeepTheScreenOnUntilTheLastIsGone() {
        val launcher = Any(); val game = Any()
        assertTrue(ScreenAwake.update("launcher-busy", true, launcher))
        assertTrue(ScreenAwake.update("game", true, game))
        assertTrue("one reason is still active", ScreenAwake.update("launcher-busy", false, launcher))
        assertFalse(ScreenAwake.update("game", false, game))
    }

    @Test fun anOldOwnerCannotClearTheRequestOfItsReplacement() {
        // The review's overlap: the new instance asks (true) before the old one is disposed (false).
        val old = Any(); val new = Any()
        ScreenAwake.update("launcher-busy", true, old)
        ScreenAwake.update("launcher-busy", true, new)
        assertTrue("the replacement's request survives the old dispose", ScreenAwake.update("launcher-busy", false, old))
        assertFalse("and its own dispose clears it", ScreenAwake.update("launcher-busy", false, new))
    }

    @Test fun repeatedRequestsOfTheSameOwnerAreIdempotent() {
        val owner = Any()
        ScreenAwake.update("game", true, owner); ScreenAwake.update("game", true, owner)
        assertFalse(ScreenAwake.update("game", false, owner))
    }
}
