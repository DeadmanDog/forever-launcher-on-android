package app.gamenative.ui.screen.wow

import android.app.Application
import com.winlator.container.Container
import com.winlator.core.envvars.EnvVars
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Saved variables cannot replace what the launcher owns; search lists get the saved entries appended. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [29])
class WowLaunchOwnedNamesTest {
    private fun launcherEnv() = EnvVars().apply {
        put("HOME", "/img/home/xuser")
        put("WINEPREFIX", "/img/home/xuser/.wine")
        put("TMPDIR", "/img/usr/tmp")
        put("LD_PRELOAD", "/img/lib/libandroid-sysvshm.so:/app/lib/libevshim.so")
        put("LD_LIBRARY_PATH", "/img/usr/lib:/system/lib64")
        put("PATH", "/proton/bin:/img/usr/bin")
    }

    private val user = EnvVars().apply {
        put("HOME", "/sdcard/elsewhere")
        put("WINEPREFIX", "/sdcard/prefix")
        put("TMPDIR", "/sdcard/tmp")
        put("EVSHIM_SHM_NAME", "other")
        put("LD_PRELOAD", "/sdcard/libmine.so")
        put("LD_LIBRARY_PATH", "/sdcard/lib")
        put("PATH", "/sdcard/bin")
        put("DXVK_HUD", "fps")
    }

    @Test fun theFinalMergeKeepsLauncherOwnedNamesAndAppendsSearchLists() {
        val env = launcherEnv()
        WowLaunchEnvironment.mergeUserValues(user, env, final = true)
        assertEquals("/img/home/xuser", env.get("HOME"))
        assertEquals("/img/home/xuser/.wine", env.get("WINEPREFIX"))
        assertEquals("/img/usr/tmp", env.get("TMPDIR"))
        assertFalse(env.has("EVSHIM_SHM_NAME"))
        assertEquals("/img/lib/libandroid-sysvshm.so:/app/lib/libevshim.so:/sdcard/libmine.so", env.get("LD_PRELOAD"))
        assertEquals("/img/usr/lib:/system/lib64:/sdcard/lib", env.get("LD_LIBRARY_PATH"))
        assertEquals("/proton/bin:/img/usr/bin:/sdcard/bin", env.get("PATH"))
        assertEquals("fps", env.get("DXVK_HUD"))
    }

    @Test fun mergingTwiceDoesNotRepeatEntries() {
        val env = launcherEnv()
        WowLaunchEnvironment.mergeUserValues(user, env, final = true)
        WowLaunchEnvironment.mergeUserValues(user, env, final = true)
        assertEquals("/img/lib/libandroid-sysvshm.so:/app/lib/libevshim.so:/sdcard/libmine.so", env.get("LD_PRELOAD"))
    }

    @Test fun theEarlyMergeLeavesSearchListsToTheLauncher() {
        val env = EnvVars().apply { put("WINEPREFIX", "/img/home/xuser/.wine") }
        WowLaunchEnvironment.mergeUserValues(user, env, final = false)
        assertFalse(env.has("LD_PRELOAD")); assertFalse(env.has("PATH")); assertFalse(env.has("HOME"))
        assertEquals("/img/home/xuser/.wine", env.get("WINEPREFIX"))
        assertEquals("fps", env.get("DXVK_HUD"))
    }

    @Test fun savedValuesCopiedInBeforeAreWithdrawnAndThePrefixRestored() {
        // What the session hands over: its own variables with every saved value copied over them.
        val env = EnvVars().apply { put("WINEPREFIX", "/img/home/xuser/.wine"); put("WINEDEBUG", "-all"); putAll(user) }
        WowLaunchEnvironment.withdrawLauncherNames(user, env, "/img/home/xuser/.wine")
        assertEquals("/img/home/xuser/.wine", env.get("WINEPREFIX"))
        for (name in listOf("HOME", "TMPDIR", "EVSHIM_SHM_NAME", "LD_PRELOAD", "LD_LIBRARY_PATH", "PATH")) assertFalse(name, env.has(name))
        assertEquals("fps", env.get("DXVK_HUD"))
        assertEquals("-all", env.get("WINEDEBUG"))
    }

    @Test fun aValueSetLaterByTheAppIsNotWithdrawn() {
        val env = EnvVars().apply { put("LD_PRELOAD", "/app/lib/other.so") }
        WowLaunchEnvironment.withdrawLauncherNames(user, env, "/img/home/xuser/.wine")
        assertEquals("/app/lib/other.so", env.get("LD_PRELOAD"))
        assertFalse("no saved WINEPREFIX here", EnvVars().also { WowLaunchEnvironment.withdrawLauncherNames(EnvVars(), it, "/p") }.has("WINEPREFIX"))
    }

    @Test fun appendListDropsEmptyAndDuplicateEntries() {
        assertEquals("a:b:c", WowLaunchEnvironment.appendList("a::b", "b:c:"))
        assertEquals("c", WowLaunchEnvironment.appendList("", "c"))
        assertEquals("a", WowLaunchEnvironment.appendList("a", ""))
    }

    @Test fun anotherContainerIsLeftAlone() {
        val c = Container("other").apply { envVars = "HOME=/x" }
        val env = EnvVars().apply { put("HOME", "/h") }
        WowLaunchEnvironment.mergeUserOverrides(c, env)
        assertEquals("/h", env.get("HOME"))
    }

    @Test fun theEditorSaysHowEachNameIsHandled() {
        assertEquals(WowEnvironmentEditor.Handling.IGNORED, WowEnvironmentEditor.handling("WINEPREFIX"))
        assertEquals(WowEnvironmentEditor.Handling.IGNORED, WowEnvironmentEditor.handling("HOME"))
        assertEquals(WowEnvironmentEditor.Handling.APPENDED, WowEnvironmentEditor.handling("LD_PRELOAD"))
        assertEquals(WowEnvironmentEditor.Handling.APPENDED, WowEnvironmentEditor.handling("PATH"))
        assertEquals(WowEnvironmentEditor.Handling.OVERRIDE, WowEnvironmentEditor.handling("DXVK_HUD"))
        assertEquals(WowEnvironmentEditor.Handling.OVERRIDE, WowEnvironmentEditor.handling("DISPLAY"))
    }
}
