package com.podvault.app

import android.app.Application
import com.podvault.app.data.datastore.PreferencesManager
import com.podvault.app.data.repository.BookmarkRepository
import com.podvault.app.data.repository.LibraryRepository
import com.podvault.app.data.repository.PlaybackController
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.podvault.app.service.LibraryScanWorker
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * No dependency-injection framework is wired up yet (Hilt would be the natural next
 * step) — this is a small manual service locator so every screen shares the same
 * Room instance instead of opening several database connections.
 */
class PodVaultApplication : Application() {

    /** Persiste les dernières données même si Android détruit le service de lecture. */
    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    lateinit var libraryRepository: LibraryRepository
        private set
    lateinit var bookmarkRepository: BookmarkRepository
        private set
    lateinit var preferencesManager: PreferencesManager
        private set

    /** Single shared connection to PlaybackService so the mini-player, full player,
     *  and lock-screen controls all reflect the same playback state. */
    lateinit var playbackController: PlaybackController
        private set

    override fun onCreate() {
        super.onCreate()
        preferencesManager = PreferencesManager(this)
        libraryRepository = LibraryRepository(this)
        bookmarkRepository = BookmarkRepository(this)
        playbackController = PlaybackController(this)
        playbackController.connect()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            LIBRARY_SCAN_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<LibraryScanWorker>(12, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
                .build()
        )
    }

    companion object {
        private const val LIBRARY_SCAN_WORK_NAME = "analyse-periodique-bibliotheque"
    }
}
