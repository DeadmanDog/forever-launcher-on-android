package app.gamenative.wow.addons

/**
 * The hosts the in-app CurseForge download page may navigate to: the CurseForge site itself (www., the download API and its
 * Cloudflare check live there) and its file CDN (edge./mediafilez.forgecdn.net). Anything else the page links to opens in the
 * player's own browser instead.
 */
object CurseForgeWebHosts {
    private val roots = listOf("curseforge.com", "forgecdn.net")

    /** The lower-case host of an https URL, or null for anything else (another scheme, no host, not a URL). */
    fun httpsHost(url: String): String? = runCatching {
        val uri = java.net.URI(url)
        if (!"https".equals(uri.scheme, ignoreCase = true)) null else uri.host?.lowercase()?.takeIf { it.isNotEmpty() }
    }.getOrNull()

    fun isAllowedHost(host: String?): Boolean {
        val h = host?.lowercase()?.trimEnd('.') ?: return false
        return roots.any { h == it || h.endsWith(".$it") }
    }

    /** True for an https URL on a CurseForge host. */
    fun isAllowed(url: String): Boolean = isAllowedHost(httpsHost(url))
}
