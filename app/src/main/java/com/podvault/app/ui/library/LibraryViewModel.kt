package com.podvault.app.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.podvault.app.PodVaultApplication
import com.podvault.app.data.database.entities.EpisodeEntity
import com.podvault.app.data.database.entities.PodcastEntity
import com.podvault.app.data.repository.LibraryRepository
import com.podvault.app.data.repository.QueueMode
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class PodcastListItem(val podcast: PodcastEntity, val episodeCount: Int, private val fallbackCover: String? = null) {
    /** Pochette du podcast, ou à défaut celle d'un de ses épisodes. */
    val coverUri: String? get() = podcast.displayCoverUri?.takeIf { it.isNotBlank() } ?: fallbackCover
}

class LibraryViewModel(app: PodVaultApplication) : ViewModel() {
    private val library: LibraryRepository = app.libraryRepository
    private val playback = app.playbackController
    private val contentResolver = app.contentResolver

    val podcasts = combine(library.podcasts(), library.episodeCountsByPodcast(), library.episodeCoversByPodcast()) { podcasts, counts, covers ->
        val countMap = counts.associate { it.podcastId to it.count }
        val coverMap = covers.associate { it.podcastId to it.coverUri }
        podcasts.map { PodcastListItem(it, countMap[it.id] ?: 0, coverMap[it.id]) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun episodesFor(podcastId: Long) = library.episodesOf(podcastId)
    fun podcastFor(podcastId: Long) = library.observePodcast(podcastId)
    fun libraryItemsFor(podcastId: Long) = library.libraryItemsOf(podcastId)
    val duplicateCandidates = library.duplicateCandidates()

    fun toggleFavorite(episode: EpisodeEntity) = viewModelScope.launch {
        library.toggleFavorite(episode.id, !episode.isFavorite)
    }

    fun addToQueue(episode: EpisodeEntity, mode: QueueMode) = viewModelScope.launch {
        val podcast = library.getPodcast(episode.podcastId)
        playback.addToQueue(episode, podcast?.displayTitle.orEmpty(), mode)
    }

    fun rescan() = viewModelScope.launch { library.rescanLibrary() }
    fun savePodcastMetadata(podcast: PodcastEntity, title: String?, author: String?, coverUri: String?) = viewModelScope.launch {
        library.savePodcastMetadata(podcast, title, author, coverUri)
    }

    fun persistCoverPermission(uri: android.net.Uri) {
        runCatching {
            contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    val scanState = library.scanState
}
