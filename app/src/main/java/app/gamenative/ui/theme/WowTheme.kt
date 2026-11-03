package app.gamenative.ui.theme

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color

/** One colour set for the whole app: launcher, settings, addon page, splash and the in-game Quick Menu. */
data class WowPalette(
    val bgTop: Color,
    val bgMid: Color,
    val bgBottom: Color,
    val card: Color,
    val cardBorder: Color,
    val divider: Color,
    val elevated: Color,
    /** Accent text, icons and outlines (the bronze-gold of the blue theme). */
    val accent: Color,
    /** Filled buttons and the selected tab. */
    val accentFill: Color,
    val onAccent: Color,
    val title: Color,
    val text: Color,
    /** Secondary text, a lighter one for small notes and one for item labels. */
    val muted: Color,
    val mutedLight: Color,
    val bodySmall: Color,
    val label: Color,
    /** The in-game menu's deeper tiles. */
    val overlaySurface: Color,
)

enum class WowThemeChoice { BLUE, RED }

/**
 * The chosen theme (Settings > Quick > Theme), observable by Compose: changing it recolours every screen at once.
 * Blue: navy with bronze-gold (the original look). Red: the Horde's crimson on a dark red-brown base.
 */
object WowTheme {
    val Blue = WowPalette(
        bgTop = Color(0xFF0A0E17), bgMid = Color(0xFF10192A), bgBottom = Color(0xFF080C14),
        card = Color(0xFF131D2E), cardBorder = Color(0xFF2A3C54), divider = Color(0xFF1E2D44), elevated = Color(0xFF1B2A42),
        accent = Color(0xFFC79C6E), accentFill = Color(0xFF9E7138), onAccent = Color(0xFF1B1307),
        title = Color(0xFFF0E6D2), text = Color(0xFFE2E8F0),
        muted = Color(0xFF7A92B0), mutedLight = Color(0xFF88A0C0), bodySmall = Color(0xFF9FB0C8), label = Color(0xFFCBD5E1),
        overlaySurface = Color(0xFF0E1523),
    )
    val Red = WowPalette(
        bgTop = Color(0xFF170A09), bgMid = Color(0xFF231110), bgBottom = Color(0xFF0E0606),
        card = Color(0xFF2A1513), cardBorder = Color(0xFF5C2B26), divider = Color(0xFF3D1E1B), elevated = Color(0xFF3A1C19),
        accent = Color(0xFFE0533F), accentFill = Color(0xFF9E1B1B), onAccent = Color(0xFF1F0605),
        title = Color(0xFFF0E6D2), text = Color(0xFFF2E6E3),
        muted = Color(0xFFB08A84), mutedLight = Color(0xFFC29A93), bodySmall = Color(0xFFCDB0AA), label = Color(0xFFE2CFCB),
        overlaySurface = Color(0xFF1C0D0C),
    )

    var choice by mutableStateOf(WowThemeChoice.BLUE)
    val palette: WowPalette get() = if (choice == WowThemeChoice.RED) Red else Blue

    private const val PREFS = "wow_forever"
    private const val KEY = "theme"

    fun load(context: Context) {
        choice = runCatching {
            WowThemeChoice.valueOf(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null) ?: "BLUE")
        }.getOrDefault(WowThemeChoice.BLUE)
    }

    fun save(context: Context, value: WowThemeChoice) {
        choice = value
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, value.name).apply()
    }
}
