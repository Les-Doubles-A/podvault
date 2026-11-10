package com.podvault.app.ui.library

import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.podvault.app.R
import com.podvault.app.data.repository.ScanState
import com.podvault.app.ui.components.PodcastCard
import com.podvault.app.ui.podVaultViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(onPodcastClick: (Long) -> Unit, onDuplicatesClick: () -> Unit) {
    val viewModel = podVaultViewModel { LibraryViewModel(it) }
    val podcasts by viewModel.podcasts.collectAsStateWithLifecycle()
    val scanState by viewModel.scanState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.library_title)) },
                actions = {
                    IconButton(onClick = onDuplicatesClick) {
                        Icon(Icons.Filled.ContentCopy, contentDescription = stringResource(R.string.show_duplicates))
                    }
                    IconButton(onClick = { viewModel.rescan() }) {
                        Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.rescan))
                    }
                }
            )
        }
    ) { padding ->
        Column(Modifier.padding(padding)) {
            if (scanState is ScanState.Running) {
                val running = scanState as ScanState.Running
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Text(
                    stringResource(R.string.scan_progress, running.progress.currentPodcast, running.progress.currentFile, running.progress.filesScanned),
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )
            }

            if (podcasts.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.empty_podcasts))
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 156.dp),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    items(podcasts, key = { it.podcast.id }) { item ->
                        PodcastCard(
                            podcastId = item.podcast.id,
                            title = item.podcast.displayTitle,
                            coverUri = item.coverUri,
                            episodeCount = item.episodeCount,
                            onClick = { onPodcastClick(item.podcast.id) }
                        )
                    }
                }
            }
        }
    }
}
