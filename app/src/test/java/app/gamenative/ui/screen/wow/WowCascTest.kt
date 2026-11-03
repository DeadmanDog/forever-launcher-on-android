package app.gamenative.ui.screen.wow

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Test vectors from the game's own storage on the phone (wowf 1.60.1, 2026-11-02). */
class WowCascTest {
    @get:Rule val temp = TemporaryFolder()

    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    @Test fun indexHeaderHashMatchesTheGame() {
        val head = hex("100000006b04c01b070000000405091e000000c0ff0000000000000000000000c89d00006a58af44")
        assertEquals(0x1bc0046b, WowCasc.hashlittle2(head, 8, 16).toInt())
    }

    @Test fun dataFileHeadersMatchTheGame() {
        // data.002's first and sixth cross-link headers, written by the game.
        val first = hex("95f7b3ad8cc3f9565d49c130e90002021e000000010099f3de0d656f480e")
        val sixth = hex("95f7b3ad8cc3f9565d49c130e90002091e0000000100ea0dbb6e668fd786")
        for ((h, offset) in listOf(first to 0L, sixth to 150L)) {
            val key = ByteArray(16) { h[15 - it] }
            assertArrayEquals(h, WowCasc.header(key, 30, 1, 2, offset))
        }
    }

    @Test fun aWrittenStorageReadsBackWithAllEntriesAndValidIndexes() {
        val dir = temp.newFolder("data")
        val template = listOf(File("src/main/assets/casc/shmem.template"), File("app/src/main/assets/casc/shmem.template")).first { it.isFile }.readBytes()
        val keys = (0 until 300).map { n -> ByteArray(16) { (if (it == 0) n and 0xFF else if (it == 1) n ushr 8 else (n * 31 + it * 7) and 0xFF).toByte() } }
        WowCasc.Storage.open(dir, template).apply {
            keys.forEachIndexed { n, k -> add(k, ByteArray(100 + n) { it.toByte() }) }
            close()
        }
        // 16 index files of version 1, each with a valid header hash and a valid chained entries hash.
        // The newest version of each bucket (a new data file is checkpointed at once, then again at the end).
        val idx = dir.listFiles { f -> f.name.endsWith(".idx") }!!.groupBy { it.name.take(2) }.values.map { v -> v.maxBy { it.name } }
        assertEquals(16, idx.size)
        var entries = 0
        for (f in idx) {
            val d = f.readBytes(); val bb = ByteBuffer.wrap(d).order(ByteOrder.LITTLE_ENDIAN)
            assertEquals(bb.getInt(4), WowCasc.hashlittle2(d, 8, 16).toInt())
            val es = bb.getInt(0x20); var pc = 0; var pb = 0
            for (i in 0 until es step 18) { val h = WowCasc.hashlittle2(d, 0x28 + i, 18, pc, pb); pc = h.toInt(); pb = (h ushr 32).toInt() }
            assertEquals(bb.getInt(0x24), pc)
            assertEquals(0, d.size % 0x10000)
            entries += es / 18
        }
        assertEquals(300 + 16, entries) // our entries and the data file's 16 cross-links
        // Reopened (as after a stop): every key is present, and a further entry goes after the existing data.
        val again = WowCasc.Storage.open(dir, template)
        val present = again.presentPrefixes()
        assertTrue(keys.all { k -> present.binarySearch(ByteBuffer.wrap(k, 0, 8).long) >= 0 })
        val before = File(dir, "data.000").length()
        again.add(ByteArray(16) { 0x55 }, ByteArray(10)); again.close()
        assertEquals(before + 40, File(dir, "data.000").length())
        // A stop after a new data file was opened but before the checkpoint: its segment headers are not in the index. Reopening
        // puts them back (the game refuses the storage without them: "Missing segment header for archive").
        WowCasc.Storage.open(dir, template).apply {
            for (b in 0 until 16) {                     // drop data.000's cross-link entries from the in-memory index
                val bk = buckets[b]; var w = 0
                for (r in 0 until bk.count) if (bk.sizes[r] != 30) {
                    System.arraycopy(bk.keys, r * 9, bk.keys, w * 9, 9); bk.locs[w] = bk.locs[r]; bk.sizes[w] = bk.sizes[r]; w++
                }
                bk.count = w
            }
            add(ByteArray(16) { 0x66 }, ByteArray(5)); close()
        }
        val fixed = WowCasc.Storage.open(dir, template)
        assertTrue(fixed.repaired)
        fixed.close()
        assertTrue(!WowCasc.Storage.open(dir, template).repaired)
        val sh = ByteBuffer.wrap(File(dir, "shmem").readBytes()).order(ByteOrder.LITTLE_ENDIAN)
        assertTrue(sh.getInt(0x110) >= 2)                        // index versions advanced
        val blk = sh.getInt(0x10C)
        val firstFreePos = ByteArray(5).also { System.arraycopy(File(dir, "shmem").readBytes(), blk + 0x20 + 5450, it, 0, 5) }
        assertEquals(before + 40 + 35, firstFreePos.fold(0L) { a, b -> (a shl 8) or (b.toLong() and 0xFF) } and 0x3FFFFFFF)
    }
}
