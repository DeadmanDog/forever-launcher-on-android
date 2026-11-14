package com.winlator.core

import android.app.Application
import android.net.Uri
import java.io.File
import java.nio.file.Files
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.archivers.tar.TarConstants
import org.apache.commons.compress.compressors.xz.XZCompressorOutputStream
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** A link checked when it was made must still stay inside the destination after later entries replaced links it goes through. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [29])
class TarSymlinkRecheckTest {
    @get:Rule val temp = TemporaryFolder()

    private sealed class Item {
        class Dir(val name: String) : Item()
        class Data(val name: String, val text: String) : Item()
        class Link(val name: String, val target: String) : Item()
    }

    private fun tar(items: List<Item>): File {
        val file = temp.newFile("pack.txz")
        TarArchiveOutputStream(XZCompressorOutputStream(file.outputStream())).use { t ->
            for (item in items) when (item) {
                is Item.Dir -> { t.putArchiveEntry(TarArchiveEntry(item.name)); t.closeArchiveEntry() }
                is Item.Data -> {
                    val bytes = item.text.toByteArray()
                    t.putArchiveEntry(TarArchiveEntry(item.name).apply { size = bytes.size.toLong() }); t.write(bytes); t.closeArchiveEntry()
                }
                is Item.Link -> { t.putArchiveEntry(TarArchiveEntry(item.name, TarConstants.LF_SYMLINK).apply { linkName = item.target }); t.closeArchiveEntry() }
            }
        }
        return file
    }

    private fun extract(archive: File, dest: File) = TarCompressorUtils.extractValidated(TarCompressorUtils.Type.XZ,
        RuntimeEnvironment.getApplication(), Uri.parse(archive.absolutePath), dest, true)

    @Test fun aLinkLeftPointingOutsideByALaterEntryFailsTheExtraction() {
        val dest = File(temp.newFolder("outer"), "inner/dest").apply { mkdirs() }
        val archive = tar(listOf(
            Item.Dir("a/"), Item.Dir("a/b/"), Item.Dir("a/b/c/"),
            Item.Link("x", "a/b/c"),
            Item.Link("p", "x/../.."),     // checked now: x/../.. = a, inside
            Item.Link("x", "."),           // x replaced: p is now ./../.. = outside the destination
        ))
        assertFalse(extract(archive, dest))
        assertFalse("the escaping link is removed", Files.isSymbolicLink(File(dest, "p").toPath()))
    }

    @Test fun linksThatStayInsideAreKept() {
        val dest = temp.newFolder("ok")
        val archive = tar(listOf(
            Item.Dir("lib/"), Item.Dir("lib/wine/"), Item.Data("lib/wine/wine", "elf"), Item.Dir("bin/"),
            Item.Link("bin/wine", "../lib/wine/wine"),
            Item.Dir(".wine/"), Item.Dir(".wine/dosdevices/"), Item.Dir(".wine/drive_c/"),
            Item.Link(".wine/dosdevices/c:", "../drive_c"),
            Item.Link(".wine/dosdevices/z:", "/"),
        ))
        assertTrue(extract(archive, dest))
        assertTrue(Files.isSymbolicLink(File(dest, "bin/wine").toPath()))
        assertTrue(Files.isSymbolicLink(File(dest, ".wine/dosdevices/z:").toPath()))
    }

    @Test fun theRecheckFindsAnEscapeAfterTheFact() {
        val root = temp.newFolder("tree")
        File(root, "d").mkdirs()
        val inside = File(root, "in").also { Files.createSymbolicLink(it.toPath(), File("d").toPath()) }
        val out = File(root, "out").also { Files.createSymbolicLink(it.toPath(), File("../..").toPath()) }
        assertTrue(TarCompressorUtils.linksStayInside(root, listOf(inside)))
        assertFalse(TarCompressorUtils.linksStayInside(root, listOf(inside, out)))
        assertTrue(Files.exists(inside.toPath()))
        assertFalse(Files.isSymbolicLink(out.toPath()))
    }
}
