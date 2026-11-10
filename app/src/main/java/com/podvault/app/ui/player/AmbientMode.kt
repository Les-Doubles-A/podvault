package com.podvault.app.ui.player

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.podvault.app.service.AudioLevel
import com.podvault.app.ui.components.CoverPalette

/**
 * Mode ambiance : fond dégradé aux couleurs de la pochette, dont les halos respirent avec le
 * niveau sonore réel de la lecture. Le niveau est lu uniquement pendant le dessin, sans recomposition.
 *
 * Le contenu reste toujours dans la même composition : l'activation anime un fondu (fond et
 * couleurs du thème interpolés) au lieu de basculer brutalement entre deux thèmes.
 */
@Composable
fun AmbientHost(
    enabled: Boolean,
    palette: CoverPalette,
    isPlaying: Boolean,
    content: @Composable () -> Unit
) {
    val base = MaterialTheme.colorScheme
    val progress by animateFloatAsState(if (enabled) 1f else 0f, tween(700), label = "ambientProgress")
    val target = ambientColorScheme(palette)
    val animatedScheme = blend(base, target, progress)

    Box(Modifier.fillMaxSize().background(base.background)) {
        if (progress > 0.01f) {
            AmbientBackground(palette, isPlaying, Modifier.graphicsLayer { alpha = progress })
        }
        MaterialTheme(
            colorScheme = animatedScheme,
            typography = MaterialTheme.typography,
            content = content
        )
    }
}

/** Interpole les rôles de couleur utilisés par le lecteur entre le thème normal et le thème ambiance. */
private fun blend(from: ColorScheme, to: ColorScheme, t: Float): ColorScheme {
    if (t <= 0f) return from
    if (t >= 1f) return to
    return from.copy(
        primary = lerp(from.primary, to.primary, t),
        onPrimary = lerp(from.onPrimary, to.onPrimary, t),
        primaryContainer = lerp(from.primaryContainer, to.primaryContainer, t),
        onPrimaryContainer = lerp(from.onPrimaryContainer, to.onPrimaryContainer, t),
        secondaryContainer = lerp(from.secondaryContainer, to.secondaryContainer, t),
        onSecondaryContainer = lerp(from.onSecondaryContainer, to.onSecondaryContainer, t),
        background = lerp(from.background, to.background, t),
        surface = lerp(from.surface, to.surface, t),
        surfaceVariant = lerp(from.surfaceVariant, to.surfaceVariant, t),
        surfaceContainer = lerp(from.surfaceContainer, to.surfaceContainer, t),
        surfaceContainerHigh = lerp(from.surfaceContainerHigh, to.surfaceContainerHigh, t),
        onBackground = lerp(from.onBackground, to.onBackground, t),
        onSurface = lerp(from.onSurface, to.onSurface, t),
        onSurfaceVariant = lerp(from.onSurfaceVariant, to.onSurfaceVariant, t),
        outline = lerp(from.outline, to.outline, t)
    )
}

@Composable
private fun AmbientBackground(palette: CoverPalette, isPlaying: Boolean, modifier: Modifier = Modifier) {
    val level: State<Float> = AudioLevel.level.collectAsStateWithLifecycle()
    val primary = animateColorAsState(palette.primary, tween(900), label = "ambientPrimary")
    val secondary = animateColorAsState(palette.secondary, tween(900), label = "ambientSecondary")
    val deep = animateColorAsState(palette.deep, tween(900), label = "ambientDeep")
    val drift = rememberInfiniteTransition(label = "ambientDrift").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(14_000, easing = LinearEasing), RepeatMode.Reverse),
        label = "drift"
    )

    Box(
        modifier.fillMaxSize().drawBehind {
            val l = if (isPlaying) level.value else 0f
            drawRect(Brush.verticalGradient(listOf(deep.value, lerp(deep.value, Color.Black, 0.65f))))

            val topCenter = Offset(size.width * (0.25f + 0.5f * drift.value), size.height * 0.26f)
            val topRadius = size.minDimension * (0.75f + 0.45f * l)
            drawCircle(
                brush = Brush.radialGradient(
                    listOf(primary.value.copy(alpha = 0.30f + 0.50f * l), Color.Transparent),
                    center = topCenter,
                    radius = topRadius
                ),
                radius = topRadius,
                center = topCenter
            )

            val bottomCenter = Offset(size.width * (0.8f - 0.5f * drift.value), size.height * 0.85f)
            val bottomRadius = size.minDimension * (0.65f + 0.35f * l)
            drawCircle(
                brush = Brush.radialGradient(
                    listOf(secondary.value.copy(alpha = 0.20f + 0.40f * l), Color.Transparent),
                    center = bottomCenter,
                    radius = bottomRadius
                ),
                radius = bottomRadius,
                center = bottomCenter
            )
        }
    )
}

/** Palette sombre lisible quel que soit le thème de l'application, accentuée par la pochette. */
@Composable
private fun ambientColorScheme(palette: CoverPalette): ColorScheme = remember(palette) {
    val glass = Color.White.copy(alpha = 0.12f)
    darkColorScheme(
        primary = palette.primary,
        onPrimary = Color.Black,
        primaryContainer = palette.primary.copy(alpha = 0.30f),
        onPrimaryContainer = Color.White,
        secondaryContainer = glass,
        onSecondaryContainer = Color.White,
        background = Color.Transparent,
        surface = Color.Transparent,
        surfaceVariant = glass,
        surfaceContainer = lerp(palette.deep, Color.White, 0.10f),
        surfaceContainerHigh = lerp(palette.deep, Color.White, 0.14f),
        onBackground = Color.White,
        onSurface = Color.White,
        onSurfaceVariant = Color.White.copy(alpha = 0.72f),
        outline = Color.White.copy(alpha = 0.3f)
    )
}
