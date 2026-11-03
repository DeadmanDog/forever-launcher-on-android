package app.gamenative.ui.screen.wow

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.winlator.container.Container
import com.winlator.container.ContainerManager
import com.winlator.contents.ContentProfile
import com.winlator.contents.ContentsManager
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** VKD3D catalog adapter. Storage and archive staging are owned by ContentsManager. */
internal object WowVkd3dComponents {
    const val PROFILE_KEY = "wowVkd3dProfile"
    const val API_KEY = "wowGraphicsApi"
    const val DX11 = "dx11"
    const val DX12 = "dx12"
    val type = ContentProfile.ContentType.CONTENT_TYPE_VKD3D
    val exports = mapOf("d3d12.dll" to setOf("D3D12CreateDevice", "D3D12GetDebugInterface", "D3D12GetInterface"),
        "d3d12core.dll" to setOf("D3D12GetInterface", "D3D12SDKVersion"))
    fun id(profile: ContentProfile) = ContentsManager.getEntryName(profile).replaceFirst("VKD3D-", "vkd3d-")
    fun api(container: Container) = container.getExtra(API_KEY).ifBlank { DX11 }.also { require(it == DX11 || it == DX12) { "Invalid saved graphics API." } }
    fun dlls(directory: File, profile: ContentProfile): Map<String, File> {
        require(profile.type == type && !profile.verName.isNullOrBlank() && profile.verCode >= 0) { "Only installed VKD3D profiles are supported." }
        require(profile.verName.none { it == '/' || it == '\\' || it == ',' || it == '=' || it.isISOControl() } && profile.verName !in setOf(".", "..")) { "Invalid VKD3D name." }
        // "frontmage" is the old name of the compatibility block, still found in VKD3D packages imported before 1.3.57.
        val contract = JSONObject(File(directory, ContentsManager.PROFILE_NAME).readText()).let { it.optJSONObject("wowforever") ?: it.optJSONObject("frontmage") }
        require(contract != null && contract.optInt("schema") == 1 && contract.optString("architecture") == "windows-arm64" &&
            contract.optString("upstreamVersion") == "3.0.1") {
            "DX12 requires a Windows ARM64 VKD3D-Proton 3.0.1 package with WoW Forever compatibility metadata."
        }
        val targets = exports.keys.associateWith { name ->
            val entry = profile.fileList.orEmpty().singleOrNull { it.target == "\${system32}/$name" }
                ?: error("VKD3D requires one system32/$name payload.")
            val source = File(directory, entry.source).canonicalFile
            require(source.toPath().startsWith(directory.canonicalFile.toPath()) && source.isFile) { "Missing or unsafe $name." }
            WowArm64Pe.validate(source, exports.getValue(name)); source
        }
        require(profile.fileList.size == 2) { "ARM64 VKD3D package must contain only the two system32 DLL targets." }
        return targets
    }
    fun profiles(context: Context): List<ContentProfile> {
        val manager = ContentsManager(context).apply { syncContents() }
        return manager.getProfiles(type).orEmpty().filter { p ->
            p.remoteUrl.isNullOrBlank() && File(ContentsManager.getInstallDir(context, p), ContentsManager.PROFILE_NAME).isFile
        }.sortedWith(compareBy({ it.verName }, { it.verCode }))
    }
    fun selected(context: Context, container: Container): Pair<ContentProfile, Map<String, File>> {
        val profile = profiles(context).singleOrNull { id(it) == container.getExtra(PROFILE_KEY) }
            ?: error("Selected VKD3D is missing. Select and Apply an installed ARM64 profile; no fallback was used.")
        return profile to dlls(ContentsManager.getInstallDir(context, profile), profile)
    }
    fun importWcp(context: Context, uri: Uri, active: () -> Boolean): ContentProfile = synchronized(ContentsManager.getLocalInstallLock()) {
        idle(active)
        val fileName = if (uri.scheme == null || uri.scheme == "file") uri.lastPathSegment else {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            }
        }
        require(fileName?.endsWith(".wcp", true) == true) { "Please select a .wcp file." }
        val manager = ContentsManager(context)
        var candidate: File? = null
        try {
            fun perform(operation: (ContentsManager.OnInstallFinishedCallback) -> Unit): ContentProfile {
                var result: ContentProfile? = null
                operation(object : ContentsManager.OnInstallFinishedCallback {
                    override fun onSucceed(profile: ContentProfile) { result = profile }
                    override fun onFailed(reason: ContentsManager.InstallFailedReason, e: Exception?) {
                        if (reason == ContentsManager.InstallFailedReason.ERROR_EXIST) candidate = null
                        error(e?.message ?: "VKD3D import failed: $reason")
                    }
                })
                return result ?: error("VKD3D import returned no profile.")
            }
            val p = perform { manager.extraContentFile(uri, type, it) }
            dlls(ContentsManager.getTmpDir(context), p)
            require(manager.getUnTrustedContentFiles(p).isEmpty()) { "Unsafe VKD3D targets." }
            val directory = ContentsManager.getInstallDir(context, p)
            check(!directory.exists()) { "VKD3D profile already exists; it was not overwritten." }
            idle(active); candidate = directory
            perform { manager.finishInstallContent(p, it) }
            dlls(directory, p); manager.syncContents(); candidate = null
            check(profiles(context).any { id(it) == id(p) }) { "Imported VKD3D is not in the catalog." }
            p
        } catch (e: Exception) {
            candidate?.let { com.winlator.container.ContainerFiles.deleteRecursively(it) }; throw e
        } finally { ContentsManager.cleanTmpDir(context) }
    }
    fun idle(active: () -> Boolean = { app.gamenative.PluviaApp.keepAlive }) {
        check(!active()) { "Stop the game before changing DX12 components or API." }
        val uid = android.os.Process.myUid().toString()
        File("/proc").listFiles().orEmpty().filter { it.name.all(Char::isDigit) }.forEach { process ->
            val status = runCatching { File(process, "status").readText() }.getOrNull() ?: return@forEach
            if (Regex("""(?m)^Uid:\s+(\d+)""").find(status)?.groupValues?.get(1) != uid) return@forEach
            val command = runCatching { File(process, "cmdline").readText().split('\u0000') }.getOrNull().orEmpty()
            check(command.none { File(it).name in setOf("wine", "wine64", "wineserver", "wine-preloader", "wine64-preloader") || it.endsWith(".exe", true) }) { "Wine is still running; close the session or shell first." }
        }
    }
    fun save(container: Container, edit: () -> Unit) {
        val original = container.configFile.readBytes()
        try {
            edit(); container.saveData()
            val saved = JSONObject(container.configFile.readText())
            check(saved.getString("execArgs") == container.execArgs) { "Container save failed." }
            check(ContainerManagerConfigExtras.matches(container, saved)) { "Graphics API save failed." }
        } catch (e: Exception) {
            val tmp = File(container.rootDir, ".dx12-config-rollback")
            tmp.writeBytes(original); Files.move(tmp.toPath(), container.configFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
            val restored = JSONObject(original.toString(Charsets.UTF_8))
            container.setExtraData(restored.optJSONObject("extraData"))
            container.loadData(restored); throw e
        }
    }
    private object ContainerManagerConfigExtras {
        fun matches(c: Container, data: JSONObject): Boolean {
            val reloaded = Container(c.id).apply { rootDir = c.rootDir; loadData(data) }
            return reloaded.getExtra(PROFILE_KEY) == c.getExtra(PROFILE_KEY) && reloaded.getExtra(API_KEY) == c.getExtra(API_KEY)
        }
    }
    fun applyProfile(context: Context, selectedId: String, active: () -> Boolean) = synchronized(ContentsManager.getLocalInstallLock()) {
        idle(active)
        val p = profiles(context).singleOrNull { id(it) == selectedId } ?: error("VKD3D is not installed.")
        dlls(ContentsManager.getInstallDir(context, p), p)
        val c = ContainerManager(context).getContainerById(WowContainerProfile.CONTAINER_ID) ?: error("WoW container has not been created yet.")
        if (api(c) == DX12) {
            checkRuntime(context, c, validatePayload = true)
            require(WowVkd3dCapabilities.probe(context, c).compatible) { "Selected driver failed DX12 compatibility." }
        }
        idle(active); save(c) { c.putExtra(PROFILE_KEY, id(p)) }
    }
    fun arguments(current: String, mode: String): String {
        require(mode == DX11 || mode == DX12)
        val removed = Regex("""(?i)(?<!\S)"?-d3d(?:11|12)"?(?!\S)""").replace(current, "").trim()
        return listOf(removed, if (mode == DX12) "-d3d12" else "-d3d11").filter { it.isNotBlank() }.joinToString(" ")
    }
    fun checkRuntime(context: Context, container: Container, validatePayload: Boolean = false) {
        require(container.containerVariant == Container.BIONIC) { "DX12 requires a Bionic container." }
        val savedEnv = com.winlator.core.envvars.EnvVars(container.envVars)
        require(listOf("VKD3D_FEATURE_LEVEL", "VKD3D_SHADER_MODEL").none { savedEnv.get(it).isNotBlank() }) {
            "Remove forced VKD3D feature-level/shader-model overrides before enabling DX12. The real device capabilities must be used."
        }
        val blacklisted = com.winlator.core.KeyValueSet(container.graphicsDriverConfig).get("blacklistedExtensions")
            .split(Regex("[,;\\s]+" )).toSet()
        require(blacklisted.intersect(setOf("VK_EXT_robustness2", "VK_KHR_push_descriptor")).isEmpty()) {
            "A required DX12 Vulkan extension is blacklisted in the saved driver configuration."
        }
        val manager = ContentsManager(context).apply { syncContents() }
        val profile = manager.getProfileByEntryName(container.wineVersion)
            ?: error("Selected Wine/Proton is not installed; no fallback was used.")
        require(profile.type in setOf(ContentProfile.ContentType.CONTENT_TYPE_WINE, ContentProfile.ContentType.CONTENT_TYPE_PROTON) && profile.remoteUrl.isNullOrBlank()) {
            "An installed Wine/Proton profile is required."
        }
        val directory = ContentsManager.getInstallDir(context, profile).canonicalFile
        val info = com.winlator.core.WineInfo.fromIdentifier(context, manager, container.wineVersion)
        require(info.isArm64EC && !info.path.isNullOrBlank() && File(info.path).canonicalFile == directory) { "Compatible ARM64EC Wine/Proton is required." }
        if (validatePayload) WowDeveloperComponents.validateRuntime(directory, profile)
        else require(File(directory, "bin/wine").isFile && File(directory, "bin/wineserver").isFile) { "Wine/Proton installation is incomplete." }
    }
    fun applyApi(context: Context, mode: String, active: () -> Boolean, probe: (Context, Container) -> WowVkd3dCapabilities.Report = WowVkd3dCapabilities::probe) = synchronized(ContentsManager.getLocalInstallLock()) {
        require(mode == DX11 || mode == DX12); idle(active)
        val c = ContainerManager(context).getContainerById(WowContainerProfile.CONTAINER_ID) ?: error("WoW container has not been created yet.")
        // Switching to DX12 also applies a VKD3D (1.0.1): the saved one if it is installed, else the bundled one, else the newest
        // installed; the player no longer has to pick it on the Components tab first.
        var vkd3d: ContentProfile? = null
        if (mode == DX12) {
            checkRuntime(context, c, validatePayload = true)
            vkd3d = profiles(context).let { all ->
                all.singleOrNull { id(it) == c.getExtra(PROFILE_KEY) } ?: all.firstOrNull { it.verName == WowBundledComponents.VKD3D_NAME } ?: all.maxByOrNull { it.verCode }
            } ?: error("No VKD3D is installed. Reinstall the app or import a VKD3D WCP on the Components tab.")
            dlls(ContentsManager.getInstallDir(context, vkd3d), vkd3d)
            val report = probe(context, c); require(report.compatible) { report.summary() }
            val dxgi = WowDxvkSelector.currentProfile(c.dxWrapper, c.dxWrapperConfig, WowDxvkSelector.installedProfiles(context))
                ?: error("DX12 requires a locally installed Vegas DXGI profile.")
            val manager = ContentsManager(context)
            val dxgiFile = dxgi.fileList.singleOrNull { it.target == "\${system32}/dxgi.dll" } ?: error("Selected DXVK has no DXGI.")
            validateDxgi(context, dxgi, dxgiFile.source)
        }
        idle(active); save(c) { c.putExtra(API_KEY, mode); c.execArgs = arguments(c.execArgs, mode); vkd3d?.let { c.putExtra(PROFILE_KEY, id(it)) } }
    }
    fun isAuditedDxgi(profile: ContentProfile): Boolean = (profile.verName to profile.verCode) in setOf(
        "Vegas-2.7.3-wow-arm64-async-gamenative-r13" to 13,
        "Vegas-2.7.3-wow-arm64-hybrid-gamenative-r14" to 14,
        "Vegas-2.7.3-wow-arm64-hybrid-gamenative-r15" to 15,
        "Vegas-2.7.3-wow-arm64-hybrid-gamenative-r16" to 16,
        "Vegas-2.7.3-wow-arm64-hybrid-gamenative-r17" to 17,
        // r30 = r17 minus dead code; only dxvk_vegas.cpp differs in the build (201 of 202 objects byte-identical).
        "Vegas-2.7.3-wow-arm64-hybrid-gamenative-r30" to 30,
        // r43 = r30 plus two hybrid-path changes in the DX11 pipeline compile logic (async-compat render target rule, High worker
        // priority); the DXGI/DX12 present path is untouched. Source-level audit only, not DX12-tested on the phone.
        "Vegas-2.7.3-wow-arm64-hybrid-gamenative-r43" to 43,
        // r47 = r43 plus an env-selected GPU persona in dxvk_vegas.cpp (vendor/name strings only); the DXGI present path is untouched.
        "Vegas-2.7.3-wow-arm64-hybrid-gamenative-r47" to 47,
        // r48 = r47 plus a larger reported video memory and Adreno chip ids for the persona (strings/ids only).
        "Vegas-2.7.3-wow-arm64-hybrid-gamenative-r48" to 48,
        // r49 = r48 plus the opt-in real pipeline bind-skip (VEGAS_BINDSKIP=1, default off); the DXGI present path is untouched.
        "Vegas-2.7.3-wow-arm64-hybrid-gamenative-r49" to 49,
        // r50 = r49 plus the opt-in DXVK_HYBRID_GPL=1 (hybrid keeps graphics pipeline libraries, default off); the DXGI present path is untouched.
        "Vegas-2.7.3-wow-arm64-hybrid-gamenative-r50" to 50,
        // r60 = EXPERIMENTAL: the r50 Vegas layer ported onto upstream DXVK 3.1.1 (separate branch, not bundled). Source-level port with
        // the Vegas host tests green; the DXGI/DX12 path is upstream 3.1.1's and has not been tested on the phone.
        "Vegas-3.1.1-wow-arm64-hybrid-gamenative-r60" to 60,
        // r61 = r60 plus two shader-cache fixes (new cache readable at once, own vegas3x- file names); DXGI/DX12 path unchanged.
        "Vegas-3.1.1-wow-arm64-hybrid-gamenative-r61" to 61,
        // r62 = r61 plus the bind-skip fix after an inline image copy (DX11 context only); DXGI/DX12 path unchanged.
        "Vegas-3.1.1-wow-arm64-hybrid-gamenative-r62" to 62,
        // r51 / r63 = r50 / r62 plus the pipeline-handle snapshot fix in the D3D11 path (Codex OPT13-02); DXGI/DX12 path unchanged.
        "Vegas-2.7.3-wow-arm64-hybrid-gamenative-r51" to 51,
        "Vegas-3.1.1-wow-arm64-hybrid-gamenative-r63" to 63,
    )
    fun validateDxgi(context: Context, profile: ContentProfile, source: String): File {
        require(isAuditedDxgi(profile)) {
            "DX12 needs the DXGI of the bundled DXVK (2.7.3 or 3.1.1). Other DXGI builds are not supported."
        }
        val directory = ContentsManager.getInstallDir(context, profile).canonicalFile
        val file = File(directory, source).canonicalFile
        require(file.toPath().startsWith(directory.toPath())) { "Unsafe DXGI payload path." }
        WowArm64Pe.validate(file, setOf("CreateDXGIFactory1"))
        return file
    }
    fun delete(context: Context, selectedId: String, active: () -> Boolean,
        cleanup: (File) -> Boolean = com.winlator.container.ContainerFiles::deleteRecursively) = synchronized(ContentsManager.getLocalInstallLock()) {
        idle(active)
        val manager = ContainerManager(context)
        val roots = File(com.winlator.xenvironment.ImageFs.find(context).rootDir, "home").listFiles().orEmpty()
            .filter { it.isDirectory && it.name.startsWith("xuser-") }
        check(roots.all { root -> manager.containers.any { it.rootDir.canonicalFile == root.canonicalFile } }) {
            "Cannot verify VKD3D dependencies: a container configuration could not be read."
        }
        check(manager.containers.none { it.getExtra(PROFILE_KEY) == selectedId }) {
            "This VKD3D profile is referenced by a container."
        }
        val p = profiles(context).singleOrNull { id(it) == selectedId } ?: error("VKD3D profile is not installed.")
        val dir = ContentsManager.getInstallDir(context, p)
        check(p.verName !in WowBundledComponents.protectedNames) { "Bundled VKD3D cannot be deleted." }
        val removed = File(dir.parentFile, ".removed-vkd3d-${java.util.UUID.randomUUID()}")
        idle(active); check(dir.renameTo(removed)) { "Unable to remove VKD3D atomically." }
        check(cleanup(removed)) { "VKD3D was removed from the catalog, but cleanup failed. The remaining files are quarantined at $removed." }
    }
}
