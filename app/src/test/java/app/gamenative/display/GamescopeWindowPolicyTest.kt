package app.gamenative.display

import org.junit.Assert.*
import org.junit.Test

class GamescopeWindowPolicyTest {
    @Test fun xserverCommandRetainsItsExistingVirtualDesktopExactly() {
        assertEquals("wine explorer /desktop=shell,1344x608 G:\\_forever_\\Wow-ARM64.exe -d3d12",
            GamescopeWindowPolicy.command("1344x608","G:\\_forever_\\Wow-ARM64.exe","-d3d12"))
    }
    @Test fun gamescopeKeepsTheWorkingExplorerBootstrapAndPreservesQuotedPathAndArguments() {
        val command = GamescopeWindowPolicy.command("1344x608","\"G:\\WoW Folder\\Wow-ARM64.exe\"","-d3d11", gamescopeGame = true)
        assertEquals("wine explorer /desktop=root,1344x608 \"G:\\WoW Folder\\Wow-ARM64.exe\" -d3d11", command)
        assertTrue(command.startsWith("wine explorer /desktop=root,1344x608 "))
    }
    @Test fun gamescopeComposesWithoutForcingClientWindowSizes() {
        assertEquals("--force-composition", GamescopeWindowPolicy.GAME_DISPLAY_FLAGS)
        assertFalse(GamescopeWindowPolicy.GAME_DISPLAY_FLAGS.contains("--force-windows-fullscreen"))
    }
    @Test fun onlyGamescopeArm64GameLaunchUsesTheNewPath() {
        assertTrue(GamescopeWindowPolicy.gameLaunch(true,"G:\\_forever_\\Wow-ARM64.exe"))
        assertFalse(GamescopeWindowPolicy.gameLaunch(false,"G:\\_forever_\\Wow-ARM64.exe"))
        assertFalse(GamescopeWindowPolicy.gameLaunch(true,"wfm.exe"))
    }
    @Test fun physicalSurfaceAndGuestModeStayIndependent() {
        assertEquals(2688 to 1216,GamescopeWindowPolicy.surfaceSize(2688,1216,1344,608))
        assertEquals(1216 to 2688,GamescopeWindowPolicy.surfaceSize(1216,2688,1344,608))
        assertEquals(1344 to 608,GamescopeWindowPolicy.surfaceSize(0,0,1344,608))
    }
    @Test fun explorerDesktopAndUnmappedWowDoNotDismissLoading() {
        val explorer = "0x00000001\n map_state=IsViewable\n WM_CLASS instance=\"explorer.exe\" class=\"Wine\"\n WM_NAME=\"shell\"\n"
        assertFalse(GamescopeWindowPolicy.hasMappedGameWindow(explorer))
        assertFalse(GamescopeWindowPolicy.hasMappedGameWindow(explorer+"0x00000002\n map_state=IsUnmapped\n WM_CLASS instance=\"Wow-ARM64.exe\" class=\"Wine\""))
    }
    @Test fun onlyAMappedWowWindowTriggersReadiness() {
        val tree = "0x00000001\n map_state=IsViewable\n WM_NAME=\"shell\"\n0x00000002\n map_state=IsViewable\n WM_NAME=\"World of Warcraft\"\n WM_CLASS instance=\"Wow-ARM64.exe\" class=\"Wine\"\n"
        assertTrue(GamescopeWindowPolicy.hasMappedGameWindow(tree))
        assertFalse(GamescopeWindowPolicy.hasMappedGameWindow("cannot connect to X server"))
    }
    @Test fun realTreeProbeFormatFindsCandidateButRequiresTargetAttributes() {
        val tree = "0x00000001\n WM_NAME=\"shell\"\n  0x00000002\n WM_CLASS instance=\"Wow-ARM64.exe\" class=\"Wine\"\n"
        assertEquals(listOf("0x00000002"),GamescopeWindowPolicy.gameWindowIds(tree))
        assertFalse(GamescopeWindowPolicy.hasMappedGameWindow(tree))
        val target = "window 0x00000002\n map_state=IsViewable override_redirect=0 class=InputOutput\n WM_CLASS instance=\"Wow-ARM64.exe\" class=\"Wine\"\n"
        assertTrue(GamescopeWindowPolicy.hasMappedGameWindow(target))
        assertFalse(GamescopeWindowPolicy.hasMappedGameWindow(target.replace("IsViewable","IsUnviewable")))
    }
}

class GamescopeDirectScanoutTest {
    @org.junit.Test fun theSwitchDropsOnlyTheForcedComposition() {
        org.junit.Assert.assertEquals(" --force-composition", GamescopeWindowPolicy.gameDisplayFlags(false))
        org.junit.Assert.assertEquals("", GamescopeWindowPolicy.gameDisplayFlags(true))
    }
}

class GamescopeCursorMinimumTest {
    @org.junit.Test fun theReadableMinimumFollowsThePanelScale() {
        org.junit.Assert.assertEquals(24, WowGamescopeRuntime.cursorMinPixels(1344 to 608, 2688 to 1216))   // game-sized output, 2x panel
        org.junit.Assert.assertEquals(36, WowGamescopeRuntime.cursorMinPixels(2016 to 912, 2688 to 1216))   // 1.5x effect output
        org.junit.Assert.assertEquals(48, WowGamescopeRuntime.cursorMinPixels(2688 to 1216, 2688 to 1216))  // panel-sized output
        org.junit.Assert.assertEquals(53, WowGamescopeRuntime.cursorMinPixels(1344 to 608, 1216 to 2688))   // portrait: shown at 0.9x -> 48 / 0.9
        org.junit.Assert.assertEquals(48, WowGamescopeRuntime.cursorMinPixels(1344 to 608, null))
    }
}
