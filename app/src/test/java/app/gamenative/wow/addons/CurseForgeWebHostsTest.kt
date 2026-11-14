package app.gamenative.wow.addons

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Only CurseForge's own hosts load in the in-app download page. */
class CurseForgeWebHostsTest {
    @Test fun curseForgeAndItsCdnAreAllowed() {
        for (url in listOf(
            "https://www.curseforge.com/wow/addons/details/download/123",
            "https://curseforge.com/",
            "https://WWW.CurseForge.com/api/v1/mods/1/files/2/download",
            "https://edge.forgecdn.net/files/1/2/addon.zip",
            "https://mediafilez.forgecdn.net/files/1/2/addon.zip",
        )) assertTrue(url, CurseForgeWebHosts.isAllowed(url))
    }

    @Test fun otherHostsLookalikesAndOtherSchemesAreNot() {
        for (url in listOf(
            "https://example.com/",
            "https://curseforge.com.example.com/",
            "https://evilcurseforge.com/",
            "https://www.curseforge.com@example.com/",
            "http://www.curseforge.com/",
            "javascript:alert(1)",
            "file:///data/data/x",
            "not a url",
            "",
        )) assertFalse(url, CurseForgeWebHosts.isAllowed(url))
    }

    @Test fun hostOfAnHttpsUrl() {
        assertEquals("www.curseforge.com", CurseForgeWebHosts.httpsHost("https://www.CurseForge.com/x"))
        assertNull(CurseForgeWebHosts.httpsHost("http://www.curseforge.com/x"))
        assertTrue(CurseForgeWebHosts.isAllowedHost("www.curseforge.com."))
        assertFalse(CurseForgeWebHosts.isAllowedHost(null))
    }
}
