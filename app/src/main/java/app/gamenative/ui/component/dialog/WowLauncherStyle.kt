package app.gamenative.ui.component.dialog

import app.gamenative.ui.theme.WowTheme

import app.gamenative.ui.screen.wow.tx
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.ui.draw.scale
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** The launcher's colours (WoWForeverScreen / BootingSplash): dark navy, bronze-gold accents, cream titles. */
internal object WowUi {
    val BgTop: Color get() = WowTheme.palette.bgTop
    val BgMid: Color get() = WowTheme.palette.bgMid
    val BgBottom: Color get() = WowTheme.palette.bgBottom
    val Card: Color get() = WowTheme.palette.card
    val CardBorder: Color get() = WowTheme.palette.cardBorder
    val Divider: Color get() = WowTheme.palette.divider
    val Gold: Color get() = WowTheme.palette.accent
    val GoldFill: Color get() = WowTheme.palette.accentFill
    val Cream: Color get() = WowTheme.palette.title
    val Text: Color get() = WowTheme.palette.text
    val Muted: Color get() = WowTheme.palette.muted
    val Error = Color(0xFFFC8181)
    val background: Brush get() = Brush.verticalGradient(listOf(BgTop, BgMid, BgBottom))
}

private val wowColors: androidx.compose.material3.ColorScheme get() = darkColorScheme(
    primary = WowUi.Gold,
    onPrimary = WowTheme.palette.onAccent,
    primaryContainer = WowUi.GoldFill,
    onPrimaryContainer = Color.White,
    secondary = WowUi.Gold,
    onSecondary = WowTheme.palette.onAccent,
    secondaryContainer = WowTheme.palette.elevated,
    onSecondaryContainer = WowUi.Text,
    tertiary = WowUi.GoldFill,
    background = WowUi.BgTop,
    onBackground = WowUi.Text,
    surface = WowUi.Card,
    onSurface = WowUi.Text,
    surfaceVariant = WowTheme.palette.elevated,
    onSurfaceVariant = WowUi.Muted,
    surfaceContainerHighest = WowTheme.palette.elevated,
    surfaceContainerHigh = WowUi.Card,
    surfaceContainer = WowUi.Card,
    surfaceContainerLow = WowUi.Card,
    surfaceContainerLowest = WowUi.BgTop,
    outline = WowUi.CardBorder,
    outlineVariant = WowUi.Divider,
    error = WowUi.Error,
    onError = Color(0xFF1B0A0A),
)

/** Slightly deeper tiles for the in-game menu so it matches the launcher's dark cards, not a lighter blue-grey. */
internal val wowOverlayColors: androidx.compose.material3.ColorScheme get() = wowColors.copy(
    surface = WowTheme.palette.overlaySurface,
    surfaceVariant = WowUi.Card,
    secondaryContainer = WowUi.Card,
    surfaceContainerHighest = WowUi.Card,
)

/**
 * The settings screens are written against MaterialTheme, so re-theming here gives every section
 * (switches, radios, chips, buttons, dialogs opened from it) the launcher's look without touching them.
 */
@Composable
internal fun WowLauncherTheme(colors: androidx.compose.material3.ColorScheme? = null, content: @Composable () -> Unit) {
    val colors = colors ?: wowColors
    val base = MaterialTheme.typography
    val typography = Typography(
        displayLarge = base.displayLarge, displayMedium = base.displayMedium, displaySmall = base.displaySmall,
        headlineLarge = base.headlineLarge, headlineMedium = base.headlineMedium, headlineSmall = base.headlineSmall,
        // Section headings: small, bold, spaced, gold (like the launcher's "KÖRNYEZET ÁLLAPOTA").
        titleLarge = base.titleLarge.copy(fontFamily = FontFamily.Serif, fontWeight = FontWeight.Black, color = WowUi.Cream),
        titleMedium = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp, color = WowUi.Gold),
        titleSmall = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Bold, color = WowUi.Cream),
        bodyLarge = base.bodyLarge, bodyMedium = base.bodyMedium,
        bodySmall = base.bodySmall.copy(color = WowTheme.palette.bodySmall),
        labelLarge = base.labelLarge.copy(fontWeight = FontWeight.Bold), labelMedium = base.labelMedium,
        labelSmall = base.labelSmall.copy(color = WowUi.Muted),
    )
    MaterialTheme(colorScheme = colors, typography = typography, shapes = MaterialTheme.shapes) {
        // Plain Text (radio labels, loose notes) must default to the light text colour, not black.
        CompositionLocalProvider(LocalContentColor provides WowUi.Text) { content() }
    }
}

/** A section card exactly like the launcher's status card: navy fill, thin blue-grey border, 16 dp corners. */
@Composable
internal fun WowCard(
    modifier: Modifier = Modifier,
    border: Color = WowUi.CardBorder,
    container: Color = WowUi.Card.copy(alpha = 0.85f),
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, border),
        colors = CardDefaults.cardColors(containerColor = container, contentColor = WowUi.Text),
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
    }
}

/** Pill-shaped tab in the launcher's button style. */
@Composable
internal fun WowTab(label: String, selected: Boolean, enabled: Boolean, fill: Boolean, center: Boolean = false,
                    fontSize: androidx.compose.ui.unit.TextUnit? = null, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(14.dp),
        color = if (selected) WowUi.GoldFill else Color.Transparent,
        border = BorderStroke(1.dp, if (selected) WowUi.Gold else WowUi.CardBorder),
        // Height follows the one-line label, so tabs in a row are even without filling the height (which stretched them down the
        // whole Settings page when the row lost its intrinsic height).
        modifier = if (center || fill) Modifier.fillMaxWidth() else Modifier,
    ) {
        // A centred tab sits in an equal-width row (WowTabRow), which picks a font size at which every label fits on one line.
        Box(contentAlignment = Alignment.Center) {
            Text(
                label,
                modifier = if (center) Modifier.fillMaxWidth().padding(horizontal = 2.dp, vertical = 8.dp) else Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                textAlign = if (center) androidx.compose.ui.text.style.TextAlign.Center else null,
                maxLines = 1,
                softWrap = false,
                color = if (selected) Color.White else WowUi.Muted,
                fontWeight = FontWeight.Bold,
                fontSize = fontSize ?: if (center) 12.sp else 13.sp,
            )
        }
    }
}

/**
 * Equal-width tabs across the width. They share one font size: [maxSize], or less when a label would not fit on one line, so a long
 * word ("Diagnosztika", "Komponensek") is never broken in the middle and the row stays even.
 */
@Composable
internal fun WowTabRow(labels: List<String>, selected: Int, enabled: Boolean, maxSize: androidx.compose.ui.unit.TextUnit = 12.sp,
                       onSelect: (Int) -> Unit) {
    val measurer = androidx.compose.ui.text.rememberTextMeasurer()
    val style = androidx.compose.material3.LocalTextStyle.current.copy(fontSize = maxSize, fontWeight = FontWeight.Bold)
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth()) {
        val gap = 6.dp
        // per tab: 2 dp text padding on each side, the 1 dp border, and a little air
        val room = with(androidx.compose.ui.platform.LocalDensity.current) { ((maxWidth - gap * (labels.size - 1)) / labels.size - 10.dp).toPx() }
        val widest = labels.maxOf { measurer.measure(it, style, maxLines = 1, softWrap = false).size.width }.toFloat()
        val size = if (widest <= room) maxSize else (maxSize.value * room / widest).sp
        androidx.compose.foundation.layout.Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(gap)) {
            labels.forEachIndexed { index, label ->
                Box(Modifier.weight(1f)) {
                    WowTab(label, selected = index == selected, enabled = enabled, fill = true, center = true, fontSize = size, onClick = { onSelect(index) })
                }
            }
        }
    }
}

/** A small gold "i": the long explanation of an item opens in a dialog instead of filling the list. */
@Composable
internal fun WowInfoButton(title: String, text: String) {
    var open by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    androidx.compose.material3.IconButton(onClick = { open = true }, modifier = Modifier.size(32.dp)) {
        androidx.compose.material3.Icon(
            Icons.Outlined.Info, contentDescription = title,
            tint = WowUi.Gold, modifier = Modifier.size(20.dp),
        )
    }
    if (open) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { open = false },
            title = { Text(title, style = MaterialTheme.typography.titleSmall) },
            text = { Text(text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState())) },
            confirmButton = { androidx.compose.material3.TextButton(onClick = { open = false }) { Text(tx("Close", "Bezárás"), color = WowUi.Gold) } },
        )
    }
}

/** The settings' on/off switch: the Material switch drawn at 80 % (it looked oversized next to the small setting texts). */
@Composable
internal fun WowSwitch(checked: Boolean, onCheckedChange: ((Boolean) -> Unit)?, enabled: Boolean = true) {
    Box(Modifier.size(width = 42.dp, height = 26.dp), contentAlignment = Alignment.Center) {
        androidx.compose.material3.Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled,
            modifier = Modifier.wrapContentSize(unbounded = true).scale(0.8f))
    }
}

/**
 * Delete chooser for a component list that switches on selection: lists only the entries not in use (the current one cannot be
 * deleted), so a component can be deleted without first switching to it.
 */
@Composable
internal fun WowDeleteChooser(title: String, items: List<Pair<String, String>>, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    androidx.compose.material3.AlertDialog(onDismissRequest = onDismiss, title = { Text(title) },
        text = { Column { items.forEach { (id, label) -> androidx.compose.material3.TextButton(onClick = { onPick(id) }) { Text(label) } } } },
        confirmButton = {}, dismissButton = { androidx.compose.material3.TextButton(onClick = onDismiss) { Text(tx("Cancel", "Mégse")) } })
}

/** Import / Delete side by side under a component list (wraps on a narrow screen), instead of one tall button per line. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
internal fun WowActionRow(content: @Composable () -> Unit) {
    androidx.compose.foundation.layout.FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) { content() }
}
