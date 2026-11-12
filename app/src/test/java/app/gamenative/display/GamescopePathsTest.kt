package app.gamenative.display

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class GamescopePathsTest {
    @Test fun ownerSecondaryAndBetaInstallPathsResolveToTheirOwnPrivateAlias() {
        for (root in listOf("/data/user/0/forever.launcher", "/data/user/10/forever.launcher", "/data/user/0/forever.launcher.beta")) {
            val prefix = GamescopePaths.prefix(root)
            assertEquals(GamescopePaths.ORIGINAL.length, prefix.toByteArray().size)
            assertEquals(File(root, GamescopePaths.ALIAS).path, File(prefix).normalize().path)
            assertFalse(prefix.contains("app.gamenative"))
        }
    }
    @Test fun codeAndStringOffsetsAreUnchangedWhileAllEmbeddedPathsAreRelocated() {
        val old = GamescopePaths.ORIGINAL
        val input = ("ELF-CODE\u0000$old/bin\u0000$old/share/X11/xkb\u0000$old/tmp/xkb/\u0000TAIL").toByteArray()
        val prefix = GamescopePaths.prefix("/data/user/0/forever.launcher")
        val actual = GamescopePaths.relocate(input, prefix, 3)
        assertArrayEquals(String(input).replace(old,prefix).toByteArray(),actual)
        assertEquals(input.size,actual.size)
        assertFalse(String(actual).contains(old))
        for (i in input.indices) if (input[i] == 0.toByte()) assertEquals(0.toByte(),actual[i])
    }
    @Test fun unexpectedPayloadAndOversizeInstallPathAreRejected() {
        assertThrows(IllegalStateException::class.java) { GamescopePaths.relocate(ByteArray(64),GamescopePaths.prefix("/data/user/0/forever.launcher"),1) }
        assertThrows(IllegalArgumentException::class.java) { GamescopePaths.relocate(GamescopePaths.ORIGINAL.toByteArray(),"short",1) }
        assertThrows(IllegalArgumentException::class.java) { GamescopePaths.prefix("/data/user/0/"+"a".repeat(60)) }
    }
}
