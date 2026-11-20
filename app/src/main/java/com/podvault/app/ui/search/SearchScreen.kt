package com.podvault.app.ui.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.podvault.app.R
import com.podvault.app.ui.components.Cover
import com.podvault.app.ui.components.EpisodeRow
import com.podvault.app.ui.components.formatDuration
import com.podvault.app.ui.podVaultViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(onEpisodeClick: (Long) -> Unit, onPodcastClick: (Long) -> Unit, onBookmarkClick: (Long, Long) -> Unit) {
    val viewModel = podVaultViewModel { SearchViewModel(it) }
    val query by viewModel.query.collectAsStateWithLifecycle()
    val results by viewModel.results.collectAsStateWithLifecycle()
    val keyboard = LocalSoftwareKeyboardController.current
    val hasResults = results.podcasts.isNotEmpty() || results.episodes.isNotEmpty() || results.bookmarks.isNotEmpty()

    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.nav_search)) }) }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            OutlinedTextField(
                value = query,
                onValueChange = { viewModel.setQuery(it) },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(top = 8.dp),
                placeholder = { Text(stringResource(R.string.search_hint)) },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { viewModel.setQuery("") }) {
                            Icon(Icons.Filled.Close, contentDescription = "Effacer")
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(28.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() })
            )
            Spacer(Modifier.height(8.dp))

            when {
                query.isBlank() -> SearchMessage("Cherchez un podcast, un épisode, un auteur ou une note de point mémoire.")
                !hasResults -> SearchMessage("Aucun résultat pour « ${query.trim()} ».")
                else -> LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                    if (results.podcasts.isNotEmpty()) {
                        item(key = "h_podcasts") { SectionHeader(stringResource(R.string.podcasts), results.podcasts.size) }
                        items(results.podcasts, key = { "p_${it.id}" }) { podcast ->
                            ListItem(
                                headlineContent = { Text(podcast.displayTitle, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                supportingContent = podcast.displayAuthor?.let { author -> { Text(author, maxLines = 1, overflow = TextOverflow.Ellipsis) } },
                                leadingContent = { Cover(podcast.displayCoverUri, modifier = Modifier.size(48.dp), cornerRadius = 10.dp) },
                                modifier = Modifier.clickable { onPodcastClick(podcast.id) }
                            )
                        }
                    }
                    if (results.episodes.isNotEmpty()) {
                        item(key = "h_episodes") { SectionHeader(stringResource(R.string.episodes_section), results.episodes.size) }
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
                                onQueue = { mode -> viewModel.addToQueue(episode, mode) },
                                modifier = Modifier.padding(horizontal = 8.dp)
                            )
                        }
                    }
                    if (results.bookmarks.isNotEmpty()) {
                        item(key = "h_bookmarks") { SectionHeader(stringResource(R.string.bookmarks_section), results.bookmarks.size) }
                        items(results.bookmarks, key = { "b_${it.id}" }) { bookmark ->
                            ListItem(
                                headlineContent = {
                                    Text(
                                        bookmark.title?.takeIf { it.isNotBlank() } ?: "Point mémoire",
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                },
                                supportingContent = {
                                    val note = bookmark.note?.takeIf { it.isNotBlank() }
                                    Text(
                                        if (note != null) "${formatDuration(bookmark.positionMs)} · $note" else formatDuration(bookmark.positionMs),
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                },
                                leadingContent = { Icon(Icons.Filled.Bookmark, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                                modifier = Modifier.clickable { onBookmarkClick(bookmark.episodeId, bookmark.positionMs) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String, count: Int) {
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        Text(count.toString(), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SearchMessage(text: String) {
    Box(Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 48.dp), contentAlignment = Alignment.Center) {
        Text(
            text,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}
