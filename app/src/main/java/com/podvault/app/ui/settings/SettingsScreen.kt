package com.podvault.app.ui.settings

import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.contract.ActivityResultContracts.CreateDocument
import androidx.activity.result.contract.ActivityResultContracts.OpenDocument
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import kotlin.math.roundToInt

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
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Bibliothèque et dossiers : la première chose à configurer.
            item(key = "library") {
                SettingsSection(stringResource(R.string.settings_library)) {
                    Text(
                        stringResource(R.string.episode_count, state.episodeCount) + " · " +
                            stringResource(R.string.storage_audio, formatBytes(state.totalSizeBytes)),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(R.string.folders_watched), style = MaterialTheme.typography.titleSmall)
                    state.watchedFolders.forEach { folderUri ->
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Filled.Folder, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
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
                    ScanStatus(scanState)
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { folderPicker.launch(null) }, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Filled.Add, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.add_folder), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        if (state.watchedFolders.isNotEmpty()) {
                            Button(
                                onClick = { viewModel.rescan() },
                                enabled = scanState !is ScanState.Running,
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Filled.Refresh, contentDescription = null)
                                Spacer(Modifier.width(6.dp))
                                Text(stringResource(R.string.rescan_now), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }

            item(key = "playback") {
                SettingsSection(stringResource(R.string.settings_playback)) {
                    // Les curseurs n'enregistrent qu'au relâchement : avant, chaque pixel de glissement
                    // déclenchait une écriture DataStore et la vitesse s'affichait « 1.2142857 ».
                    var speed by remember(state.defaultSpeed) { mutableFloatStateOf(state.defaultSpeed) }
                    var rewind by remember(state.rewindSeconds) { mutableFloatStateOf(state.rewindSeconds.toFloat()) }
                    var forward by remember(state.forwardSeconds) { mutableFloatStateOf(state.forwardSeconds.toFloat()) }

                    SliderSetting(
                        label = stringResource(R.string.default_speed, formatSpeed(speed)),
                        value = speed,
                        onValueChange = { speed = (it * 4).roundToInt() / 4f },
                        onValueChangeFinished = { viewModel.setDefaultSpeed(speed) },
                        valueRange = 0.5f..2.5f,
                        steps = 7
                    )
                    SliderSetting(
                        label = stringResource(R.string.rewind_interval, rewind.roundToInt()),
                        value = rewind,
                        onValueChange = { rewind = it },
                        onValueChangeFinished = { viewModel.setSkipIntervals(rewind.roundToInt(), forward.roundToInt()) },
                        valueRange = 5f..60f,
                        steps = 10
                    )
                    SliderSetting(
                        label = stringResource(R.string.forward_interval, forward.roundToInt()),
                        value = forward,
                        onValueChange = { forward = it },
                        onValueChangeFinished = { viewModel.setSkipIntervals(rewind.roundToInt(), forward.roundToInt()) },
                        valueRange = 5f..60f,
                        steps = 10
                    )
                    SwitchSetting(
                        title = stringResource(R.string.smart_rewind),
                        description = stringResource(R.string.smart_rewind_description),
                        checked = state.smartRewind,
                        onCheckedChange = { viewModel.setSmartRewind(it) }
                    )
                }
            }

            item(key = "appearance") {
                SettingsSection(stringResource(R.string.appearance)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(
                            null to stringResource(R.string.theme_system),
                            false to stringResource(R.string.theme_light),
                            true to stringResource(R.string.theme_dark)
                        ).forEach { (value, label) ->
                            FilterChip(
                                selected = darkThemeOverride == value,
                                onClick = { viewModel.setDarkThemeOverride(value) },
                                label = { Text(label) }
                            )
                        }
                    }
                    SwitchSetting(
                        title = stringResource(R.string.dynamic_colors),
                        description = null,
                        checked = dynamicColors,
                        onCheckedChange = { viewModel.setDynamicColors(it) }
                    )
                }
            }

            item(key = "backup") {
                SettingsSection(stringResource(R.string.manual_backup)) {
                    Text(
                        stringResource(R.string.backup_description),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { exportPicker.launch("podvault-sauvegarde.json") }, modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.export))
                        }
                        OutlinedButton(
                            onClick = { importPicker.launch(arrayOf("application/json", "text/json", "text/plain")) },
                            modifier = Modifier.weight(1f)
                        ) { Text(stringResource(R.string.import_backup)) }
                    }
                    backupMessage?.let {
                        Spacer(Modifier.height(4.dp))
                        Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }

            item(key = "stats") {
                Card(
                    onClick = onOpenStatistics,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
                ) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.BarChart, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(12.dp))
                        Text(stringResource(R.string.stats_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
                    }
                }
            }

            item(key = "privacy") {
                Text(
                    stringResource(R.string.privacy_local),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp)
                )
            }
        }
    }
}

@Composable
private fun SettingsSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(4.dp))
            content()
        }
    }
}

@Composable
private fun SliderSetting(
    label: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int
) {
    Text(label, style = MaterialTheme.typography.bodyLarge)
    Slider(
        value = value,
        onValueChange = onValueChange,
        onValueChangeFinished = onValueChangeFinished,
        valueRange = valueRange,
        steps = steps
    )
}

@Composable
private fun SwitchSetting(title: String, description: String?, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onCheckedChange(!checked) }.padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (description != null) {
                Text(description, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun ScanStatus(scanState: ScanState) {
    when (scanState) {
        is ScanState.Running -> {
            Spacer(Modifier.height(4.dp))
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(
                stringResource(
                    R.string.folder_scan_progress,
                    scanState.progress.currentPodcast,
                    scanState.progress.currentFile,
                    scanState.progress.filesScanned
                ),
                style = MaterialTheme.typography.labelMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        is ScanState.Done -> {
            val report = scanState.report
            Text(
                stringResource(R.string.scan_summary, report.filesScanned, report.newEpisodes, report.removedEpisodes, report.updatedEpisodes),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (report.errors.isNotEmpty()) {
                Text(stringResource(R.string.scan_failed_elements, report.errors.size), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelMedium)
            }
        }
        is ScanState.Failed -> Text(scanState.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelMedium)
        ScanState.Idle -> Unit
    }
}

private fun formatSpeed(speed: Float): String =
    if (speed % 1f == 0f) speed.toInt().toString() else DecimalFormat("0.##").format(speed)

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
