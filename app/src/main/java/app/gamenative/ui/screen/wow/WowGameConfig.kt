package app.gamenative.ui.screen.wow

import java.io.File

/** Game graphics defaults are applied only to a new Config.wtf; existing player choices persist. */
internal object WowGameConfig {
    /**
     * [gameAccount]: 1-based WoW game account (WoW1, WoW2 ...) to preselect when the Battle.net account has several, or null for the
     * one WoW used last. It only moves WoW's own selection mark in an accountList that WoW wrote after a first login.
     */
    fun prepare(root: File, resolution: String? = null, graphicsApi: String = "D3D11", gameAccount: Int? = null, region: String? = null,
                locale: String? = null) {
        require(graphicsApi in setOf("D3D11", "D3D12"))
        val flavorDir = File(root, WowClientDownloader.FLAVOR_DIR)
        flavorDir.mkdirs()
        val flavorInfo = File(flavorDir, ".flavor.info")
        if (!flavorInfo.exists()) {
            flavorInfo.writeText("Product Flavor!STRING:0\nwowf\n")
        }
        val configWtf = File(flavorDir, "WTF/Config.wtf")
        val defaults = linkedMapOf(
            // Without the Battle.net app the portal comes from here; a missing one stops WoW at a region picker.
            "portal" to "\"${region ?: WowClientDownloader.region(root)}\"",
            "agentUID" to "\"wowf\"",
            "gxApi" to "\"$graphicsApi\"",
            "textLocale" to "\"${locale ?: "enUS"}\"",
            "audioLocale" to "\"${locale ?: "enUS"}\"",
            "gxMaximize" to "\"1\"",
            "gxWindowedResolution" to "\"1920x1080\"",
            "graphicsQuality" to "\"0\"",
            "ResampleQuality" to "\"0\"",
            "RenderScale" to "\"1\"",
            "farclip" to "\"1200\"",
            "horizonClip" to "\"1200\"",
            "RAIDfarclip" to "\"1200\"",
            "RAIDhorizonClip" to "\"1200\"",
            "shadowMode" to "\"0\"",
            "graphicsShadowQuality" to "\"0\"",
            "raidGraphicsShadowQuality" to "\"0\"",
            "worldBaseMip" to "\"0\"",
            "RAIDworldBaseMip" to "\"0\"",
            "graphicsTextureResolution" to "\"2\"",
            "raidGraphicsTextureResolution" to "\"2\"",
            "componentTextureLevel" to "\"0\"",
            "RAIDcomponentTextureLevel" to "\"0\"",
            "entityShadowFadeScale" to "\"0\"",
            "refraction" to "\"0\"",
            "groundEffectDensity" to "\"16\"",
            // The first-run Screen Narrator prompt otherwise waits for an answer in front of the login (jaredgei 2.3.0).
            "showScreenNarrationDialog" to "\"0\"",
            "accessibilityScreenNarrationEnabled" to "\"0\"",
            "GamePadEnable" to "\"1\"",
            "InputDeviceInterfaceStyle" to "\"1\""
        )
        // An install upgraded from the Classic beta keeps the player's settings; the beta's portal, agent and caches stay behind.
        val betaConfig = File(root, "${WowClientDownloader.LEGACY_FLAVOR_DIR}/WTF/Config.wtf")
        if (!configWtf.exists() && betaConfig.isFile) {
            val left = listOf("SET portal ", "SET agentUID ", "SET CACHE-")
            val carried = betaConfig.readLines().filter { line -> left.none { line.trimStart().startsWith(it, ignoreCase = true) } }
            configWtf.parentFile?.mkdirs()
            writeAtomically(configWtf, carried.joinToString("\n", postfix = "\n"))
        }
        if (!configWtf.exists()) {
            configWtf.parentFile?.mkdirs()
            writeAtomically(configWtf, defaults.entries.joinToString("\n") { "SET ${it.key} ${it.value}" } + "\n")
        } else {
            val before = configWtf.readLines()
            val scaled = renderScalePerApi(before, graphicsApi, File(flavorDir, "WTF/$RENDER_SCALE_MEMORY"))
            val after = selectGameAccount(prepareExistingLines(scaled, defaults, locale != null), gameAccount)
            if (before != after) writeAtomically(configWtf, after.joinToString("\n", postfix = "\n"))
        }
        WowDisplaySettings.alignGameConfig(configWtf, resolution)
    }


    /** The launcher's own note of the last render scale under each graphics API ("D3D11=1.25"), next to Config.wtf. */
    const val RENDER_SCALE_MEMORY = "forever-renderscale.properties"

    /**
     * WoW keeps one renderScale for both APIs, and its DX12 renderer allows at most 100% on this phone (DX11 up to 200%): after one
     * DX12 session the file says 1 and a 125% chosen under DX11 was gone. The last value seen under each API is noted in [memory];
     * when the next launch switches the API, the value noted for the new one replaces the file's. Without a switch nothing changes.
     */
    internal fun renderScalePerApi(lines: List<String>, api: String, memory: File): List<String> {
        val setting = Regex("^\\s*SET\\s+(\\S+)\\s+\"([^\"]*)\".*$", RegexOption.IGNORE_CASE)
        fun value(name: String) = lines.firstNotNullOfOrNull { line ->
            setting.matchEntire(line)?.takeIf { it.groupValues[1].equals(name, true) }?.groupValues?.get(2) }
        val current = value("gxApi")?.uppercase()?.takeIf { it == "D3D11" || it == "D3D12" }
        // 1.1.9: WoW leaves a CVar at its default out of the file, so no line is 100% ("1"), not "unknown".
        val written = value("renderScale")
        val scale = if (written == null) "1" else written.takeIf { it.toDoubleOrNull()?.let { v -> v > 0.0 && v <= 4.0 } == true }
        val noted = java.util.Properties()
        runCatching { if (memory.isFile) memory.inputStream().use { noted.load(it) } }
        val before = noted.toMap()
        if (current != null && scale != null) noted.setProperty(current, scale)
        var result = lines
        val wanted = noted.getProperty(api)
        if (current != null && current != api && wanted != null && wanted != scale) {
            var replaced = false
            result = lines.mapNotNull { line ->
                if (setting.matchEntire(line)?.groupValues?.get(1)?.equals("renderScale", true) != true) line
                else if (replaced) null
                else { replaced = true; Regex("(?i)^(\\s*SET\\s+\\S+\\s+\")[^\"]*").replace(line) { it.groupValues[1] + wanted } }
            }
            if (!replaced) result = result + "SET renderScale \"$wanted\""
        }
        if (noted.toMap() != before) runCatching {
            memory.parentFile?.mkdirs()
            memory.outputStream().use { noted.store(it, "Forever Launcher: last WoW renderScale per graphics API") }
        }
        return result
    }

    /** What the game uses now, for the Settings Game tab: region code, game language, game account ("1", "2"). */
    data class Current(val region: String, val locale: String, val account: String)

    /**
     * The install's region (.build.info), Config.wtf's textLocale (else enUS) and the account WoW marks with "!" in accountList
     * (else WoW1, the only one an account with a single game account has).
     */
    fun current(root: File): Current {
        val lines = runCatching { File(root, "${WowClientDownloader.FLAVOR_DIR}/WTF/Config.wtf").readLines() }.getOrDefault(emptyList())
        fun value(name: String) = lines.firstNotNullOfOrNull { line ->
            Regex("^\\s*SET\\s+$name\\s+\"([^\"]*)\"", RegexOption.IGNORE_CASE).find(line)?.groupValues?.get(1)
        }
        val locale = value("textLocale")?.takeIf { it in WowRuntimeTuning.GAME_LOCALES } ?: "enUS"
        val account = value("accountList")?.split("|")?.firstOrNull { it.startsWith("!") }
            ?.let { Regex("^!WoW(\\d+)$", RegexOption.IGNORE_CASE).find(it)?.groupValues?.get(1) }?.takeIf { it == "1" || it == "2" } ?: "1"
        return Current(WowClientDownloader.region(root), locale, account)
    }

    /** A crash or power loss while writing must not leave a half-written Config.wtf (it holds the player's settings). */
    private fun writeAtomically(file: File, text: String) {
        val temp = File(file.parentFile, "${file.name}.tmp")
        temp.writeText(text)
        java.nio.file.Files.move(temp.toPath(), file.toPath(),
            java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
    }

    /**
     * WoW remembers the game account it logged into last as `SET accountList "!WoW1|"` (the "!" marks the one it picks; a player's
     * file had only that one entry). Marks WoW<[account]> instead, adding the entry (or the line) when it is not there yet; the
     * other entries stay. A list in an unexpected format is left as it is.
     */
    internal fun selectGameAccount(lines: List<String>, account: Int?): List<String> {
        if (account == null) return lines
        val wanted = "WoW$account"
        val pattern = Regex("^(\\s*SET\\s+accountList\\s+\")([^\"]*)(\".*)$", RegexOption.IGNORE_CASE)
        if (lines.none { pattern.matches(it) }) return lines + "SET accountList \"!$wanted|\""
        return lines.map { line ->
            val match = pattern.matchEntire(line) ?: return@map line
            val (prefix, list, suffix) = match.destructured
            val entries = if (list.isEmpty()) emptyList() else list.split("|").let { if (list.endsWith("|")) it.dropLast(1) else it }
            if (entries.any { it.removePrefix("!").isBlank() }) return@map line
            val names = entries.map { it.removePrefix("!") }.let { if (it.none { n -> n.equals(wanted, true) }) it + wanted else it }
            prefix + names.joinToString("") { (if (it.equals(wanted, true)) "!" else "") + it + "|" } + suffix
        }
    }

    /** [ownLocale]: a game language chosen in the launcher replaces the one in the file; otherwise the file's own stays. */
    private fun prepareExistingLines(lines: List<String>, defaults: Map<String, String>, ownLocale: Boolean = false): List<String> {
        // The portal follows the install's region (.build.info branch), so a region chosen in the launcher reaches the game.
        val required = linkedMapOf("gxApi" to defaults.getValue("gxApi"), "GamePadEnable" to "\"1\"",
            "InputDeviceInterfaceStyle" to "\"1\"", "portal" to defaults.getValue("portal"))
        if (ownLocale) { required["textLocale"] = defaults.getValue("textLocale"); required["audioLocale"] = defaults.getValue("audioLocale") }
        val metadata = listOf("agentUID", "textLocale", "audioLocale", "showScreenNarrationDialog", "accessibilityScreenNarrationEnabled")
            .filter { it !in required }
        val setting = Regex("^\\s*SET\\s+(\\S+)\\s+.*$", RegexOption.IGNORE_CASE)
        val seenRequired = mutableSetOf<String>()
        val seenMetadata = mutableSetOf<String>()
        val result = mutableListOf<String>()
        for (line in lines) {
            val key = setting.matchEntire(line)?.groupValues?.get(1)
            val owned = required.keys.firstOrNull { it.equals(key, ignoreCase = true) }
            metadata.firstOrNull { it.equals(key, ignoreCase = true) }?.let { seenMetadata += it }
            if (owned == null) result += line
            else if (seenRequired.add(owned)) result += "SET $owned ${required.getValue(owned)}"
        }
        required.forEach { (key, value) -> if (key !in seenRequired) result += "SET $key $value" }
        metadata.forEach { key -> if (key !in seenMetadata) result += "SET $key ${defaults.getValue(key)}" }
        return result
    }
}
