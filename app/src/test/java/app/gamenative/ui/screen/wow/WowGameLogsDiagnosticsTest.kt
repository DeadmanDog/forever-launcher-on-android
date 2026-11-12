package app.gamenative.ui.screen.wow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** 1.1.6: the diagnostics carry WoW's own gx.log and the graphics lines of Config.wtf (never account names). */
class WowGameLogsDiagnosticsTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun gxLogAndGraphicsConfigGoIntoTheSession() {
        val root = temp.newFolder(); val session = temp.newFolder()
        val flavor = File(root, WowClientDownloader.FLAVOR_DIR)
        File(flavor, "Logs").mkdirs(); File(flavor, "Logs/gx.log").writeText("D3D12 adapter: Adreno\nrenderScale max 1.0\n")
        File(flavor, "WTF").mkdirs()
        File(flavor, "WTF/Config.wtf").writeText("SET gxApi \"D3D12\"\nSET renderScale \"1\"\nSET accountName \"secret@mail\"\n" +
            "SET accountList \"!WoW1|\"\nSET ResampleQuality \"3\"\nSET lastCharacterIndex \"2\"\n")
        WowDiagnostics.copyGameLogs(root, session)
        assertEquals("D3D12 adapter: Adreno\nrenderScale max 1.0\n", File(session, "wow-gx.log").readText())
        val config = File(session, "wow-config-graphics.txt").readText()
        assertTrue(config.contains("gxApi") && config.contains("renderScale") && config.contains("ResampleQuality"))
        assertFalse(config.contains("account") || config.contains("lastCharacter"))
    }

    @Test fun nothingToCopyIsNoError() {
        val session = temp.newFolder()
        WowDiagnostics.copyGameLogs(temp.newFolder(), session)
        WowDiagnostics.copyGameLogs(null, session)
        assertTrue(session.listFiles()!!.isEmpty())
    }
}
