package app.gamenative.ui.screen.wow

import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 1.3.0 (user): an APK update removes the older bundled TurnRIP, Proton, DXVK and VKD3D builds it replaces, and only those. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class WowSupersededCleanupTest {
    @Test fun onlyOlderBundledBuildsAreSelectedNeverTheCurrentOrAnImport() {
        val selected = WowBundledComponents.supersededInstalled(
            drivers = listOf("TurnRIP A840 Performance v1.1.30", WowBundledComponents.DRIVER_NAME, "Turnip 25.1", "TurnRIP A840 Custom"),
            dxvks = listOf("dxvk-Vegas-3.1.1-wow-arm64-hybrid-gamenative-r62-62", WowBundledComponents.DXVK_ID,
                "dxvk-Vegas-2.7.3-wow-arm64-hybrid-gamenative-r50-50", "dxvk-2.4.1-wow-aarch64-test", "dxvk-2.6-my-build-1"),
            vkd3ds = listOf("vkd3d-VKD3D-Proton-3.0.1-ARM64-r11-11", "vkd3d-VKD3D-Proton-3.0.1-ARM64-r${WowBundledComponents.VKD3D_VERSION_CODE}-${WowBundledComponents.VKD3D_VERSION_CODE}",
                "vkd3d-FrontMage-VKD3D-Proton-3.0.1-ARM64-r10-10", "vkd3d-Imported-2.14-1"),
            runtimes = listOf("proton-11.0-90639.33-arm64ec-33", WowBundledComponents.PROTON_ID, "proton-11.0-90624-arm64ec-0", "wine-10.0-arm64ec-1"),
        )
        assertEquals(listOf("TurnRIP A840 Performance v1.1.30",
            "dxvk-Vegas-3.1.1-wow-arm64-hybrid-gamenative-r62-62", "dxvk-Vegas-2.7.3-wow-arm64-hybrid-gamenative-r50-50",
            "vkd3d-VKD3D-Proton-3.0.1-ARM64-r11-11", "vkd3d-FrontMage-VKD3D-Proton-3.0.1-ARM64-r10-10",
            "proton-11.0-90639.33-arm64ec-33"), selected)
    }

    @Test fun aComponentThatCannotGoIsKeptAndTheRestStillRemoved() {
        val done = mutableListOf<String>()
        val status = mutableListOf<String>()
        val removed = WowBundledComponents.runRemovals(listOf(
            WowBundledComponents.Removal("old-driver") { done += "old-driver" },
            WowBundledComponents.Removal("in-use-proton") { error("This component is currently in use.") },
            WowBundledComponents.Removal("old-vkd3d") { done += "old-vkd3d" },
        ), { status += it }, { false })
        assertEquals(listOf("old-driver", "old-vkd3d"), removed)
        assertEquals(removed, done)
        assertEquals(3, status.size)
    }

    @Test fun nothingIsRemovedOnceAGameSessionStarts() {
        var calls = 0
        var active = false
        val removed = WowBundledComponents.runRemovals(listOf(
            WowBundledComponents.Removal("a") { calls++; active = true },
            WowBundledComponents.Removal("b") { calls++ },
        ), {}, { active })
        assertEquals(listOf("a"), removed)
        assertEquals(1, calls)
    }
}
