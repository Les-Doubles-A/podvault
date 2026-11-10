package com.podvault.app.data.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import com.podvault.app.data.database.entities.ListeningDayEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ListeningDayDao {
    @Query("INSERT INTO listening_days(day, listenedMs) VALUES(:day, :deltaMs) ON CONFLICT(day) DO UPDATE SET listenedMs = listening_days.listenedMs + :deltaMs")
    suspend fun addListening(day: String, deltaMs: Long)

    @Query("SELECT * FROM listening_days WHERE day = :day LIMIT 1")
    suspend fun getForDay(day: String): ListeningDayEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(day: ListeningDayEntity)

    @Query("SELECT * FROM listening_days ORDER BY day DESC")
    fun observeAll(): Flow<List<ListeningDayEntity>>

    @Query("SELECT * FROM listening_days ORDER BY day")
    suspend fun getAll(): List<ListeningDayEntity>
}
