package app.gamenative.ui.screen.wow

import android.app.Application
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [29])
class WowD3d12DllTransactionTest {
    @get:Rule val temp = TemporaryFolder()
    private fun payloads() = WowD3d12DllTransaction.names.associateWith { temp.newFile().apply { writeText("new:$it") } }
    private fun system() = temp.newFolder().also { root -> WowD3d12DllTransaction.names.forEach { File(root, it).writeText("old:$it") } }
    @Test fun replacesBothDllsAndCleansJournal() {
        val root = temp.newFolder(); val system = system(); WowD3d12DllTransaction.replace(root, system, payloads())
        WowD3d12DllTransaction.names.forEach { assertEquals("new:$it", File(system, it).readText()) }
        assertFalse(File(root, ".frontmage-dll-transaction").exists())
    }
    @Test fun firstOrSecondWriteFailureRestoresBothOriginals() {
        for (index in 0..1) { val root = temp.newFolder(); val system = system()
            assertThrows(Exception::class.java) { WowD3d12DllTransaction.replace(root, system, payloads()) { if (it == index) error("Injected publish failure") } }
            WowD3d12DllTransaction.names.forEach { assertEquals("old:$it", File(system, it).readText()) }
        }
    }
    @Test fun recoveryOfInterruptedProcessRestoresBothDllsIdempotently() {
        val root = temp.newFolder(); val system = system(); val journal = File(root, ".frontmage-dll-transaction").apply { mkdir() }
        WowD3d12DllTransaction.snapshot(system, File(journal, "original")); File(journal, "publishing").writeText("incomplete")
        File(system, "d3d12.dll").writeText("partially replaced")
        repeat(2) { WowD3d12DllTransaction.recover(root, system) }
        WowD3d12DllTransaction.names.forEach { assertEquals("old:$it", File(system, it).readText()) }
    }
    @Test fun committedPublicationIsNotRolledBackOnRecovery() {
        val root = temp.newFolder(); val system = system(); val journal = File(root, ".frontmage-dll-transaction").apply { mkdir() }
        WowD3d12DllTransaction.snapshot(system, File(journal, "original")); File(journal, "publishing").writeText("yes"); File(journal, "committed").writeText("yes")
        File(system, "d3d12.dll").writeText("complete")
        WowD3d12DllTransaction.recover(root, system); assertEquals("complete", File(system, "d3d12.dll").readText())
    }
    @Test fun rollbackPreservesSymlinkAndAbsentDll() {
        val root = temp.newFolder(); val system = temp.newFolder(); val backing = temp.newFile().apply { writeText("base prefix DLL") }
        val link = File(system, "d3d12.dll"); Files.createSymbolicLink(link.toPath(), backing.toPath())
        assertThrows(Exception::class.java) { WowD3d12DllTransaction.replace(root, system, payloads()) { error("failure") } }
        assertTrue(Files.isSymbolicLink(link.toPath())); assertEquals(backing.toPath(), Files.readSymbolicLink(link.toPath()))
        assertEquals("base prefix DLL", backing.readText()); assertFalse(File(system, "d3d12core.dll").exists())
    }
    @Test fun originalDx11SnapshotCanBeRestoredAfterDx12() {
        val root = temp.newFolder(); val system = system(); val baseline = File(root, "saved")
        WowD3d12DllTransaction.snapshot(system, baseline); WowD3d12DllTransaction.replace(root, system, payloads())
        WowD3d12DllTransaction.replace(root, system, WowD3d12DllTransaction.fromSnapshot(baseline))
        WowD3d12DllTransaction.names.forEach { assertEquals("old:$it", File(system, it).readText()) }
    }
    @Test fun incompleteBackupIsRejectedBeforeAnyRecoveryWrite() {
        val root = temp.newFolder(); val system = system(); val journal = File(root, ".frontmage-dll-transaction").apply { mkdir() }
        val snapshot = File(journal, "original"); WowD3d12DllTransaction.snapshot(system, snapshot)
        File(snapshot, "d3d12core.dll").delete(); File(journal, "publishing").writeText("yes"); File(system, "d3d12.dll").writeText("changed")
        assertThrows(Exception::class.java) { WowD3d12DllTransaction.recover(root, system) }
        assertEquals("changed", File(system, "d3d12.dll").readText()); assertTrue(journal.exists())
    }
}
