package app.gamenative.ui.component.dialog

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.gamenative.ui.component.NoExtractOutlinedTextField
import app.gamenative.ui.theme.PluviaBackground
import app.gamenative.ui.theme.PluviaBorder
import app.gamenative.ui.theme.settingsTileColorsAlt
import com.alorma.compose.settings.ui.SettingsSwitch
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

@Composable
fun GestureRow(
    title: String,
    subtitle: String,
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    expandedContent: (@Composable ColumnScope.() -> Unit)? = null,
) {
    GestureBlock {
        SettingsSwitch(
            colors = settingsTileColorsAlt(),
            title = { Text(title) },
            subtitle = {
                Text(
                    text = subtitle,
                    color = if (!enabled) {
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                    } else {
                        Color.Unspecified
                    },
                )
            },
            state = enabled,
            onCheckedChange = onEnabledChange,
        )
        if (enabled && expandedContent != null) {
            GestureSubSettings { expandedContent() }
        }
    }
}

@Composable
fun GestureBlock(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 6.dp),
        shape = RoundedCornerShape(12.dp),
        color = PluviaBackground,
        border = androidx.compose.foundation.BorderStroke(1.dp, PluviaBorder.copy(alpha = 0.55f)),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 2.dp),
        ) {
            content()
        }
    }
}

@Composable
fun GestureSubSettings(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .offset(y = (-6).dp)
            .padding(start = 12.dp, end = 10.dp, bottom = 4.dp),
    ) {
        content()
    }
}

@Composable
fun DelayTextField(
    label: String,
    value: Int,
    valueRange: IntRange = 0..10000,
    onValueChange: (Int) -> Unit,
) {
    val clampedValue = value.coerceIn(valueRange)
    LaunchedEffect(clampedValue, value) {
        if (value != clampedValue) onValueChange(clampedValue)
    }
    var text by remember { mutableStateOf(clampedValue.toString()) }
    var isFocused by remember { mutableStateOf(false) }
    var lastSubmittedValue by remember { mutableStateOf(clampedValue) }

    fun normalizedDraftValue(draft: String): Int? {
        if (draft.isEmpty()) return null
        return draft.toLongOrNull()
            ?.coerceIn(valueRange.first.toLong(), valueRange.last.toLong())
            ?.toInt()
            ?: valueRange.last
    }

    LaunchedEffect(clampedValue, isFocused) {
        if (!isFocused || clampedValue != lastSubmittedValue) {
            text = clampedValue.toString()
            lastSubmittedValue = clampedValue
        }
    }

    NoExtractOutlinedTextField(
        value = text,
        onValueChange = { newText ->
            val filtered = newText.filter { it.isDigit() }
            text = filtered
            normalizedDraftValue(filtered)?.let { nextValue ->
                lastSubmittedValue = nextValue
                if (nextValue != value) onValueChange(nextValue)
            }
        },
        label = { Text(label) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        singleLine = true,
        modifier = Modifier
            .fillMaxWidth()
            .onFocusChanged { focusState ->
                val wasFocused = isFocused
                isFocused = focusState.isFocused
                if (wasFocused && !focusState.isFocused) {
                    val nextValue = normalizedDraftValue(text) ?: clampedValue
                    text = nextValue.toString()
                    lastSubmittedValue = nextValue
                    if (nextValue != value) onValueChange(nextValue)
                }
            }
            .padding(horizontal = 12.dp, vertical = 0.dp),
    )
}

data class SettingsActionCategory(
    val header: String,
    val actions: List<Pair<String, String>>,
)
