package com.podvault.app.ui.queue

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.podvault.app.R
import com.podvault.app.data.repository.QueueItem
import com.podvault.app.ui.components.Cover
import com.podvault.app.ui.components.NowPlayingIndicator
import com.podvault.app.ui.components.formatDuration
import com.podvault.app.ui.components.formatTotal
import com.podvault.app.ui.podVaultViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QueueScreen(onBack: () -> Unit) {
    val viewModel = podVaultViewModel { QueueViewModel(it) }
    val queue by viewModel.queue.collectAsStateWithLifecycle()
    val playback by viewModel.playbackState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.queue_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                actions = {
                    IconButton(onClick = viewModel::clear, enabled = queue.upNext.isNotEmpty()) {
                        Icon(Icons.Filled.DeleteSweep, contentDescription = stringResource(R.string.queue_clear))
                    }
                }
            )
        }
    ) { padding ->
        val current = queue.current
        if (current == null) {
            EmptyQueue(Modifier.padding(padding).fillMaxSize())
            return@Scaffold
        }
        LazyColumn(
            modifier = Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(bottom = 24.dp)
        ) {
            item(key = "now_title") { SectionLabel(stringResource(R.string.queue_now_playing), highlighted = true) }
            item(key = "now_item") {
                QueueRow(
                    item = current,
                    isCurrent = true,
                    isPlaying = playback.isPlaying,
                    onClick = viewModel::togglePlayPause,
                    actions = {}
                )
            }
            item(key = "next_title") {
                SectionLabel(
                    if (queue.upNext.isEmpty()) stringResource(R.string.queue_next)
                    else stringResource(R.string.queue_next_summary, queue.upNext.size, formatTotal(queue.upNextDurationMs))
                )
            }
            if (queue.upNext.isEmpty()) {
                item(key = "next_empty") {
                    Text(
                        stringResource(R.string.queue_next_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )
                }
            }
            itemsIndexed(queue.upNext, key = { _, item -> "q_${item.episodeId}" }) { position, item ->
                QueueRow(
                    item = item,
                    isCurrent = false,
                    isPlaying = false,
                    onClick = { viewModel.play(item.index) },
                    actions = {
                        IconButton(
                            onClick = { viewModel.move(item.index, item.index - 1) },
                            enabled = position > 0
                        ) { Icon(Icons.Filled.ArrowUpward, contentDescription = stringResource(R.string.queue_move_up)) }
                        IconButton(
                            onClick = { viewModel.move(item.index, item.index + 1) },
                            enabled = position < queue.upNext.lastIndex
                        ) { Icon(Icons.Filled.ArrowDownward, contentDescription = stringResource(R.string.queue_move_down)) }
                        IconButton(onClick = { viewModel.remove(item.index) }) {
                            Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.queue_remove))
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun QueueRow(
    item: QueueItem,
    isCurrent: Boolean,
    isPlaying: Boolean,
    onClick: () -> Unit,
    actions: @Composable () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (isCurrent) Modifier.padding(horizontal = 12.dp) else Modifier)
            .clip(RoundedCornerShape(16.dp))
            .then(if (isCurrent) Modifier.background(MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)) else Modifier)
            .clickable(onClick = onClick)
            .padding(horizontal = if (isCurrent) 8.dp else 20.dp, vertical = if (isCurrent) 10.dp else 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Cover(item.coverUri, modifier = Modifier.size(52.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                item.title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (isCurrent) NowPlayingIndicator(isPlaying = isPlaying)
                Text(
                    listOfNotNull(item.podcastTitle.ifBlank { null }, item.durationMs.takeIf { it > 0 }?.let(::formatDuration))
                        .joinToString(" · "),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        actions()
    }
}

@Composable
private fun SectionLabel(text: String, highlighted: Boolean = false) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 0.5.sp, fontWeight = FontWeight.SemiBold),
        color = if (highlighted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 22.dp, bottom = 8.dp)
    )
}

@Composable
private fun EmptyQueue(modifier: Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
            Icon(
                Icons.Filled.QueueMusic,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.queue_empty_title), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(R.string.queue_empty_message),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
