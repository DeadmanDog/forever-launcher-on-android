package app.gamenative.display

import java.io.ByteArrayInputStream
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class GamescopeRandrLibraryTest {
    @get:Rule val temp = TemporaryFolder()
    private fun asset() = listOf(File("src/main/assets"), File("app/src/main/assets"))
        .map { File(it, GamescopeRandrLibrary.ASSET) }.first { it.isFile }

    @Test fun theRealBundledArm64LibraryInstallsAndVerifies() {
        val stage = temp.newFolder()
        assertFalse(GamescopeRandrLibrary.isReady(stage))
        asset().inputStream().use { GamescopeRandrLibrary.install(it, stage) }
        assertTrue(GamescopeRandrLibrary.isReady(stage))
        assertArrayEquals(asset().readBytes(), File(stage, "lib/libXrandr.so").readBytes())
    }
    @Test fun aMissingLibraryInvalidatesTheOtherwisePreparedPayload() {
        val stage = temp.newFolder()
        asset().inputStream().use { GamescopeRandrLibrary.install(it, stage) }
        assertTrue(File(stage, "lib/libXrandr.so").delete())
        assertFalse(GamescopeRandrLibrary.isReady(stage))
    }
    @Test fun corruptLibraryCannotBeInstalledOrConsideredReady() {
        val bytes = asset().readBytes().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        val stage = temp.newFolder()
        assertThrows(IllegalStateException::class.java) { GamescopeRandrLibrary.install(ByteArrayInputStream(bytes), stage) }
        assertFalse(File(stage, "lib/libXrandr.so").exists())
        asset().inputStream().use { GamescopeRandrLibrary.install(it, stage) }
        File(stage, "lib/libXrandr.so").writeBytes(bytes)
        assertFalse(GamescopeRandrLibrary.isReady(stage))
    }
    @Test fun nonArm64AndTruncatedLibrariesAreRejectedBeforeWriting() {
        for (bytes in listOf(byteArrayOf(0x7f), asset().readBytes().also { it[18] = 62 })) {
            val stage = temp.newFolder()
            assertThrows(IllegalStateException::class.java) { GamescopeRandrLibrary.install(ByteArrayInputStream(bytes), stage) }
            assertFalse(File(stage, "lib/libXrandr.so").exists())
        }
    }
}
