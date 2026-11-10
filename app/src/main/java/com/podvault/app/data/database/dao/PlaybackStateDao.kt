package com.podvault.app.data.database.dao

import androidx.room.*
import com.podvault.app.data.database.entities.EpisodeEntity
import com.podvault.app.data.database.entities.PlaybackStateEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PlaybackStateDao {

    @Query("SELECT * FROM playback_states WHERE episodeId = :episodeId")
    suspend fun getForEpisode(episodeId: Long): PlaybackStateEntity?

    @Query("SELECT * FROM playback_states ORDER BY episodeId")
    suspend fun getAll(): List<PlaybackStateEntity>

    @Query("SELECT * FROM playback_states WHERE episodeId = :episodeId")
    fun observeForEpisode(episodeId: Long): Flow<PlaybackStateEntity?>

    @Query("""
        SELECT * FROM playback_states
        WHERE completed = 0 AND positionMs > 0
        ORDER BY lastPlayedAt DESC LIMIT :limit
    """)
    fun observeInProgress(limit: Int = 20): Flow<List<PlaybackStateEntity>>

    /** Épisodes en cours (non terminés, position > 0) avec leur position, en une seule requête. */
    @Query("""
        SELECT e.*, ps.positionMs AS positionMs, ps.lastPlayedAt AS lastPlayedAt
        FROM playback_states ps JOIN episodes e ON e.id = ps.episodeId
        WHERE ps.completed = 0 AND ps.positionMs > 0 AND e.isMissing = 0
        ORDER BY ps.lastPlayedAt DESC LIMIT :limit
    """)
    fun observeInProgressEpisodes(limit: Int = 30): Flow<List<InProgressEpisode>>

    /** Statistiques par podcast servant de signal d'affinité à la page d'accueil. */
    @Query("""
        SELECT e.podcastId AS podcastId,
               COALESCE(SUM(ps.totalListenedMs), 0) AS listenedMs,
               COALESCE(SUM(CASE WHEN ps.completed = 1 THEN 1 ELSE 0 END), 0) AS completedCount,
               COALESCE(MAX(ps.lastPlayedAt), 0) AS lastPlayedAt,
               COALESCE(SUM(CASE WHEN e.isFavorite = 1 THEN 1 ELSE 0 END), 0) AS favoriteCount
        FROM episodes e LEFT JOIN playback_states ps ON ps.episodeId = e.id
        GROUP BY e.podcastId
    """)
    fun observePodcastAffinity(): Flow<List<PodcastAffinityRow>>

    /** Dernier épisode écouté d'un podcast (point de départ de "À suivre"). */
    @Query("""
        SELECT e.* FROM episodes e JOIN playback_states ps ON ps.episodeId = e.id
        WHERE e.podcastId = :podcastId ORDER BY ps.lastPlayedAt DESC LIMIT 1
    """)
    suspend fun getLastPlayedEpisode(podcastId: Long): EpisodeEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(state: PlaybackStateEntity)

    @Query("SELECT podcasts.id AS podcastId, podcasts.title AS podcastTitle, COALESCE(SUM(playback_states.totalListenedMs), 0) AS listenedMs, COUNT(playback_states.episodeId) AS episodesPlayed FROM podcasts LEFT JOIN episodes ON episodes.podcastId = podcasts.id LEFT JOIN playback_states ON playback_states.episodeId = episodes.id GROUP BY podcasts.id ORDER BY listenedMs DESC")
    fun observePodcastStats(): Flow<List<PodcastListeningStat>>
}

data class PodcastListeningStat(val podcastId: Long, val podcastTitle: String, val listenedMs: Long, val episodesPlayed: Int)

data class InProgressEpisode(
    @Embedded val episode: EpisodeEntity,
    val positionMs: Long,
    val lastPlayedAt: Long
)

data class PodcastAffinityRow(
    val podcastId: Long,
    val listenedMs: Long,
    val completedCount: Int,
    val lastPlayedAt: Long,
    val favoriteCount: Int
)
