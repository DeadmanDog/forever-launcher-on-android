package app.gamenative.ui.components

import app.gamenative.ui.theme.WowTheme

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.gamenative.PluviaApp
import app.gamenative.R
import app.gamenative.events.AndroidEvent
import androidx.compose.runtime.getValue

@Composable
fun BootingSplash(
    visible: Boolean = true,
    text: String = "Loading...",
    progress: Float = -1f,
    onAbort: (() -> Unit)? = null,
) {
    if (visible && onAbort != null) {
        DisposableEffect(visible) {
            var start = false
            var select = false
            var l2 = false
            var r2 = false
            var aborted = false

            fun check(): Boolean = (!aborted && start && select && l2 && r2).also {
                if (it) {
                    aborted = true
                    onAbort()
                }
            }

            val keyHandler: (AndroidEvent.KeyEvent) -> Boolean = keyHandler@ { event ->
                val down = event.event.action == android.view.KeyEvent.ACTION_DOWN
                when (event.event.keyCode) {
                    android.view.KeyEvent.KEYCODE_BUTTON_START -> start = down
                    android.view.KeyEvent.KEYCODE_BUTTON_SELECT, android.view.KeyEvent.KEYCODE_BACK -> select = down
                    android.view.KeyEvent.KEYCODE_BUTTON_L2 -> l2 = down
                    android.view.KeyEvent.KEYCODE_BUTTON_R2 -> r2 = down
                    else -> return@keyHandler false
                }
                check() || (down && (start || select || l2 || r2))
            }

            val motionHandler: (AndroidEvent.MotionEvent) -> Boolean = { event ->
                event.event?.let { me ->
                    l2 = maxOf(me.getAxisValue(android.view.MotionEvent.AXIS_LTRIGGER), me.getAxisValue(android.view.MotionEvent.AXIS_BRAKE)) >= 0.3f
                    r2 = maxOf(me.getAxisValue(android.view.MotionEvent.AXIS_RTRIGGER), me.getAxisValue(android.view.MotionEvent.AXIS_GAS)) >= 0.3f
                    check()
                } ?: false
            }

            PluviaApp.events.on<AndroidEvent.KeyEvent, Boolean>(keyHandler)
            PluviaApp.events.on<AndroidEvent.MotionEvent, Boolean>(motionHandler)
            onDispose {
                PluviaApp.events.off<AndroidEvent.KeyEvent, Boolean>(keyHandler)
                PluviaApp.events.off<AndroidEvent.MotionEvent, Boolean>(motionHandler)
            }
        }
    }

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(animationSpec = tween(durationMillis = 400)),
        exit = fadeOut(animationSpec = tween(durationMillis = 300)),
    ) {
        val infiniteTransition = rememberInfiniteTransition(label = "bootSplash")
        val shimmerPosition by infiniteTransition.animateFloat(
            initialValue = -0.3f,
            targetValue = 1.3f,
            animationSpec = infiniteRepeatable(
                animation = tween(1500, easing = LinearEasing),
                repeatMode = RepeatMode.Restart,
            ),
            label = "shimmer",
        )

        Box(
            modifier = Modifier
                .fillMaxSize()
                // Review 2026-11-13: the splash covers the game (or the launcher) underneath, which must not get the touches made on
                // it. Its own back button still works: a child sees each touch before this parent consumes it.
                .pointerInput(Unit) {
                    awaitPointerEventScope { while (true) awaitPointerEvent().changes.forEach { it.consume() } }
                }
                .testTag("bootingSplashTouchShield")
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            WowTheme.palette.bgTop,
                            WowTheme.palette.bgMid,
                            WowTheme.palette.bgBottom,
                        ),
                    ),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(horizontal = 32.dp),
            ) {
                Spacer(modifier = Modifier.weight(0.4f))

                // Approved 04-2 emblem with F1 glow; background follows the app theme, progress stays gold.
                val narrow = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp < 500
                val logoHeight = if (narrow) 100.dp else 128.dp
                androidx.compose.foundation.Image(
                    bitmap = ImageBitmap.imageResource(R.drawable.forever_emblem_042),
                    filterQuality = FilterQuality.High,
                    contentDescription = null,
                    modifier = Modifier
                        .height(logoHeight)
                        // The halo is drawn behind the logo and past its edges; it takes no layout space.
                        .drawBehind {
                            val radius = size.height * .75f
                            drawCircle(
                                Brush.radialGradient(
                                    0f to Color(0xFFF6DDB0).copy(alpha = 0.03f),
                                    0.45f to Color(0xFFF6DDB0).copy(alpha = 0.01125f),
                                    1f to Color.Transparent,
                                    center = center, radius = radius,
                                ),
                                radius = radius, center = center,
                            )
                        },
                )

                Spacer(modifier = Modifier.height(34.dp))

                ProgressBar(
                    progress = progress,
                    shimmerPosition = shimmerPosition,
                    modifier = Modifier
                        .width(if (narrow) 220.dp else 260.dp)
                        .height(3.dp)
                        .testTag("splashProgress"),
                )

                Spacer(modifier = Modifier.height(10.dp))

                Text(
                    text = text,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontWeight = FontWeight.Medium,
                        fontSize = 11.sp,
                        letterSpacing = 0.5.sp,
                    ),
                    color = WowTheme.palette.muted,
                    textAlign = TextAlign.Center,
                )

                Spacer(modifier = Modifier.weight(0.4f))

                // How to reach the Quick Menu once the game runs: Android's Back (gesture or button) opens it (XServerScreen.gameBack).
                Text(
                    text = app.gamenative.ui.screen.wow.tx(
                        "Tip: in the game, swipe back (or press Back) to open the Quick Menu.",
                        "Tipp: játék közben a vissza-mozdulattal (vagy a Vissza gombbal) nyílik a gyorsmenü.",
                    ),
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp, letterSpacing = 0.3.sp),
                    color = WowTheme.palette.muted.copy(alpha = 0.8f),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 24.dp).padding(bottom = 20.dp),
                )
            }

            if (onAbort != null) {
                IconButton(
                    onClick = onAbort,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(12.dp),
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.back),
                        tint = WowTheme.palette.accent,
                    )
                }
            }
        }
    }
}

@Composable
private fun ProgressBar(
    progress: Float,
    shimmerPosition: Float,
    modifier: Modifier = Modifier,
) {
    val isIndeterminate = progress < 0f
    val target = if (isIndeterminate) 1f else progress.coerceIn(0f, 1f)
    // Ease between the reported values so the bar never jumps.
    val actualProgress by animateFloatAsState(targetValue = target, animationSpec = tween(450), label = "launchProgress")

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(3.dp))
            .background(WowTheme.Blue.divider),
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth(actualProgress)
                .clip(RoundedCornerShape(3.dp))
                .background(
                    Brush.horizontalGradient(
                        colors = listOf(WowTheme.Blue.accentFill, WowTheme.Blue.accent, WowTheme.Blue.title),
                    ),
                ),
        )

        if (isIndeterminate || progress > 0f) {
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(3.dp)),
            ) {
                val shimmerWidth = size.width * 0.3f
                val shimmerStart = (shimmerPosition * size.width) - shimmerWidth
                val shimmerEnd = shimmerStart + shimmerWidth

                drawRect(
                    brush = Brush.horizontalGradient(
                        colors = listOf(
                            Color.Transparent,
                            Color.White.copy(alpha = 0.30f),
                            Color.Transparent,
                        ),
                        startX = shimmerStart,
                        endX = shimmerEnd,
                    ),
                )
            }
        }
    }
}
