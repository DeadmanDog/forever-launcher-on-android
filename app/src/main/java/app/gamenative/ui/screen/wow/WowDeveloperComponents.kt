package app.gamenative.ui.screen.wow

import android.content.Context
import android.net.Uri
import com.winlator.container.*
import com.winlator.contents.*
import com.winlator.core.KeyValueSet
import com.winlator.core.WineInfo
import com.winlator.core.TarCompressorUtils
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile

internal object WowDeveloperComponents {
    enum class Kind { DXVK, TURNIP, RUNTIME }
    data class Entry(val kind: Kind, val id: String, val description: String, val directory: File)
    private val lock = ContentsManager.getLocalInstallLock()
    private val protectedNames = WowBundledComponents.protectedNames + setOf("2.4.1-wow-aarch64-test", "proton-11.0-90624-arm64ec")

    fun runtimeId(p: ContentProfile) = "${p.verName}-${p.verCode}"
    fun runtimeProfiles(context: Context): List<ContentProfile> {
        val m = ContentsManager(context).apply { syncContents() }
        return (m.getProfiles(ContentProfile.ContentType.CONTENT_TYPE_WINE).orEmpty() +
            m.getProfiles(ContentProfile.ContentType.CONTENT_TYPE_PROTON).orEmpty()).distinctBy { runtimeId(it) }
            .filter { runCatching { validateRuntime(ContentsManager.getInstallDir(context, it), it); requireResolvable(context, m, it) }.isSuccess }
    }

    /** Every installed Wine/Proton profile (no validation): what a cleanup may look at. */
    fun installedRuntimeProfiles(context: Context): List<ContentProfile> {
        val m = ContentsManager(context).apply { syncContents() }
        return (m.getProfiles(ContentProfile.ContentType.CONTENT_TYPE_WINE).orEmpty() +
            m.getProfiles(ContentProfile.ContentType.CONTENT_TYPE_PROTON).orEmpty()).distinctBy { runtimeId(it) }
    }

    fun drivers(context: Context): List<Entry> {
        val manager = AdrenotoolsManager(context)
        val root = File(context.filesDir, "contents/adrenotools")
                val ids = (manager.enumarateInstalledDrivers() + root.listFiles().orEmpty().filter { File(it, "meta.json").isFile }.map { it.name }).distinct()
        return ids.mapNotNull { id ->
            val dir = File(root, id)
            runCatching {
                check(AdrenotoolsManager.validateDriverDirectory(dir) == id)
                Entry(Kind.TURNIP, id, manager.getDriverVersion(id), dir)
            }.getOrNull()
        }
    }

    fun validateRuntime(directory: File, profile: ContentProfile) {
        require(profile.type == ContentProfile.ContentType.CONTENT_TYPE_WINE || profile.type == ContentProfile.ContentType.CONTENT_TYPE_PROTON) { "Only Wine/Proton WCP is supported." }
        require(profile.verName?.contains("arm64ec", true) == true) { "Runtime must be ARM64EC." }
        fun path(value: String?): File {
            require(!value.isNullOrBlank()) { "Runtime metadata path missing." }
            val file = File(directory, value).canonicalFile
            require(file.toPath().startsWith(directory.canonicalFile.toPath())) { "Unsafe runtime metadata path." }
            return file
        }
        val wine = File(path(profile.wineBinPath), "wine")
        require(profile.wineBinPath == "bin" && profile.wineLibPath == "lib") { "Runtime must use the supported bin/lib layout." }
        require(wine.isFile && wine.canonicalFile.toPath().startsWith(directory.canonicalFile.toPath())) { "Wine executable missing or unsafe." }
        validateBionicElf(wine)
        val server = File(path(profile.wineBinPath), "wineserver")
        require(server.isFile && server.canonicalFile.toPath().startsWith(directory.canonicalFile.toPath())) { "Wine server missing or unsafe." }
        validateBionicElf(server)
        val lib = path(profile.wineLibPath)
        val ntdll = File(lib, "wine/aarch64-windows/ntdll.dll").takeIf { it.isFile }
            ?: File(lib, "aarch64-windows/ntdll.dll")
        require(ntdll.canonicalFile.toPath().startsWith(directory.canonicalFile.toPath())) { "Unsafe Wine ntdll path." }
        // The 32-bit (i386-windows) payload is optional: WoW is native ARM64 and the BasePrefix
        // builder skips syswow64 when it is absent. If present it must stay inside the runtime.
        val compatibility = File(ntdll.parentFile!!.parentFile, "i386-windows")
        require(!compatibility.exists() || compatibility.canonicalFile.toPath().startsWith(directory.canonicalFile.toPath())) {
            "Unsafe i386-windows compatibility payload."
        }
        RandomAccessFile(ntdll, "r").use { pe ->
            require(pe.length() > 64 && pe.readUnsignedByte() == 77 && pe.readUnsignedByte() == 90) { "ARM64 Wine ntdll missing or invalid." }
            pe.seek(60); val offset = Integer.reverseBytes(pe.readInt()).toLong() and 0xffffffffL
            require(offset + 6 <= pe.length()) { "Invalid PE header." }
            pe.seek(offset); require(pe.readInt() == 0x50450000) { "Invalid PE signature." }
            val machine = pe.readUnsignedByte() or (pe.readUnsignedByte() shl 8)
            require(machine in setOf(0xaa64, 0xa641, 0xa64e)) { "Runtime ntdll is not ARM64/ARM64EC." }
        }
        val pack = path(profile.winePrefixPack)
        require(pack.isFile && profile.winePrefixPack in setOf("prefixPack.tzst", "prefixPack.txz")) { "Supported prefixPack missing." }
    }

    fun validateBionicElf(file: File) {
        RandomAccessFile(file, "r").use { elf ->
            require(elf.length() >= 64 && elf.readInt() == 0x7f454c46 && elf.readUnsignedByte() == 2 && elf.readUnsignedByte() == 1) { "Wine must be an ARM64 ELF executable." }
            elf.seek(18); require(elf.readUnsignedByte() == 183 && elf.readUnsignedByte() == 0) { "Wine is not ARM64." }
            elf.seek(32); val ph = java.lang.Long.reverseBytes(elf.readLong())
            elf.seek(54); val size = java.lang.Short.reverseBytes(elf.readShort()).toInt() and 65535
            val count = java.lang.Short.reverseBytes(elf.readShort()).toInt() and 65535
            require(size >= 56 && count in 1..1024 && ph >= 0 && ph + size.toLong() * count <= elf.length()) { "Invalid ELF program headers." }
            var interpreter: String? = null
            for (i in 0 until count) {
                elf.seek(ph + i.toLong() * size)
                if (Integer.reverseBytes(elf.readInt()) == 3) {
                    elf.skipBytes(4); val offset = java.lang.Long.reverseBytes(elf.readLong())
                    elf.skipBytes(16); val length = java.lang.Long.reverseBytes(elf.readLong())
                    require(offset >= 0 && length in 1..512 && offset + length <= elf.length()) { "Invalid ELF interpreter." }
                    val data = ByteArray(length.toInt()); elf.seek(offset); elf.readFully(data)
                    interpreter = data.toString(Charsets.UTF_8).trimEnd('\u0000')
                }
            }
            require(interpreter == "/system/bin/linker64") { "Wine executable is not Android/Bionic-compatible." }
        }
    }

    private fun requireResolvable(context: Context, manager: ContentsManager, p: ContentProfile): WineInfo {
        val info = WineInfo.fromIdentifier(context, manager, runtimeId(p))
        require(info.isArm64EC && !info.path.isNullOrBlank() && File(info.path).canonicalFile == ContentsManager.getInstallDir(context, p).canonicalFile) { "Runtime identifier cannot be resolved without fallback." }
        return info
    }

    private fun validatePrefixPack(context: Context, directory: File, profile: ContentProfile, sanitize: Boolean = false) {
        val pack = File(directory, profile.winePrefixPack)
        val stage = File(context.cacheDir, "developer-prefix-${java.util.UUID.randomUUID()}")
        check(stage.mkdirs()) { "Unable to create prefix validation staging." }
        try {
            val type = if (pack.name.endsWith(".tzst")) TarCompressorUtils.Type.ZSTD else TarCompressorUtils.Type.XZ
            val legacy = sanitize || profile.verName in protectedNames
            require(if (legacy) TarCompressorUtils.extractValidatedPrefix(type, context, Uri.fromFile(pack), stage)
                else TarCompressorUtils.extractValidated(type, context, Uri.fromFile(pack), stage, true)) { "Invalid or unsafe prefixPack." }
            require(File(stage, ".wine/drive_c/windows").isDirectory) { "prefixPack has no Windows prefix skeleton." }
            require(listOf("system.reg", "user.reg", "userdef.reg").all { File(stage, ".wine/$it").isFile }) { "prefixPack registry skeleton is incomplete." }
            if (sanitize) {
                check(BasePrefix.normalize(File(stage, ".wine"))) { "Unable to normalize imported prefix drive aliases." }
                val safePack = File(directory, "developer-safe-${pack.name}")
                TarCompressorUtils.compress(type, File(stage, ".wine"), safePack)
                check(safePack.isFile && safePack.length() > 0) { "Unable to prepare safe prefixPack." }
                java.nio.file.Files.move(safePack.toPath(), pack.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                validatePrefixPack(context, directory, profile)
            }
        } finally { ContainerFiles.deleteRecursively(stage) }
    }

    fun importDriver(context: Context, uri: Uri): String = synchronized(lock) {
        requireIdle()
        AdrenotoolsManager(context).installValidatedDriver(uri)
    }

    fun importRuntime(context: Context, uri: Uri): String = synchronized(lock) {
        requireIdle()
        val manager = ContentsManager(context)
        var installed: File? = null
        try {
            var p: ContentProfile? = null
            val callback = object : ContentsManager.OnInstallFinishedCallback {
                override fun onSucceed(profile: ContentProfile) { p = profile }
                override fun onFailed(reason: ContentsManager.InstallFailedReason, e: Exception?) {
                    if (reason == ContentsManager.InstallFailedReason.ERROR_EXIST) installed = null
                    error(e?.message ?: "Runtime WCP import failed: $reason")
                }
            }
            manager.extraContentFile(uri, ContentProfile.ContentType.CONTENT_TYPE_WINE, callback)
            val profile = p ?: error("Runtime profile missing.")
            require(!profile.verName.contains('/') && !profile.verName.contains('\\') && profile.verCode >= 0) { "Invalid runtime name/version." }
            validateRuntime(ContentsManager.getTmpDir(context), profile)
            validatePrefixPack(context, ContentsManager.getTmpDir(context), profile, sanitize = true)
            val dir = ContentsManager.getInstallDir(context, profile)
            check(!dir.exists()) { "Runtime already installed; it was not overwritten." }
            requireIdle()
            installed = dir
            manager.finishInstallContent(profile, callback)
            manager.syncContents()
            requireResolvable(context, manager, profile)
            installed = null
            runtimeId(profile)
        } catch (e: Exception) {
            installed?.let { ContainerFiles.deleteRecursively(it) }
            throw e
        } finally { ContentsManager.cleanTmpDir(context) }
    }

    fun applyDriver(container: Container, entry: Entry) {
        requireIdle()
        require(container.id == "wow_forever") { "Not the WoW container." }
        require(entry.kind == Kind.TURNIP && AdrenotoolsManager.validateDriverDirectory(entry.directory) == entry.id) { "Driver is not installed." }
        require(container.graphicsDriver.equals("Wrapper", true) && container.displayRenderer.equals("vulkan", true)) { "Current container must use Wrapper/Vulkan." }
        val config = KeyValueSet(container.graphicsDriverConfig).put("version", entry.id).put("adrenotoolsTurnip", "1").toString()
        container.graphicsDriverConfig = config
        container.graphicsDriverVersion = entry.id
        container.saveData()
        val saved = JSONObject(container.configFile.readText())
        check(saved.getString("graphicsDriverVersion") == entry.id && saved.getString("graphicsDriverConfig") == config) { "Driver persistence validation failed." }
    }

    fun applyRuntime(context: Context, container: Container, id: String) = synchronized(lock) {
        requireIdle()
        require(container.id == "wow_forever") { "Not the WoW container." }
        val manager = ContentsManager(context).apply { syncContents() }
        val p = runtimeProfiles(context).singleOrNull { runtimeId(it) == id } ?: error("Compatible installed runtime not found. No fallback was applied.")
        val info = requireResolvable(context, manager, p)
        validatePrefixPack(context, ContentsManager.getInstallDir(context, p), p)
        require(ContainerOverlay.bionicLibFile(context).isFile) { "Bionic overlay library missing." }
        RuntimePrefixTransaction.run(container) { candidate ->
            requireIdle()
            candidate.wineVersion = id
            require(candidate.containerVariant == Container.BIONIC) { "Runtime requires a Bionic container." }
            val base = BasePrefix.ensure(context, manager, id) ?: error("Unable to prepare BasePrefix.")
            val libs = listOf(File(info.path, "lib/wine/aarch64-windows"), File(info.path, "lib/wine/i386-windows"))
            ContainerOverlayMigrator.migrateRuntime(candidate, base, libs)
            requireIdle()
            check(File(candidate.basePrefix, "drive_c/windows").isDirectory) { "Migrated prefix validation failed." }
            // Test the selected ELF loader and Bionic libraries against the isolated candidate,
            // before publishing either the live config or prefix. No game/auto wineboot command.
            val shell = DeveloperShell()
            val request = shell.wineRequest(context, candidate, "ver")
            val versionRequest = request.copy(command = request.command.take(2) + "--version")
            val output = shell.run(versionRequest, 10)
            check(output.trimEnd().endsWith("Exit: 0")) { "Selected runtime failed its Wine version check: $output" }
            requireIdle()
        }
    }

    fun inUse(entry: Entry, containers: List<Container>, active: Boolean): Boolean {
        if (active) return true
        return containers.any { c -> when (entry.kind) {
            Kind.DXVK -> c.getDXWrapper().equals(entry.id, true) || c.getDXWrapper().equals("dxvk-${entry.directory.name}", true) ||
                (c.getDXWrapper() == "dxvk" && KeyValueSet(c.getDXWrapperConfig()).get("version") == entry.id.removePrefix("dxvk-"))
            Kind.TURNIP -> c.graphicsDriverVersion == entry.id || KeyValueSet(c.graphicsDriverConfig).get("version") == entry.id
            Kind.RUNTIME -> c.wineVersion.equals(entry.id, true) || c.wineVersion.equals(entry.directory.name, true) ||
                c.wineVersion.equals("${entry.id.substringBefore('-')}-${entry.id}", true) ||
                (c.basePrefix.isNotBlank() && File(c.basePrefix).canonicalFile.toPath().startsWith(entry.directory.canonicalFile.toPath()))
        } }
    }

    fun delete(context: Context, entry: Entry) = synchronized(lock) {
        val containerManager = ContainerManager(context)
        val containers = containerManager.containers
        val configuredRoots = File(com.winlator.xenvironment.ImageFs.find(context).rootDir, "home").listFiles().orEmpty()
            .filter { it.isDirectory && it.name.startsWith("xuser-") }
        check(configuredRoots.all { root -> containers.any { it.rootDir.canonicalFile == root.canonicalFile } }) {
            "Cannot verify component dependencies: a container configuration could not be read."
        }
        check(!inUse(entry, containers, app.gamenative.PluviaApp.keepAlive)) { "This component is currently in use." }
        check(entry.directory.name !in protectedNames) { "Bundled WoW default components are protected." }
        val manager = ContentsManager(context).apply { syncContents() }
        if (entry.kind == Kind.TURNIP) {
            check(drivers(context).any { it.id == entry.id && it.directory.canonicalFile == entry.directory.canonicalFile }) { "Driver is not installed." }
            check(AdrenotoolsManager(context).deleteDriverWithoutReassigning(entry.id)) { "Unable to delete driver." }
        } else {
            // A runtime may be catalogued as Wine or as Proton (the bundled Proton WCP): look in both.
            val candidates = if (entry.kind == Kind.DXVK) manager.getProfiles(ContentProfile.ContentType.CONTENT_TYPE_DXVK).orEmpty()
                else manager.getProfiles(ContentProfile.ContentType.CONTENT_TYPE_WINE).orEmpty() +
                    manager.getProfiles(ContentProfile.ContentType.CONTENT_TYPE_PROTON).orEmpty()
            val p = candidates.distinctBy { ContentsManager.getInstallDir(context, it).canonicalPath }.singleOrNull {
                (if (entry.kind == Kind.DXVK) WowDxvkSelector.identifier(it) else runtimeId(it)) == entry.id &&
                    ContentsManager.getInstallDir(context, it).canonicalFile == entry.directory.canonicalFile
            } ?: error("Installed component not found.")
            manager.removeContent(p)
            if (entry.kind == Kind.DXVK) WowLaunchEnvironment.invalidateDxvkCapabilities()
            check(!entry.directory.exists()) { "Unable to delete component." }
        }
    }

    private fun requireIdle() { check(!app.gamenative.PluviaApp.keepAlive) { "Operation unavailable during an active game session." } }
}
