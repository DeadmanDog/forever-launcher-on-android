package app.gamenative.ui.util

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Composable
fun rememberScreenWidthDp(): Int {
    val configuration = LocalConfiguration.current
    return configuration.screenWidthDp
}

@Composable
fun adaptivePanelWidth(preferredWidth: Dp, maxWidthPercent: Float = 0.85f): Dp {
    val screenWidthDp = rememberScreenWidthDp()
    val maxWidth = (screenWidthDp * maxWidthPercent).dp
    return minOf(preferredWidth, maxWidth)
}
