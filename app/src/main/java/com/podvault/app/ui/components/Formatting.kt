package com.podvault.app.ui.components

import java.util.concurrent.TimeUnit

/** "42:37" / "1:12:43" style duration formatting used throughout the player UI. */
fun formatDuration(ms: Long): String {
    if (ms <= 0) return "0:00"
    val totalSeconds = ms / 1000
    val hours = TimeUnit.SECONDS.toHours(totalSeconds)
    val minutes = TimeUnit.SECONDS.toMinutes(totalSeconds) % 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        String.format("%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format("%d:%02d", minutes, seconds)
    }
}

/** "12 min restantes" / "1 h 05 min restantes" / "Presque terminé". */
fun formatRemaining(ms: Long): String {
    val totalMinutes = TimeUnit.MILLISECONDS.toMinutes(ms.coerceAtLeast(0L))
    return when {
        totalMinutes < 1 -> "Presque terminé"
        totalMinutes < 60 -> "$totalMinutes min restantes"
        else -> String.format("%d h %02d min restantes", totalMinutes / 60, totalMinutes % 60)
    }
}

/** "1 h 12" / "42 min" pour des durées cumulées (file d'attente, statistiques). */
fun formatTotal(ms: Long): String {
    val totalMinutes = TimeUnit.MILLISECONDS.toMinutes(ms.coerceAtLeast(0L))
    return if (totalMinutes < 60) "$totalMinutes min" else String.format("%d h %02d", totalMinutes / 60, totalMinutes % 60)
}
