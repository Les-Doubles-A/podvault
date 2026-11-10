package com.podvault.app.ui.bookmarks

import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.podvault.app.R
import com.podvault.app.ui.components.Cover
import com.podvault.app.ui.components.formatDuration
import com.podvault.app.ui.podVaultViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookmarksScreen(onOpenEpisodeAt: (episodeId: Long, positionMs: Long) -> Unit) {
    val viewModel = podVaultViewModel { BookmarksViewModel(it) }
    val items by viewModel.items.collectAsStateWithLifecycle()

    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.nav_bookmarks)) }) }) { padding ->
        if (items.isEmpty()) {
            Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.empty_bookmarks))
            }
            return@Scaffold
        }
        LazyColumn(Modifier.padding(padding).fillMaxSize(), contentPadding = PaddingValues(16.dp)) {
            items(items, key = { it.bookmark.id }) { item ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { item.episode?.let { onOpenEpisodeAt(it.id, item.bookmark.positionMs) } }
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Cover(item.episode?.coverUri, modifier = Modifier.size(48.dp))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(item.podcastTitle, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(item.episode?.displayTitle ?: "", style = MaterialTheme.typography.bodyLarge, maxLines = 1)
                        Text(
                            "${formatDuration(item.bookmark.positionMs)}" + (item.bookmark.title?.let { " — $it" } ?: ""),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }
        }
    }
}
