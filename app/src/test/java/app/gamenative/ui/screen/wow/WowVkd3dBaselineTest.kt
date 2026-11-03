package app.gamenative.ui.screen.wow

import android.app.Application
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * AUDIT13-APP-01: the "original Wine D3D12" snapshot across a Proton change. A thin (overlay) prefix's upper system32 has no
 * d3d12.dll of its own and a runtime switch leaves it as it was, VKD3D included. Drives WowVkd3dRuntime's own steps in prepare()'s order.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [29])
class WowVkd3dBaselineTest {
    @get:Rule val temp = TemporaryFolder()
    private val r27 = "proton-11.0-90639.27-arm64ec-27"
    private val r28 = "proton-11.0-90639.28-arm64ec-28"

    private fun vkd3d(tag: String) = WowD3d12DllTransaction.names.associateWith { temp.newFile().apply { writeText("vkd3d-$tag:$it") } }
    private fun wineDll(text: String) = temp.newFile().apply {
        writeBytes(ByteArray(64) { if (it == 0) 'M'.code.toByte() else if (it == 1) 'Z'.code.toByte() else 0 } +
            "Wine builtin DLL\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000".toByteArray(Charsets.ISO_8859_1) + text.toByteArray())
    }
    private fun dx12Launch(root: File, system32: File, wine: String, payloads: Map<String, File?>) {
        WowVkd3dRuntime.retireStaleBaseline(root, system32, wine)
        WowVkd3dRuntime.takeBaseline(root, system32, wine)
        WowD3d12DllTransaction.replace(root, system32, payloads)
    }
    private fun dx11Launch(root: File, system32: File, wine: String) {
        WowVkd3dRuntime.retireStaleBaseline(root, system32, wine)
        WowVkd3dRuntime.restoreBaseline(root, system32)
    }
    private fun left(system32: File) = WowD3d12DllTransaction.names.filter { File(system32, it).exists() }.map { "$it=" + File(system32, it).readText() }

    @Test fun dx11AfterAProtonChangeLeavesNoVkd3dInTheUpperSystem32() {
        val root = temp.newFolder(); val system32 = temp.newFolder()
        dx12Launch(root, system32, r27, vkd3d("r4"))
        dx12Launch(root, system32, r28, vkd3d("r5"))            // Proton r27 -> r28: the upper system32 still holds r4
        dx11Launch(root, system32, r28)
        assertEquals(emptyList<String>(), left(system32))
        assertFalse(File(root, ".frontmage-original-d3d12").exists())
    }

    @Test fun aProtonChangeAloneAlsoPutsTheOriginalsBackBeforeTheNextSnapshot() {
        val root = temp.newFolder(); val system32 = temp.newFolder()
        dx12Launch(root, system32, r27, vkd3d("r4"))
        WowVkd3dRuntime.retireStaleBaseline(root, system32, r28)
        assertEquals(emptyList<String>(), left(system32))
    }

    @Test fun aSnapshotThatRecordedVkd3dIsHealed() {
        // The state a phone is in after the r27 -> r28 upgrade on 1.3.43: the r28 snapshot holds VKD3D r4 as "original".
        val root = temp.newFolder(); val system32 = temp.newFolder()
        WowD3d12DllTransaction.replace(root, system32, vkd3d("r4"))
        WowVkd3dRuntime.takeBaseline(root, system32, r28)
        WowD3d12DllTransaction.replace(root, system32, vkd3d("r5"))
        dx11Launch(root, system32, r28)
        assertEquals(emptyList<String>(), left(system32))
        // And through the next Proton change (1.3.44: r28 -> r29) in DX12: the new snapshot records nothing of VKD3D.
        dx12Launch(root, system32, r28, vkd3d("r4"))
        File(root, ".frontmage-original-d3d12").deleteRecursively(); WowVkd3dRuntime.takeBaseline(root, system32, r28)  // corrupt again
        dx12Launch(root, system32, "proton-11.0-90639.29-arm64ec-29", vkd3d("r6"))
        dx11Launch(root, system32, "proton-11.0-90639.29-arm64ec-29")
        assertEquals(emptyList<String>(), left(system32))
    }

    @Test fun wineOwnDllsAreRestoredAsTheyWere() {
        val root = temp.newFolder(); val system32 = temp.newFolder()
        val original = WowD3d12DllTransaction.names.associateWith { wineDll("wine:$it") }
        WowD3d12DllTransaction.names.forEach { original.getValue(it).copyTo(File(system32, it)) }
        dx12Launch(root, system32, r27, vkd3d("r5"))
        dx11Launch(root, system32, r27)
        WowD3d12DllTransaction.names.forEach { assertArrayEquals(original.getValue(it).readBytes(), File(system32, it).readBytes()) }
    }

    @Test fun wineMarkerDetection() {
        assertTrue(WowVkd3dRuntime.isWineDll(wineDll("x")))
        assertFalse(WowVkd3dRuntime.isWineDll(temp.newFile().apply { writeText("MZ vkd3d-proton") }))
        assertFalse(WowVkd3dRuntime.isWineDll(temp.newFile()))
    }
}
