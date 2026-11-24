package app.gamenative.wow.addons

import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AddonCaseRenameRegressionTest {
    @get:Rule val temp = TemporaryFolder()
    private fun folder(name: String) = File(temp.root, name).apply {
        mkdirs(); File(this, "$name.toc").writeText("## Interface: 16001\n## Title: Guide\n")
    }
    private fun known(vararg folders: String) = InstalledAddon("url:custom", "My guide", folders.toList(), "v42",
        AddonFlavor.FOREVER, SourceRef(ProviderIds.URL, "https://example.invalid/guide.zip"), 123L)

    @Test fun caseOnlyFolderRenamePreservesUpdateSourceAndInstallIdentity() {
        folder("questie")
        val before = known("Questie")
        val scanned = AddonScanner.scan(temp.root, listOf(before), 999L)
        assertEquals(listOf(before.copy(folders = listOf("questie"))), scanned)
        assertEquals(scanned, AddonScanner.scan(temp.root, scanned, 1000L))
    }
    @Test fun renamedPartOfMultiFolderAddonIsNotDroppedOrCreatedAsAnotherAddon() {
        folder("Guide");folder("guidedata")
        val before = known("Guide", "GuideData")
        assertEquals(listOf(before.copy(folders = listOf("Guide", "guidedata"))),
            AddonScanner.scan(temp.root, listOf(before), 999L))
    }
    @Test fun exactCaseWinsAndAmbiguousCaseVariantsAreNotGuessed() {
        folder("Guide");folder("guide")
        val before = known("Guide")
        val scanned = AddonScanner.scan(temp.root, listOf(before), 999L)
        assertEquals(before, scanned.first())
        assertEquals(2, scanned.size)
        val missing = AddonScanner.scan(temp.root, listOf(known("GUIDE")), 999L)
        assertFalse(missing.any { it.id == "url:custom" })
    }
}
