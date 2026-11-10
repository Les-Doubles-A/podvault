package com.podvault.app.data.database.dao

import androidx.room.*
import com.podvault.app.data.database.entities.PodcastEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PodcastDao {

    @Query("SELECT * FROM podcasts ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<PodcastEntity>>

    @Query("SELECT * FROM podcasts WHERE id = :id")
    fun observeById(id: Long): Flow<PodcastEntity?>

    @Query("SELECT * FROM podcasts WHERE id = :id")
    suspend fun getById(id: Long): PodcastEntity?

    @Query("SELECT * FROM podcasts ORDER BY id")
    suspend fun getAll(): List<PodcastEntity>

    @Query("SELECT * FROM podcasts WHERE folderUri = :folderUri LIMIT 1")
    suspend fun getByFolderUri(folderUri: String): PodcastEntity?

    @Query("SELECT * FROM podcasts WHERE groupKey = :groupKey LIMIT 1")
    suspend fun getByGroupKey(groupKey: String): PodcastEntity?

    @Query("SELECT * FROM podcasts WHERE title = :title LIMIT 1")
    suspend fun getByTitle(title: String): PodcastEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(podcast: PodcastEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnoringConflict(podcast: PodcastEntity): Long

    @Update
    suspend fun update(podcast: PodcastEntity)

    @Query("""
        SELECT p.* FROM podcasts p
        WHERE p.title LIKE '%' || :query || '%'
           OR p.userTitle LIKE '%' || :query || '%'
           OR p.author LIKE '%' || :query || '%'
           OR p.userAuthor LIKE '%' || :query || '%'
           OR p.description LIKE '%' || :query || '%'
    """)
    fun search(query: String): Flow<List<PodcastEntity>>
}
