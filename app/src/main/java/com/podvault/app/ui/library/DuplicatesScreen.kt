package com.podvault.app.ui.library

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.podvault.app.R
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.podvault.app.ui.components.EpisodeRow
import com.podvault.app.ui.podVaultViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DuplicatesScreen(onBack: () -> Unit, onEpisodeClick: (Long) -> Unit) {
    val viewModel = podVaultViewModel { LibraryViewModel(it) }
    val candidates by viewModel.duplicateCandidates.collectAsStateWithLifecycle(initialValue = emptyList())
    val groups = candidates.groupBy { it.fileName.lowercase() to it.fileSize }.values

    Scaffold(topBar = {
        TopAppBar(title = { Text(stringResource(R.string.possible_duplicates)) }, navigationIcon = {
            IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, contentDescription = stringResource(R.string.back)) }
        })
    }) { padding ->
        if (groups.isEmpty()) {
            Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.no_duplicates))
            }
        } else {
            LazyColumn(Modifier.padding(padding).fillMaxSize(), contentPadding = PaddingValues(16.dp)) {
                item {
                    Text(stringResource(R.string.duplicates_hint), style = MaterialTheme.typography.bodyMedium)
                }
                groups.forEachIndexed { groupIndex, group ->
                    item(key = "group_$groupIndex") {
                        Text(stringResource(R.string.similar_files, group.size), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 16.dp))
                    }
                    items(group, key = { it.id }) { episode ->
                        EpisodeRow(
                            episodeId = episode.id,
                            title = episode.displayTitle,
                            coverUri = episode.displayCoverUri,
                            durationMs = episode.durationMs,
                            progressFraction = null,
                            isFavorite = episode.isFavorite,
                            isMissing = episode.isMissing,
                            onClick = { onEpisodeClick(episode.id) }
                        )
                    }
                }
            }
        }
    }
}
