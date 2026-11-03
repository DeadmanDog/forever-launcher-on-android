package com.winlator.container

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class BasePrefixSwapTest {
    @get:Rule val temp = TemporaryFolder()

    private fun base(identity: String?, marker: String = "old"): File {
        val dir = File(temp.root, "base").also { it.mkdirs() }
        File(dir, ".wine").mkdirs(); File(dir, ".wine/$marker").writeText(marker)
        if (identity != null) File(dir, ".complete").writeText(identity)
        return dir
    }

    private fun staging(baseDir: File): Pair<File, File> {
        val staging = File(baseDir, ".staging").also { it.mkdirs() }
        val wine = File(staging, ".wine").also { it.mkdirs() }
        File(wine, "new").writeText("new")
        return staging to wine
    }

    @Test fun aSuccessfulSwapReplacesTheBaseAndTheMarker() {
        val dir = base("1:old")
        val (staging, wine) = staging(dir)
        assertTrue(BasePrefix.swapIn(dir, staging, wine, "2:new"))
        assertTrue(File(dir, ".wine/new").isFile())
        assertFalse(File(dir, ".wine/old").exists())
        assertEquals("2:new", File(dir, ".complete").readText())
        assertFalse("the previous base is gone", File(dir, ".wine.old").exists())
    }

    @Test fun aMarkerThatCannotBeWrittenRestoresThePreviousBase() {
        val dir = base("1:old")
        // The marker path is a non-empty directory: the atomic rename of the new marker onto it fails.
        File(dir, ".complete").delete()
        File(dir, ".complete").mkdirs(); File(dir, ".complete/blocker").writeText("x")
        val (staging, wine) = staging(dir)
        assertFalse(BasePrefix.swapIn(dir, staging, wine, "2:new"))
        assertTrue("the previous base is back", File(dir, ".wine/old").isFile())
        assertFalse("the half-installed new base is gone", File(dir, ".wine/new").exists())
    }

    @Test fun aFailedRenameOfTheNewBaseKeepsThePreviousOne() {
        val dir = base("1:old")
        val (staging, _) = staging(dir)
        val missing = File(staging, "does-not-exist")
        assertFalse(BasePrefix.swapIn(dir, staging, missing, "2:new"))
        assertTrue(File(dir, ".wine/old").isFile())
        assertEquals("the old marker stays", "1:old", File(dir, ".complete").readText())
    }
}
