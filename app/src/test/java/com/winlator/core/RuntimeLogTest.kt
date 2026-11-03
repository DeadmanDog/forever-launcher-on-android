package com.winlator.core

import org.junit.*
import org.junit.Assert.*
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class RuntimeLogTest {
    @get:Rule val temp = TemporaryFolder()
    @Test fun logIsBoundedEvenWithoutNewlinesAndAfterClose() {
        val file=temp.newFile()
        val log=RuntimeLog(file,17)
        val bytes=ByteArray(1024) { 65 }
        repeat(100) { log.append(bytes,bytes.size) }
        log.close();log.append(bytes,bytes.size)
        assertEquals(17L,file.length())
        assertArrayEquals(ByteArray(17) { 65 },file.readBytes())
    }
    @Test fun rotatingLogRetainsTheMostRecentOutputWithinTwoFiles() {
        val file=temp.newFile()
        RuntimeLog(file,4,true).use { log ->
            val bytes="abcdefghijklmn".toByteArray();log.append(bytes,bytes.size)
        }
        assertEquals("mn",file.readText())
        assertEquals("ijkl",File(file.path+".previous").readText())
        assertEquals(2,temp.root.listFiles()!!.size)
    }
    @Test fun startupPrefixSurvivesTailRotationAndOneReaderClosing() {
        val startupFile = temp.newFile("startup.log")
        val tailFile = temp.newFile("stderr.log")
        val startup = RuntimeStartupLog(startupFile)
        val first = "startup marker\n".toByteArray()
        startup.append(first, first.size)
        startup.readerFinished()
        val noisy = ByteArray(8192) { 65 }
        RuntimeLog(tailFile, 1024, true).use { tail ->
            repeat(100) { startup.append(noisy, noisy.size); tail.append(noisy, noisy.size) }
        }
        startup.readerFinished()
        assertEquals(RuntimeStartupLog.MAX_BYTES, startupFile.length())
        assertTrue(startupFile.readText().startsWith("startup marker\n"))
        assertFalse(tailFile.readText().contains("startup marker"))
    }
    private fun drain(destination: File?) {
        val bytes=ByteArray(RuntimeLog.MAX_BYTES.toInt()+65536) { 65 }
        val done=CountDownLatch(1)
        val stream=object : ByteArrayInputStream(bytes) {
            override fun close() { super.close();done.countDown() }
        }
        val method=ProcessHelper::class.java.getDeclaredMethod("drainOutput",java.io.InputStream::class.java,File::class.java).apply { isAccessible=true }
        method.invoke(null,stream,destination)
        assertTrue("Drain worker must finish even when log is full/unavailable",done.await(10,TimeUnit.SECONDS))
        assertEquals(0,stream.available())
    }
    @Test fun fullLogStillDrainsAllChildOutput() {
        val file=temp.newFile();drain(file);assertEquals(65536L,file.length());assertEquals(RuntimeLog.MAX_BYTES,File(file.path+".previous").length())
    }
    @Test fun failedLogCreationStillDrainsAllChildOutput() { drain(temp.newFolder()) }
    @Test fun disabledLoggingDrainsWithoutCreatingFiles() { drain(null);assertTrue(temp.root.listFiles()!!.isEmpty()) }
}
