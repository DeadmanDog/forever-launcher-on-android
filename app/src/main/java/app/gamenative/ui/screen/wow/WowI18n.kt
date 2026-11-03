package app.gamenative.ui.screen.wow

import android.content.Context
import android.content.res.AssetManager
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * The UI languages beyond English and Hungarian. The code keeps its tx("en", "hu") pairs; for another language the rendered
 * English text is looked up in assets/i18n/<code>.json ({"English template": "translated template"}), written from
 * tools/i18n/source_en.json. Templates carry the Kotlin string-template values as {0}, {1} ...: a rendered text that matches a
 * template gets the translated template filled with the same values (each value is itself translated when it is a known text,
 * e.g. an addon job label inside "{0}: done."). Text without a translation stays English.
 */
internal object WowI18n {
    @Volatile private var assets: AssetManager? = null

    fun init(context: Context) { assets = context.applicationContext.assets }

    private class Table(val exact: Map<String, String>, val templates: List<Pair<Regex, String>>)

    private val tables = ConcurrentHashMap<String, Table>()
    private val cache = ConcurrentHashMap<String, String>()
    private val placeholder = Regex("""\{(\d+)\}""")

    /** Loads a table from JSON text; exposed for tests. */
    internal fun table(json: String): Pair<Map<String, String>, Int> = parse(json).let { it.exact to it.templates.size }

    private fun parse(json: String): Table {
        val obj = JSONObject(json)
        val exact = HashMap<String, String>()
        val templates = ArrayList<Pair<Regex, String>>()
        for (key in obj.keys()) {
            val value = obj.optString(key).takeIf { it.isNotEmpty() } ?: continue
            if (placeholder.containsMatchIn(key)) {
                templates += pattern(key) to value
            } else {
                exact[unescape(key)] = unescape(value)
            }
        }
        // More fixed text first: "Updating all addons" before "{0}: done.".
        templates.sortByDescending { (regex, _) -> regex.pattern.length }
        return Table(exact, templates)
    }

    private fun unescape(text: String) = text.replace("{{", "{").replace("}}", "}")

    private fun pattern(template: String): Regex {
        val out = StringBuilder("^")
        var last = 0
        for (m in placeholder.findAll(template)) {
            out.append(Regex.escape(unescape(template.substring(last, m.range.first))))
            out.append("(.+?)")
            last = m.range.last + 1
        }
        out.append(Regex.escape(unescape(template.substring(last)))).append("$")
        return Regex(out.toString(), RegexOption.DOT_MATCHES_ALL)
    }

    private fun load(code: String): Table? = tables[code] ?: runCatching {
        val manager = assets ?: return null
        parse(manager.open("i18n/$code.json").bufferedReader().use { it.readText() })
    }.getOrNull()?.also { tables[code] = it }

    /** Installs a table directly (tests). */
    internal fun install(code: String, json: String) { tables[code] = parse(json); cache.clear() }

    fun translate(code: String, en: String, depth: Int = 0): String {
        // Hungarian has a table too (generated from the tx pairs) for texts that background work reports in English.
        if (en.isEmpty() || code == "en") return en
        val key = code + '\u0000' + en
        cache[key]?.let { return it }
        val table = load(code) ?: return en
        val result = table.exact[en] ?: run {
            for ((regex, translated) in table.templates) {
                val match = regex.matchEntire(en) ?: continue
                val values = match.groupValues.drop(1).map { if (depth < 2) translate(code, it, depth + 1) else it }
                return@run unescape(placeholder.replace(translated) { m -> values.getOrElse(m.groupValues[1].toInt()) { m.value } })
            }
            en
        }
        if (cache.size > 2000) cache.clear()
        cache[key] = result
        return result
    }

    /** For text built outside composition (services, background work): the saved UI language. */
    fun pick(context: Context, en: String, hu: String): String =
        when (val code = WowUiLanguage.code(WowUiLanguage.saved(context))) { "hu" -> hu; else -> translate(code, en) }
}
