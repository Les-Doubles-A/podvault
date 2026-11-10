package com.podvault.app.data.repository

import com.podvault.app.data.database.entities.PlaybackStateEntity

/**
 * Règles de lecture pures (sans dépendance Android) partagées entre le service,
 * le contrôleur, l'écran d'accueil et les tests unitaires.
 */
object PlaybackRules {

    /** Clé du Bundle d'extras Media3 portant l'identifiant du podcast. */
    const val EXTRA_PODCAST_ID = "podvault.podcastId"

    /** Position en dessous de laquelle on ne reprend pas : l'épisode redémarre au début. */
    private const val MIN_RESUME_MS = 5_000L

    /** Retour en arrière appliqué à la reprise d'une position sauvegardée (session précédente). */
    private const val COLD_RESUME_REWIND_MS = 3_000L

    /**
     * Marge de fin : un épisode arrêté à moins de cette durée de la fin est considéré comme
     * terminé (génériques, outros). 3 % de la durée, bornée entre 5 et 30 secondes.
     */
    fun completionThresholdMs(durationMs: Long): Long =
        (durationMs * 0.03).toLong().coerceIn(5_000L, 30_000L)

    fun isCompleted(positionMs: Long, durationMs: Long): Boolean =
        durationMs > 0 && positionMs >= durationMs - completionThresholdMs(durationMs)

    /**
     * Retour en arrière après une pause : plus la pause est longue, plus on recule pour
     * retrouver le fil. Aucune correction pour une pause de quelques secondes.
     */
    fun smartRewindMs(pausedForMs: Long): Long = when {
        pausedForMs < 10_000L -> 0L
        pausedForMs < 60_000L -> 2_000L
        pausedForMs < 5 * 60_000L -> 5_000L
        pausedForMs < 30 * 60_000L -> 10_000L
        else -> 15_000L
    }

    /** Position de reprise d'un épisode à partir de son état sauvegardé. */
    fun resumePosition(saved: PlaybackStateEntity?, durationMs: Long, smartRewind: Boolean): Long {
        if (saved == null || saved.completed) return 0L
        if (saved.positionMs < MIN_RESUME_MS || isCompleted(saved.positionMs, durationMs)) return 0L
        val rewind = if (smartRewind) COLD_RESUME_REWIND_MS else 0L
        return (saved.positionMs - rewind).coerceAtLeast(0L)
    }
}
