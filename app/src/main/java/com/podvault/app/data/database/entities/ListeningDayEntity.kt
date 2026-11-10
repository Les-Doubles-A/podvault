package com.podvault.app.data.database.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "listening_days")
data class ListeningDayEntity(
    @PrimaryKey val day: String,
    val listenedMs: Long
)
