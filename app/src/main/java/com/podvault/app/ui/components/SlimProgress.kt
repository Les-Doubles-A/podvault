package com.podvault.app.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Barre de progression fine et continue. Le style Material 3 par défaut ajoute un espace entre
 * la partie lue et le reste ainsi qu'un point final : sur une barre de 2 à 4 dp, cela donnait
 * l'impression d'une barre vide avec deux points.
 */
@Composable
fun SlimProgress(
    fraction: () -> Float,
    modifier: Modifier = Modifier,
    height: Dp = 3.dp,
    trackColor: Color = MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
) {
    LinearProgressIndicator(
        progress = { fraction().coerceIn(0f, 1f) },
        modifier = modifier.fillMaxWidth().height(height).clip(RoundedCornerShape(height / 2)),
        color = MaterialTheme.colorScheme.primary,
        trackColor = trackColor,
        strokeCap = StrokeCap.Butt,
        gapSize = 0.dp,
        drawStopIndicator = {}
    )
}
