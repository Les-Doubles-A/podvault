package com.podvault.app.data.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.podvault.app.data.database.dao.BookmarkDao
import com.podvault.app.data.database.dao.EpisodeDao
import com.podvault.app.data.database.dao.PlaybackStateDao
import com.podvault.app.data.database.dao.PodcastDao
import com.podvault.app.data.database.dao.ListeningDayDao
import com.podvault.app.data.database.entities.BookmarkEntity
import com.podvault.app.data.database.entities.EpisodeEntity
import com.podvault.app.data.database.entities.PlaybackStateEntity
import com.podvault.app.data.database.entities.PodcastEntity
import com.podvault.app.data.database.entities.ListeningDayEntity

@Database(
    entities = [
        PodcastEntity::class,
        EpisodeEntity::class,
        BookmarkEntity::class,
        PlaybackStateEntity::class,
        ListeningDayEntity::class
    ],
    version = 6,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun podcastDao(): PodcastDao
    abstract fun episodeDao(): EpisodeDao
    abstract fun bookmarkDao(): BookmarkDao
    abstract fun playbackStateDao(): PlaybackStateDao
    abstract fun listeningDayDao(): ListeningDayDao

    companion object {
        @Volatile private var instance: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "podvault.db"
                )
                    .addMigrations(MIGRATION_1_2)
                    .addMigrations(MIGRATION_2_3)
                    .addMigrations(MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)
                    .build().also { instance = it }
            }

        /** Ajoute la clé de regroupement sans toucher aux données existantes. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "ALTER TABLE podcasts ADD COLUMN groupKey TEXT NOT NULL DEFAULT ''"
                )
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("CREATE INDEX IF NOT EXISTS index_podcasts_folderUri ON podcasts(folderUri)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_podcasts_groupKey ON podcasts(groupKey)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_podcasts_title ON podcasts(title)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_episodes_title ON episodes(title)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_episodes_publicationDate ON episodes(publicationDate)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_episodes_isFavorite ON episodes(isFavorite)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_episodes_isPlayed ON episodes(isPlayed)")
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE playback_states ADD COLUMN totalListenedMs INTEGER NOT NULL DEFAULT 0")
            }
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("CREATE TABLE IF NOT EXISTS listening_days (day TEXT NOT NULL PRIMARY KEY, listenedMs INTEGER NOT NULL)")
            }
        }

        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE podcasts ADD COLUMN userTitle TEXT")
                database.execSQL("ALTER TABLE podcasts ADD COLUMN userAuthor TEXT")
                database.execSQL("ALTER TABLE podcasts ADD COLUMN userCoverUri TEXT")
                database.execSQL("ALTER TABLE episodes ADD COLUMN userTitle TEXT")
                database.execSQL("ALTER TABLE episodes ADD COLUMN userAuthor TEXT")
                database.execSQL("ALTER TABLE episodes ADD COLUMN userCoverUri TEXT")
            }
        }
    }
}
