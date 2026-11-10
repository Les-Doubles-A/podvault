package com.podvault.app.ui.search

import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.podvault.app.R
import com.podvault.app.ui.components.EpisodeRow
import com.podvault.app.ui.components.formatDuration
import com.podvault.app.ui.podVaultViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(onEpisodeClick: (Long) -> Unit, onPodcastClick: (Long) -> Unit, onBookmarkClick: (Long, Long) -> Unit) {
    val viewModel = podVaultViewModel { SearchViewModel(it) }
    val query by viewModel.query.collectAsStateWithLifecycle()
    val results by viewModel.results.collectAsStateWithLifecycle()

    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.nav_search)) }) }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().padding(horizontal = 16.dp)) {
            OutlinedTextField(
                value = query,
                onValueChange = { viewModel.setQuery(it) },
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                placeholder = { Text(stringResource(R.string.search_hint)) },
                singleLine = true
            )
            Spacer(Modifier.height(12.dp))

            LazyColumn {
                if (results.podcasts.isNotEmpty()) {
                    item { Text(stringResource(R.string.podcasts), style = MaterialTheme.typography.titleMedium) }
                    items(results.podcasts, key = { "p_${it.id}" }) { podcast ->
                        Text(
                            podcast.displayTitle,
                            modifier = Modifier.fillMaxWidth()
                                .clickable { onPodcastClick(podcast.id) }
                                .padding(vertical = 8.dp)
                        )
                    }
                }
                if (results.episodes.isNotEmpty()) {
                    item { Text(stringResource(R.string.episodes_section), style = MaterialTheme.typography.titleMedium) }
                    items(results.episodes, key = { "e_${it.id}" }) { episode ->
                        EpisodeRow(
                            episodeId = episode.id,
                            title = episode.displayTitle,
                            coverUri = episode.displayCoverUri,
                            durationMs = episode.durationMs,
                            progressFraction = null,
                            isFavorite = episode.isFavorite,
                            isMissing = episode.isMissing,
                            onClick = { onEpisodeClick(episode.id) },
                            onToggleFavorite = { viewModel.toggleFavorite(episode) },
                            onQueue = { mode -> viewModel.addToQueue(episode, mode) }
                        )
                    }
                }
                if (results.bookmarks.isNotEmpty()) {
                    item { Text(stringResource(R.string.bookmarks_section), style = MaterialTheme.typography.titleMedium) }
                    items(results.bookmarks, key = { "b_${it.id}" }) { bookmark ->
                        Text(
                            "${formatDuration(bookmark.positionMs)} — ${bookmark.title ?: bookmark.note ?: ""}",
                            modifier = Modifier.fillMaxWidth().clickable {
                                onBookmarkClick(bookmark.episodeId, bookmark.positionMs)
                            }.padding(vertical = 8.dp)
                        )
                    }
                }
            }
        }
    }
}
