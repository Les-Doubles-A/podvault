package com.podvault.app.data.database.dao

import androidx.room.*
import com.podvault.app.data.database.entities.BookmarkEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface BookmarkDao {

    @Query("SELECT * FROM bookmarks WHERE episodeId = :episodeId ORDER BY positionMs ASC")
    fun observeForEpisode(episodeId: Long): Flow<List<BookmarkEntity>>

    @Query("SELECT * FROM bookmarks ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<BookmarkEntity>>

    @Query("SELECT * FROM bookmarks ORDER BY id")
    suspend fun getAll(): List<BookmarkEntity>

    @Query("SELECT * FROM bookmarks ORDER BY createdAt DESC LIMIT :limit")
    fun observeRecent(limit: Int = 10): Flow<List<BookmarkEntity>>

    @Insert
    suspend fun insert(bookmark: BookmarkEntity): Long

    @Query("SELECT EXISTS(SELECT 1 FROM bookmarks WHERE episodeId = :episodeId AND positionMs = :positionMs AND title IS :title AND note IS :note)")
    suspend fun existsAt(episodeId: Long, positionMs: Long, title: String?, note: String?): Boolean

    @Update
    suspend fun update(bookmark: BookmarkEntity)

    @Delete
    suspend fun delete(bookmark: BookmarkEntity)

    @Query("""
        SELECT * FROM bookmarks
        WHERE title LIKE '%' || :query || '%' OR note LIKE '%' || :query || '%'
        ORDER BY createdAt DESC
    """)
    fun search(query: String): Flow<List<BookmarkEntity>>

    @Query("SELECT COUNT(*) FROM bookmarks WHERE episodeId = :episodeId")
    suspend fun countForEpisode(episodeId: Long): Int
}
