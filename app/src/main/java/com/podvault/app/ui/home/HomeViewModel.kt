package com.podvault.app.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.podvault.app.PodVaultApplication
import com.podvault.app.data.database.dao.InProgressEpisode
import com.podvault.app.data.database.dao.PodcastAffinityRow
import com.podvault.app.data.database.entities.EpisodeEntity
import com.podvault.app.data.database.entities.PodcastEntity
import com.podvault.app.data.repository.LibraryRepository
import com.podvault.app.data.repository.PlaybackRules
import com.podvault.app.data.repository.QueueMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Épisode affiché sur l'accueil, avec le contexte nécessaire à son rendu. */
data class HomeEpisode(
    val episode: EpisodeEntity,
    val podcastTitle: String,
    val progress: Float? = null,
    val remainingMs: Long? = null
)

data class HomeUiState(
    val isLoading: Boolean = true,
    val isLibraryEmpty: Boolean = false,
    val continueListening: List<HomeEpisode> = emptyList(),
    val upNext: List<HomeEpisode> = emptyList(),
    val forYou: List<HomeEpisode> = emptyList(),
    val recentlyAdded: List<HomeEpisode> = emptyList(),
    val favorites: List<HomeEpisode> = emptyList()
)

private class Sources(
    val inProgress: List<InProgressEpisode>,
    val affinity: List<PodcastAffinityRow>,
    val candidates: List<EpisodeEntity>,
    val favorites: List<EpisodeEntity>,
    val podcasts: List<PodcastEntity>
)

@OptIn(FlowPreview::class)
class HomeViewModel(app: PodVaultApplication) : ViewModel() {
    private val library: LibraryRepository = app.libraryRepository
    private val playback = app.playbackController

    val uiState = combine(
        library.inProgressEpisodes(),
        library.podcastAffinity(),
        library.unplayedCandidates(),
        library.favorites(FAVORITES_LIMIT),
        library.podcasts()
    ) { inProgress, affinity, candidates, favorites, podcasts ->
        Sources(inProgress, affinity, candidates, favorites, podcasts)
    }
        // La position est sauvegardée toutes les 5 s pendant l'écoute : on regroupe les rafales.
        .debounce(300)
        .combine(library.totalEpisodeCount()) { sources, total -> sources to total }
        .map { (sources, total) -> build(sources, total) }
        .distinctUntilChanged()
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState())

    private suspend fun build(sources: Sources, totalCount: Int): HomeUiState {
        if (totalCount == 0) return HomeUiState(isLoading = false, isLibraryEmpty = true)

        val now = System.currentTimeMillis()
        val podcastTitles = sources.podcasts.associate { it.id to it.displayTitle }
        fun wrap(episode: EpisodeEntity, progress: Float? = null, remaining: Long? = null) =
            HomeEpisode(episode, podcastTitles[episode.podcastId].orEmpty(), progress, remaining)

        // 1. Reprendre : épisodes entamés, hors ceux arrêtés à la fin ou juste avant.
        val continueListening = sources.inProgress
            .filterNot { PlaybackRules.isCompleted(it.positionMs, it.episode.durationMs) }
            .take(CONTINUE_LIMIT)
            .map { item ->
                val duration = item.episode.durationMs
                wrap(
                    item.episode,
                    progress = if (duration > 0) (item.positionMs / duration.toFloat()).coerceIn(0f, 1f) else null,
                    remaining = if (duration > 0) (duration - item.positionMs).coerceAtLeast(0L) else null
                )
            }
        val shown = HashSet<Long>().apply { continueListening.forEach { add(it.episode.id) } }

        // 2. À suivre : l'épisode suivant dans la série des podcasts écoutés récemment.
        val signals = sources.affinity.associate {
            it.podcastId to PodcastSignal(it.podcastId, it.listenedMs, it.completedCount, it.lastPlayedAt, it.favoriteCount)
        }
        val upNext = ArrayList<HomeEpisode>()
        signals.values
            .filter { it.lastPlayedAt > 0L }
            .sortedByDescending { it.lastPlayedAt }
            .take(UP_NEXT_PODCASTS_SCANNED)
            .forEach { signal ->
                val last = library.lastPlayedEpisode(signal.podcastId) ?: return@forEach
                val next = HomeRecommender.nextInSeries(last, library.unplayedForPodcast(signal.podcastId))
                if (next != null && shown.add(next.id)) upNext += wrap(next)
            }

        // 3. Pour vous : recommandations pondérées par l'historique d'écoute.
        val forYou = HomeRecommender.recommend(sources.candidates, signals, excludeIds = shown, now = now, limit = FOR_YOU_LIMIT)
        forYou.forEach { shown.add(it.id) }

        // 4. Récemment ajoutés : ce qui reste dans le réservoir, du plus récent au plus ancien.
        val recent = sources.candidates.asSequence()
            .filter { it.id !in shown }
            .take(RECENT_LIMIT)
            .map { wrap(it) }
            .toList()

        return HomeUiState(
            isLoading = false,
            continueListening = continueListening,
            upNext = upNext,
            forYou = forYou.map { wrap(it) },
            recentlyAdded = recent,
            favorites = sources.favorites.take(FAVORITES_SHOWN).map { wrap(it) }
        )
    }

    fun toggleFavorite(episode: EpisodeEntity) = viewModelScope.launch {
        library.toggleFavorite(episode.id, !episode.isFavorite)
    }

    fun addToQueue(episode: EpisodeEntity, mode: QueueMode) = viewModelScope.launch {
        playback.addToQueue(episode, library.getPodcast(episode.podcastId)?.displayTitle.orEmpty(), mode)
    }

    private companion object {
        const val CONTINUE_LIMIT = 10
        const val UP_NEXT_PODCASTS_SCANNED = 6
        const val FOR_YOU_LIMIT = 10
        const val RECENT_LIMIT = 10
        const val FAVORITES_LIMIT = 12
        const val FAVORITES_SHOWN = 5
    }
}
