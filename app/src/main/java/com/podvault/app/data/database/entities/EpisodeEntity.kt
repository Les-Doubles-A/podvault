package com.podvault.app.data.database.entities

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "episodes",
    foreignKeys = [
        ForeignKey(
            entity = PodcastEntity::class,
            parentColumns = ["id"],
            childColumns = ["podcastId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index("podcastId"), Index(value = ["audioUri"], unique = true),
        Index("title"), Index("publicationDate"), Index("isFavorite"), Index("isPlayed")
    ]
)
data class EpisodeEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val podcastId: Long,
    val title: String,
    val description: String? = null,
    val audioUri: String,
    val fileName: String,
    val fileSize: Long,
    val durationMs: Long,
    val coverUri: String? = null,
    val publicationDate: Long? = null,
    val seasonNumber: Int? = null,
    val episodeNumber: Int? = null,
    val codec: String? = null,
    val bitrate: Int? = null,
    val addedAt: Long = System.currentTimeMillis(),
    val modifiedAt: Long = System.currentTimeMillis(),
    // Last-modified timestamp of the underlying file, used by the scanner to
    // decide whether metadata needs to be re-extracted without re-reading every file.
    val lastModifiedFile: Long = 0,
    val isFavorite: Boolean = false,
    val isPlayed: Boolean = false,
    // True when a previously-scanned file can no longer be found (deleted/moved out).
    // We never drop the row automatically so bookmarks/progress/favorites survive.
    val isMissing: Boolean = false,
    val userTitle: String? = null,
    val userAuthor: String? = null,
    val userCoverUri: String? = null
) {
    val displayTitle: String get() = userTitle ?: title
    val displayAuthor: String? get() = userAuthor
    val displayCoverUri: String? get() = userCoverUri ?: coverUri
}
