package app.gamenative.ui.screen.wow

import android.content.Context
import com.winlator.container.Container
import com.winlator.contents.ContentProfile
import com.winlator.contents.ContentsManager
import com.winlator.core.envvars.EnvVars
import java.io.File

/** Cache defaults are launch-local. No container config, prefix or component is written here. */
internal object WowLaunchEnvironment {
    const val LOCATION_KEY = "wowCacheLocation"
    const val GAME_DIRECTORY = "game"
    const val GAMENATIVE_DEFAULT = "runtimeDefault"
    /** v1.0: the caches default to the app's private storage; the Mesa shader cache could not be written on the shared /sdcard (0-byte .tmp files). */
    fun location(container: Container) = container.getExtra(LOCATION_KEY).ifBlank { GAMENATIVE_DEFAULT }

    fun saveLocation(context: Context, value: String, active: () -> Boolean) {
        require(value == GAME_DIRECTORY || value == GAMENATIVE_DEFAULT) { "Invalid cache location." }
        check(!active()) { "Cache settings cannot be saved during an active session." }
        val manager = com.winlator.container.ContainerManager(context)
        val container = manager.getContainerById(WowContainerProfile.CONTAINER_ID) ?: error("WoW container has not been created yet")
        check(!active()) { "Cache settings cannot be saved during an active session." }
        container.putExtra(LOCATION_KEY, value)
        container.saveData()
        check(com.winlator.container.ContainerManager(context).getContainerById(container.id)?.getExtra(LOCATION_KEY) == value) {
            "Unable to save cache location."
        }
    }

    data class GameDirectory(val host: File, val windows: String)
    private val cacheNames = setOf("DXVK_STATE_CACHE_PATH", "DXVK_SHADER_CACHE_PATH")
    private data class Stamp(val path: String, val length: Long, val modified: Long)
    private val capabilities = object : LinkedHashMap<Stamp, Set<String>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Stamp, Set<String>>?) = size > 16
    }

    @Synchronized
    fun invalidateDxvkCapabilities() { capabilities.clear() }

    fun gameDirectory(container: Container): GameDirectory {
        val executable = container.executablePath.replace('/', '\\')
        require(Regex("^[A-Za-z]:\\\\.+").matches(executable)) { "Game executable must have an absolute Windows drive path." }
        val letter = executable.substring(0, 1).uppercase()
        val mapping = Container.drivesIterator(container.drives).asSequence().map { it.copyOf() }.singleOrNull { it[0].equals(letter, true) }
            ?: error("Game executable drive $letter: is not mapped.")
        require(File(mapping[1]).isAbsolute) { "Game drive must map to an absolute host directory." }
        val root = File(mapping[1]).canonicalFile
        val segments = executable.substring(3).split('\\').filter { it.isNotEmpty() && it != "." }
        require(segments.isNotEmpty() && segments.none { it == ".." || ':' in it || '\u0000' in it }) { "Unsafe game executable path." }
        val directory = segments.dropLast(1).joinToString("/")
        val host = File(root, directory).canonicalFile
        require(host.toPath().startsWith(root.toPath())) { "Game executable escapes the mapped drive." }
        val windows = "$letter:\\" + segments.dropLast(1).joinToString("\\")
        return GameDirectory(host, windows)
    }

    /** Inspect the selected payload, so newer imported builds get only their supported cache variable. */
    @Synchronized
    fun supportedDxvkCacheVariables(files: List<File>): Set<String> = files.filter { it.isFile }.flatMap { file ->
        val stamp = Stamp(file.canonicalPath, file.length(), file.lastModified())
        capabilities.getOrPut(stamp) {
            val found = mutableSetOf<String>()
            file.inputStream().buffered().use { stream ->
                val buffer = ByteArray(65536)
                var tail = ""
                while (true) {
                    val count = stream.read(buffer)
                    if (count < 0) break
                    val text = tail + String(buffer, 0, count, Charsets.ISO_8859_1)
                    cacheNames.filterTo(found) { "$it\u0000" in text }
                    tail = text.takeLast(64)
                }
            }
            found.toSet()
        }
    }.toSet()

    fun selectedDxvkFiles(context: Context, container: Container, manager: ContentsManager): List<File> {
        val profile = WowDxvkSelector.currentProfile(container.dxWrapper, container.dxWrapperConfig,
            manager.getProfiles(ContentProfile.ContentType.CONTENT_TYPE_DXVK).orEmpty()) ?: return emptyList()
        val root = ContentsManager.getInstallDir(context, profile).canonicalFile
        return profile.fileList.orEmpty().mapNotNull { item ->
            val source = item.source ?: return@mapNotNull null
            val path = File(root, source).canonicalFile
            path.takeIf { it.toPath().startsWith(root.toPath()) && it.extension.equals("dll", true) }
        }
    }

    fun defaults(container: Container, supported: Set<String>): EnvVars {
        if (location(container) == GAMENATIVE_DEFAULT) return EnvVars()
        val directory = gameDirectory(container)
        return EnvVars().apply {
            // DXVK is a Windows DLL: use its mapped Windows path, not a Unix path interpreted by the CRT.
            supported.intersect(cacheNames).forEach { put(it, directory.windows) }
            // Mesa appends mesa_shader_cache[_db/_sf] below this host directory itself.
            put("MESA_SHADER_CACHE_DIR", directory.host.path)
        }
    }

    fun merge(base: EnvVars, automatic: EnvVars, user: String): EnvVars = EnvVars().apply {
        putAll(base); putAll(automatic); putAll(user)
    }

    fun apply(context: Context, container: Container, manager: ContentsManager, env: EnvVars) {

        if (container.id != WowContainerProfile.CONTAINER_ID) return
        applyRuntimeDefaults(container, env)
        keepWinegstreamerVisibleForDiagnostics(container, env, WowDiagnostics.enabled(context))
        val gamescope = app.gamenative.display.WowDisplayBackend.isGamescope(container)
        applyPresentation(if (gamescope) "fifo" else container.rendererPresentMode, container.envVars, env,
            if (gamescope) "" else com.winlator.core.KeyValueSet(container.graphicsDriverConfig).get("presentMode"),
            WowRuntimeTuning.selected(context, "present"))
        WowRuntimeTuning.applyGameGpuWait(env, EnvVars(container.envVars), !gamescope &&
            WowVkd3dComponents.api(container) == WowVkd3dComponents.DX12 && WowLaunchPolicy.enabled(context, WowRuntimeTuning.GAME_GPU_WAIT))
        if (location(container) == GAMENATIVE_DEFAULT) {
            mergeUserOverrides(container, env)
            return
        }
        val supported = supportedDxvkCacheVariables(selectedDxvkFiles(context, container, manager))
        // The old global DXVK helper default must not survive for a build with a different cache API.
        cacheNames.forEach(env::remove)
        env.putAll(defaults(container, supported))
        mergeUserOverrides(container, env)

    }

    // Every FrontMage Proton (90639.7+) and Vegas WoW build (r6+) we have shipped. Matching the
    // naming scheme keeps older selections recognized after the bundled identifiers move forward.
    private val bundledProton = Regex("""proton-11\.0-90639\.(\d+)-arm64ec-(\d+)""")
    private val bundledVegas = Regex("""Vegas-(?:2\.7\.3|3\.1\.1)-wow-arm64-(?:async|hybrid)-gamenative-r(\d+)-(\d+)""")

    internal fun isBundledProton(id: String): Boolean = bundledProton.matchEntire(id)?.destructured
        ?.let { (build, code) -> build == code && build.toInt() >= 7 } ?: false

    internal fun isBundledVegas(version: String): Boolean = bundledVegas.matchEntire(version)?.destructured
        ?.let { (build, code) -> build == code && build.toInt() >= 6 } ?: false

    internal const val WINEGSTREAMER_OFF = "winegstreamer=d"

    /** With diagnostic logging on, let Wine really try (and log) a winegstreamer load; only our own default is dropped, never a saved value. */
    internal fun keepWinegstreamerVisibleForDiagnostics(container: Container, env: EnvVars, diagnostics: Boolean) {
        if (diagnostics && env.get("WINEDLLOVERRIDES") == WINEGSTREAMER_OFF && !EnvVars(container.envVars).has("WINEDLLOVERRIDES")) {
            env.remove("WINEDLLOVERRIDES")
        }
    }

    /** Supported defaults for our bundled stack; explicit saved switches remain authoritative. */
    fun applyRuntimeDefaults(container: Container, env: EnvVars) {
        if (container.id != WowContainerProfile.CONTAINER_ID) return
        val saved = EnvVars(container.envVars)
        fun default(name: String, value: String) {
            if (!saved.has(name)) env.put(name, value)
        }
        if (isBundledProton(container.wineVersion)) {
            default("WINENTSYNC", "1")
            default("PROTON_NO_NTSYNC", "0")
            default("WINEESYNC", "0")
            default("WINEFSYNC", "0")
            // The imagefs ships no GStreamer (and no plugins since 1.1.8): never try to load winegstreamer at all.
            default("WINEDLLOVERRIDES", WINEGSTREAMER_OFF)
        }
        val dxvkConfig = com.winlator.core.KeyValueSet(container.dxWrapperConfig)
        val dxvkVersion = dxvkConfig.get("version")
        // Identifier and config must name the same bundled Vegas build.
        val supportedVegas = isBundledVegas(dxvkVersion) && container.dxWrapper == "dxvk-$dxvkVersion"
        if (supportedVegas) {
            val async = dxvkConfig.get("async")
            default("DXVK_ASYNC", if (async == "0") "0" else "1")
        }
        env.putAll(saved)
    }

    /** Default both presentation layers consistently, preserving explicit Mesa overrides. */
    fun applyPresentation(rendererMode: String, user: String, env: EnvVars, driverMode: String = "", uiMode: String = "") {
        env.put("MESA_VK_WSI_PRESENT_MODE", resolvePresentMode(user, uiMode, driverMode, rendererMode))
    }

    /**
     * The one present policy for the game's swapchain (Mesa) and the compositor: a saved MESA_VK_WSI_PRESENT_MODE wins, then the
     * Developer choice, then the driver config, then the renderer default. Both sides call this, so the menu choice reaches both.
     */
    fun resolvePresentMode(saved: String, uiMode: String, driverMode: String, rendererMode: String): String {
        val override = EnvVars(saved)
        if (override.has("MESA_VK_WSI_PRESENT_MODE")) return override.get("MESA_VK_WSI_PRESENT_MODE")
        return when (val requested = uiMode.ifBlank { driverMode.ifBlank { rendererMode } }.lowercase()) {
            "mailbox", "immediate", "fifo" -> requested
            "relaxed", "fifo_relaxed" -> "relaxed"
            else -> "fifo"
        }
    }

    @JvmStatic
    fun mergeUserOverrides(container: Container, env: EnvVars) {

        if (container.id == WowContainerProfile.CONTAINER_ID) env.putAll(container.envVars)

    }
}
