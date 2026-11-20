package com.podvault.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.podvault.app.data.repository.QueueMode

/** Carte verticale pour les rails horizontaux de l'accueil. */
@Composable
fun EpisodeCard(
    episodeId: Long,
    title: String,
    podcastTitle: String,
    coverUri: String?,
    durationMs: Long,
    progressFraction: Float?,
    remainingMs: Long?,
    onClick: () -> Unit,
    onQueue: (QueueMode) -> Unit,
    modifier: Modifier = Modifier
) {
    val nowPlaying = LocalNowPlaying.current
    val isCurrent = nowPlaying.episodeId == episodeId

    Column(
        modifier = modifier
            .width(152.dp)
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
    ) {
        Box {
            Cover(coverUri, modifier = Modifier.fillMaxWidth().aspectRatio(1f), contentDescription = "Pochette de $title")
            if (isCurrent) {
                Box(
                    Modifier
                        .align(Alignment.TopStart)
                        .padding(8.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.85f))
                        .padding(horizontal = 8.dp, vertical = 6.dp)
                ) { NowPlayingIndicator(isPlaying = nowPlaying.isPlaying) }
            }
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .padding(4.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.85f))
            ) { QueueMenuButton(episodeId, onQueue) }
            if (progressFraction != null && progressFraction > 0f) {
                // Sur la pochette : sous le texte, la barre était rognée par l'arrondi de la carte.
                SlimProgress(
                    fraction = { progressFraction },
                    height = 4.dp,
                    trackColor = Color.Black.copy(alpha = 0.35f),
                    modifier = Modifier.align(Alignment.BottomStart).padding(horizontal = 10.dp, vertical = 8.dp)
                        .fillMaxWidth(0.55f)
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 2.dp)
        )
        Text(
            podcastTitle,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 2.dp)
        )
        Text(
            if (remainingMs != null) formatRemaining(remainingMs) else formatDuration(durationMs),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 2.dp)
        )
        Spacer(Modifier.height(4.dp))
    }
}
