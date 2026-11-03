package app.gamenative.ui.screen.wow

import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** RenderExtension covers only cursors: Wine's own RENDER drawing must be off in the WoW prefix before Wine starts. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [29])
class WowX11OptionsTest {
    @get:Rule val temp = TemporaryFolder()

    private val reg = "WINE REGISTRY Version 2\n;; All keys relative to \\\\User\\\\S-1-5-21-0-0-0-1000\n\n#arch=win64\n\n" +
        "[Software\\\\Wine\\\\Drivers] 1700000000\n#time=1d0000000000000\n\"Audio\"=\"pulse\"\n"

    @Test fun addsClientSideWithRenderOffOnceAndKeepsTheOtherKeys() {
        val file = temp.newFile("user.reg").apply { writeText(reg) }
        assertTrue(WowX11Options.ensure(file))
        val text = file.readText()
        assertTrue(WowX11Options.hasValue(text, "N"))
        assertTrue(text.contains("[Software\\\\Wine\\\\Drivers]") && text.contains("\"Audio\"=\"pulse\""))
        val modified = file.lastModified()
        assertFalse("an unchanged prefix is not rewritten", WowX11Options.ensure(file))
        assertEquals(text, file.readText()); assertEquals(modified, file.lastModified())
    }

    @Test fun aSavedYesIsTurnedOffAndAMissingRegistryIsNotCreated() {
        val file = temp.newFile("user.reg").apply {
            writeText(reg + "\n[Software\\\\Wine\\\\X11 Driver] 1700000000\n#time=1d0000000000000\n\"ClientSideWithRender\"=\"Y\"\n")
        }
        assertFalse(WowX11Options.hasValue(file.readText(), "N"))
        assertTrue(WowX11Options.ensure(file))
        assertTrue(WowX11Options.hasValue(file.readText(), "N"))
        assertFalse(file.readText().contains("\"ClientSideWithRender\"=\"Y\""))
        val missing = File(temp.root, "none/user.reg")
        assertFalse(WowX11Options.ensure(missing)); assertFalse(missing.exists())
    }

    @Test fun theValueUnderAnotherKeyDoesNotCount() {
        assertFalse(WowX11Options.hasValue(reg + "\n[Software\\\\Wine\\\\Other] 1\n\"ClientSideWithRender\"=\"N\"\n", "N"))
    }

    @Test fun theLauncherWritesItBeforeWineStarts() {
        val source = listOf(File("src/main/java"), File("app/src/main/java")).first { it.isDirectory }
        val text = File(source, "com/winlator/xenvironment/components/BionicProgramLauncherComponent.java").readText()
        val ensure = text.indexOf("WowX11Options.ensure(")
        assertTrue(ensure > 0 && ensure < text.indexOf("WowDriverProbe.prepare("))
    }
}
