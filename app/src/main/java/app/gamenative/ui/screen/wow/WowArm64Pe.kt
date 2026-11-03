package app.gamenative.ui.screen.wow

import java.io.File
import java.io.RandomAccessFile

/** Validates the actual Windows ARM64 payload, not a filename or profile label. */
internal object WowArm64Pe {
    fun validate(file: File, requiredExports: Set<String> = emptySet(), dll: Boolean = true) {
        RandomAccessFile(file, "r").use { f ->
            fun u16(): Int = java.lang.Short.reverseBytes(f.readShort()).toInt() and 65535
            fun u32(): Long = Integer.reverseBytes(f.readInt()).toLong() and 0xffffffffL
            fun range(offset: Long, bytes: Long) {
                require(offset >= 0 && bytes >= 0 && offset <= f.length() && bytes <= f.length() - offset) { "Truncated PE payload: ${file.name}" }
            }
            range(0, 64); require(u16() == 0x5a4d) { "Not a Windows PE: ${file.name}" }
            f.seek(60); val pe = u32(); range(pe, 24); f.seek(pe)
            require(u32() == 0x4550L && u16() == 0xaa64) { "Windows ARM64 (0xAA64) required; ARM64EC/x64 DLLs are different ABIs: ${file.name}" }
            val sections = u16(); f.skipBytes(12); val optionalSize = u16(); val flags = u16()
            require(sections in 1..96 && (!dll || flags and 0x2000 != 0)) { "Invalid PE DLL: ${file.name}" }
            val optional = pe + 24; range(optional, optionalSize.toLong()); f.seek(optional)
            require(u16() == 0x20b && optionalSize >= 120) { "PE32+ required: ${file.name}" }
            if (requiredExports.isEmpty()) return
            f.seek(optional + 60); val headerSize = u32()
            f.seek(optional + 108); require(u32() >= 1) { "PE export directory missing." }
            val exportRva = u32(); val exportSize = u32(); require(exportRva > 0 && exportSize >= 40) { "PE exports missing." }
            data class Section(val rva: Long, val size: Long, val offset: Long)
            val table = optional + optionalSize; range(table, sections * 40L)
            val entries = (0 until sections).map { i ->
                f.seek(table + i * 40L + 12); val va = u32(); val raw = u32(); val off = u32(); range(off, raw)
                Section(va, raw, off)
            }
            fun offset(rva: Long, count: Long): Long {
                if (rva < headerSize) { range(rva, count); require(count <= headerSize - rva); return rva }
                val section = entries.singleOrNull { rva >= it.rva && rva - it.rva < it.size }
                    ?: error("Invalid PE RVA: ${file.name}")
                require(count <= section.size - (rva - section.rva)) { "PE directory exceeds section." }
                return section.offset + rva - section.rva
            }
            val exports = offset(exportRva, 40); f.seek(exports + 24); val count = u32()
            require(count in 1..65536); f.skipBytes(4); val names = u32(); val nameArray = offset(names, count * 4)
            val found = mutableSetOf<String>()
            repeat(count.toInt()) { i ->
                f.seek(nameArray + i * 4L); val nameRva = u32()
                val text = StringBuilder()
                var terminated = false
                for (j in 0 until 512) {
                    f.seek(offset(nameRva + j, 1)); val b = f.readUnsignedByte()
                    if (b == 0) { terminated = true; break }
                    require(b in 32..126); text.append(b.toChar())
                }
                require(terminated) { "Invalid PE export name." }; found += text.toString()
            }
            require(found.containsAll(requiredExports)) { "Missing exports in ${file.name}: ${requiredExports - found}" }
        }
    }
}
