package app.gamenative.ui.screen.wow

import android.content.Context
import android.net.Uri
import app.gamenative.PluviaApp
import com.winlator.contents.AdrenotoolsManager
import com.winlator.contents.ContentProfile
import com.winlator.contents.ContentsManager
import com.winlator.core.WineInfo
import java.io.File
import java.security.MessageDigest

/** Installs bundled profiles into the existing catalog. Never creates or edits a container. Call on IO. */
internal object WowBundledComponents {
    /** Reported in English; the launcher shows it through localizedStatus. */
    private val SESSION_ACTIVE = "Bundled components cannot be installed during an active game session." to
        "A beépített komponensek nem telepíthetők futó játék közben."
    const val PROTON_NAME = "proton-11.0-90639.34-arm64ec"
    /** The only bundled/default DXVK: Vegas r63 on upstream 3.1.1, including the cache, bind-skip and pipeline-handle fixes. */
    const val DXVK_NAME = "Vegas-3.1.1-wow-arm64-hybrid-gamenative-r63"
    /** The only bundled driver: TurnRIP 1.1.14 + community A8xx fixes (1.1.15) + 1.1.16 + 1.1.17 + 1.1.18 (A830 check) + 1.1.19 (wait-any mixed list, unsorted binding flags) + 1.1.20 (no diagnostic counter atomics without TU_STATS_FILE) + 1.1.21 (Gamescope pacing threads no longer steer the wait boost) + 1.1.22 (TU_KGSL_WAITBOOST_PRESENT_STEERS for DX12 under Gamescope) + 1.1.24 (TU_KGSL_WAITBOOST_VKD3D_STEERS: VKD3D waits steer under DX12 latency 1; helper_ms stats) + 1.1.25 (TU_KGSL_WAITBOOST_FRAME_STEER) + 1.1.26 (frame steering holds the GPU-wait share at 60-80% of the frame: only the clock the frame rate needs) + 1.1.27 (a single late frame, WoW's own CPU hitch, no longer raises it) + 1.1.28 (TU_FPS_FILE: a limit changed in the running game retargets ADPF and frame steering) + 1.1.29 (the panel refresh in TU_FPS_FILE: frames on the vsync grid are not late) + 1.1.30 (opt-in GPU profile: TU_PROFILE_SAMPLE_MS samples u_trace, per-second API counters in TU_STATS_FILE) + 1.1.31 (WoW: GMEM or sysmem measured per render pass instead of the DX driconf prefer_sysmem; redundancy counters) + 1.1.32 (a descriptor bind that changes nothing is skipped: 98% of WoW DX12's binds) + 1.1.33 (shader caches survive driver updates: keyed on the shader epoch, not the build id; desc_why counters) + 1.1.34 (push reuse, removed in 1.1.35: 0.05% hit) + 1.1.35 (compute shader statistics with diagnostics; its disassembly crashed: black screen) + 1.1.36 (raw instruction words instead). */
    const val DRIVER_NAME = "TurnRIP A840 Performance v1.1.36"
    /** The only bundled VKD3D: r2 + one fallback pipeline compile per PSO (r3) + fence/query/OOM fixes (r4) + swapchain OOM/cleanup fixes (r5) + fallback variants compiled in parallel (r6) + disk-cache merge recovery, worker wait and short-write fixes (r7) + no busy retry when the worker cannot grow its queue (r8) + upstream WoW MSAA workaround (also for Wow-ARM64.exe / Wow-ARM64.exe), no CPU fence waits on the shared Turnip queue, racy fence signal order (r9) + depth/stencil barrier fusion, disjoint copy rectangles, sparse-fallback leak (r10) + VKD3D_REPORT_DISCRETE: the GPU reported as not UMA, for WoW's DX12 render scale above 100% (r11) + with it the dedicated-GPU heap layout accepted (r12). */
    const val VKD3D_NAME = "VKD3D-Proton-3.0.1-ARM64-r12"
    const val VKD3D_VERSION_CODE = 12
    /** r34: r33 + the event pool hardened against stale handles (DUPLICATE_CLOSE_SOURCE of a pooled event fails, a stale entry is dropped). r33: r32 + a duplicated event (DuplicateHandle without DUPLICATE_CLOSE_SOURCE) is never reused by the event pool. r32: r31 + event reuse in ntdll (WINE_EVENT_POOL, see WowRuntimeTuning.EVENT_POOL): WoW's post-login Net Queue/TACT ping-pong no longer floods the wineserver. r31: r30 without the DirectX modules WoW never loads (Wine D3D10, DirectComposition, DXVA2, DirectWrite, XAudio2). r30: r29 + the wineserver request statistics + spin cap and cached tid in ntsync. */
    const val PROTON_VERSION_CODE = 34
    const val DXVK_VERSION_CODE = 63
    const val PROTON_ID = "$PROTON_NAME-$PROTON_VERSION_CODE"
    const val DXVK_VERSION = "$DXVK_NAME-$DXVK_VERSION_CODE"
    const val DXVK_ID = "dxvk-$DXVK_VERSION"

    data class Bundle(val asset: String, val name: String, val sha256: String, val type: ContentProfile.ContentType?, val versionCode: Int? = null,
                      val experimental: Boolean = false)

    val bundles = listOf(
        Bundle("VKD3D-Proton-ARM64-r12.wcp", VKD3D_NAME, "0681f7bb882ec298b66cab45c9cacbabd1f12b0eac6dc6c27a0029bd35633fea", ContentProfile.ContentType.CONTENT_TYPE_VKD3D),
        Bundle("proton-11.0-90639.34-arm64ec-r34.wcp", PROTON_NAME,
            "a99eb0850b402d4ea1c4fc9ab5d9cc15aa62163ce91c3eb1ff800a1f72ec6cc5", ContentProfile.ContentType.CONTENT_TYPE_PROTON),
        Bundle("Vegas-3.1.1-wow-arm64-hybrid-gamenative-r63.wcp", DXVK_NAME,
            "e2d75a833298943794055e17ead33cfc17c3d6fe0b84ee476972adb7f6195477", ContentProfile.ContentType.CONTENT_TYPE_DXVK,
            DXVK_VERSION_CODE),
        Bundle("TurnRIP-A840-Performance-v1.1.36.zip", DRIVER_NAME,
            "ebaa5597783ef1e5caade38380caca5ef432242e011cc12656724b39c6a172e4", null),
    )
    val protectedNames = bundles.map { it.name }.toSet()

    private val bundledProton = Regex("""proton-11\.0-90639\.(\d+)-arm64ec-(\d+)""")
    private val bundledVkd3d = Regex("""vkd3d-VKD3D-Proton-3\.0\.1-ARM64-r(\d+)-(\d+)""")
    /** The same VKD3D builds under their old package name (r2 .. r10, up to 1.3.57's first build). */
    private val legacyVkd3d = Regex("""vkd3d-FrontMage-VKD3D-Proton-3\.0\.1-ARM64-r(\d+)-(\d+)""")
    private val bundledDxvk3 = Regex("""dxvk-Vegas-3\.1\.1-wow-arm64-hybrid-gamenative-r(\d+)-(\d+)""", RegexOption.IGNORE_CASE)
    private val bundledDxvk2 = Regex("""dxvk-Vegas-2\.7\.3-wow-arm64-hybrid-gamenative-r(\d+)-(\d+)""", RegexOption.IGNORE_CASE)
    private val bundledDriver = Regex("""TurnRIP A840 Performance v1\.1\.(\d+)""")

    /** An earlier bundled Proton (r26, r27 ...) that this build's Proton replaces; a runtime the player imported is never matched. */
    fun supersededProton(id: String): Boolean =
        bundledProton.matchEntire(id)?.let { it.groupValues[1].toInt() < PROTON_VERSION_CODE } ?: false

    /** An earlier bundled Vegas 3.1.1 build (r60 .. r62) replaced by the current default; imported DXVKs are kept. */
    fun supersededDxvk(id: String): Boolean =
        bundledDxvk3.matchEntire(id)?.let { it.groupValues[1].toInt() < DXVK_VERSION_CODE } ?: false

    /** Retired bundled 2.7.3 builds through r51 move to 3.1.1. Other imports are not changed or deleted. */
    fun supersededDefaultDxvk(id: String): Boolean =
        bundledDxvk2.matchEntire(id)?.let { it.groupValues[1].toInt() <= 51 } ?: false

    /** An earlier bundled TurnRIP (1.1.14 .. 1.1.35, and Codex's import-only 1.1.23 test) that 1.1.36 replaces; other imported drivers are kept. */
    fun supersededDriver(id: String): Boolean =
        bundledDriver.matchEntire(id)?.let { it.groupValues[1].toInt() < 36 } ?: false

    /** An earlier bundled VKD3D, or any build under the old package name (the same r10 included), that this build's VKD3D replaces. */
    fun supersededVkd3d(id: String): Boolean = legacyVkd3d.matches(id) ||
        (bundledVkd3d.matchEntire(id)?.let { it.groupValues[1].toInt() < VKD3D_VERSION_CODE } ?: false)

    /**
     * A container still on a bundled Proton, VKD3D or Vegas 3.1.1 that this build replaces moves to the current one, through the same validated
     * Apply paths as the Components tab (the Proton switch migrates the prefix, about a minute, once). A failure keeps the old one
     * and never blocks the game. Call on IO after [ensureInstalled].
     */
    fun upgradeSuperseded(context: Context, onStatus: (String) -> Unit = {}, isSessionActive: () -> Boolean = { PluviaApp.keepAlive }) {
        fun container() = com.winlator.container.ContainerManager(context).getContainerById(WowContainerProfile.CONTAINER_ID)
        val vkd3d = container()?.getExtra(WowVkd3dComponents.PROFILE_KEY) ?: return
        if (supersededVkd3d(vkd3d)) {
            val current = WowVkd3dComponents.profiles(context)
                .singleOrNull { it.verName == VKD3D_NAME && it.verCode == VKD3D_VERSION_CODE }?.let(WowVkd3dComponents::id)
            if (current != null) try {
                onStatus(WowI18n.pick(context, "Updating VKD3D to r$VKD3D_VERSION_CODE...", "A VKD3D frissítése: r$VKD3D_VERSION_CODE..."))
                WowVkd3dComponents.applyProfile(context, current, isSessionActive)
            } catch (e: Exception) {
                if (isSessionActive()) throw e
                android.util.Log.w("WowBundled", "VKD3D upgrade to $current failed; the previous one stays", e)
            }
        }
        container()?.let { c ->
            val wrapper = if (c.dxWrapper.equals("dxvk", ignoreCase = true))
                "dxvk-${com.winlator.core.KeyValueSet(c.dxWrapperConfig).get("version")}" else c.dxWrapper
            val (name, code) = when {
                supersededDxvk(wrapper) || supersededDefaultDxvk(wrapper) -> DXVK_NAME to DXVK_VERSION_CODE
                else -> null to 0
            }
            if (name != null) try {
                onStatus(WowI18n.pick(context, "Updating DXVK to r$code...", "A DXVK frissítése: r$code..."))
                val profiles = WowDxvkSelector.installedProfiles(context)
                val target = profiles.singleOrNull { it.verName == name && it.verCode == code } ?: error("$name is not installed")
                WowDxvkSelector.applyToContainer(c, WowDxvkSelector.identifier(target), profiles, isSessionActive)
            } catch (e: Exception) {
                if (isSessionActive()) throw e
                android.util.Log.w("WowBundled", "DXVK upgrade to $name failed; the previous one stays", e)
            }
        }
        container()?.let { c ->
            val driver = com.winlator.core.KeyValueSet(c.graphicsDriverConfig).get("version").ifBlank { c.graphicsDriverVersion }
            if (supersededDriver(driver)) try {
                onStatus(WowI18n.pick(context, "Updating the driver to ${DRIVER_NAME.substringAfterLast(' ')}...",
                    "A driver frissítése: ${DRIVER_NAME.substringAfterLast(' ')}..."))
                val entry = WowDeveloperComponents.drivers(context).singleOrNull { it.id == DRIVER_NAME }
                    ?: error("$DRIVER_NAME is not installed")
                WowDeveloperComponents.applyDriver(c, entry)
            } catch (e: Exception) {
                if (isSessionActive()) throw e
                android.util.Log.w("WowBundled", "Driver upgrade to $DRIVER_NAME failed; the previous one stays", e)
            }
        }
        val runtime = container() ?: return
        if (supersededProton(runtime.wineVersion)) try {
            onStatus(WowI18n.pick(context, "Updating Proton to r$PROTON_VERSION_CODE (once, about a minute)...", "A Proton frissítése: r$PROTON_VERSION_CODE (egyszer, kb. egy perc)..."))
            WowDeveloperComponents.applyRuntime(context, runtime, PROTON_ID)
        } catch (e: Exception) {
            if (isSessionActive()) throw e
            android.util.Log.w("WowBundled", "Proton upgrade to $PROTON_ID failed; the previous one stays", e)
        }
    }

    /**
     * 1.3.0 (user: "ha telepítem az új APK-t, törölje a régi drivereket, Protont, DXVK-t és VKD3D-t"): the installed components an
     * update leaves behind that this build replaces - older bundled TurnRIP, Proton, Vegas DXVK and VKD3D builds. Only the bundled
     * families are matched (the same [supersededDriver] .. [supersededVkd3d] rules as the upgrade), so a component the player
     * imported is never selected, and neither is anything this build ships.
     */
    fun supersededInstalled(drivers: List<String>, dxvks: List<String>, vkd3ds: List<String>, runtimes: List<String>): List<String> =
        drivers.filter(::supersededDriver) + dxvks.filter { supersededDxvk(it) || supersededDefaultDxvk(it) } +
            vkd3ds.filter(::supersededVkd3d) + runtimes.filter(::supersededProton)

    internal class Removal(val id: String, val remove: () -> Unit)

    /**
     * Runs the removals one by one: a component still in use (a failed upgrade keeps the old one in the container) or one that
     * cannot be deleted is skipped and logged, never fatal; nothing is removed once a game session starts. Returns the removed ids.
     */
    internal fun runRemovals(removals: List<Removal>, onStatus: (String) -> Unit, isSessionActive: () -> Boolean,
                             label: (String) -> String = { "Removing the old $it..." }): List<String> {
        val removed = mutableListOf<String>()
        for (removal in removals) {
            if (isSessionActive()) break
            try {
                onStatus(label(removal.id))
                removal.remove()
                removed += removal.id
            } catch (e: Exception) {
                android.util.Log.w("WowBundled", "Old component ${removal.id} was kept", e)
            }
        }
        return removed
    }

    /** Deletes the older bundled builds after [upgradeSuperseded] moved the container off them. Call on IO; never blocks the game. */
    fun removeSuperseded(context: Context, onStatus: (String) -> Unit = {}, isSessionActive: () -> Boolean = { PluviaApp.keepAlive }): List<String> {
        if (isSessionActive()) return emptyList()
        val drivers = runCatching { WowDeveloperComponents.drivers(context) }.getOrDefault(emptyList())
        val dxvks = runCatching { WowDxvkSelector.installedProfiles(context) }.getOrDefault(emptyList())
        val vkd3ds = runCatching { WowVkd3dComponents.profiles(context) }.getOrDefault(emptyList())
        val runtimes = runCatching { WowDeveloperComponents.installedRuntimeProfiles(context) }.getOrDefault(emptyList())
        val wanted = supersededInstalled(drivers.map { it.id }, dxvks.map(WowDxvkSelector::identifier),
            vkd3ds.map(WowVkd3dComponents::id), runtimes.map(WowDeveloperComponents::runtimeId)).toSet()
        if (wanted.isEmpty()) return emptyList()
        val removals = drivers.filter { it.id in wanted }.map { e -> Removal(e.id) { WowDeveloperComponents.delete(context, e) } } +
            dxvks.filter { WowDxvkSelector.identifier(it) in wanted }.map { p ->
                val id = WowDxvkSelector.identifier(p)
                Removal(id) { WowDeveloperComponents.delete(context,
                    WowDeveloperComponents.Entry(WowDeveloperComponents.Kind.DXVK, id, "", ContentsManager.getInstallDir(context, p))) }
            } +
            vkd3ds.filter { WowVkd3dComponents.id(it) in wanted }.map { p ->
                val id = WowVkd3dComponents.id(p)
                Removal(id) { WowVkd3dComponents.delete(context, id, isSessionActive) }
            } +
            runtimes.filter { WowDeveloperComponents.runtimeId(it) in wanted }.map { p ->
                val id = WowDeveloperComponents.runtimeId(p)
                Removal(id) { WowDeveloperComponents.delete(context,
                    WowDeveloperComponents.Entry(WowDeveloperComponents.Kind.RUNTIME, id, "", ContentsManager.getInstallDir(context, p))) }
            }
        return runRemovals(removals, onStatus, isSessionActive) {
            WowI18n.pick(context, "Removing the old $it...", "A régi $it eltávolítása...")
        }
    }

    fun ensureInstalled(
        context: Context,
        onStatus: (String) -> Unit = {},
        isSessionActive: () -> Boolean = { PluviaApp.keepAlive },
    ) = synchronized(ContentsManager.getLocalInstallLock()) {
        check(!isSessionActive()) { SESSION_ACTIVE.first }
        val manager = ContentsManager(context)
        for (bundle in bundles) {
            if (!bundle.experimental) {
                install(context, manager, bundle, onStatus, isSessionActive)
                continue
            }
            // An optional experimental component must not block the game: the defaults are complete without it.
            try {
                install(context, manager, bundle, onStatus, isSessionActive)
            } catch (e: Exception) {
                if (isSessionActive()) throw e
                android.util.Log.w("WowBundled", "Optional ${bundle.name} was not installed", e)
                onStatus("Optional ${bundle.name} was not installed: ${e.message}")
            }
        }
        manager.syncContents()
    }

    private fun install(
        context: Context,
        manager: ContentsManager,
        bundle: Bundle,
        onStatus: (String) -> Unit,
        isSessionActive: () -> Boolean,
    ) {
        check(!isSessionActive()) { SESSION_ACTIVE.first }
        val directory = directory(context, bundle)
        if (directory.exists()) {
            // A complete install is preserved, never silently overwritten. A folder left half-written by an
            // interrupted first install is ours (the bundled name), so it is removed and installed again, but ONLY
            // when the damage is provably structural and shows twice: an unreadable file, a transient I/O error or a
            // low-memory failure must never cost an installed component.
            val problem = runCatching { validateInstalled(context, manager, bundle, directory) }.exceptionOrNull()
            if (problem == null) return
            // A complete profile of another version is somebody's install: keep refusing to touch it.
            if (problem is BundledConflict) throw problem
            if (!isProvablyIncomplete(problem)) throw problem
            Thread.sleep(300)
            val second = runCatching { validateInstalled(context, manager, bundle, directory) }.exceptionOrNull() ?: return
            if (second is BundledConflict || !isProvablyIncomplete(second)) throw second
            check(!isSessionActive()) { SESSION_ACTIVE.first }
            onStatus(WowI18n.pick(context, "Repairing ${bundle.name}...", "${bundle.name} javítása..."))
            com.winlator.core.FileUtils.delete(directory)
            check(!directory.exists()) { "Cannot remove the incomplete ${bundle.name} installation." }
        }
        onStatus(when (bundle.type) {
            ContentProfile.ContentType.CONTENT_TYPE_PROTON -> WowI18n.pick(context, "Installing Proton...", "A Proton telepítése...")
            ContentProfile.ContentType.CONTENT_TYPE_DXVK -> WowI18n.pick(context, "Installing DXVK...", "A DXVK telepítése...")
            ContentProfile.ContentType.CONTENT_TYPE_VKD3D -> WowI18n.pick(context, "Installing VKD3D...", "A VKD3D telepítése...")
            else -> WowI18n.pick(context, "Installing driver...", "A driver telepítése...")
        })
        val temporary = File(context.cacheDir, "bundled-${java.util.UUID.randomUUID()}-${bundle.asset}")
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            context.assets.open("bundled_components/${bundle.asset}").use { input ->
                temporary.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val length = input.read(buffer)
                        if (length < 0) break
                        digest.update(buffer, 0, length)
                        output.write(buffer, 0, length)
                    }
                }
            }
            val hash = digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
            check(hash == bundle.sha256) { "Bundled ${bundle.name} checksum mismatch." }
            check(!isSessionActive()) { SESSION_ACTIVE.first }
            val uri = Uri.fromFile(temporary)
            when (bundle.type) {
                ContentProfile.ContentType.CONTENT_TYPE_PROTON -> WowDeveloperComponents.importRuntime(context, uri)
                ContentProfile.ContentType.CONTENT_TYPE_DXVK -> WowDxvkImporter.importWcp(context, uri, isSessionActive)
                ContentProfile.ContentType.CONTENT_TYPE_VKD3D -> WowVkd3dComponents.importWcp(context, uri, isSessionActive)
                null -> WowDeveloperComponents.importDriver(context, uri)
                else -> error("Unsupported bundled component type.")
            }
            manager.syncContents()
            validateInstalled(context, manager, bundle, directory)
        } finally {
            temporary.delete()
        }
    }

    /** The installation is provably missing files or cut short (not merely unreadable): the only failure that may be repaired. */
    internal class BundledIncomplete(message: String) : IllegalStateException(message)

    /** A complete profile of another version or type sits under the bundled name: somebody's install, never touched. */
    internal class BundledConflict(message: String) : IllegalStateException(message)

    /**
     * Failures that mean "files are missing or cut short": the typed [BundledIncomplete], the explicit structural checks of the runtime
     * and VKD3D validators (IllegalArgumentException), truncated reads and absent files. Everything else (an I/O error, a generic
     * IllegalStateException, low memory) is "unreadable or unexpected" and never leads to a deletion.
     */
    internal fun isProvablyIncomplete(error: Throwable): Boolean = when (error) {
        is BundledIncomplete, is IllegalArgumentException, is java.io.EOFException, is org.json.JSONException,
        is java.nio.file.NoSuchFileException -> true
        is java.io.FileNotFoundException -> error.message.orEmpty().contains("No such file")
        else -> false
    }

    private fun directory(context: Context, bundle: Bundle): File =
        if (bundle.type == null) File(context.filesDir, "contents/adrenotools/${bundle.name}")
        else File(ContentsManager.getContentTypeDir(context, bundle.type), bundle.name)

    private fun validateInstalled(context: Context, manager: ContentsManager, bundle: Bundle, directory: File) {
        // A folder that cannot even be listed is an access problem, not a missing installation.
        if (directory.listFiles() == null) throw java.io.IOException("Cannot read the bundled ${bundle.name} folder.")
        if (bundle.type == null) {
            if (AdrenotoolsManager.validateDriverDirectory(directory) != bundle.name) throw BundledIncomplete("Invalid bundled Turnip installation.")
            return
        }
        val profileFile = File(directory, ContentsManager.PROFILE_NAME)
        if (!profileFile.exists()) throw BundledIncomplete("Bundled ${bundle.name} installation is incomplete (no profile); it was not overwritten.")
        // Read the bytes on their own: ContentsManager.readProfile turns every failure into null, which would make an unreadable
        // profile look like a damaged one. An I/O error here propagates as it is and leaves the installation alone.
        profileFile.readBytes()
        val profile = manager.readProfile(profileFile)
            ?: throw BundledIncomplete("Bundled ${bundle.name} installation is incomplete (unparsable profile); it was not overwritten.")
        val expectedCode = bundle.versionCode ?: if (bundle.type == ContentProfile.ContentType.CONTENT_TYPE_PROTON)
            PROTON_VERSION_CODE else if (bundle.type == ContentProfile.ContentType.CONTENT_TYPE_VKD3D) VKD3D_VERSION_CODE
            else DXVK_VERSION_CODE
        if (!(profile.type == bundle.type && profile.verName == bundle.name && profile.verCode == expectedCode)) {
            throw BundledConflict("Bundled ${bundle.name} profile conflicts with an existing installation; it was not overwritten.")
        }
        if (bundle.type == ContentProfile.ContentType.CONTENT_TYPE_PROTON) {
            WowDeveloperComponents.validateRuntime(directory, profile)
            val info = WineInfo.fromIdentifier(context, manager, PROTON_ID)
            check(info.isArm64EC && !info.path.isNullOrBlank() && File(info.path).canonicalFile == directory.canonicalFile) {
                "Bundled Proton identifier cannot be resolved without fallback."
            }
        } else if (bundle.type == ContentProfile.ContentType.CONTENT_TYPE_VKD3D) {
            WowVkd3dComponents.dlls(directory, profile)
        } else {
            if (!(!profile.fileList.isNullOrEmpty() && manager.getUnTrustedContentFiles(profile).isEmpty() &&
                profile.fileList.all { File(directory, it.source).isFile })) throw BundledIncomplete("Bundled DXVK installation is incomplete.")
        }
    }
}
