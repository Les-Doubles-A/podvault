package com.podvault.app.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.podvault.app.PodVaultApplication
import com.podvault.app.data.database.entities.BookmarkEntity
import com.podvault.app.data.database.entities.EpisodeEntity
import com.podvault.app.data.database.entities.PodcastEntity
import com.podvault.app.data.repository.QueueMode
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class SearchResults(
    val podcasts: List<PodcastEntity> = emptyList(),
    val episodes: List<EpisodeEntity> = emptyList(),
    val bookmarks: List<BookmarkEntity> = emptyList()
)

class SearchViewModel(app: PodVaultApplication) : ViewModel() {
    private val library = app.libraryRepository
    private val bookmarksRepo = app.bookmarkRepository
    private val playback = app.playbackController

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    fun setQuery(q: String) {
        _query.value = q
    }

    // Section 60: search covers podcasts, episodes, authors/descriptions, and bookmarks/notes.
    @OptIn(FlowPreview::class)
    val results: StateFlow<SearchResults> = _query.debounce(250).distinctUntilChanged().flatMapLatest { q ->
        if (q.isBlank()) {
            flowOf(SearchResults())
        } else {
            combine(
                library.searchPodcasts(q),
                library.searchEpisodes(q),
                bookmarksRepo.searchBookmarks(q)
            ) { podcasts, episodes, bookmarks ->
                SearchResults(podcasts, episodes, bookmarks)
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SearchResults())

    fun toggleFavorite(episode: EpisodeEntity) = viewModelScope.launch {
        library.toggleFavorite(episode.id, !episode.isFavorite)
    }

    fun addToQueue(episode: EpisodeEntity, mode: QueueMode) = viewModelScope.launch {
        playback.addToQueue(episode, library.getPodcast(episode.podcastId)?.displayTitle.orEmpty(), mode)
    }
}
