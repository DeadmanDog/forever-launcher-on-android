package com.winlator.container

import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Durable rollback journal; migration happens on a copy before the live prefix is replaced. */
internal object RuntimePrefixTransaction {
    private const val DIRECTORY = ".developer-runtime-transaction"
    private val activeRoots = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    @JvmStatic fun recover(root: File) {
        if (root.absolutePath in activeRoots) return
        val journal = File(root, DIRECTORY)
        if (!journal.isDirectory) return
        if (!File(journal, "committed").isFile && File(journal, "publishing").isFile) {
            val backup = File(journal, "original-upper")
            if (ContainerFiles.exists(backup)) {
                check(ContainerFiles.deleteRecursively(File(root, ".wine"))) { "Unable to remove incomplete prefix; rollback retained." }
                check(backup.renameTo(File(root, ".wine"))) { "Unable to restore prefix; rollback retained." }
            }
            val config = File(journal, "original-config")
            if (config.isFile) Files.copy(config.toPath(), File(root, ".container").toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
        check(ContainerFiles.deleteRecursively(journal)) { "Unable to clean runtime rollback journal." }
    }

    fun run(container: Container, migrate: (Container) -> Unit) {
        val root = container.rootDir
        recover(root)
        check(File(root, ".wine").isDirectory) { "Writable prefix is missing; runtime switch refused." }
        for (rel in listOf("", "drive_c", "drive_c/windows", "drive_c/windows/temp", "drive_c/windows/system32", "drive_c/windows/syswow64",
            "dosdevices", ".gnoverlay", ".gnoverlay/opaque", ".gnoverlay/wh", ".gnoverlay/opaque/dosdevices")) {
            check(!Files.isSymbolicLink(File(File(root, ".wine"), rel).toPath())) { "Prefix migration path is a symlink; safe migration cannot be guaranteed." }
        }
        val journal = File(root, DIRECTORY)
        check(journal.mkdir()) { "Unable to create runtime rollback journal." }
        val stage = File(journal, "candidate").apply { mkdirs() }
        check(activeRoots.add(root.absolutePath)) { "Runtime migration already running." }
        try {
            val config = container.configFile.readText()
            File(journal, "original-config").writeText(config)
            check(ContainerOverlay.copyTree(File(root, ".wine"), File(stage, ".wine"), false)) { "Unable to back up writable prefix." }
            val candidate = Container(container.id).apply { rootDir = stage; loadData(JSONObject(config)) }
            migrate(candidate)
            candidate.saveData()
            check(candidate.configFile.isFile && JSONObject(candidate.configFile.readText()).getString("wineVersion") == candidate.wineVersion) { "Runtime config validation failed." }
            File(journal, "publishing").writeText("rollback required")
            val upper = File(root, ".wine")
            if (ContainerFiles.exists(upper)) check(upper.renameTo(File(journal, "original-upper"))) { "Unable to retain old prefix." }
            check(File(stage, ".wine").renameTo(upper)) { "Unable to publish migrated prefix." }
            Files.copy(candidate.configFile.toPath(), container.configFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
            check(JSONObject(container.configFile.readText()).getString("wineVersion") == candidate.wineVersion) { "Saved runtime validation failed." }
            File(journal, "committed").writeText("complete")
            container.loadData(JSONObject(container.configFile.readText()))
        } finally {
            activeRoots.remove(root.absolutePath)
            recover(root)
        }
    }
}
