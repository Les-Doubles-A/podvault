package com.podvault.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.podvault.app.data.media.toDisplayUri

/**
 * Carte de podcast : la pochette est empilée sur deux calques décalés (effet "groupe"), assombrie
 * en bas par un dégradé qui porte le titre. Un indicateur animé signale le podcast en lecture.
 */
@Composable
fun PodcastCard(
    podcastId: Long,
    title: String,
    coverUri: String?,
    episodeCount: Int,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val nowPlaying = LocalNowPlaying.current
    val isCurrent = nowPlaying.podcastId == podcastId
    val shape = RoundedCornerShape(22.dp)

    Box(
        modifier = modifier
            .aspectRatio(1f)
            .clickable(onClick = onClick)
    ) {
        if (!coverUri.isNullOrBlank()) {
            // Calques d'arrière-plan : même pochette, réduite, inclinée et atténuée.
            StackLayer(coverUri, scale = 0.86f, rotation = -4f, alpha = 0.35f, offsetY = (-2).dp, shape = shape)
            StackLayer(coverUri, scale = 0.93f, rotation = 3f, alpha = 0.6f, offsetY = (-1).dp, shape = shape)
        }
        Box(
            Modifier
                .fillMaxSize()
                .padding(top = 6.dp)
                .shadow(8.dp, shape, ambientColor = MaterialTheme.colorScheme.primary, spotColor = MaterialTheme.colorScheme.primary)
                .clip(shape)
        ) {
            Cover(coverUri, modifier = Modifier.fillMaxSize(), contentDescription = "Pochette de $title")
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(
                        0.45f to Color.Transparent,
                        1f to Color.Black.copy(alpha = 0.82f)
                    )
                )
            )
            Column(Modifier.align(Alignment.BottomStart).padding(12.dp)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    if (episodeCount == 1) "1 épisode" else "$episodeCount épisodes",
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White.copy(alpha = 0.75f)
                )
            }
            if (isCurrent) {
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(10.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.55f))
                        .padding(horizontal = 8.dp, vertical = 6.dp)
                ) { NowPlayingIndicator(isPlaying = nowPlaying.isPlaying) }
            }
        }
    }
}

@Composable
private fun StackLayer(
    coverUri: String,
    scale: Float,
    rotation: Float,
    alpha: Float,
    offsetY: androidx.compose.ui.unit.Dp,
    shape: androidx.compose.ui.graphics.Shape
) {
    AsyncImage(
        model = coverUri.toDisplayUri(),
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                rotationZ = rotation
                this.alpha = alpha
                translationY = offsetY.toPx()
            }
            .clip(shape)
    )
}

@Composable
fun Cover(
    coverUri: String?,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
    cornerRadius: androidx.compose.ui.unit.Dp = 12.dp
) {
    if (coverUri.isNullOrBlank()) {
        Box(
            modifier
                .clip(RoundedCornerShape(cornerRadius))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Filled.MusicNote,
                contentDescription = contentDescription,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    } else {
        AsyncImage(
            model = coverUri.toDisplayUri(),
            contentDescription = contentDescription,
            contentScale = ContentScale.Crop,
            modifier = modifier.clip(RoundedCornerShape(cornerRadius))
        )
    }
}
