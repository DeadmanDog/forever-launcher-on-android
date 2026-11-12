package app.gamenative.ui.screen.wow

import java.io.File
import java.nio.file.Files
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

internal object DiagnosticExport {
    /** No single log may grow the export past this: a longer file contributes only its newest bytes. */
    const val MAX_ENTRY_BYTES = 4L * 1024 * 1024

    /** Writes the file, or its last [MAX_ENTRY_BYTES] (without the NUL hole a truncated-while-open log can start with). */
    internal fun writeBounded(file: File, out: java.io.OutputStream) {
        java.io.RandomAccessFile(file, "r").use { input ->
            val length = input.length()
            val start = if (length > MAX_ENTRY_BYTES) length - MAX_ENTRY_BYTES else 0L
            // A cut CSV keeps its header and starts on a whole row (it started mid-row without a header, 1.1.1 diag).
            val csv = start > 0 && (file.name.endsWith(".csv", ignoreCase = true) || file.name.endsWith(".csv.previous", ignoreCase = true))
            if (csv) {
                val header = java.io.ByteArrayOutputStream()
                input.seek(0)
                while (header.size() < 4096) { val b = input.read(); if (b < 0) break; header.write(b); if (b == '\n'.code) break }
                out.write(header.toByteArray())
            }
            input.seek(start)
            val buffer = ByteArray(64 * 1024)
            var skippingZeros = start > 0
            var skippingPartialRow = csv
            // The end point is fixed now: a log that keeps growing while it is copied cannot push the entry past its budget.
            var remaining = length - start
            while (remaining > 0) {
                val count = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                if (count < 0) break
                remaining -= count
                var from = 0
                if (skippingZeros) {
                    while (from < count && buffer[from] == 0.toByte()) from++
                    if (from < count) skippingZeros = false
                }
                if (skippingPartialRow && !skippingZeros) {
                    while (from < count && buffer[from] != '\n'.code.toByte()) from++
                    if (from < count) { from++; skippingPartialRow = false }
                }
                if (from < count) out.write(buffer, from, count - from)
            }
        }
    }

    fun create(root: File, cache: File): File {
        check(root.isDirectory) { "No diagnostic logs recorded yet." }
        val base=root.canonicalFile.toPath()
        val files=root.walkTopDown().onEnter { !Files.isSymbolicLink(it.toPath()) && it.canonicalFile.toPath().startsWith(base) }
            .filter { it.isFile && !Files.isSymbolicLink(it.toPath()) && it.canonicalFile.toPath().startsWith(base) }.toList()
        check(files.isNotEmpty()) { "No diagnostic logs recorded yet." }
        check(cache.isDirectory || cache.mkdirs())
        cache.listFiles().orEmpty().filter { it.name.startsWith("wow-diagnostics-") && it.extension=="zip" }.forEach { it.delete() }
        val output=File(cache,"wow-diagnostics-${UUID.randomUUID()}.zip")
        try {
            ZipOutputStream(output.outputStream().buffered()).use { zip ->
                files.forEach { file ->
                    zip.putNextEntry(ZipEntry(base.relativize(file.canonicalFile.toPath()).toString().replace('\\','/')))
                    writeBounded(file, zip);zip.closeEntry()
                }
            }
        } catch(e:Exception) { output.delete();throw e }
        return output
    }
}
