package app.gamenative.ui.screen.wow

import app.gamenative.CrashHandler
import org.junit.Test
import org.junit.Assert.*
import org.junit.Rule
import org.junit.rules.TemporaryFolder

class DiagnosticCrashTest {
    @get:Rule val temp=TemporaryFolder()
    @Test fun crashCaptureIncludesStackAndIsBounded() {
        val file=temp.newFile();val e=IllegalStateException("x".repeat(100000),IllegalArgumentException("cause"))
        CrashHandler.writeCrash(file,"game-thread",e)
        assertTrue(file.length()<=65536);assertTrue(file.readText().contains("game-thread"));assertTrue(file.readText().contains("IllegalStateException"));assertTrue(file.readText().contains("IllegalArgumentException"))
    }
}
