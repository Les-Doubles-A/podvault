package com.podvault.app.data.database.dao

import androidx.room.*
import com.podvault.app.data.database.entities.EpisodeEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface EpisodeDao {

    @Query("SELECT * FROM episodes WHERE podcastId = :podcastId ORDER BY episodeNumber DESC, publicationDate DESC, addedAt DESC")
    fun observeByPodcast(podcastId: Long): Flow<List<EpisodeEntity>>

    @Query("SELECT episodes.*, COALESCE(playback_states.completed, 0) AS completed FROM episodes LEFT JOIN playback_states ON playback_states.episodeId = episodes.id WHERE episodes.podcastId = :podcastId ORDER BY episodes.episodeNumber DESC, episodes.publicationDate DESC, episodes.addedAt DESC")
    fun observeLibraryItems(podcastId: Long): Flow<List<EpisodeLibraryItem>>

    @Query("SELECT e.* FROM episodes e JOIN (SELECT fileName, fileSize FROM episodes GROUP BY fileName, fileSize HAVING COUNT(*) > 1) d ON e.fileName = d.fileName AND e.fileSize = d.fileSize ORDER BY e.fileName COLLATE NOCASE, e.id")
    fun observeDuplicateCandidates(): Flow<List<EpisodeEntity>>

    @Query("SELECT * FROM episodes WHERE id = :id")
    fun observeById(id: Long): Flow<EpisodeEntity?>

    @Query("SELECT * FROM episodes WHERE id = :id")
    suspend fun getById(id: Long): EpisodeEntity?

    @Query("SELECT * FROM episodes ORDER BY id")
    suspend fun getAll(): List<EpisodeEntity>

    @Query("SELECT * FROM episodes WHERE audioUri = :uri LIMIT 1")
    suspend fun getByUri(uri: String): EpisodeEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnoringConflict(episode: EpisodeEntity): Long

    @Query("""
        SELECT * FROM episodes
        WHERE fileName = :fileName AND fileSize = :fileSize AND isMissing = 1
        LIMIT 1
    """)
    suspend fun findMissingCandidate(fileName: String, fileSize: Long): EpisodeEntity?

    @Query("SELECT * FROM episodes ORDER BY addedAt DESC LIMIT :limit")
    fun observeRecentlyAdded(limit: Int = 25): Flow<List<EpisodeEntity>>

    /** Épisodes jamais commencés ni marqués lus : réservoir de candidats de l'accueil. */
    @Query("""
        SELECT e.* FROM episodes e LEFT JOIN playback_states ps ON ps.episodeId = e.id
        WHERE e.isMissing = 0 AND e.isPlayed = 0 AND ps.episodeId IS NULL
        ORDER BY e.addedAt DESC LIMIT :limit
    """)
    fun observeUnplayedCandidates(limit: Int = 400): Flow<List<EpisodeEntity>>

    /** Épisodes jamais commencés d'un podcast (pour déterminer le suivant dans la série). */
    @Query("""
        SELECT e.* FROM episodes e LEFT JOIN playback_states ps ON ps.episodeId = e.id
        WHERE e.podcastId = :podcastId AND e.isMissing = 0 AND e.isPlayed = 0 AND ps.episodeId IS NULL
    """)
    suspend fun getUnplayedForPodcast(podcastId: Long): List<EpisodeEntity>

    @Query("SELECT * FROM episodes WHERE isFavorite = 1 ORDER BY modifiedAt DESC LIMIT :limit")
    fun observeFavorites(limit: Int = 50): Flow<List<EpisodeEntity>>

    @Query("SELECT * FROM episodes WHERE isMissing = 1 ORDER BY title ASC")
    fun observeMissing(): Flow<List<EpisodeEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(episode: EpisodeEntity): Long

    @Update
    suspend fun update(episode: EpisodeEntity)

    @Query("UPDATE episodes SET isMissing = 1, modifiedAt = :now WHERE id = :id")
    suspend fun markMissing(id: Long, now: Long = System.currentTimeMillis())

    @Query("UPDATE episodes SET isFavorite = :favorite, modifiedAt = :now WHERE id = :id")
    suspend fun setFavorite(id: Long, favorite: Boolean, now: Long = System.currentTimeMillis())

    @Query("UPDATE episodes SET isPlayed = :played, modifiedAt = :now WHERE id = :id")
    suspend fun setPlayed(id: Long, played: Boolean, now: Long = System.currentTimeMillis())

    @Query("SELECT audioUri FROM episodes WHERE podcastId = :podcastId")
    suspend fun getUrisForPodcast(podcastId: Long): List<String>

    @Query("""
        SELECT * FROM episodes
        WHERE title LIKE '%' || :query || '%' OR userTitle LIKE '%' || :query || '%' OR userAuthor LIKE '%' || :query || '%' OR description LIKE '%' || :query || '%'
        ORDER BY addedAt DESC
    """)
    fun search(query: String): Flow<List<EpisodeEntity>>

    @Query("SELECT COUNT(*) FROM episodes WHERE podcastId = :podcastId")
    suspend fun countForPodcast(podcastId: Long): Int

    @Query("SELECT COALESCE(SUM(durationMs),0) FROM episodes WHERE podcastId = :podcastId")
    suspend fun totalDurationForPodcast(podcastId: Long): Long

    @Query("SELECT COUNT(*) FROM episodes")
    fun observeTotalCount(): Flow<Int>

    @Query("SELECT COALESCE(SUM(fileSize),0) FROM episodes")
    fun observeTotalSize(): Flow<Long>

    /** Une pochette d'épisode par podcast (la plus ancienne), pour les podcasts sans pochette propre. */
    @Query("""
        SELECT podcastId, COALESCE(userCoverUri, coverUri) AS coverUri FROM episodes
        WHERE id IN (
            SELECT MIN(id) FROM episodes
            WHERE COALESCE(userCoverUri, coverUri) IS NOT NULL AND COALESCE(userCoverUri, coverUri) != ''
            GROUP BY podcastId
        )
    """)
    fun observeEpisodeCoversByPodcast(): Flow<List<PodcastEpisodeCover>>

    @Query("SELECT podcastId, COUNT(*) as count FROM episodes GROUP BY podcastId")
    fun observeEpisodeCountsByPodcast(): Flow<List<PodcastEpisodeCount>>
}

data class PodcastEpisodeCover(val podcastId: Long, val coverUri: String)

data class PodcastEpisodeCount(val podcastId: Long, val count: Int)

data class EpisodeLibraryItem(@Embedded val episode: EpisodeEntity, val completed: Boolean?)
