package app.gamenative.ui.screen.wow

import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.io.FileOutputStream

/** A two-DLL write-ahead journal. Recovery keeps backups until both targets are restored. */
internal object WowD3d12DllTransaction {
    val names = listOf("d3d12.dll", "d3d12core.dll")
    private fun durable(file: File, text: String) = FileOutputStream(file).use { it.write(text.toByteArray()); it.fd.sync() }
    fun snapshot(source: File, destination: File) {
        check(destination.mkdir()) { "Unable to create DLL snapshot." }
        val state = JSONObject()
        names.forEach { name ->
            val from = File(source, name)
            val kind = when {
                Files.isSymbolicLink(from.toPath()) -> "link"
                from.isFile -> "file"
                !Files.exists(from.toPath(), LinkOption.NOFOLLOW_LINKS) -> "absent"
                else -> error("DLL target is not a regular file: $name")
            }
            state.put(name, kind)
            if (kind == "link") Files.createSymbolicLink(File(destination, name).toPath(), Files.readSymbolicLink(from.toPath()))
            else if (kind == "file") {
                val copy = File(destination, name)
                Files.copy(from.toPath(), copy.toPath())
                FileOutputStream(copy, true).use { it.fd.sync() }
            }
        }
        durable(File(destination, "state.json"), state.toString())
    }
    fun fromSnapshot(snapshot: File): Map<String, File?> {
        val state = JSONObject(File(snapshot, "state.json").readText())
        return names.associateWith { name ->
            when (state.getString(name)) {
                "absent" -> null
                "file" -> File(snapshot, name).also { require(it.isFile && !Files.isSymbolicLink(it.toPath())) { "DLL backup is incomplete." } }
                "link" -> File(snapshot, name).also { require(Files.isSymbolicLink(it.toPath())) { "DLL link backup is incomplete." } }
                else -> error("Invalid DLL recovery state.")
            }
        }
    }
    private fun publish(source: File?, target: File) {
        if (source == null) { Files.deleteIfExists(target.toPath()); return }
        val tmp = File(target.parentFile, ".frontmage-${target.name}-${java.util.UUID.randomUUID()}")
        try {
            if (Files.isSymbolicLink(source.toPath())) Files.createSymbolicLink(tmp.toPath(), Files.readSymbolicLink(source.toPath()))
            else {
                Files.copy(source.toPath(), tmp.toPath())
                FileOutputStream(tmp, true).use { it.fd.sync() }
            }
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { Files.deleteIfExists(tmp.toPath()) }
    }
    fun recover(root: File, system32: File) {
        val journal = File(root, ".frontmage-dll-transaction")
        if (!journal.exists()) return
        require(system32.isDirectory && !Files.isSymbolicLink(system32.toPath()) && !Files.isSymbolicLink(journal.toPath())) { "Unsafe DLL recovery directory." }
        if (File(journal, "publishing").exists() && !File(journal, "committed").exists()) {
            fromSnapshot(File(journal, "original")).forEach { (name, source) -> publish(source, File(system32, name)) }
        }
        check(com.winlator.container.ContainerFiles.deleteRecursively(journal)) { "DLL recovery journal cleanup failed." }
    }
    fun replace(root: File, system32: File, desired: Map<String, File?>, afterWrite: (Int) -> Unit = {}) {
        require(desired.keys == names.toSet())
        require(system32.isDirectory && !Files.isSymbolicLink(system32.toPath())) { "Unsafe or missing system32 directory." }
        recover(root, system32)
        val journal = File(root, ".frontmage-dll-transaction"); check(journal.mkdir())
        try {
            snapshot(system32, File(journal, "original"))
            durable(File(journal, "publishing"), "restore both DLLs on incomplete publication")
            names.forEachIndexed { index, name -> publish(desired[name], File(system32, name)); afterWrite(index) }
            durable(File(journal, "committed"), "complete")
        } finally { recover(root, system32) }
    }
}
