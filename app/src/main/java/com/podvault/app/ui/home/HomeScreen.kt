package com.podvault.app.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.podvault.app.R
import com.podvault.app.ui.components.Cover
import com.podvault.app.ui.components.EpisodeCard
import com.podvault.app.ui.components.EpisodeRow
import com.podvault.app.ui.components.LocalNowPlaying
import com.podvault.app.ui.components.NowPlayingIndicator
import com.podvault.app.ui.components.formatRemaining
import com.podvault.app.ui.podVaultViewModel
import java.time.LocalTime

@Composable
fun HomeScreen(onEpisodeClick: (Long) -> Unit, onGoToSettings: () -> Unit, onOpenQueue: () -> Unit) {
    val viewModel = podVaultViewModel { HomeViewModel(it) }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val queuedCount = LocalNowPlaying.current.queuedIds.size

    when {
        state.isLoading -> Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
        }
        state.isLibraryEmpty -> EmptyLibraryState(onGoToSettings)
        else -> LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 24.dp)
        ) {
            item(key = "header") { HomeHeader(queuedCount, onOpenQueue) }

            state.continueListening.firstOrNull()?.let { hero ->
                item(key = "continue_title") { SectionTitle(stringResource(R.string.home_continue)) }
                item(key = "continue_hero") {
                    ContinueHero(
                        item = hero,
                        onClick = { onEpisodeClick(hero.episode.id) },
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )
                }
                val others = state.continueListening.drop(1)
                if (others.isNotEmpty()) {
                    item(key = "continue_rail") {
                        Spacer(Modifier.height(12.dp))
                        EpisodeRail(others, viewModel, onEpisodeClick)
                    }
                }
            }
            railSection("up_next", R.string.home_up_next, state.upNext, viewModel, onEpisodeClick)
            railSection("for_you", R.string.home_for_you, state.forYou, viewModel, onEpisodeClick)
            railSection("recent", R.string.home_recent, state.recentlyAdded, viewModel, onEpisodeClick)

            if (state.favorites.isNotEmpty()) {
                item(key = "fav_title") { SectionTitle(stringResource(R.string.home_favorites)) }
                items(state.favorites, key = { "fav_${it.episode.id}" }) { item ->
                    val episode = item.episode
                    EpisodeRow(
                        episodeId = episode.id,
                        title = episode.displayTitle,
                        subtitle = item.podcastTitle,
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
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.railSection(
    key: String,
    titleRes: Int,
    items: List<HomeEpisode>,
    viewModel: HomeViewModel,
    onEpisodeClick: (Long) -> Unit
) {
    if (items.isEmpty()) return
    item(key = "${key}_title") { SectionTitle(stringResource(titleRes)) }
    item(key = "${key}_rail") { EpisodeRail(items, viewModel, onEpisodeClick) }
}

@Composable
private fun EpisodeRail(items: List<HomeEpisode>, viewModel: HomeViewModel, onEpisodeClick: (Long) -> Unit) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        items(items, key = { it.episode.id }) { item ->
            val episode = item.episode
            EpisodeCard(
                episodeId = episode.id,
                title = episode.displayTitle,
                podcastTitle = item.podcastTitle,
                coverUri = episode.displayCoverUri,
                durationMs = episode.durationMs,
                progressFraction = item.progress,
                remainingMs = item.remainingMs,
                onClick = { onEpisodeClick(episode.id) },
                onQueue = { mode -> viewModel.addToQueue(episode, mode) }
            )
        }
    }
}

@Composable
private fun HomeHeader(queuedCount: Int, onOpenQueue: () -> Unit) {
    val hour = LocalTime.now().hour
    val greeting = when {
        hour < 5 -> R.string.greeting_evening
        hour < 12 -> R.string.greeting_morning
        hour < 18 -> R.string.greeting_afternoon
        else -> R.string.greeting_evening
    }
    Row(
        modifier = Modifier.fillMaxWidth().statusBarsPadding().padding(start = 16.dp, end = 8.dp, top = 16.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                stringResource(R.string.app_name),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary
            )
            Text(stringResource(greeting), style = MaterialTheme.typography.headlineMedium)
        }
        IconButton(onClick = onOpenQueue) {
            BadgedBox(badge = { if (queuedCount > 0) Badge { Text(queuedCount.toString()) } }) {
                Icon(Icons.Filled.QueueMusic, contentDescription = stringResource(R.string.queue_title))
            }
        }
    }
}

/** Grande carte "reprendre là où vous en étiez" pour l'épisode le plus récemment écouté. */
@Composable
private fun ContinueHero(item: HomeEpisode, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val nowPlaying = LocalNowPlaying.current
    val episode = item.episode
    val isCurrent = nowPlaying.episodeId == episode.id
    Card(
        modifier = modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).clickable(onClick = onClick),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Cover(episode.displayCoverUri, modifier = Modifier.size(92.dp), contentDescription = null)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    episode.displayTitle,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    item.podcastTitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(8.dp))
                item.progress?.let {
                    LinearProgressIndicator(
                        progress = { it },
                        modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp))
                    )
                    Spacer(Modifier.height(4.dp))
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (isCurrent) NowPlayingIndicator(isPlaying = nowPlaying.isPlaying)
                    item.remainingMs?.let {
                        Text(
                            formatRemaining(it),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            Spacer(Modifier.width(8.dp))
            Box(
                Modifier.size(48.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Filled.PlayArrow,
                    contentDescription = stringResource(R.string.resume),
                    tint = MaterialTheme.colorScheme.onPrimary
                )
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleLarge,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 12.dp)
    )
}

@Composable
private fun EmptyLibraryState(onGoToSettings: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Text(stringResource(R.string.empty_library_title), style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.empty_library_message),
            style = MaterialTheme.typography.bodyLarge
        )
        Spacer(Modifier.height(20.dp))
        Button(onClick = onGoToSettings) { Text(stringResource(R.string.scan_podcasts)) }
    }
}
