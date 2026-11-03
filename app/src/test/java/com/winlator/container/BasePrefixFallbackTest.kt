package com.winlator.container

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class BasePrefixFallbackTest {
    @get:Rule val temp = TemporaryFolder()

    private fun baseDir(wine: Boolean, marker: String?): File {
        val dir = File(temp.root, "base").also { it.mkdirs() }
        if (wine) File(dir, ".wine").mkdirs()
        if (marker != null) File(dir, ".complete").writeText(marker)
        return dir
    }

    @Test fun thePreviousCompleteBaseIsDescribedWithBothIdentities() {
        val notice = BasePrefix.previousBaseNotice(baseDir(true, "3:proton-arm64ec:30\n"), "3:proton-arm64ec:31")
        assertNotNull(notice)
        assertTrue(notice!!.contains("wanted 3:proton-arm64ec:31"))
        assertTrue("the stored identity is trimmed", notice.contains("found 3:proton-arm64ec:30") && !notice.contains("\n"))
    }

    @Test fun anEmptyMarkerStillCountsAsAPreviousBase() {
        assertEquals("wanted x, found unknown", BasePrefix.previousBaseNotice(baseDir(true, ""), "x"))
    }

    @Test fun withoutAMarkerOrWithoutTheWineFolderThereIsNothingToFallBackTo() {
        assertNull(BasePrefix.previousBaseNotice(baseDir(true, null), "x"))
        assertNull(BasePrefix.previousBaseNotice(File(temp.root, "base").also { it.deleteRecursively() }.let { baseDir(false, "1:old") }, "x"))
    }
}
