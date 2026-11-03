package app.gamenative.ui.component.dialog

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.zIndex
import app.gamenative.ui.components.BootingSplash
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Progress overlay in the launcher's own style (same look as the boot splash) instead of a system-style popup.
 * @param progress A value between 0 and 1 (inclusive); below 0 the bar is indeterminate.
 */
@Composable
fun LoadingDialog(
    visible: Boolean,
    onDismissRequest: () -> Unit = {},
    progress: Float,
    message: String = "Loading...",
) {
    Box(Modifier.zIndex(20f)) {
        BootingSplash(
            visible = visible,
            text = if (progress >= 0) message + "  " + min(100, (progress * 100.0).roundToInt()) + "%" else message,
            progress = progress,
        )
    }
}
