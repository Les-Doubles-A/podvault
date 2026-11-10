package com.podvault.app.ui.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.podvault.app.PodVaultApplication
import com.podvault.app.data.database.entities.BookmarkEntity
import com.podvault.app.data.database.entities.EpisodeEntity
import com.podvault.app.data.repository.BookmarkRepository
import com.podvault.app.data.repository.LibraryRepository
import com.podvault.app.data.repository.PlaybackController
import com.podvault.app.data.repository.PlaybackRules
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

private fun formatDurationShort(ms: Long): String {
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return String.format("%d:%02d", minutes, seconds)
}

@OptIn(ExperimentalCoroutinesApi::class)
class PlayerViewModel(app: PodVaultApplication) : ViewModel() {
    private val library: LibraryRepository = app.libraryRepository
    private val bookmarks: BookmarkRepository = app.bookmarkRepository
    private val preferences = app.preferencesManager
    val playback: PlaybackController = app.playbackController
    private val contentResolver = app.contentResolver

    val playbackState = playback.state
    val progress = playback.progress
    val queue = playback.queue

    // L'épisode affiché suit toujours ce que le lecteur joue réellement : si la file passe à
    // l'épisode suivant, favori, points mémoire et édition ciblent le bon épisode.
    private val currentEpisodeId = playbackState.map { it.episodeId }.distinctUntilChanged()

    val episode: StateFlow<EpisodeEntity?> = currentEpisodeId
        .flatMapLatest { id -> if (id == null) flowOf(null) else library.observeEpisode(id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val bookmarkList: StateFlow<List<BookmarkEntity>> = currentEpisodeId
        .flatMapLatest { id -> if (id == null) flowOf(emptyList()) else bookmarks.bookmarksFor(id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val rewindSeconds: StateFlow<Int> = preferences.rewindSeconds
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 15)
    val forwardSeconds: StateFlow<Int> = preferences.forwardSeconds
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 30)

    val ambientMode: StateFlow<Boolean> = preferences.ambientMode
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    fun toggleAmbientMode() {
        viewModelScope.launch { preferences.setAmbientMode(!ambientMode.value) }
    }

    private val _lastSavedFeedback = MutableStateFlow<String?>(null)
    val lastSavedFeedback: StateFlow<String?> = _lastSavedFeedback.asStateFlow()

    // Set right after a quick-add so the UI can pop the naming dialog automatically:
    // save instantly, then offer an optional title/note form.
    private val _pendingNamingBookmark = MutableStateFlow<BookmarkEntity?>(null)
    val pendingNamingBookmark: StateFlow<BookmarkEntity?> = _pendingNamingBookmark.asStateFlow()

    private var openedKey: String? = null

    /**
     * Charge un épisode et le lance là où l'utilisateur s'était arrêté. N'agit qu'une fois par
     * destination de navigation : une rotation d'écran ne doit pas ramener sur l'épisode d'origine
     * si la file a avancé entre-temps.
     */
    fun openAndPlay(episodeId: Long, requestedPositionMs: Long? = null) {
        val key = "$episodeId/$requestedPositionMs"
        if (openedKey == key) return
        openedKey = key
        viewModelScope.launch {
            val ep = library.getEpisode(episodeId) ?: return@launch
            if (ep.isMissing) {
                playback.showError("Le fichier audio est introuvable. Vérifiez le dossier sélectionné.")
                return@launch
            }
            val podcastTitle = library.getPodcast(ep.podcastId)?.displayTitle ?: ""
            val resumePosition = requestedPositionMs ?: PlaybackRules.resumePosition(
                saved = bookmarks.getPlaybackPosition(episodeId),
                durationMs = ep.durationMs,
                smartRewind = preferences.smartRewind.first()
            )
            val speed = preferences.speedForPodcast(ep.podcastId).first()
            playback.playEpisode(ep, podcastTitle, resumePosition, speed, forcePosition = requestedPositionMs != null)
        }
    }

    fun togglePlayPause() = playback.togglePlayPause()
    fun skipToNext() = playback.skipToNext()
    fun clearError() = playback.clearError()

    fun skipBackward() = playback.skipBackward(rewindSeconds.value)
    fun skipForward() = playback.skipForward(forwardSeconds.value)
    fun seekTo(positionMs: Long) = playback.seekTo(positionMs)

    fun setSpeed(speed: Float) {
        playback.setSpeed(speed)
        episode.value?.let { ep -> viewModelScope.launch { preferences.setSpeedForPodcast(ep.podcastId, speed) } }
    }

    fun startSleepTimer(minutes: Int) = playback.startSleepTimer(minutes)
    fun sleepAtEpisodeEnd() = playback.sleepAtEpisodeEnd()
    fun cancelSleepTimer() = playback.cancelSleepTimer()

    /** Instant point-mémoire creation: save first, name it right after. */
    fun quickAddBookmark() {
        val ep = episode.value ?: return
        val position = progress.value.positionMs
        viewModelScope.launch {
            val id = bookmarks.quickAddBookmark(ep.id, position)
            _lastSavedFeedback.value = "Point mémoire enregistré — ${formatDurationShort(position)}"
            _pendingNamingBookmark.value = BookmarkEntity(id = id, episodeId = ep.id, positionMs = position)
        }
    }

    fun dismissPendingNaming() {
        _pendingNamingBookmark.value = null
    }

    fun clearFeedback() {
        _lastSavedFeedback.value = null
    }

    fun updateBookmark(bookmark: BookmarkEntity, title: String?, note: String?) {
        viewModelScope.launch {
            bookmarks.updateBookmark(bookmark.copy(title = title, note = note))
        }
    }

    fun deleteBookmark(bookmark: BookmarkEntity) {
        viewModelScope.launch { bookmarks.deleteBookmark(bookmark) }
    }

    fun saveEpisodeMetadata(title: String?, author: String?, coverUri: String?) {
        val episode = episode.value ?: return
        viewModelScope.launch {
            library.saveEpisodeMetadata(episode, title, author, coverUri)
            val updated = episode.copy(userTitle = title, userAuthor = author, userCoverUri = coverUri)
            playback.updateCurrentMetadata(updated, library.getPodcast(updated.podcastId)?.displayTitle.orEmpty())
        }
    }

    fun persistCoverPermission(uri: android.net.Uri) {
        runCatching {
            contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    fun toggleFavorite() {
        val ep = episode.value ?: return
        viewModelScope.launch { library.toggleFavorite(ep.id, !ep.isFavorite) }
    }
}
