package app.gamenative.ui.screen.wow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Locale

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(application = android.app.Application::class, sdk = [29])
class WowI18nTest {
    @Test fun exactTemplateAndNestedTextsAreTranslated() {
        WowI18n.install("xx", """{"Settings": "Einstellungen", "Installing {0}": "{0} wird installiert", "{0}: done.": "{0}: fertig.",
            "Updating all addons": "Alle Addons werden aktualisiert", "Not enough free space: {0} GB needed, {1} GB free.": "{1} GB frei, {0} GB nötig."}""")
        assertEquals("Einstellungen", WowI18n.translate("xx", "Settings"))
        assertEquals("DBM wird installiert", WowI18n.translate("xx", "Installing DBM"))
        // A known text inside a placeholder is translated too: "{0}: done." around "Updating all addons".
        assertEquals("Alle Addons werden aktualisiert: fertig.", WowI18n.translate("xx", "Updating all addons: done."))
        // Translations may reorder the values.
        assertEquals("3 GB frei, 5 GB nötig.", WowI18n.translate("xx", "Not enough free space: 5 GB needed, 3 GB free."))
        // Unknown text and English/Hungarian stay as they are.
        assertEquals("Something new", WowI18n.translate("xx", "Something new"))
        assertEquals("Settings", WowI18n.translate("en", "Settings"))
    }

    @Test fun systemLanguageMapsToTheAppLanguages() {
        assertEquals("de", WowUiLanguage.systemCode(Locale.GERMANY))
        assertEquals("pt-BR", WowUiLanguage.systemCode(Locale.forLanguageTag("pt-PT")))
        assertEquals("zh-TW", WowUiLanguage.systemCode(Locale.forLanguageTag("zh-Hant-HK")))
        assertEquals("zh-CN", WowUiLanguage.systemCode(Locale.forLanguageTag("zh-CN")))
        assertEquals("en", WowUiLanguage.systemCode(Locale.ITALY))
        assertEquals("ko", WowUiLanguage.code(WowLanguage.SYSTEM, Locale.KOREA))
        assertEquals("ru", WowUiLanguage.code(WowLanguage.RU, Locale.US))
        assertTrue(WowUiLanguage.isHungarian(WowLanguage.SYSTEM, "hu"))
    }

    /** Every bundled language file covers every source text with matching placeholders (tools/i18n/extract_strings.py --check). */
    @Test fun bundledLanguageFilesAreCompleteAndKeepPlaceholders() {
        val dir = listOf(File("src/main/assets/i18n"), File("app/src/main/assets/i18n")).first { it.isDirectory }
        val codes = WowLanguage.entries.map { it.code }.filter { it !in setOf("system", "en", "hu") }
        val placeholder = Regex("""\{\d+\}""")
        var reference: Set<String>? = null
        for (code in codes) {
            val json = org.json.JSONObject(File(dir, "$code.json").readText())
            val keys = json.keys().asSequence().toSet()
            if (reference == null) reference = keys
            assertEquals("$code covers the same texts", reference, keys)
            for (key in keys) {
                assertEquals("$code: $key", placeholder.findAll(key).map { it.value }.sorted().toList(),
                    placeholder.findAll(json.getString(key)).map { it.value }.sorted().toList())
            }
            WowI18n.install(code, File(dir, "$code.json").readText())
            assertTrue(WowI18n.translate(code, "Settings") != "Settings")
        }
    }
}
