package com.podvault.app.ui.settings

import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.contract.ActivityResultContracts.CreateDocument
import androidx.activity.result.contract.ActivityResultContracts.OpenDocument
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.core.net.toUri
import com.podvault.app.data.repository.ScanState
import com.podvault.app.ui.podVaultViewModel
import com.podvault.app.R
import java.text.DecimalFormat

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onOpenStatistics: () -> Unit) {
    val viewModel = podVaultViewModel { SettingsViewModel(it) }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val scanState by viewModel.scanState.collectAsStateWithLifecycle()
    val backupMessage by viewModel.backupMessage.collectAsStateWithLifecycle()
    val darkThemeOverride by viewModel.darkThemeOverride.collectAsStateWithLifecycle(initialValue = null)
    val dynamicColors by viewModel.dynamicColors.collectAsStateWithLifecycle(initialValue = false)
    val foldersWithoutPersistedAccess by viewModel.foldersWithoutPersistedAccess.collectAsStateWithLifecycle()

    val folderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri -> uri?.let { viewModel.addFolder(it) } }
    val exportPicker = rememberLauncherForActivityResult(CreateDocument("application/json")) { uri ->
        uri?.let(viewModel::exportBackup)
    }
    val importPicker = rememberLauncherForActivityResult(OpenDocument()) { uri ->
        uri?.let(viewModel::importBackup)
    }

    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.nav_settings)) }) }) { padding ->
        LazyColumn(
            Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(16.dp)
        ) {
            item {
                Text(stringResource(R.string.settings_library), style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.episode_count, state.episodeCount),
                    style = MaterialTheme.typography.bodyLarge
                )
                Text(
                    stringResource(R.string.storage_audio, formatBytes(state.totalSizeBytes)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(16.dp))

                Text(stringResource(R.string.appearance), style = MaterialTheme.typography.titleLarge)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = darkThemeOverride == null, onClick = { viewModel.setDarkThemeOverride(null) })
                    Text(stringResource(R.string.theme_system))
                    RadioButton(selected = darkThemeOverride == false, onClick = { viewModel.setDarkThemeOverride(false) })
                    Text(stringResource(R.string.theme_light))
                    RadioButton(selected = darkThemeOverride == true, onClick = { viewModel.setDarkThemeOverride(true) })
                    Text(stringResource(R.string.theme_dark))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.dynamic_colors), modifier = Modifier.weight(1f))
                    Switch(checked = dynamicColors, onCheckedChange = { viewModel.setDynamicColors(it) })
                }

                if (scanState is ScanState.Running) {
                    val running = scanState as ScanState.Running
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Spacer(Modifier.height(4.dp))
                    Text(
                        stringResource(R.string.folder_scan_progress, running.progress.currentPodcast, running.progress.currentFile, running.progress.filesScanned),
                        style = MaterialTheme.typography.labelMedium
                    )
                    Spacer(Modifier.height(12.dp))
                } else if (scanState is ScanState.Done) {
                    val report = (scanState as ScanState.Done).report
                    Text(
                        stringResource(R.string.scan_summary, report.filesScanned, report.newEpisodes, report.removedEpisodes, report.updatedEpisodes),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(Modifier.height(12.dp))
                    if (report.errors.isNotEmpty()) {
                        Text(
                            stringResource(R.string.scan_failed_elements, report.errors.size),
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                } else if (scanState is ScanState.Failed) {
                    Text((scanState as ScanState.Failed).message, color = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.height(12.dp))
                }

                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.folders_watched), style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
            }

            items(state.watchedFolders, key = { it }) { folderUri ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Filled.Folder, contentDescription = null)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(displayNameFor(folderUri), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (folderUri in foldersWithoutPersistedAccess) {
                            Text(stringResource(R.string.access_renew), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    IconButton(onClick = { viewModel.removeFolder(folderUri) }) {
                        Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.remove_folder))
                    }
                }
            }

            item {
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { folderPicker.launch(null) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Filled.Add, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.add_folder))
                }

                Spacer(Modifier.height(8.dp))
                if (state.watchedFolders.isNotEmpty()) {
                    Button(onClick = { viewModel.rescan() }, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.rescan_now))
                    }
                }

                Spacer(Modifier.height(32.dp))
                Text(stringResource(R.string.settings_playback), style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.default_speed, state.defaultSpeed.toString()),
                    style = MaterialTheme.typography.bodyLarge
                )
                Slider(
                    value = state.defaultSpeed,
                    onValueChange = { viewModel.setDefaultSpeed(it) },
                    valueRange = 0.5f..2.5f,
                    steps = 7
                )

                Text(stringResource(R.string.rewind_interval, state.rewindSeconds), style = MaterialTheme.typography.bodyMedium)
                Slider(
                    value = state.rewindSeconds.toFloat(),
                    onValueChange = { viewModel.setSkipIntervals(it.toInt(), state.forwardSeconds) },
                    valueRange = 5f..60f,
                    steps = 10
                )
                Text(stringResource(R.string.forward_interval, state.forwardSeconds), style = MaterialTheme.typography.bodyMedium)
                Slider(
                    value = state.forwardSeconds.toFloat(),
                    onValueChange = { viewModel.setSkipIntervals(state.rewindSeconds, it.toInt()) },
                    valueRange = 5f..60f,
                    steps = 10
                )

                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.smart_rewind), style = MaterialTheme.typography.bodyLarge)
                        Text(
                            stringResource(R.string.smart_rewind_description),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(checked = state.smartRewind, onCheckedChange = { viewModel.setSmartRewind(it) })
                }

                Spacer(Modifier.height(24.dp))
                Text(stringResource(R.string.manual_backup), style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.backup_description), style = MaterialTheme.typography.bodyMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { exportPicker.launch("podvault-sauvegarde.json") }) { Text(stringResource(R.string.export)) }
                    OutlinedButton(onClick = { importPicker.launch(arrayOf("application/json", "text/json", "text/plain")) }) { Text(stringResource(R.string.import_backup)) }
                }
                backupMessage?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                Spacer(Modifier.height(24.dp))
                OutlinedButton(onClick = onOpenStatistics, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.stats_title))
                }
                Spacer(Modifier.height(24.dp))
                Text(
                    stringResource(R.string.privacy_local),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(48.dp))
            }
        }
    }
}

@Composable
private fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return stringResource(R.string.size_zero)
    val gb = bytes / (1024.0 * 1024.0 * 1024.0)
    return if (gb >= 1.0) {
        stringResource(R.string.size_gb, DecimalFormat("#.#").format(gb))
    } else {
        val mb = bytes / (1024.0 * 1024.0)
        stringResource(R.string.size_mb, DecimalFormat("#.#").format(mb))
    }
}

private fun displayNameFor(folderUriString: String): String {
    return try {
        val uri = folderUriString.toUri()
        uri.lastPathSegment?.substringAfterLast(':') ?: folderUriString
    } catch (e: Exception) {
        folderUriString
    }
}
