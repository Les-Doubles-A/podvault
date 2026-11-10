package com.podvault.app.data.repository

import android.content.Context
import androidx.room.withTransaction
import com.podvault.app.data.database.AppDatabase
import com.podvault.app.data.database.entities.BookmarkEntity
import com.podvault.app.data.database.entities.PlaybackStateEntity
import java.time.LocalDate
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Handles bookmarks (points mémoire) and the exact-resume playback state.
 * These are deliberately kept as two separate concepts (spec section 29):
 * playback state is a single "where you left off" marker per episode,
 * bookmarks are an unlimited set of user-named markers.
 */
class BookmarkRepository(
    context: Context,
    private val db: AppDatabase = AppDatabase.getInstance(context)
) {
    private val playbackSaveMutex = Mutex()
    fun bookmarksFor(episodeId: Long): Flow<List<BookmarkEntity>> =
        db.bookmarkDao().observeForEpisode(episodeId)

    fun allBookmarks(): Flow<List<BookmarkEntity>> = db.bookmarkDao().observeAll()

    fun recentBookmarks(limit: Int = 10): Flow<List<BookmarkEntity>> =
        db.bookmarkDao().observeRecent(limit)

    fun searchBookmarks(query: String): Flow<List<BookmarkEntity>> =
        db.bookmarkDao().search(query)

    /** Instant-save a bookmark at the current position; title/note can be added after. */
    suspend fun quickAddBookmark(episodeId: Long, positionMs: Long): Long =
        db.bookmarkDao().insert(BookmarkEntity(episodeId = episodeId, positionMs = positionMs))

    suspend fun updateBookmark(bookmark: BookmarkEntity) =
        db.bookmarkDao().update(bookmark.copy(updatedAt = System.currentTimeMillis()))

    suspend fun deleteBookmark(bookmark: BookmarkEntity) =
        db.bookmarkDao().delete(bookmark)

    suspend fun savePlaybackPosition(
        episodeId: Long,
        positionMs: Long,
        completed: Boolean = false,
        listenedDeltaMs: Long = 0L
    ) {
        playbackSaveMutex.withLock {
            db.withTransaction {
                val dao = db.playbackStateDao()
                val previous = dao.getForEpisode(episodeId)
                dao.upsert(PlaybackStateEntity(
                    episodeId = episodeId,
                    positionMs = positionMs,
                    lastPlayedAt = maxOf(System.currentTimeMillis(), (previous?.lastPlayedAt ?: 0L) + 1L),
                    playCount = (previous?.playCount ?: 0) + if (completed && previous?.completed != true) 1 else 0,
                    completed = completed,
                    totalListenedMs = (previous?.totalListenedMs ?: 0L) + listenedDeltaMs.coerceAtLeast(0L)
                ))
                if (listenedDeltaMs > 0L) db.listeningDayDao().addListening(LocalDate.now().toString(), listenedDeltaMs)
            }
        }
    }

    suspend fun getPlaybackPosition(episodeId: Long): PlaybackStateEntity? =
        db.playbackStateDao().getForEpisode(episodeId)
}
