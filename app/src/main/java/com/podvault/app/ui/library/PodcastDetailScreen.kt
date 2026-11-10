package com.podvault.app.ui.library

import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.net.Uri
import com.podvault.app.data.database.entities.PodcastEntity
import com.podvault.app.R
import com.podvault.app.ui.components.Cover
import com.podvault.app.ui.components.EpisodeRow
import com.podvault.app.ui.components.LocalNowPlaying
import com.podvault.app.ui.components.NowPlayingIndicator
import com.podvault.app.ui.components.formatDuration
import com.podvault.app.ui.podVaultViewModel

private enum class EpisodeFilter(val labelRes: Int) {
    ALL(R.string.filter_all),
    TO_LISTEN(R.string.filter_to_listen),
    FAVORITES(R.string.filter_favorites),
    COMPLETED(R.string.filter_completed)
}

private enum class EpisodeSort(val labelRes: Int) {
    RECENT(R.string.sort_recent),
    RECENTLY_ADDED(R.string.sort_recently_added),
    TITLE(R.string.sort_title),
    DURATION(R.string.sort_duration)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PodcastDetailScreen(podcastId: Long, onBack: () -> Unit, onEpisodeClick: (Long) -> Unit) {
    val viewModel = podVaultViewModel { LibraryViewModel(it) }
    // Les flux Room sont mémorisés : sinon chaque recomposition créait un nouveau Flow
    // et relançait la collecte (clignotement de la liste, requêtes en boucle).
    val libraryItems by remember(podcastId) { viewModel.libraryItemsFor(podcastId) }
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val podcast by remember(podcastId) { viewModel.podcastFor(podcastId) }
        .collectAsStateWithLifecycle(initialValue = null)
    var filter by remember(podcastId) { mutableStateOf(EpisodeFilter.ALL) }
    var sort by remember(podcastId) { mutableStateOf(EpisodeSort.RECENT) }
    val episodes = remember(libraryItems, filter, sort) {
        val filtered = libraryItems.filter { item ->
            when (filter) {
                EpisodeFilter.TO_LISTEN -> !item.episode.isPlayed && item.completed != true
                EpisodeFilter.FAVORITES -> item.episode.isFavorite
                EpisodeFilter.COMPLETED -> item.completed == true || item.episode.isPlayed
                EpisodeFilter.ALL -> true
            }
        }
        when (sort) {
            EpisodeSort.TITLE -> filtered.sortedBy { it.episode.displayTitle.lowercase() }
            EpisodeSort.DURATION -> filtered.sortedByDescending { it.episode.durationMs }
            EpisodeSort.RECENTLY_ADDED -> filtered.sortedByDescending { it.episode.addedAt }
            EpisodeSort.RECENT -> filtered.sortedByDescending { it.episode.publicationDate ?: it.episode.addedAt }
        }
    }
    val totalDurationMs = remember(libraryItems) { libraryItems.sumOf { it.episode.durationMs } }
    val isPodcastPlaying = LocalNowPlaying.current.podcastId == podcastId
    var editingMetadata by remember { mutableStateOf(false) }
    var selectedCoverUri by remember(podcastId) { mutableStateOf<String?>(null) }
    val coverPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { selectedCoverUri = it.toString() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(podcast?.displayTitle ?: "") },
                actions = {
                    IconButton(onClick = { selectedCoverUri = null; editingMetadata = true }, enabled = podcast != null) {
                        Icon(Icons.Filled.Edit, contentDescription = stringResource(R.string.edit_podcast_info))
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(16.dp)
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Cover(podcast?.displayCoverUri, modifier = Modifier.size(96.dp))
                    Spacer(Modifier.width(16.dp))
                    Column {
                        podcast?.displayAuthor?.let {
                            Text(it, style = MaterialTheme.typography.bodyMedium)
                        }
                        Text(
                            "${libraryItems.size} épisodes",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            formatDuration(totalDurationMs),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (isPodcastPlaying) {
                            Spacer(Modifier.height(4.dp))
                            NowPlayingIndicator(isPlaying = LocalNowPlaying.current.isPlaying)
                        }
                    }
                }
                Spacer(Modifier.height(20.dp))
                Text(stringResource(R.string.episodes), style = MaterialTheme.typography.titleLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterMenu(
                        label = stringResource(R.string.filter_label, stringResource(filter.labelRes)),
                        options = EpisodeFilter.entries.map { it to stringResource(it.labelRes) }
                    ) { filter = it }
                    FilterMenu(
                        label = stringResource(R.string.sort_label, stringResource(sort.labelRes)),
                        options = EpisodeSort.entries.map { it to stringResource(it.labelRes) }
                    ) { sort = it }
                }
            }
            items(episodes, key = { it.episode.id }) { item ->
                val episode = item.episode
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
    }

    if (editingMetadata) podcast?.let { currentPodcast ->
        PodcastMetadataDialog(
            podcast = currentPodcast,
            coverUri = selectedCoverUri,
            onChooseCover = { coverPicker.launch(arrayOf("image/*")) },
            onDismiss = { editingMetadata = false },
            onSave = { title, author, cover ->
                val effectiveCover = cover ?: currentPodcast.userCoverUri
                effectiveCover?.let { viewModel.persistCoverPermission(Uri.parse(it)) }
                viewModel.savePodcastMetadata(currentPodcast, title, author, effectiveCover)
                editingMetadata = false
            }
        )
    }
}

@Composable
private fun PodcastMetadataDialog(
    podcast: PodcastEntity,
    coverUri: String?,
    onChooseCover: () -> Unit,
    onDismiss: () -> Unit,
    onSave: (String?, String?, String?) -> Unit
) {
    var title by remember(podcast.id) { mutableStateOf(podcast.userTitle ?: podcast.title) }
    var author by remember(podcast.id) { mutableStateOf(podcast.userAuthor ?: podcast.author.orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.podcast_info)) },
        text = {
            Column {
                OutlinedTextField(title, { title = it.take(512) }, label = { Text(stringResource(R.string.field_title)) }, singleLine = true)
                OutlinedTextField(author, { author = it.take(512) }, label = { Text(stringResource(R.string.field_author)) }, singleLine = true)
                TextButton(onClick = onChooseCover) { Text(if (coverUri == null) stringResource(R.string.choose_cover) else stringResource(R.string.cover_selected)) }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(title.ifBlank { null }, author.ifBlank { null }, coverUri) }) { Text(stringResource(R.string.save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } }
    )
}

@Composable
private fun <T> FilterMenu(label: String, options: List<Pair<T, String>>, onSelect: (T) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        AssistChip(onClick = { expanded = true }, label = { Text(label) })
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { (value, text) ->
                DropdownMenuItem(text = { Text(text) }, onClick = {
                    onSelect(value)
                    expanded = false
                })
            }
        }
    }
}
