package com.podvault.app.data.database.entities

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Index
import androidx.room.ColumnInfo

/**
 * A podcast "show" that groups together local episode files.
 * folderUri is the SAF tree/document URI of the folder this podcast was discovered in,
 * used to re-associate newly scanned files with the right show.
 */
@Entity(tableName = "podcasts", indices = [Index("folderUri"), Index("groupKey"), Index("title")])
data class PodcastEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val author: String? = null,
    val description: String? = null,
    val coverUri: String? = null,
    val folderUri: String,
    val category: String? = null,
    // Key used by the scanner to decide whether two files belong to the same show:
    // "album:<lowercased album tag>" when the files carry an album tag, otherwise
    // "folder:<folder uri>". Lets episodes sharing an album tag merge into one show
    // even across different folders (spec section 19).
    @ColumnInfo(defaultValue = "''") val groupKey: String = "",
    val userTitle: String? = null,
    val userAuthor: String? = null,
    val userCoverUri: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
) {
    val displayTitle: String get() = userTitle ?: title
    val displayAuthor: String? get() = userAuthor ?: author
    val displayCoverUri: String? get() = userCoverUri ?: coverUri
}
