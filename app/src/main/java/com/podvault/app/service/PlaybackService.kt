package com.podvault.app.service

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.podvault.app.MainActivity
import com.podvault.app.PodVaultApplication
import com.podvault.app.data.repository.PlaybackRules
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Local playback that keeps running when the app is minimised or the screen is locked,
 * exposes lock-screen/notification controls via MediaSession, and periodically persists
 * "exact resume" position to Room.
 *
 * Media3's MediaSessionService automatically manages the foreground-service
 * notification (play/pause/skip + artwork) from the session's current MediaMetadata,
 * so we don't hand-build a NotificationCompat here.
 */
@UnstableApi
class PlaybackService : MediaSessionService() {

    private lateinit var player: ExoPlayer
    private lateinit var mediaSession: MediaSession

    // Main dispatcher is required here: ExoPlayer's player instance must only ever be
    // touched from the main thread, and this scope drives both the periodic tick and
    // the position-save calls that read from `player`.
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var positionSaveJob: Job? = null
    private var trackedEpisodeId: Long? = null
    private var trackedPositionMs: Long = 0L

    // Petit retour en arrière à la reprise : on mémorise où et quand la pause a eu lieu.
    private var smartRewindEnabled = true
    private var pausedAtElapsedMs: Long? = null
    private var pausedMediaId: String? = null
    private var pausedPositionMs: Long = 0L

    override fun onCreate() {
        super.onCreate()

        val audioAttributes = AudioAttributes.Builder()
            .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
            .setUsage(C.USAGE_MEDIA)
            .build()

        // Même rendu audio que par défaut, avec en plus la mesure du niveau sonore (mode ambiance).
        val renderersFactory = object : DefaultRenderersFactory(this) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean
            ): AudioSink = DefaultAudioSink.Builder(context)
                .setEnableFloatOutput(enableFloatOutput)
                .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                .setAudioProcessors(arrayOf(AudioLevelProcessor()))
                .build()
        }

        player = ExoPlayer.Builder(this, renderersFactory)
            .setAudioAttributes(audioAttributes, /* handleAudioFocus = */ true)
            // Pause (rather than duck) on transient loss, and stop on becoming noisy (headphones unplugged).
            .setHandleAudioBecomingNoisy(true)
            // Local-file playback still needs the CPU to keep decoding while the screen is off.
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()

        val sessionActivityIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        mediaSession = MediaSession.Builder(this, player)
            .setSessionActivity(sessionActivityIntent)
            .build()

        val app = application as? PodVaultApplication
        if (app != null) {
            serviceScope.launch {
                app.preferencesManager.smartRewind.collect { smartRewindEnabled = it }
            }
        }

        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) startPeriodicPositionSave() else {
                    stopPeriodicPositionSave()
                    saveCurrentPositionNow()
                }
            }

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                if (!playWhenReady) rememberPause() else applySmartRewind()
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) {
                    saveCurrentPositionNow(completed = true)
                }
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                // À cet instant le Player expose déjà le nouvel élément : sauvegarder
                // l'ancien à partir du Player écraserait la position du nouvel épisode.
                // Une transition automatique signifie que l'épisode précédent est allé au bout.
                savePosition(
                    trackedEpisodeId,
                    trackedPositionMs,
                    completed = reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO
                )
                trackedEpisodeId = mediaItem?.mediaId?.toLongOrNull()
                trackedPositionMs = player.currentPosition.coerceAtLeast(0)
                pausedAtElapsedMs = null
                if (mediaItem != null) {
                    applyPodcastSpeed(mediaItem)
                    if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO ||
                        reason == Player.MEDIA_ITEM_TRANSITION_REASON_SEEK
                    ) resumeSavedPosition(mediaItem)
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                android.util.Log.w("PlaybackService", "Erreur de lecture du fichier local (${error.errorCodeName})")
                saveCurrentPositionNow()
            }
        })
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession = mediaSession

    // region Reprise intelligente

    private fun rememberPause() {
        if (player.mediaItemCount == 0 || player.playbackState == Player.STATE_ENDED) {
            pausedAtElapsedMs = null
            return
        }
        pausedAtElapsedMs = SystemClock.elapsedRealtime()
        pausedMediaId = player.currentMediaItem?.mediaId
        pausedPositionMs = player.currentPosition
    }

    /**
     * Reculer de quelques secondes quand on reprend après une pause, proportionnellement à
     * sa durée. Ignoré si l'élément ou la position ont changé entre-temps (nouvel épisode,
     * navigation manuelle) pour ne jamais décaler une position choisie par l'utilisateur.
     */
    private fun applySmartRewind() {
        val pausedAt = pausedAtElapsedMs ?: return
        pausedAtElapsedMs = null
        if (!smartRewindEnabled) return
        val sameItem = player.currentMediaItem?.mediaId == pausedMediaId
        val samePosition = kotlin.math.abs(player.currentPosition - pausedPositionMs) < 1_500L
        if (!sameItem || !samePosition) return
        val rewind = PlaybackRules.smartRewindMs(SystemClock.elapsedRealtime() - pausedAt)
        if (rewind > 0L && player.currentPosition > 0L) {
            player.seekTo((player.currentPosition - rewind).coerceAtLeast(0L))
        }
    }

    /** Applique la vitesse mémorisée pour le podcast de l'élément qui démarre. */
    private fun applyPodcastSpeed(item: MediaItem) {
        val app = application as? PodVaultApplication ?: return
        val podcastId = item.mediaMetadata.extras
            ?.getLong(PlaybackRules.EXTRA_PODCAST_ID, -1L)?.takeIf { it >= 0 } ?: return
        serviceScope.launch {
            val speed = app.preferencesManager.speedForPodcast(podcastId).first()
            if (player.currentMediaItem?.mediaId == item.mediaId && player.playbackParameters.speed != speed) {
                player.setPlaybackSpeed(speed)
            }
        }
    }

    /** Dans une file, un épisode entamé reprend là où on l'avait laissé plutôt qu'au début. */
    private fun resumeSavedPosition(item: MediaItem) {
        val app = application as? PodVaultApplication ?: return
        val episodeId = item.mediaId.toLongOrNull() ?: return
        serviceScope.launch {
            val saved = app.bookmarkRepository.getPlaybackPosition(episodeId) ?: return@launch
            val duration = player.duration.takeIf { it > 0 } ?: item.mediaMetadata.durationMs ?: 0L
            val target = PlaybackRules.resumePosition(saved, duration, smartRewindEnabled)
            if (target > 0L && player.currentMediaItem?.mediaId == item.mediaId && player.currentPosition < 2_000L) {
                player.seekTo(target)
            }
        }
    }

    // endregion

    private fun currentEpisodeId(): Long? =
        player.currentMediaItem?.mediaId?.toLongOrNull()

    private fun startPeriodicPositionSave() {
        stopPeriodicPositionSave()
        positionSaveJob = serviceScope.launch {
            while (true) {
                delay(POSITION_SAVE_INTERVAL_MS)
                saveCurrentPositionNow(listenedDeltaMs = POSITION_SAVE_INTERVAL_MS)
            }
        }
    }

    private fun stopPeriodicPositionSave() {
        positionSaveJob?.cancel()
        positionSaveJob = null
    }

    private fun saveCurrentPositionNow(completed: Boolean = false, listenedDeltaMs: Long = 0L) {
        // Player property reads MUST happen here, synchronously, while we're still on the main
        // thread. Only the actual Room write is pushed to Dispatchers.IO.
        val episodeId = currentEpisodeId() ?: return
        val position = player.currentPosition.coerceAtLeast(0)
        trackedEpisodeId = episodeId
        trackedPositionMs = position
        val duration = player.duration.takeIf { it > 0 }
            ?: player.currentMediaItem?.mediaMetadata?.durationMs ?: 0L
        savePosition(episodeId, position, completed || PlaybackRules.isCompleted(position, duration), listenedDeltaMs)
    }

    private fun savePosition(episodeId: Long?, position: Long, completed: Boolean = false, listenedDeltaMs: Long = 0L) {
        episodeId ?: return
        val app = application as? PodVaultApplication ?: return
        app.applicationScope.launch {
            app.bookmarkRepository.savePlaybackPosition(episodeId, position, completed, listenedDeltaMs)
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // If nothing is playing, let the service (and its notification) go away when the app is
        // swiped from recents. If playback is active, MediaSessionService keeps it alive.
        if (!player.playWhenReady || player.mediaItemCount == 0) {
            saveCurrentPositionNow()
            stopSelf()
        }
    }

    override fun onDestroy() {
        stopPeriodicPositionSave()
        saveCurrentPositionNow()
        mediaSession.release()
        player.release()
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val POSITION_SAVE_INTERVAL_MS = 5_000L
    }
}
