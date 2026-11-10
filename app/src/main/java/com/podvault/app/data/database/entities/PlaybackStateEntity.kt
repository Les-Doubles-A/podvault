package com.podvault.app.data.database.entities

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.ColumnInfo

/** Exact resume position for an episode. Distinct from bookmarks. */
@Entity(tableName = "playback_states")
data class PlaybackStateEntity(
    @PrimaryKey val episodeId: Long,
    val positionMs: Long,
    val lastPlayedAt: Long,
    val playCount: Int = 0,
    val completed: Boolean = false,
    @ColumnInfo(defaultValue = "0") val totalListenedMs: Long = 0L
)
