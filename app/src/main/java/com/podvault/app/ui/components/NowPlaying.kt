package com.podvault.app.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material.icons.filled.PlaylistAddCheck
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.podvault.app.data.repository.QueueMode

/** Ce qui est en lecture et ce qui est en file, diffusé aux listes sans passer par chaque écran. */
data class NowPlaying(
    val episodeId: Long? = null,
    val podcastId: Long? = null,
    val isPlaying: Boolean = false,
    val queuedIds: Set<Long> = emptySet()
)

val LocalNowPlaying = compositionLocalOf { NowPlaying() }

/** Petit égaliseur : barres animées en lecture, figées en pause. */
@Composable
fun NowPlayingIndicator(
    isPlaying: Boolean,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary
) {
    if (isPlaying) AnimatedBars(modifier, color) else Bars(modifier, color) { 0.4f }
}

@Composable
private fun AnimatedBars(modifier: Modifier, color: Color) {
    val transition = rememberInfiniteTransition(label = "equalizer")
    val durations = listOf(420, 560, 480)
    val bars = durations.mapIndexed { index, duration ->
        transition.animateFloat(
            initialValue = 0.25f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(duration, easing = LinearEasing), RepeatMode.Reverse),
            label = "bar$index"
        )
    }
    Bars(modifier, color) { bars[it].value }
}

@Composable
private fun Bars(modifier: Modifier, color: Color, fraction: (Int) -> Float) {
    Canvas(modifier.size(width = 16.dp, height = 14.dp)) {
        val barWidth = size.width / 5f
        for (i in 0..2) {
            val height = size.height * fraction(i)
            drawRoundRect(
                color = color,
                topLeft = Offset(i * 2 * barWidth, size.height - height),
                size = Size(barWidth, height),
                cornerRadius = CornerRadius(barWidth / 2f)
            )
        }
    }
}

/** Bouton "file d'attente" : menu Lire ensuite / Ajouter à la fin, avec état "déjà en file". */
@Composable
fun QueueMenuButton(episodeId: Long, onQueue: (QueueMode) -> Unit, modifier: Modifier = Modifier) {
    val queued = episodeId in LocalNowPlaying.current.queuedIds
    var expanded by remember { mutableStateOf(false) }
    Box(modifier) {
        IconButton(onClick = { expanded = true }) {
            Icon(
                if (queued) Icons.Filled.PlaylistAddCheck else Icons.Filled.PlaylistAdd,
                contentDescription = if (queued) "Déjà dans la file d'attente" else "Ajouter à la file d'attente",
                tint = if (queued) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text("Lire ensuite") },
                leadingIcon = { Icon(Icons.Filled.SkipNext, contentDescription = null) },
                onClick = { expanded = false; onQueue(QueueMode.NEXT) }
            )
            DropdownMenuItem(
                text = { Text("Ajouter à la fin de la file") },
                leadingIcon = { Icon(Icons.Filled.PlaylistAdd, contentDescription = null) },
                onClick = { expanded = false; onQueue(QueueMode.LAST) }
            )
        }
    }
}
