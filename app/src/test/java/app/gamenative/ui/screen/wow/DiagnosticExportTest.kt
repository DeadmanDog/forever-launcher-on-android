package app.gamenative.ui.screen.wow

import org.junit.Test
import org.junit.Assert.*
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipFile

class DiagnosticExportTest {
    @get:Rule val temp=TemporaryFolder()
    @Test fun onlyDiagnosticRootIsExportedAndExternalSymlinksAreSkipped() {
        val root=temp.newFolder();val cache=temp.newFolder();val external=temp.newFile().apply { writeText("outside") }
        File(root,"session-test").mkdir();File(root,"session-test/stderr.log").writeText("error")
        Files.createSymbolicLink(File(root,"outside.log").toPath(),external.toPath())
        val file=DiagnosticExport.create(root,cache)
        ZipFile(file).use { zip ->
            assertEquals(listOf("session-test/stderr.log"),zip.entries().asSequence().map { it.name }.toList())
            assertEquals("error",zip.getInputStream(zip.getEntry("session-test/stderr.log")).reader().readText())
        }
    }
    @Test fun missingLogsAreVisibleFailureWithoutExportFile() {
        val cache=temp.newFolder()
        assertThrows(IllegalStateException::class.java) { DiagnosticExport.create(File(temp.root,"missing"),cache) }
        assertTrue(cache.listFiles()!!.isEmpty())
    }
}

class DiagnosticExportBoundTest {
    @org.junit.Rule @JvmField val temp = org.junit.rules.TemporaryFolder()

    @org.junit.Test fun aSmallFileIsExportedWhole() {
        val f = temp.newFile("small.log").also { it.writeText("hello") }
        val out = java.io.ByteArrayOutputStream()
        DiagnosticExport.writeBounded(f, out)
        org.junit.Assert.assertEquals("hello", out.toString())
    }

    @org.junit.Test fun aHugeFileContributesOnlyItsNewestBytesWithoutALeadingZeroHole() {
        val f = temp.newFile("big.log")
        java.io.RandomAccessFile(f, "rw").use {
            it.setLength(DiagnosticExport.MAX_ENTRY_BYTES * 2)   // sparse: the leading hole reads as zeros
            it.seek(DiagnosticExport.MAX_ENTRY_BYTES * 2 - 5); it.write("ENDED".toByteArray())
        }
        val out = java.io.ByteArrayOutputStream()
        DiagnosticExport.writeBounded(f, out)
        org.junit.Assert.assertEquals("ENDED", out.toString())
    }

    @org.junit.Test fun aLogThatKeepsGrowingWhileItIsCopiedStaysInsideTheBudget() {
        val f = temp.newFile("growing.log")
        java.io.RandomAccessFile(f, "rw").use { it.setLength(DiagnosticExport.MAX_ENTRY_BYTES) }
        f.outputStream().use { it.write(ByteArray(DiagnosticExport.MAX_ENTRY_BYTES.toInt()) { 'a'.code.toByte() }) }
        var grown = false
        val sink = object : java.io.OutputStream() {
            var written = 0L
            private fun growOnce() {
                if (grown) return
                grown = true
                java.io.FileOutputStream(f, true).use { it.write(ByteArray(1024 * 1024) { 'b'.code.toByte() }) }
            }
            override fun write(b: Int) { growOnce(); written++ }
            override fun write(b: ByteArray, off: Int, len: Int) { growOnce(); written += len }
        }
        DiagnosticExport.writeBounded(f, sink)
        org.junit.Assert.assertTrue("the source did grow during the copy", grown && f.length() > DiagnosticExport.MAX_ENTRY_BYTES)
        org.junit.Assert.assertEquals("the entry stops at the end point read at the start", DiagnosticExport.MAX_ENTRY_BYTES, sink.written)
    }
}

class LogSizeGuardTest {
    @org.junit.Rule @JvmField val temp = org.junit.rules.TemporaryFolder()

    @org.junit.Test fun anOversizedLogIsCopiedAndEmptied() {
        val log = temp.newFile("pulse.log").also { it.writeBytes(ByteArray(2000) { 7 }) }
        org.junit.Assert.assertFalse(com.winlator.core.LogSizeGuard.trimOnce(log, 5000))
        org.junit.Assert.assertTrue(com.winlator.core.LogSizeGuard.trimOnce(log, 1000))
        org.junit.Assert.assertEquals(0L, log.length())
        org.junit.Assert.assertEquals(2000L, java.io.File(log.path + ".1").length())
    }
}
