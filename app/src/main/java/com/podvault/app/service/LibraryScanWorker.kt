package com.podvault.app.service

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.podvault.app.PodVaultApplication
import com.podvault.app.data.repository.ScanState

/** Analyse périodiquement les dossiers locaux sans contrainte réseau. */
class LibraryScanWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as? PodVaultApplication ?: return Result.failure()
        return try {
            app.libraryRepository.rescanLibrary()
            when (val state = app.libraryRepository.scanState.value) {
                is ScanState.Done -> if (state.report.errors.isEmpty()) Result.success() else Result.retry()
                is ScanState.Failed -> Result.retry()
                else -> Result.success()
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Result.retry()
        }
    }
}
