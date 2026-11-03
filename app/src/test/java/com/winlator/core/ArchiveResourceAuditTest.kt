package com.winlator.core

import android.app.Application
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [29])
class ArchiveResourceAuditTest {
    @get:Rule val temp = TemporaryFolder()
    @Test fun corruptArchiveClosesSourceEvenWhenDecompressorConstructionFails() {
        val stream = object : ByteArrayInputStream("invalid archive".toByteArray()) {
            var closed = false
            override fun close() { closed = true; super.close() }
        }
        assertFalse(TarCompressorUtils.extract(TarCompressorUtils.Type.XZ, stream, temp.newFolder()))
        assertTrue(stream.closed)
    }
    @Test fun fileCopyTransfersEntirePayload() {
        val source = temp.newFile().apply { writeBytes(ByteArray(1024 * 1024 + 17) { (it % 251).toByte() }) }
        val destination = File(temp.newFolder(), "copy")
        assertTrue(FileUtils.copy(source, destination))
        assertArrayEquals(source.readBytes(), destination.readBytes())
    }
    @Test fun failedCopyReturnsFailureAndSourceRemainsUsable() {
        val source = temp.newFile().apply { writeText("original") }
        val destination = temp.newFolder()
        assertFalse(FileUtils.copy(source, destination))
        assertEquals("original", source.readText())
    }
}
