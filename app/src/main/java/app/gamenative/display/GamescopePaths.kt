package app.gamenative.display

/** Relocate upstream's compiled-in install prefix without shifting ELF offsets. */
internal object GamescopePaths {
    const val ORIGINAL = "/data/user/0/app.gamenative/files/gamescope"
    const val ALIAS = ".gsc"
    val binaries = linkedMapOf("bin/Xwayland" to 8, "bin/xkbcomp" to 1, "bin/gamescope" to 1)

    fun prefix(dataDirectory: String): String {
        require(dataDirectory.startsWith('/') && '\u0000' !in dataDirectory)
        val base = dataDirectory.trimEnd('/') + "/"
        val padding = ORIGINAL.length - base.length - ALIAS.length
        require(padding >= 0) { "Application data path is too long for the Gamescope XKB runtime" }
        // Repeated separators resolve to the same app-private alias on Android/Linux.
        return base + "/".repeat(padding) + ALIAS
    }

    fun relocate(input: ByteArray, replacement: String, expectedMatches: Int): ByteArray {
        val source = ORIGINAL.toByteArray(Charsets.UTF_8)
        val target = replacement.toByteArray(Charsets.UTF_8)
        require(target.size == source.size && 0.toByte() !in target) { "Relocation must preserve ELF string widths" }
        val output = input.copyOf()
        var matches = 0
        var offset = 0
        while (offset <= input.size - source.size) {
            if (source.indices.all { input[offset + it] == source[it] }) {
                target.copyInto(output, offset)
                matches++
                offset += source.size
            } else offset++
        }
        check(matches == expectedMatches) { "Unexpected Gamescope binary layout: expected $expectedMatches install paths, found $matches" }
        return output
    }
}
