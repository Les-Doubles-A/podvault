package com.podvault.app.ui.home

import com.podvault.app.data.database.entities.EpisodeEntity
import kotlin.math.ln
import kotlin.math.pow

/** Signaux d'écoute agrégés pour un podcast. */
data class PodcastSignal(
    val podcastId: Long,
    val listenedMs: Long,
    val completedCount: Int,
    val lastPlayedAt: Long,
    val favoriteCount: Int
)

/**
 * Algorithme de recommandation de l'accueil, 100 % local et déterministe (sans réseau).
 *
 * Pour chaque épisode jamais commencé :
 *
 *   score = 0.50 * affinité(podcast) + 0.30 * fraîcheur(ajout) + 0.12 * favori + 0.08 * variation du jour
 *
 * - affinité : temps d'écoute (échelle logarithmique, pour qu'un gros podcast n'écrase pas tout),
 *   récence de la dernière écoute (demi-vie 14 jours), taux d'épisodes terminés, favoris.
 *   Un podcast jamais écouté reçoit une affinité d'exploration fixe pour rester découvrable.
 * - fraîcheur : décroissance exponentielle, demi-vie 10 jours.
 * - variation du jour : petit bruit stable sur 24 h pour renouveler la sélection chaque jour.
 *
 * La sélection est gloutonne avec pénalité de diversité (chaque épisode déjà retenu d'un même
 * podcast multiplie le score par 0.65) et un plafond par podcast.
 */
object HomeRecommender {

    private const val DAY_MS = 86_400_000.0
    private const val EXPLORATION_AFFINITY = 0.30
    private const val AFFINITY_HALF_LIFE_DAYS = 14.0
    private const val FRESHNESS_HALF_LIFE_DAYS = 10.0
    private const val DIVERSITY_PENALTY = 0.65

    fun affinity(signal: PodcastSignal?, maxListenedMs: Long, now: Long): Double {
        if (signal == null || (signal.listenedMs <= 0L && signal.lastPlayedAt <= 0L && signal.favoriteCount == 0)) {
            return EXPLORATION_AFFINITY
        }
        val listened = if (maxListenedMs > 0L) {
            ln(1.0 + signal.listenedMs / 60_000.0) / ln(1.0 + maxListenedMs / 60_000.0)
        } else 0.0
        val recency = if (signal.lastPlayedAt > 0L) halfLife(now - signal.lastPlayedAt, AFFINITY_HALF_LIFE_DAYS) else 0.0
        val completion = signal.completedCount / (signal.completedCount + 2.0)
        val favorite = (signal.favoriteCount / 3.0).coerceAtMost(1.0)
        return (0.40 * listened + 0.30 * recency + 0.15 * completion + 0.15 * favorite).coerceIn(0.0, 1.0)
    }

    fun score(episode: EpisodeEntity, affinity: Double, now: Long): Double {
        val freshness = halfLife(now - episode.addedAt, FRESHNESS_HALF_LIFE_DAYS)
        val favorite = if (episode.isFavorite) 1.0 else 0.0
        return 0.50 * affinity + 0.30 * freshness + 0.12 * favorite + 0.08 * dailyJitter(episode.id, now)
    }

    /** Sélection diversifiée d'au plus [limit] épisodes parmi [candidates]. */
    fun recommend(
        candidates: List<EpisodeEntity>,
        signals: Map<Long, PodcastSignal>,
        excludeIds: Set<Long>,
        now: Long,
        limit: Int = 10,
        maxPerPodcast: Int = 2
    ): List<EpisodeEntity> {
        val maxListened = signals.values.maxOfOrNull { it.listenedMs } ?: 0L
        val affinityCache = HashMap<Long, Double>()
        val scored = candidates
            .filter { it.id !in excludeIds && !it.isMissing }
            .map { episode ->
                val affinity = affinityCache.getOrPut(episode.podcastId) { affinity(signals[episode.podcastId], maxListened, now) }
                episode to score(episode, affinity, now)
            }
            .toMutableList()

        val picked = ArrayList<EpisodeEntity>(limit)
        val perPodcast = HashMap<Long, Int>()
        while (picked.size < limit && scored.isNotEmpty()) {
            var bestIndex = -1
            var bestValue = Double.NEGATIVE_INFINITY
            for (i in scored.indices) {
                val (episode, base) = scored[i]
                val count = perPodcast[episode.podcastId] ?: 0
                if (count >= maxPerPodcast) continue
                val value = base * DIVERSITY_PENALTY.pow(count)
                if (value > bestValue) {
                    bestValue = value
                    bestIndex = i
                }
            }
            if (bestIndex < 0) break
            val (chosen, _) = scored.removeAt(bestIndex)
            picked += chosen
            perPodcast[chosen.podcastId] = (perPodcast[chosen.podcastId] ?: 0) + 1
        }
        return picked
    }

    /**
     * Ordre "série" : saison puis numéro d'épisode si connus, sinon date de publication,
     * sinon nom de fichier (ordre naturel : "ep2" avant "ep10"), sinon identifiant.
     */
    fun seriesCompare(a: EpisodeEntity, b: EpisodeEntity): Int {
        val numberA = a.episodeNumber
        val numberB = b.episodeNumber
        if (numberA != null && numberB != null) {
            val season = (a.seasonNumber ?: 0).compareTo(b.seasonNumber ?: 0)
            if (season != 0) return season
            val number = numberA.compareTo(numberB)
            if (number != 0) return number
        }
        val dateA = a.publicationDate
        val dateB = b.publicationDate
        if (dateA != null && dateB != null) {
            val date = dateA.compareTo(dateB)
            if (date != 0) return date
        }
        val name = naturalKey(a.fileName).compareTo(naturalKey(b.fileName))
        if (name != 0) return name
        return a.id.compareTo(b.id)
    }

    /** Premier épisode non commencé qui suit [last] dans la série, s'il existe. */
    fun nextInSeries(last: EpisodeEntity, unplayed: List<EpisodeEntity>): EpisodeEntity? =
        unplayed
            .filter { it.id != last.id && seriesCompare(it, last) > 0 }
            .minWithOrNull(::seriesCompare)

    private fun naturalKey(name: String): String =
        DIGITS.replace(name.lowercase()) { it.value.padStart(9, '0') }

    private val DIGITS = Regex("\\d+")

    private fun halfLife(ageMs: Long, halfLifeDays: Double): Double =
        0.5.pow(ageMs.coerceAtLeast(0L) / DAY_MS / halfLifeDays)

    /** Bruit pseudo-aléatoire dans [0, 1), stable sur 24 h pour un épisode donné. */
    private fun dailyJitter(episodeId: Long, now: Long): Double {
        val day = now / 86_400_000L
        var h = episodeId * -7046029254386353131L + day * 1442695040888963407L
        h = h xor (h ushr 33)
        h *= -49064778989728563L
        h = h xor (h ushr 29)
        return (h and 0xFFFF).toDouble() / 65536.0
    }
}
