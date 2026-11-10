package com.podvault.app.data.repository

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.podvault.app.data.database.entities.EpisodeEntity
import com.podvault.app.data.media.toDisplayUri
import com.podvault.app.service.PlaybackService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Minuterie d'arrêt active, le cas échéant. */
sealed interface SleepTimer {
    data object Off : SleepTimer
    /** [endsAtElapsedMs] est exprimé dans la base de temps de [SystemClock.elapsedRealtime]. */
    data class Countdown(val endsAtElapsedMs: Long) : SleepTimer
    data object EndOfEpisode : SleepTimer
}

/** État de lecture "lent" : ne change pas à chaque tick de position. */
data class PlaybackUiState(
    val episodeId: Long? = null,
    val podcastId: Long? = null,
    val podcastTitle: String = "",
    val episodeTitle: String = "",
    val coverUri: String? = null,
    val isPlaying: Boolean = false,
    val speed: Float = 1.0f,
    val errorMessage: String? = null,
    val sleepTimer: SleepTimer = SleepTimer.Off
)

/** Position courante, mise à jour deux fois par seconde. Séparée pour éviter de recomposer tout l'écran. */
data class PlaybackProgress(val positionMs: Long = 0L, val durationMs: Long = 0L) {
    val fraction: Float
        get() = if (durationMs > 0) (positionMs / durationMs.toFloat()).coerceIn(0f, 1f) else 0f
}

data class QueueItem(
    /** Index dans la playlist du lecteur ; sert aux opérations de déplacement/suppression. */
    val index: Int,
    val episodeId: Long,
    val title: String,
    val podcastTitle: String,
    val coverUri: String?,
    val durationMs: Long
)

data class QueueSnapshot(
    val current: QueueItem? = null,
    val upNext: List<QueueItem> = emptyList()
) {
    val upNextIds: Set<Long> by lazy(LazyThreadSafetyMode.NONE) { upNext.mapTo(HashSet()) { it.episodeId } }
    val upNextDurationMs: Long get() = upNext.sumOf { it.durationMs }
}

enum class QueueMode { NEXT, LAST }

enum class QueueAddResult { ADDED, PLAY_NEXT, ALREADY_QUEUED, NOW_PLAYING }

/** Message éphémère affiché globalement (snackbar). */
data class PlaybackEvent(val message: String, val offerQueueShortcut: Boolean = false)

/**
 * UI-facing wrapper around a Media3 [MediaController] connected to [PlaybackService].
 * Screens read [state] / [progress] / [queue] and call the transport functions instead of
 * touching ExoPlayer directly, so the same controls work whether the app is foregrounded or
 * the media is being driven from the lock screen / notification.
 */
class PlaybackController(private val context: Context) {

    private data class PendingPlayback(val item: MediaItem, val positionMs: Long, val speed: Float)

    private var pendingPlayback: PendingPlayback? = null
    private val pendingQueue = mutableListOf<Pair<MediaItem, QueueMode>>()

    private var controller: MediaController? = null

    private val _state = MutableStateFlow(PlaybackUiState())
    val state: StateFlow<PlaybackUiState> = _state.asStateFlow()

    private val _progress = MutableStateFlow(PlaybackProgress())
    val progress: StateFlow<PlaybackProgress> = _progress.asStateFlow()

    private val _queue = MutableStateFlow(QueueSnapshot())
    val queue: StateFlow<QueueSnapshot> = _queue.asStateFlow()

    private val _events = MutableSharedFlow<PlaybackEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<PlaybackEvent> = _events.asSharedFlow()

    private val scope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())
    private var tickerJob: Job? = null
    private var sleepTimerJob: Job? = null
    private var pauseAtEpisodeEnd = false

    fun connect(onReady: () -> Unit = {}) {
        if (controller != null) return
        val sessionToken = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, sessionToken).buildAsync()
        future.addListener({
            try {
                val connected = future.get()
                controller = connected
                attachListener(connected)
                pendingPlayback?.let { pending ->
                    pendingPlayback = null
                    startPlayback(pending)
                }
                pendingQueue.toList().forEach { (item, mode) -> enqueueItem(connected, item, mode) }
                pendingQueue.clear()
                rebuildQueue()
                onReady()
            } catch (e: Exception) {
                _state.update { it.copy(errorMessage = "Impossible de démarrer le lecteur audio.") }
            }
        }, ContextCompat.getMainExecutor(context))
    }

    // region Listener

    private fun attachListener(controller: MediaController) {
        controller.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                _state.update { it.copy(isPlaying = isPlaying) }
                if (isPlaying) startTicker() else {
                    stopTicker()
                    refreshProgress()
                }
            }

            override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
                refreshCurrent()
            }

            override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) {
                rebuildQueue()
            }

            override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
                _state.update { it.copy(speed = playbackParameters.speed) }
            }

            override fun onPlayerError(error: PlaybackException) {
                _state.update { it.copy(errorMessage = "Ce fichier ne peut pas être lu. Vérifiez l'accès au dossier.") }
                // Un fichier illisible ne doit pas bloquer toute la file : on passe au suivant.
                if (controller.hasNextMediaItem()) {
                    controller.seekToNextMediaItem()
                    controller.prepare()
                    controller.play()
                }
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                refreshCurrent()
                _progress.value = PlaybackProgress(0L, durationOf(controller))
                _state.update { it.copy(errorMessage = null) }
                rebuildQueue()
                if (pauseAtEpisodeEnd && reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
                    // Fin d'épisode atteinte avec un épisode suivant en file : on s'arrête sur le suivant.
                    finishSleepAtEpisodeEnd()
                    controller.pause()
                    controller.seekTo(0L)
                }
            }

            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                reason: Int
            ) {
                refreshProgress()
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) refreshProgress()
                if (playbackState == Player.STATE_ENDED && pauseAtEpisodeEnd) {
                    finishSleepAtEpisodeEnd()
                    controller.pause()
                }
            }
        })
        refreshCurrent()
        _state.update { it.copy(isPlaying = controller.isPlaying, speed = controller.playbackParameters.speed) }
        refreshProgress()
        rebuildQueue()
        if (controller.isPlaying) startTicker()
    }

    private fun durationOf(player: Player): Long {
        val duration = player.duration.takeIf { it > 0 }
        return duration ?: player.mediaMetadata.durationMs?.takeIf { it > 0 } ?: 0L
    }

    private fun refreshCurrent() {
        val player = controller ?: return
        val item = player.currentMediaItem
        if (item == null) {
            _state.update { it.copy(episodeId = null, podcastId = null, podcastTitle = "", episodeTitle = "", coverUri = null) }
            return
        }
        val meta = item.mediaMetadata
        val podcastId = meta.extras?.getLong(PlaybackRules.EXTRA_PODCAST_ID, -1L)?.takeIf { it >= 0 }
        _state.update {
            it.copy(
                episodeId = item.mediaId.toLongOrNull(),
                podcastId = podcastId,
                podcastTitle = meta.albumTitle?.toString().orEmpty(),
                episodeTitle = meta.title?.toString().orEmpty(),
                coverUri = meta.artworkUri?.toString()
            )
        }
    }

    private fun refreshProgress() {
        val player = controller ?: return
        val next = PlaybackProgress(player.currentPosition.coerceAtLeast(0L), durationOf(player))
        if (next != _progress.value) _progress.value = next
    }

    private fun startTicker() {
        if (tickerJob?.isActive == true) return
        tickerJob = scope.launch {
            while (true) {
                refreshProgress()
                delay(500)
            }
        }
    }

    private fun stopTicker() {
        tickerJob?.cancel()
        tickerJob = null
    }

    // endregion

    // region Lecture

    private fun buildMediaItem(episode: EpisodeEntity, podcastTitle: String): MediaItem {
        val extras = Bundle().apply { putLong(PlaybackRules.EXTRA_PODCAST_ID, episode.podcastId) }
        return MediaItem.Builder()
            .setMediaId(episode.id.toString())
            .setUri(Uri.parse(episode.audioUri))
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(episode.displayTitle)
                    .setArtist(episode.displayAuthor)
                    .setAlbumTitle(podcastTitle)
                    .setArtworkUri(episode.displayCoverUri.toDisplayUri()?.let { Uri.parse(it) })
                    .setDurationMs(episode.durationMs.takeIf { it > 0 })
                    .setExtras(extras)
                    .build()
            )
            .build()
    }

    /**
     * Lance un épisode à la position demandée. La file "à suivre" est conservée : l'épisode est
     * inséré juste après l'élément courant, les éléments déjà lus sont retirés de la playlist.
     */
    fun playEpisode(
        episode: EpisodeEntity,
        podcastTitle: String,
        resumePositionMs: Long,
        speed: Float = 1.0f,
        forcePosition: Boolean = false
    ) {
        val position = resumePositionMs.coerceAtLeast(0L).let {
            if (episode.durationMs > 0L) it.coerceAtMost(episode.durationMs) else it
        }
        val player = controller
        val mediaItem = buildMediaItem(episode, podcastTitle)
        if (player == null) {
            pendingPlayback = PendingPlayback(mediaItem, position, speed)
            return
        }
        val id = episode.id.toString()
        val currentIndex = player.currentMediaItemIndex
        if (player.currentMediaItem?.mediaId == id) {
            val ended = player.playbackState == Player.STATE_ENDED
            if (forcePosition || ended) player.seekTo(position)
            if (ended) {
                player.prepare()
                player.play()
            }
            return
        }
        if (player.mediaItemCount == 0 || currentIndex < 0) {
            startPlayback(PendingPlayback(mediaItem, position, speed))
            return
        }
        // Retire l'historique et une éventuelle occurrence déjà en file.
        for (i in player.mediaItemCount - 1 downTo currentIndex + 1) {
            if (player.getMediaItemAt(i).mediaId == id) player.removeMediaItem(i)
        }
        if (currentIndex > 0) player.removeMediaItems(0, currentIndex)
        player.addMediaItem(1, mediaItem)
        player.seekTo(1, position)
        player.setPlaybackSpeed(speed)
        player.playWhenReady = true
        player.prepare()
    }

    private fun startPlayback(pending: PendingPlayback) {
        val player = controller ?: run { pendingPlayback = pending; return }
        player.setMediaItem(pending.item, pending.positionMs)
        player.setPlaybackSpeed(pending.speed)
        player.playWhenReady = true
        player.prepare()
    }

    fun showError(message: String) {
        _state.update { it.copy(errorMessage = message) }
    }

    fun clearError() {
        _state.update { it.copy(errorMessage = null) }
    }

    /** Met à jour titre/auteur/pochette de l'élément en cours sans interrompre la lecture. */
    fun updateCurrentMetadata(episode: EpisodeEntity, podcastTitle: String) {
        val player = controller ?: return
        if (player.currentMediaItem?.mediaId != episode.id.toString()) return
        val index = player.currentMediaItemIndex
        val position = player.currentPosition
        val wasPlaying = player.playWhenReady
        player.replaceMediaItem(index, buildMediaItem(episode, podcastTitle))
        // Le remplacement de l'élément courant peut réinitialiser la position : on la restaure.
        player.seekTo(index, position)
        player.playWhenReady = wasPlaying
    }

    fun togglePlayPause() {
        controller?.let { player ->
            if (player.isPlaying) player.pause()
            else {
                if (player.playbackState == Player.STATE_ENDED) player.seekTo(0L)
                player.play()
            }
        }
    }

    fun seekTo(positionMs: Long) {
        controller?.seekTo(positionMs.coerceAtLeast(0L))
        refreshProgress()
    }

    fun skipForward(seconds: Int) {
        val player = controller ?: return
        val duration = player.duration
        val target = player.currentPosition + seconds * 1000L
        player.seekTo(if (duration > 0) target.coerceAtMost(duration) else target)
        refreshProgress()
    }

    fun skipBackward(seconds: Int) {
        val player = controller ?: return
        player.seekTo((player.currentPosition - seconds * 1000L).coerceAtLeast(0L))
        refreshProgress()
    }

    fun skipToNext() {
        controller?.takeIf { it.hasNextMediaItem() }?.seekToNextMediaItem()
    }

    fun setSpeed(speed: Float) {
        controller?.setPlaybackSpeed(speed)
    }

    // endregion

    // region File d'attente

    private fun enqueueItem(player: Player, item: MediaItem, mode: QueueMode): QueueAddResult {
        if (player.mediaItemCount == 0) {
            player.setMediaItem(item)
            player.prepare()
            return QueueAddResult.ADDED
        }
        val current = player.currentMediaItemIndex.coerceAtLeast(0)
        val existing = (current until player.mediaItemCount).firstOrNull { player.getMediaItemAt(it).mediaId == item.mediaId }
        if (existing == current) return QueueAddResult.NOW_PLAYING
        if (existing != null) {
            if (mode == QueueMode.NEXT && existing != current + 1) {
                player.moveMediaItem(existing, current + 1)
                return QueueAddResult.PLAY_NEXT
            }
            return QueueAddResult.ALREADY_QUEUED
        }
        if (mode == QueueMode.NEXT) {
            player.addMediaItem(current + 1, item)
            return QueueAddResult.PLAY_NEXT
        }
        player.addMediaItem(item)
        return QueueAddResult.ADDED
    }

    /** Ajoute un épisode à la file et affiche un retour à l'utilisateur. */
    fun addToQueue(episode: EpisodeEntity, podcastTitle: String, mode: QueueMode = QueueMode.LAST): QueueAddResult {
        val item = buildMediaItem(episode, podcastTitle)
        val player = controller
        if (player == null) {
            if (pendingQueue.none { it.first.mediaId == item.mediaId }) pendingQueue.add(item to mode)
            _events.tryEmit(PlaybackEvent("Ajouté à la file d'attente", offerQueueShortcut = true))
            return QueueAddResult.ADDED
        }
        val wasEmpty = player.mediaItemCount == 0
        val result = enqueueItem(player, item, mode)
        val title = episode.displayTitle
        val message = when {
            wasEmpty -> "« $title » est prêt à être lu"
            result == QueueAddResult.NOW_PLAYING -> "« $title » est en cours de lecture"
            result == QueueAddResult.ALREADY_QUEUED -> "« $title » est déjà dans la file d'attente"
            result == QueueAddResult.PLAY_NEXT -> "« $title » sera lu ensuite"
            else -> {
                val position = player.mediaItemCount - 1 - player.currentMediaItemIndex.coerceAtLeast(0)
                "Ajouté à la file d'attente (position $position)"
            }
        }
        _events.tryEmit(PlaybackEvent(message, offerQueueShortcut = result != QueueAddResult.NOW_PLAYING))
        return result
    }

    private fun rebuildQueue() {
        val player = controller ?: return
        val count = player.mediaItemCount
        val current = player.currentMediaItemIndex
        if (count == 0 || current < 0) {
            if (_queue.value != QueueSnapshot()) _queue.value = QueueSnapshot()
            return
        }
        fun itemAt(index: Int): QueueItem? {
            val media = player.getMediaItemAt(index)
            val id = media.mediaId.toLongOrNull() ?: return null
            val meta = media.mediaMetadata
            return QueueItem(
                index = index,
                episodeId = id,
                title = meta.title?.toString().orEmpty(),
                podcastTitle = meta.albumTitle?.toString().orEmpty(),
                coverUri = meta.artworkUri?.toString(),
                durationMs = meta.durationMs ?: 0L
            )
        }
        val snapshot = QueueSnapshot(
            current = itemAt(current),
            upNext = (current + 1 until count).mapNotNull(::itemAt)
        )
        if (snapshot != _queue.value) _queue.value = snapshot
    }

    /** Lance directement un élément de la file. */
    fun playQueueItem(index: Int) {
        val player = controller ?: return
        if (index !in 0 until player.mediaItemCount) return
        player.seekTo(index, 0L)
        player.playWhenReady = true
        player.prepare()
    }

    fun removeFromQueue(index: Int) {
        val player = controller ?: return
        if (index == player.currentMediaItemIndex || index !in 0 until player.mediaItemCount) return
        player.removeMediaItem(index)
    }

    fun moveInQueue(fromIndex: Int, toIndex: Int) {
        val player = controller ?: return
        val current = player.currentMediaItemIndex
        if (fromIndex <= current || toIndex <= current) return
        if (fromIndex !in 0 until player.mediaItemCount || toIndex !in 0 until player.mediaItemCount) return
        player.moveMediaItem(fromIndex, toIndex)
    }

    fun clearUpNext() {
        val player = controller ?: return
        val from = player.currentMediaItemIndex + 1
        if (from in 1 until player.mediaItemCount) player.removeMediaItems(from, player.mediaItemCount)
    }

    // endregion

    // region Minuterie d'arrêt

    fun startSleepTimer(minutes: Int) {
        cancelSleepTimer()
        val duration = minutes.coerceAtLeast(1) * 60_000L
        _state.update { it.copy(sleepTimer = SleepTimer.Countdown(SystemClock.elapsedRealtime() + duration)) }
        sleepTimerJob = scope.launch {
            delay(duration)
            controller?.pause()
            _state.update { it.copy(sleepTimer = SleepTimer.Off) }
            sleepTimerJob = null
        }
    }

    fun cancelSleepTimer() {
        sleepTimerJob?.cancel()
        sleepTimerJob = null
        pauseAtEpisodeEnd = false
        _state.update { it.copy(sleepTimer = SleepTimer.Off) }
    }

    fun sleepAtEpisodeEnd() {
        cancelSleepTimer()
        pauseAtEpisodeEnd = true
        _state.update { it.copy(sleepTimer = SleepTimer.EndOfEpisode) }
    }

    private fun finishSleepAtEpisodeEnd() {
        pauseAtEpisodeEnd = false
        _state.update { it.copy(sleepTimer = SleepTimer.Off) }
    }

    // endregion

    fun release() {
        stopTicker()
        cancelSleepTimer()
        controller?.release()
        controller = null
        scope.cancel()
    }
}
