package com.winlator.xenvironment.components

import com.winlator.core.ProcessHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Review 2026-11-13: the wineserver is asked to exit (it saves the registry) before anything kills it. */
class WineTeardownOrderTest {
    private fun info(pid: Int, name: String) = ProcessHelper.ProcessInfo(pid, 1, name)

    @Test fun everythingButTheWineserverIsKilledFirst() {
        val processes = listOf(
            info(10, "/data/user/0/forever.launcher/files/imagefs/opt/wine/bin/wineserver"),
            info(11, "C:\\_forever_\\Wow-ARM64.exe"),
            info(12, "/data/user/0/forever.launcher/files/pulseaudio/libpulseaudio.so"),
            info(13, "wineserver"),
            info(14, "services.exe"),
            info(15, "winedevice.exe"),
        )
        assertEquals(listOf(11, 12, 14, 15), BionicProgramLauncherComponent.pidsToKillBeforeWineserverExit(processes))
    }

    @Test fun wineserverNamesAreRecognised() {
        assertTrue(BionicProgramLauncherComponent.isWineserver("wineserver"))
        assertTrue(BionicProgramLauncherComponent.isWineserver("/opt/wine/bin/wineserver"))
        assertTrue(BionicProgramLauncherComponent.isWineserver("/opt/wine/bin/wineserver -k"))
        assertFalse(BionicProgramLauncherComponent.isWineserver("wine"))
        assertFalse(BionicProgramLauncherComponent.isWineserver("/opt/wine/bin/wine64"))
        assertFalse(BionicProgramLauncherComponent.isWineserver("winedevice.exe"))
        assertFalse(BionicProgramLauncherComponent.isWineserver(null))
    }

    @Test fun aWineserverStartedThroughTheLinkerIsRecognisedByItsCommandLine() {
        assertTrue(BionicProgramLauncherComponent.isWineserverCommandLine(arrayOf("/system/bin/linker64", "/opt/wine/bin/wineserver", "--foreground")))
        assertFalse(BionicProgramLauncherComponent.isWineserverCommandLine(arrayOf("/system/bin/linker64", "/opt/wine/bin/wine", "wineserver")))
        assertFalse(BionicProgramLauncherComponent.isWineserverCommandLine(null))
    }
}
