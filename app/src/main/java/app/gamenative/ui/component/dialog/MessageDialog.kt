package app.gamenative.ui.component.dialog

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.fromHtml
import androidx.compose.ui.text.style.TextDecoration

@Composable
fun MessageDialog(
    visible: Boolean,
    onDismissRequest: (() -> Unit)? = null,
    onConfirmClick: (() -> Unit)? = null,
    onDismissClick: (() -> Unit)? = null,
    onActionClick: (() -> Unit)? = null,
    confirmBtnText: String = "Confirm",
    dismissBtnText: String = "Dismiss",
    actionBtnText: String? = null,
    icon: ImageVector? = null,
    title: String? = null,
    message: String? = null,
    useHtmlInMsg: Boolean = false,
) {
    when {
        visible -> {
            AlertDialog(
                icon = icon?.let { { Icon(imageVector = icon, contentDescription = null) } },
                title = title?.let { { Text(it) } },
                text = message?.let {
                    {
                        Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                            if (useHtmlInMsg) {
                                Text(
                                    text = AnnotatedString.fromHtml(
                                        htmlString = it,
                                        linkStyles = TextLinkStyles(
                                            style = SpanStyle(
                                                textDecoration = TextDecoration.Underline,
                                                fontStyle = FontStyle.Italic,
                                                color = Color.Blue,
                                            ),
                                        ),
                                    ),
                                )
                            } else {
                                Text(it)
                            }
                        }
                    }
                },
                onDismissRequest = { onDismissRequest?.invoke() },
                dismissButton = onDismissClick?.let {
                    {
                        TextButton(onClick = it) {
                            Text(dismissBtnText)
                        }
                    }
                },
                confirmButton = {
                    Row {
                        // Action button (displayed first if available)
                        if (actionBtnText != null && onActionClick != null) {
                            TextButton(onClick = onActionClick) {
                                Text(actionBtnText)
                            }
                        }

                        // Confirm button
                        onConfirmClick?.let {
                            TextButton(onClick = it) {
                                Text(confirmBtnText)
                            }
                        }
                    }
                },
            )
        }
    }
}
