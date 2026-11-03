package app.gamenative.utils

import kotlin.math.roundToInt

object DateTimeUtils {

    fun formatRuntimeHours(hours: Double): String {
        val totalMinutes = (hours * 60.0).roundToInt().coerceAtLeast(1)
        val wholeHours = totalMinutes / 60
        val minutes = totalMinutes % 60
        return when {
            wholeHours > 0 && minutes > 0 -> "${wholeHours}h ${minutes}m"
            wholeHours > 0 -> "${wholeHours}h"
            else -> "${minutes}m"
        }
    }
}
