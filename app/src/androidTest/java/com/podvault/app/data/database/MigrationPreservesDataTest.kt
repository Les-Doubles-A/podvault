package com.podvault.app.data.database

import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MigrationPreservesDataTest {
    @Test
    fun migrationsPreservePodcastsEpisodesBookmarksAndPositions() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val config = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name("migration-preserves-data-test.db")
            .callback(object : SupportSQLiteOpenHelper.Callback(1) {
                override fun onCreate(db: SupportSQLiteDatabase) = createVersionOneSchema(db)
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
            })
            .build()
        val helper = FrameworkSQLiteOpenHelperFactory().create(config)
        try {
            val db = helper.writableDatabase
            db.execSQL("INSERT INTO podcasts VALUES(1,'Émission','Auteur',NULL,NULL,'content://dossier',NULL,10,20)")
            db.execSQL("INSERT INTO episodes VALUES(1,1,'Épisode',NULL,'content://audio/1','episode.mp3',100,2000,NULL,NULL,NULL,NULL,NULL,NULL,10,20,30,0,0,0)")
            db.execSQL("INSERT INTO bookmarks VALUES(1,1,500,'Repère','Note',10,20)")
            db.execSQL("INSERT INTO playback_states VALUES(1,700,30,2,0)")

            AppDatabase.MIGRATION_1_2.migrate(db)
            AppDatabase.MIGRATION_2_3.migrate(db)
            AppDatabase.MIGRATION_3_4.migrate(db)
            AppDatabase.MIGRATION_4_5.migrate(db)
            AppDatabase.MIGRATION_5_6.migrate(db)

            assertEquals(1, db.query("SELECT COUNT(*) FROM podcasts").use { it.moveToFirst(); it.getInt(0) })
            assertEquals(1, db.query("SELECT COUNT(*) FROM episodes").use { it.moveToFirst(); it.getInt(0) })
            assertEquals("", db.query("SELECT groupKey FROM podcasts WHERE id=1").use { it.moveToFirst(); it.getString(0) })
            assertEquals(700L, db.query("SELECT positionMs FROM playback_states WHERE episodeId=1").use { it.moveToFirst(); it.getLong(0) })
            assertEquals(0L, db.query("SELECT totalListenedMs FROM playback_states WHERE episodeId=1").use { it.moveToFirst(); it.getLong(0) })
            assertEquals(1, db.query("SELECT COUNT(*) FROM bookmarks").use { it.moveToFirst(); it.getInt(0) })
            assertEquals(0, db.query("SELECT COUNT(*) FROM listening_days").use { it.moveToFirst(); it.getInt(0) })
            assertEquals(null, db.query("SELECT userTitle FROM episodes WHERE id=1").use { it.moveToFirst(); it.getString(0) })
        } finally {
            helper.close()
            context.deleteDatabase("migration-preserves-data-test.db")
        }
    }

    private fun createVersionOneSchema(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE podcasts (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, title TEXT NOT NULL, author TEXT, description TEXT, coverUri TEXT, folderUri TEXT NOT NULL, category TEXT, createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE episodes (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, podcastId INTEGER NOT NULL, title TEXT NOT NULL, description TEXT, audioUri TEXT NOT NULL, fileName TEXT NOT NULL, fileSize INTEGER NOT NULL, durationMs INTEGER NOT NULL, coverUri TEXT, publicationDate INTEGER, seasonNumber INTEGER, episodeNumber INTEGER, codec TEXT, bitrate INTEGER, addedAt INTEGER NOT NULL, modifiedAt INTEGER NOT NULL, lastModifiedFile INTEGER NOT NULL, isFavorite INTEGER NOT NULL, isPlayed INTEGER NOT NULL, isMissing INTEGER NOT NULL, FOREIGN KEY(podcastId) REFERENCES podcasts(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
        db.execSQL("CREATE UNIQUE INDEX index_episodes_audioUri ON episodes(audioUri)")
        db.execSQL("CREATE INDEX index_episodes_podcastId ON episodes(podcastId)")
        db.execSQL("CREATE TABLE bookmarks (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, episodeId INTEGER NOT NULL, positionMs INTEGER NOT NULL, title TEXT, note TEXT, createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL, FOREIGN KEY(episodeId) REFERENCES episodes(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
        db.execSQL("CREATE INDEX index_bookmarks_episodeId ON bookmarks(episodeId)")
        db.execSQL("CREATE TABLE playback_states (episodeId INTEGER NOT NULL PRIMARY KEY, positionMs INTEGER NOT NULL, lastPlayedAt INTEGER NOT NULL, playCount INTEGER NOT NULL, completed INTEGER NOT NULL)")
    }
}
