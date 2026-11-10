package com.podvault.app.data.repository

import android.content.Context
import android.net.Uri
import com.podvault.app.data.database.AppDatabase
import com.podvault.app.data.database.entities.EpisodeEntity
import com.podvault.app.data.database.entities.PodcastEntity
import com.podvault.app.data.datastore.PreferencesManager
import com.podvault.app.data.media.MediaScanner
import com.podvault.app.data.media.ScanProgress
import com.podvault.app.data.media.ScanReport
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

sealed class ScanState {
    data object Idle : ScanState()
    data class Running(val progress: ScanProgress) : ScanState()
    data class Done(val report: ScanReport) : ScanState()
    data class Failed(val message: String) : ScanState()
}

class LibraryRepository(
    private val context: Context,
    private val db: AppDatabase = AppDatabase.getInstance(context),
    private val prefs: PreferencesManager = PreferencesManager(context)
) {
    private val scanner = MediaScanner(context, db.podcastDao(), db.episodeDao())
    private val scanMutex = Mutex()

    private val _scanState = MutableStateFlow<ScanState>(ScanState.Idle)
    val scanState: StateFlow<ScanState> = _scanState.asStateFlow()

    fun podcasts(): Flow<List<PodcastEntity>> = db.podcastDao().observeAll()
    fun episodeCountsByPodcast(): Flow<List<com.podvault.app.data.database.dao.PodcastEpisodeCount>> =
        db.episodeDao().observeEpisodeCountsByPodcast()
    fun episodeCoversByPodcast() = db.episodeDao().observeEpisodeCoversByPodcast()
    fun episodesOf(podcastId: Long): Flow<List<EpisodeEntity>> = db.episodeDao().observeByPodcast(podcastId)
    fun libraryItemsOf(podcastId: Long) = db.episodeDao().observeLibraryItems(podcastId)
    fun duplicateCandidates() = db.episodeDao().observeDuplicateCandidates()
    fun recentlyAdded(limit: Int = 25): Flow<List<EpisodeEntity>> = db.episodeDao().observeRecentlyAdded(limit)
    fun favorites(limit: Int = 50): Flow<List<EpisodeEntity>> = db.episodeDao().observeFavorites(limit)
    fun inProgressEpisodes(limit: Int = 30) = db.playbackStateDao().observeInProgressEpisodes(limit)
    fun podcastAffinity() = db.playbackStateDao().observePodcastAffinity()
    fun unplayedCandidates(limit: Int = 400) = db.episodeDao().observeUnplayedCandidates(limit)
    suspend fun unplayedForPodcast(podcastId: Long) = db.episodeDao().getUnplayedForPodcast(podcastId)
    suspend fun lastPlayedEpisode(podcastId: Long) = db.playbackStateDao().getLastPlayedEpisode(podcastId)
    fun missingEpisodes(): Flow<List<EpisodeEntity>> = db.episodeDao().observeMissing()
    fun inProgress(limit: Int = 20) = db.playbackStateDao().observeInProgress(limit)
    fun totalEpisodeCount(): Flow<Int> = db.episodeDao().observeTotalCount()
    fun totalLibrarySize(): Flow<Long> = db.episodeDao().observeTotalSize()
    fun podcastListeningStats() = db.playbackStateDao().observePodcastStats()
    fun listeningDays() = db.listeningDayDao().observeAll()

    fun searchEpisodes(query: String): Flow<List<EpisodeEntity>> = db.episodeDao().search(query)
    fun searchPodcasts(query: String): Flow<List<PodcastEntity>> = db.podcastDao().search(query)

    fun observeEpisode(id: Long): Flow<EpisodeEntity?> = db.episodeDao().observeById(id)
    suspend fun getEpisode(id: Long): EpisodeEntity? = db.episodeDao().getById(id)
    suspend fun getPodcast(id: Long): PodcastEntity? = db.podcastDao().getById(id)
    fun observePodcast(id: Long) = db.podcastDao().observeById(id)

    suspend fun savePodcastMetadata(podcast: PodcastEntity, title: String?, author: String?, coverUri: String?) {
        db.podcastDao().update(podcast.copy(userTitle = title, userAuthor = author, userCoverUri = coverUri))
    }

    suspend fun saveEpisodeMetadata(episode: EpisodeEntity, title: String?, author: String?, coverUri: String?) {
        db.episodeDao().update(episode.copy(userTitle = title, userAuthor = author, userCoverUri = coverUri, modifiedAt = System.currentTimeMillis()))
    }

    suspend fun toggleFavorite(episodeId: Long, favorite: Boolean) =
        db.episodeDao().setFavorite(episodeId, favorite)

    suspend fun setPlayed(episodeId: Long, played: Boolean) =
        db.episodeDao().setPlayed(episodeId, played)

    /** Kicks off a scan of every currently-watched folder (section 8-13 of the spec). */
    suspend fun rescanLibrary() {
        if (!scanMutex.tryLock()) return
        try {
        val folders = prefs.watchedFolders.first().map { Uri.parse(it) }
        if (folders.isEmpty()) {
            _scanState.value = ScanState.Failed("Aucun dossier sélectionné")
            return
        }
        _scanState.value = ScanState.Running(ScanProgress(0, 0, "", ""))
        try {
            val report = scanner.scan(folders) { progress ->
                _scanState.value = ScanState.Running(progress)
            }
            _scanState.value = ScanState.Done(report)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            _scanState.value = ScanState.Failed(e.message ?: "Erreur inconnue")
        }
        } finally {
            scanMutex.unlock()
        }
    }

    suspend fun addFolderAndScan(uri: Uri) {
        prefs.addWatchedFolder(uri.toString())
        rescanLibrary()
    }
}
