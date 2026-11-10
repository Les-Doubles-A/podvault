package com.podvault.app.ui.bookmarks

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.podvault.app.PodVaultApplication
import com.podvault.app.data.database.entities.BookmarkEntity
import com.podvault.app.data.database.entities.EpisodeEntity
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

data class BookmarkListItem(val bookmark: BookmarkEntity, val episode: EpisodeEntity?, val podcastTitle: String)

class BookmarksViewModel(app: PodVaultApplication) : ViewModel() {
    private val bookmarksRepo = app.bookmarkRepository
    private val library = app.libraryRepository

    val items = bookmarksRepo.allBookmarks().map { bookmarks ->
        bookmarks.map { bookmark ->
            val episode = library.getEpisode(bookmark.episodeId)
            val podcastTitle = episode?.let { library.getPodcast(it.podcastId)?.displayTitle } ?: ""
            BookmarkListItem(bookmark, episode, podcastTitle)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
}
