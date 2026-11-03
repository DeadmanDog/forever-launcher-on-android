package app.gamenative.ui.screen.wow

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * WoW's local CASC storage (Data/data): data.NNN files of up to 1 GiB, 16 bucket index files (.idx, version 7) and the shmem
 * file the game keeps its index versions and free space in. Every field and checksum here was checked against the files the
 * game itself wrote on the phone (wowf 1.60.1, 2026-11-02), and a storage written this way was accepted by the game: it kept
 * all our entries, appended its own after them and carried ours into its next index versions.
 */
internal object WowCasc {
    const val GIB = 1L shl 30
    private const val HEADER = 30
    private val TABLE = intArrayOf(0x049396b8, 0x72a82a9b, 0xee626cca.toInt(), 0x9917754f.toInt(), 0x15de40b1, 0xf5a8a9b6.toInt(),
        0x421eac7e, 0xa9d55c9a.toInt(), 0x317fd40c, 0x04faf80d, 0x3d6be971, 0x52933cfd, 0x27f64b7d, 0xc6f5c11b.toInt(),
        0xd5757e3a.toInt(), 0x6c388745)

    private fun le32(d: ByteArray, i: Int) =
        (d[i].toInt() and 0xFF) or ((d[i + 1].toInt() and 0xFF) shl 8) or ((d[i + 2].toInt() and 0xFF) shl 16) or ((d[i + 3].toInt() and 0xFF) shl 24)

    /** Bob Jenkins' lookup3 hashlittle2: returns pc in the low and pb in the high 32 bits. */
    fun hashlittle2(d: ByteArray, off: Int, len: Int, pc: Int = 0, pb: Int = 0): Long {
        var a = 0xdeadbeef.toInt() + len + pc
        var b = a
        var c = a + pb
        var i = off
        var n = len
        while (n > 12) {
            a += le32(d, i); b += le32(d, i + 4); c += le32(d, i + 8)
            a -= c; a = a xor Integer.rotateLeft(c, 4); c += b
            b -= a; b = b xor Integer.rotateLeft(a, 6); a += c
            c -= b; c = c xor Integer.rotateLeft(b, 8); b += a
            a -= c; a = a xor Integer.rotateLeft(c, 16); c += b
            b -= a; b = b xor Integer.rotateLeft(a, 19); a += c
            c -= b; c = c xor Integer.rotateLeft(b, 4); b += a
            n -= 12; i += 12
        }
        if (n == 0) return (c.toLong() and 0xFFFFFFFFL) or (b.toLong() shl 32)
        val tail = ByteArray(12)
        System.arraycopy(d, i, tail, 0, n)
        a += le32(tail, 0); b += le32(tail, 4); c += le32(tail, 8)
        c = c xor b; c -= Integer.rotateLeft(b, 14)
        a = a xor c; a -= Integer.rotateLeft(c, 11)
        b = b xor a; b -= Integer.rotateLeft(a, 25)
        c = c xor b; c -= Integer.rotateLeft(b, 16)
        a = a xor c; a -= Integer.rotateLeft(c, 4)
        b = b xor a; b -= Integer.rotateLeft(a, 14)
        c = c xor b; c -= Integer.rotateLeft(b, 24)
        return (c.toLong() and 0xFFFFFFFFL) or (b.toLong() shl 32)
    }

    fun hashlittle(d: ByteArray, off: Int, len: Int, init: Int): Int = hashlittle2(d, off, len, init, 0).toInt()

    fun bucket(key: ByteArray, off: Int = 0): Int {
        var x = 0
        for (i in 0 until 9) x = x xor (key[off + i].toInt() and 0xFF)
        return (x and 0xF) xor (x ushr 4)
    }

    /** The 30-byte header in front of every entry of a data file: reversed key, size, flags and its two checksums. */
    fun header(key16: ByteArray, size: Int, flags: Int, archive: Int, offset: Long): ByteArray {
        val h = ByteArray(HEADER)
        for (i in 0 until 16) h[i] = key16[15 - i]
        val bb = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN)
        bb.putInt(16, size); bb.putShort(20, flags.toShort())
        bb.putInt(22, hashlittle(h, 0, 22, 0x3D6BE971))
        val o = ((offset and 0x3FFFFFFF) or ((archive.toLong() and 3) shl 30)).toInt()
        val enc = TABLE[(o + 0x1E) and 0xF] xor (o + 0x1E)
        val acc = IntArray(4)
        for (i in 0 until 0x1A) acc[(i + o) and 3] = acc[(i + o) and 3] xor (h[i].toInt() and 0xFF)
        for (j in 0 until 4) {
            val lane = (j + 0x1A + o) and 3
            h[26 + j] = (acc[lane] xor ((enc ushr (lane * 8)) and 0xFF)).toByte()
        }
        return h
    }

    /** Growable index entries of one bucket: 9-byte keys, location (archive shl 30 or offset) and size. */
    class Bucket {
        var keys = ByteArray(9 * 1024); var locs = LongArray(1024); var sizes = IntArray(1024); var count = 0
        fun add(key: ByteArray, keyOff: Int, loc: Long, size: Int) {
            if (count == locs.size) {
                keys = keys.copyOf(keys.size * 2); locs = locs.copyOf(locs.size * 2); sizes = sizes.copyOf(sizes.size * 2)
            }
            System.arraycopy(key, keyOff, keys, count * 9, 9); locs[count] = loc; sizes[count] = size; count++
        }
        fun prefix(i: Int): Long = ByteBuffer.wrap(keys, i * 9, 8).long
        /** Sorted by key (unsigned), the last of equal keys kept. */
        fun sortedOrder(): IntArray {
            val order = (0 until count).sortedWith { x, y ->
                for (k in 0 until 9) {
                    val d = (keys[x * 9 + k].toInt() and 0xFF) - (keys[y * 9 + k].toInt() and 0xFF)
                    if (d != 0) return@sortedWith d
                }
                x - y
            }
            val out = ArrayList<Int>(order.size)
            for (i in order) {
                if (out.isNotEmpty() && (0 until 9).all { keys[out.last() * 9 + it] == keys[i * 9 + it] }) out[out.size - 1] = i else out += i
            }
            return out.toIntArray()
        }
    }

    /**
     * A local storage opened for appending (the game must not run). Existing entries come from each bucket's newest index,
     * its sorted part and its update journal (a key whose "allocated" record (status 2) is fully covered by "written" records
     * (status 6) counts; status 1 records are single complete ones). New entries go after the last data file's end, and a
     * [checkpoint] writes new index versions and the shmem free-space table, so a stop at any time loses at most the data
     * written since the last checkpoint.
     */
    class Storage private constructor(val dir: File, private val template: ByteArray) {
        val buckets = Array(16) { Bucket() }
        private val versions = IntArray(16)
        private var shmem = template.copyOf()
        private var cross = DEFAULT_CROSS
        private var file = 0
        private var pos = 0L
        private var raf: RandomAccessFile? = null
        private val lengths = HashMap<Int, Long>()
        private val holes = ArrayList<Pair<Long, Long>>() // other free entries of the game (size, packed position), kept
        var dirty = false; private set
        /** True when [load] put back missing segment headers (a stop between a new data file and the next checkpoint). */
        var repaired = false; private set

        companion object {
            private val DEFAULT_CROSS = byteArrayOf(0xe9.toByte(), 0x30, 0xc1.toByte(), 0x49, 0x5d, 0x56, 0xf9.toByte(), 0xc3.toByte(),
                0x8c.toByte(), 0xad.toByte(), 0xb3.toByte(), 0xf7.toByte(), 0x95.toByte())

            fun open(dir: File, template: ByteArray): Storage = Storage(dir, template).also { it.load() }
        }

        private fun load() {
            dir.mkdirs()
            val shm = File(dir, "shmem")
            if (shm.isFile) shmem = shm.readBytes()
            val bb = ByteBuffer.wrap(shmem).order(ByteOrder.LITTLE_ENDIAN)
            for (b in 0 until 16) versions[b] = if (shm.isFile) bb.getInt(0x110 + 4 * b) else 0
            dir.listFiles()?.forEach { f ->
                Regex("""data\.(\d{3})""").matchEntire(f.name)?.let { lengths[it.groupValues[1].toInt()] = f.length() }
            }
            // The cross-link key of this storage (MD5 of the host's data path) from data.000's first header.
            File(dir, "data.000").takeIf { it.length() >= HEADER }?.let { f ->
                val h = ByteArray(16); RandomAccessFile(f, "r").use { it.readFully(h) }
                cross = ByteArray(13) { h[12 - it] }
            }
            if (shm.isFile) {
                val blk = bb.getInt(0x10C)
                val n = bb.getInt(blk + 4)
                for (i in 0 until n) {
                    val size = be40(shmem, blk + 0x20 + i * 5)
                    val packed = be40(shmem, blk + 0x20 + 5450 + i * 5)
                    val dn = (packed ushr 30).toInt()
                    if (lengths.containsKey(dn) && (packed and 0x3FFFFFFF) + size < GIB) holes += size to packed
                }
            }
            for (b in 0 until 16) if (versions[b] > 0) readIndex(b, File(dir, idxName(b, versions[b])))
            ensureSegmentHeaders()
            file = lengths.keys.maxOrNull() ?: -1
            pos = if (file >= 0) lengths.getValue(file) else 0L
            if (file < 0 || pos >= GIB - HEADER) newFile()
        }

        private fun be40(d: ByteArray, o: Int): Long {
            var v = 0L
            for (i in 0 until 5) v = (v shl 8) or (d[o + i].toLong() and 0xFF)
            return v
        }

        private fun idxName(b: Int, v: Int) = String.format("%02x%08x.idx", b, v)

        private fun readIndex(b: Int, f: File) {
            if (!f.isFile) return
            val d = f.readBytes()
            val bb = ByteBuffer.wrap(d).order(ByteOrder.LITTLE_ENDIAN)
            val es = bb.getInt(0x20)
            var i = 0x28
            while (i + 18 <= 0x28 + es) {
                buckets[b].add(d, i, be40(d, i + 9), bb.getInt(i + 14)); i += 18
            }
            // Update journal: 512-byte pages from the next 4 KiB boundary, 21 records of 24 bytes each.
            class Alloc(val key: ByteArray, val loc: Long, val size: Int) { var written = 0L }
            val allocs = LinkedHashMap<String, Alloc>()
            var page = ((0x28 + es) + 0xFFF) and 0xFFF.inv()
            while (page + 0x200 <= d.size) {
                for (k in 0 until 21) {
                    val o = page + k * 24
                    if ((0 until 24).all { d[o + it].toInt() == 0 }) continue
                    if ((hashlittle(d, o + 4, 19, 0) or Int.MIN_VALUE) != bb.getInt(o)) continue
                    val key = d.copyOfRange(o + 4, o + 13)
                    val id = key.joinToString("") { "%02x".format(it) }
                    val loc = be40(d, o + 13); val size = bb.getInt(o + 18)
                    when (d[o + 22].toInt()) {
                        1 -> buckets[b].add(key, 0, loc, size)
                        2 -> allocs[id] = Alloc(key, loc, size)
                        6 -> allocs[id]?.let { a -> if (loc >= a.loc && loc < a.loc + a.size) a.written += size }
                    }
                }
                page += 0x200
            }
            for (a in allocs.values) if (a.written >= a.size) buckets[b].add(a.key, 0, a.loc, a.size)
        }

        private fun crossKey(n: Int, i: Int): ByteArray {
            val key = ByteArray(16)
            key[0] = (((-i) and 0xF) xor (n and 0xF)).toByte(); key[1] = (n and 0xFF).toByte(); key[2] = 0
            System.arraycopy(cross, 0, key, 3, 13)
            return key
        }

        /**
         * Every data file starts with 16 segment headers (cross-links), and the game refuses a storage whose index lacks them
         * ("Missing segment header for archive 'N', repair needed"). A download stopped after it opened a new data file but before
         * the next checkpoint left such a file; this puts the index entries back (and rewrites the headers of a file that holds
         * nothing indexed yet when they are not there).
         */
        private fun ensureSegmentHeaders() {
            val indexed = HashSet<Long>()
            val used = HashSet<Int>()
            for (bk in buckets) for (i in 0 until bk.count) {
                if (bk.sizes[i] == HEADER) indexed += bk.locs[i]
                used += (bk.locs[i] ushr 30).toInt()
            }
            for (n in lengths.keys.sorted()) {
                val f = File(dir, String.format("data.%03d", n))
                val have = ByteArray(16 * HEADER)
                val read = RandomAccessFile(f, "r").use { r -> var got = 0; while (got < have.size) { val k = r.read(have, got, have.size - got); if (k < 0) break; got += k }; got }
                val headersOk = read == have.size && (0 until 16).all { i ->
                    header(crossKey(n, i), HEADER, 1, n, i * HEADER.toLong()).contentEquals(have.copyOfRange(i * HEADER, (i + 1) * HEADER))
                }
                if (!headersOk) {
                    if (n in used) continue // indexed data inside: not ours to rewrite
                    RandomAccessFile(f, "rw").use { r ->
                        for (i in 0 until 16) { r.seek(i * HEADER.toLong()); r.write(header(crossKey(n, i), HEADER, 1, n, i * HEADER.toLong())) }
                    }
                    lengths[n] = maxOf(f.length(), 16L * HEADER)
                }
                for (i in 0 until 16) {
                    val loc = (n.toLong() shl 30) or (i * HEADER).toLong()
                    if (loc in indexed) continue
                    val key = crossKey(n, i)
                    buckets[(bucket(key) + 1) % 16].add(key, 0, loc, HEADER)
                    dirty = true; repaired = true
                }
            }
        }

        private fun newFile() {
            raf?.let { it.fd.sync(); it.close() }
            file += 1; pos = 0
            val r = RandomAccessFile(File(dir, String.format("data.%03d", file)), "rw")
            r.setLength(0)
            raf = r
            for (i in 0 until 16) {
                val key = crossKey(file, i)
                r.write(header(key, HEADER, 1, file, pos))
                buckets[(bucket(key) + 1) % 16].add(key, 0, (file.toLong() shl 30) or pos, HEADER)
                pos += HEADER
            }
            lengths[file] = pos; dirty = true
            // Saved at once: a new data file is never left without its segment headers in the index, whatever stops the app
            // next (an update of the app during a download did exactly that to data.026 on the phone).
            checkpoint()
        }

        private fun out(): RandomAccessFile = raf ?: RandomAccessFile(File(dir, String.format("data.%03d", file)), "rw").also {
            it.seek(pos); raf = it
        }

        /** Appends one entry (its BLTE-encoded bytes, keyed by the 16-byte encoding key). */
        fun add(key16: ByteArray, data: ByteArray, off: Int = 0, len: Int = data.size) {
            val size = HEADER + len
            if (pos + size > GIB) newFile()
            val r = out()
            r.seek(pos)
            r.write(header(key16, size, 0, file, pos)); r.write(data, off, len)
            buckets[bucket(key16)].add(key16, 0, (file.toLong() shl 30) or pos, size)
            pos += size; lengths[file] = pos; dirty = true
        }

        /** Data on disk, then the new index versions, then shmem: an interruption before shmem leaves the previous state valid. */
        fun checkpoint() {
            if (!dirty) return
            raf?.fd?.sync()
            for (b in 0 until 16) {
                val v = versions[b] + 1
                writeIndex(b, v)
                File(dir, idxName(b, v - 3)).takeIf { v - 3 > 0 }?.delete()
                versions[b] = v
            }
            writeShmem()
            dirty = false
        }

        fun close() {
            checkpoint()
            raf?.close(); raf = null
        }

        private fun writeIndex(b: Int, v: Int) {
            val bk = buckets[b]
            val order = bk.sortedOrder()
            val body = ByteArray(order.size * 18)
            var pc = 0; var pb = 0
            order.forEachIndexed { n, i ->
                val o = n * 18
                System.arraycopy(bk.keys, i * 9, body, o, 9)
                val loc = bk.locs[i]
                for (k in 0 until 5) body[o + 9 + k] = (loc ushr (8 * (4 - k))).toByte()
                ByteBuffer.wrap(body, o + 14, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(bk.sizes[i])
                val h = hashlittle2(body, o, 18, pc, pb); pc = h.toInt(); pb = (h ushr 32).toInt()
            }
            val start = (0x28 + body.size + 0xFFF) and 0xFFF.inv()
            val total = (start + 0x8000 + 0xFFFF) and 0xFFFF.inv()
            val out = ByteArray(total)
            val bb = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN)
            bb.putShort(8, 7); out[10] = b.toByte(); out[11] = 0; out[12] = 4; out[13] = 5; out[14] = 9; out[15] = 30
            bb.putLong(16, 0x000000ffc0000000L)
            bb.putInt(0, 0x10); bb.putInt(4, hashlittle2(out, 8, 16).toInt())
            bb.putInt(0x20, body.size); bb.putInt(0x24, pc)
            System.arraycopy(body, 0, out, 0x28, body.size)
            val tmp = File(dir, idxName(b, v) + ".tmp")
            RandomAccessFile(tmp, "rw").use { it.setLength(0); it.write(out); it.fd.sync() }
            check(tmp.renameTo(File(dir, idxName(b, v)))) { "Cannot write index ${idxName(b, v)}" }
        }

        private fun writeShmem() {
            val sh = shmem.copyOf(maxOf(shmem.size, template.size))
            val bb = ByteBuffer.wrap(sh).order(ByteOrder.LITTLE_ENDIAN)
            for (b in 0 until 16) bb.putInt(0x110 + 4 * b, versions[b])
            bb.putInt(0x150, maxOf(bb.getInt(0x150), file))
            val blk = bb.getInt(0x10C)
            val free = ArrayList<Pair<Long, Long>>()
            free += holes.filter { (it.second ushr 30).toInt() < file }
            free += (GIB - pos) to ((file.toLong() shl 30) or pos)
            for (k in file + 1 until 1023) free += GIB to (k.toLong() shl 30)
            val used = lengths.values.sum()
            for (i in blk until blk + 0x20 + 10900) sh[i] = 0
            bb.putInt(blk, 1); bb.putInt(blk + 4, free.size); bb.putInt(blk + 8, 1)
            bb.putInt(blk + 16, used.toInt()); bb.putInt(blk + 24, ((file + 1) * GIB - used).toInt())
            free.forEachIndexed { i, (size, packed) ->
                for (k in 0 until 5) {
                    sh[blk + 0x20 + i * 5 + k] = (size ushr (8 * (4 - k))).toByte()
                    sh[blk + 0x20 + 5450 + i * 5 + k] = (packed ushr (8 * (4 - k))).toByte()
                }
            }
            val tmp = File(dir, "shmem.tmp")
            RandomAccessFile(tmp, "rw").use { it.setLength(0); it.write(sh); it.fd.sync() }
            check(tmp.renameTo(File(dir, "shmem"))) { "Cannot write shmem" }
            shmem = sh
        }

        /** Sorted 8-byte key prefixes of every entry present, for skipping what the storage already has. */
        fun presentPrefixes(): LongArray {
            val all = LongArray(buckets.sumOf { it.count })
            var n = 0
            for (bk in buckets) for (i in 0 until bk.count) all[n++] = bk.prefix(i)
            all.sort()
            return all
        }
    }
}
