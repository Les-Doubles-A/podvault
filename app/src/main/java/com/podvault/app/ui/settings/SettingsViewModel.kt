package com.podvault.app.ui.settings

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.podvault.app.PodVaultApplication
import com.podvault.app.data.repository.LibraryBackupRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

data class SettingsUiState(
    val watchedFolders: List<String> = emptyList(),
    val episodeCount: Int = 0,
    val totalSizeBytes: Long = 0L,
    val defaultSpeed: Float = 1.0f,
    val rewindSeconds: Int = 15,
    val forwardSeconds: Int = 30,
    val smartRewind: Boolean = true
)

class SettingsViewModel(app: PodVaultApplication) : ViewModel() {
    private val prefs = app.preferencesManager
    private val library = app.libraryRepository
    private val contentResolver = app.contentResolver
    private val backup = LibraryBackupRepository(app)
    private val _backupMessage = MutableStateFlow<String?>(null)
    val backupMessage = _backupMessage.asStateFlow()

    private class PlaybackPreferences(val speed: Float, val rewind: Int, val forward: Int, val smartRewind: Boolean)

    private val playbackPreferences = combine(
        prefs.defaultSpeed, prefs.rewindSeconds, prefs.forwardSeconds, prefs.smartRewind
    ) { speed, rewind, forward, smart -> PlaybackPreferences(speed, rewind, forward, smart) }

    val uiState = combine(
        prefs.watchedFolders,
        library.totalEpisodeCount(),
        library.totalLibrarySize(),
        playbackPreferences
    ) { folders, count, size, playback ->
        SettingsUiState(
            watchedFolders = folders.toList(),
            episodeCount = count,
            totalSizeBytes = size,
            defaultSpeed = playback.speed,
            rewindSeconds = playback.rewind,
            forwardSeconds = playback.forward,
            smartRewind = playback.smartRewind
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SettingsUiState())

    val scanState = library.scanState
    val foldersWithoutPersistedAccess = prefs.watchedFolders.map { folders ->
        val grants = contentResolver.persistedUriPermissions.filter { it.isReadPermission }
            .map { it.uri.toString() }.toSet()
        folders.filterNot { it in grants }.toSet()
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())
    val darkThemeOverride = prefs.darkThemeOverride
    val dynamicColors = prefs.dynamicColors

    /** Called after the SAF folder picker returns a tree Uri. Persists access across
     *  reboots (spec section 65: "conserver les permissions persistantes") and scans it. */
    fun addFolder(uri: Uri) {
        try {
            contentResolver.takePersistableUriPermission(
                uri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (e: SecurityException) {
            // Some providers don't support persistable permissions; the folder will
            // still work for this session, it just won't survive a reboot.
        }
        viewModelScope.launch { library.addFolderAndScan(uri) }
    }

    fun removeFolder(uri: String) {
        runCatching {
            contentResolver.releasePersistableUriPermission(
                Uri.parse(uri), android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
        viewModelScope.launch { prefs.removeWatchedFolder(uri) }
    }

    fun rescan() = viewModelScope.launch { library.rescanLibrary() }

    fun setDefaultSpeed(speed: Float) = viewModelScope.launch { prefs.setDefaultSpeed(speed) }
    fun setDarkThemeOverride(enabled: Boolean?) = viewModelScope.launch { prefs.setDarkThemeOverride(enabled) }
    fun setDynamicColors(enabled: Boolean) = viewModelScope.launch { prefs.setDynamicColors(enabled) }
    fun setSmartRewind(enabled: Boolean) = viewModelScope.launch { prefs.setSmartRewind(enabled) }

    fun setSkipIntervals(rewind: Int, forward: Int) =
        viewModelScope.launch { prefs.setSkipIntervals(rewind, forward) }

    fun exportBackup(uri: Uri) = viewModelScope.launch {
        runCatching { backup.export(uri) }
            .onSuccess { _backupMessage.value = "Sauvegarde exportée." }
            .onFailure { _backupMessage.value = it.message ?: "Échec de l'export." }
    }

    fun importBackup(uri: Uri) = viewModelScope.launch {
        runCatching { backup.import(uri) }
            .onSuccess { summary -> _backupMessage.value = "Import terminé : ${summary.episodes} épisodes et ${summary.bookmarks} points mémoire." }
            .onFailure { _backupMessage.value = it.message ?: "Échec de l'import." }
    }
}
