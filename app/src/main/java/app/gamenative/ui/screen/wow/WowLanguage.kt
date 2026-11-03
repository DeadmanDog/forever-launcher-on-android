package app.gamenative.ui.screen.wow

import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.content.res.Resources
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import java.util.Locale

/**
 * In-app language for the launcher, settings, splash and Quick Menu: English, Hungarian and the WoW Forever game languages
 * (German, French, Spanish, Brazilian Portuguese, Russian, Korean, Simplified and Traditional Chinese). [label] is the
 * language's own name for the picker.
 */
enum class WowLanguage(val code: String, val label: String) {
    SYSTEM("system", ""), EN("en", "English"), HU("hu", "Magyar"), DE("de", "Deutsch"), FR("fr", "Français"), ES("es", "Español"),
    PT("pt-BR", "Português (BR)"), RU("ru", "Русский"), KO("ko", "한국어"), ZH_CN("zh-CN", "简体中文"), ZH_TW("zh-TW", "繁體中文"),
}

internal object WowUiLanguage {
    private const val PREFS = "wow_ui"
    private const val KEY = "language"

    fun saved(context: Context): WowLanguage {
        val code = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
        return WowLanguage.entries.firstOrNull { it.code == code } ?: WowLanguage.SYSTEM
    }

    /** The chosen language as snapshot state: the launcher, the game screen, the splash and the Quick Menu all follow a change. */
    var current by mutableStateOf(WowLanguage.SYSTEM)
        private set

    fun load(context: Context) { current = saved(context) }

    fun save(context: Context, language: WowLanguage) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, language.code).apply()
        current = language
    }

    fun isHungarian(language: WowLanguage, systemLanguage: String = Locale.getDefault().language): Boolean =
        code(language, Locale.forLanguageTag(systemLanguage)) == "hu"

    /** The effective language code: the chosen one, or for "System" the phone's language when the app has it (else English). */
    fun code(language: WowLanguage, system: Locale = Locale.getDefault()): String =
        if (language != WowLanguage.SYSTEM) language.code else systemCode(system)

    fun systemCode(locale: Locale): String = when (locale.language) {
        "hu", "de", "fr", "es", "ru", "ko" -> locale.language
        "pt" -> "pt-BR"
        "zh" -> if (locale.script == "Hant" || locale.country in setOf("TW", "HK", "MO")) "zh-TW" else "zh-CN"
        else -> "en"
    }
}

/** The effective UI language code (see [WowUiLanguage.code]); provided next to [LocalWowHungarian]. */
val LocalWowLanguage = compositionLocalOf { "en" }

/** True when the WoW UI should render Hungarian text. Provided at the app root (PluviaMain) from [WowUiLanguage.current]. */
val LocalWowHungarian = compositionLocalOf { false }

/** Picks the text for the current WoW UI language: Hungarian, English, or the English text's translation (WowI18n). */
@Composable
fun tx(en: String, hu: String): String =
    if (LocalWowHungarian.current) hu else LocalWowLanguage.current.let { if (it == "en") en else WowI18n.translate(it, en) }

/**
 * A text that may arrive in English from background work (the game downloader's progress) in the UI language, Hungarian
 * included (assets/i18n/hu.json); a text that is already translated, or unknown, is shown as it is.
 */
@Composable
fun localizedStatus(text: String): String = LocalWowLanguage.current.let { if (it == "en") text else WowI18n.translate(it, text) }

/** Translator for text built outside composition (coroutines, callbacks). */
@Composable
fun rememberTx(): (String, String) -> String {
    val hungarian = LocalWowHungarian.current
    val code = LocalWowLanguage.current
    return remember(hungarian, code) { { en: String, hu: String -> if (hungarian) hu else WowI18n.translate(code, en) } }
}

/**
 * Android string resources in the in-app language: the Quick Menu reads resources (values / values-hu), which would otherwise
 * follow the phone's language. With "System" the phone's language applies anyway, so nothing is replaced.
 */
@Composable
fun WowLocalizedResources(content: @Composable () -> Unit) {
    val language = WowUiLanguage.current
    if (language == WowLanguage.SYSTEM) { content(); return }
    val base = LocalContext.current
    val localized = remember(base, language) { localizedContext(base, language.code) }
    CompositionLocalProvider(LocalContext provides localized, LocalConfiguration provides localized.resources.configuration) { content() }
}

/** [base] with its resources in [languageTag]; everything else (the activity, services) still goes to [base]. */
fun localizedContext(base: Context, languageTag: String): Context {
    val config = Configuration(base.resources.configuration).apply { setLocale(Locale.forLanguageTag(languageTag)) }
    val resources = base.createConfigurationContext(config).resources
    return object : ContextWrapper(base) {
        override fun getResources(): Resources = resources
    }
}
