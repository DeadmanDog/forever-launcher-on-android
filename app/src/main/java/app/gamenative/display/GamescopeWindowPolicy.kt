package app.gamenative.display

/** Display/session policy kept independent of JNI for regression checks. */
internal object GamescopeWindowPolicy {
    fun gameLaunch(gamescope: Boolean, executable: String) = gamescope && executable.contains("ARM64", ignoreCase = true)
    // Keep Explorer bootstrap. Gamescope owns the real X11 display; asking Wine
    // for another virtual desktop adds a second display/coordinate space beneath it.
    // Wine's documented-in-source special desktop name "root" uses DF_WINE_ROOT_DESKTOP.
    fun command(screen: String, target: String, arguments: String, gamescopeGame: Boolean = false): String =
        "wine explorer /desktop=${if (gamescopeGame) "root" else "shell"},$screen " + target +
            if (arguments.isNotEmpty()) " $arguments" else ""

    // Keep output composition, but do not race Wine's/game's own window resize
    // with a forced size on every newly focused window (including auxiliary UI).
    const val GAME_DISPLAY_FLAGS = "--force-composition"

    /**
     * WowLaunchPolicy switch (off by default): let Gamescope flip the game's own buffer to the Android layer when it can, instead
     * of compositing every frame. The forced composition came in when the WoW window was 1024x768 on a 1344x608 output and every
     * flip was refused ("Failed to prepare 1-layer flip"); since 1036 the window matches the output. Gamescope composites by
     * itself whenever a flip is not possible (effects, cursor-only frames, a refused buffer).
     */
    const val DIRECT_SCANOUT = "gamescopeflip"

    fun gameDisplayFlags(directScanout: Boolean): String = if (directScanout) "" else " $GAME_DISPLAY_FLAGS"

    fun surfaceSize(actualWidth: Int, actualHeight: Int, guestWidth: Int, guestHeight: Int): Pair<Int, Int> {
        require(guestWidth > 0 && guestHeight > 0)
        return if (actualWidth > 0 && actualHeight > 0) actualWidth to actualHeight else guestWidth to guestHeight
    }

    private val node = Regex("(?m)^\\s*(?:window\\s+)?(0x[0-9a-fA-F]+)\\s*$")
    private fun hasGameName(window: String): Boolean = window.lineSequence().any { line ->
        (line.contains("WM_CLASS") || line.contains("WM_NAME")) &&
            Regex("(?i)(?:world of warcraft|\\bwow(?:-arm64)?(?:\\.exe)?\\b)").containsMatchIn(line)
    }
    /** Tree mode names candidates; target mode supplies each candidate's map state. */
    fun gameWindowIds(tree: String): List<String> {
        val nodes = node.findAll(tree).toList()
        return nodes.mapIndexedNotNull { index, match ->
            val block = tree.substring(match.range.first, nodes.getOrNull(index + 1)?.range?.first ?: tree.length)
            match.groupValues[1].takeIf { hasGameName(block) }
        }.distinct().take(8)
    }
    fun hasMappedGameWindow(details: String): Boolean = details.split(node).any {
        it.contains("map_state=IsViewable") && hasGameName(it)
    }
}
