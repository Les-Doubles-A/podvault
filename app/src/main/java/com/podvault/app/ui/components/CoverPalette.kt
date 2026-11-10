package com.podvault.app.ui.components

import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.core.graphics.drawable.toBitmap
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.podvault.app.data.media.toDisplayUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Couleurs tirées d'une pochette : [primary] vive, [secondary] complémentaire, [deep] sombre pour les fonds. */
data class CoverPalette(val primary: Color, val secondary: Color, val deep: Color) {
    companion object {
        val Default = CoverPalette(Color(0xFF3ED9C5), Color(0xFF1F9C8C), Color(0xFF0B1F1D))
    }
}

/** Palette de la pochette, calculée hors du thread principal ; [CoverPalette.Default] en attendant. */
@Composable
fun rememberCoverPalette(coverUri: String?): CoverPalette {
    val context = LocalContext.current
    val palette by produceState(CoverPalette.Default, coverUri) {
        value = if (coverUri.isNullOrBlank()) CoverPalette.Default else withContext(Dispatchers.Default) {
            val request = ImageRequest.Builder(context)
                .data(coverUri.toDisplayUri())
                .size(64)
                .allowHardware(false)
                .build()
            val bitmap = (context.imageLoader.execute(request) as? SuccessResult)?.drawable?.toBitmap()
            bitmap?.let { extractPalette(it) } ?: CoverPalette.Default
        }
    }
    return palette
}

/**
 * Quantification simple en teintes : on retient la teinte dominante pondérée par la saturation
 * (les gris et les blancs ne doivent pas gagner), puis la deuxième teinte éloignée de la première.
 */
internal fun extractPalette(bitmap: Bitmap): CoverPalette {
    val width = bitmap.width
    val height = bitmap.height
    if (width == 0 || height == 0) return CoverPalette.Default
    val pixels = IntArray(width * height)
    bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

    val buckets = 12
    val weight = FloatArray(buckets)
    val hueSum = FloatArray(buckets)
    val satSum = FloatArray(buckets)
    val valSum = FloatArray(buckets)
    val hsv = FloatArray(3)
    for (pixel in pixels) {
        if (AndroidColor.alpha(pixel) < 128) continue
        AndroidColor.colorToHSV(pixel, hsv)
        if (hsv[1] < 0.15f || hsv[2] < 0.15f) continue
        val bucket = ((hsv[0] / 360f) * buckets).toInt().coerceIn(0, buckets - 1)
        val w = hsv[1] * hsv[2]
        weight[bucket] += w
        hueSum[bucket] += hsv[0] * w
        satSum[bucket] += hsv[1] * w
        valSum[bucket] += hsv[2] * w
    }
    val best = weight.indices.maxByOrNull { weight[it] } ?: return CoverPalette.Default
    if (weight[best] <= 0f) return grayPalette(pixels)

    fun colorOf(bucket: Int, minValue: Float = 0.75f): Color {
        val w = weight[bucket]
        val hue = hueSum[bucket] / w
        val sat = (satSum[bucket] / w).coerceIn(0.45f, 1f)
        val value = (valSum[bucket] / w).coerceAtLeast(minValue)
        return Color(AndroidColor.HSVToColor(floatArrayOf(hue, sat, value)))
    }

    val primary = colorOf(best)
    val second = weight.indices
        .filter { it != best && distance(it, best, buckets) >= 2 && weight[it] > weight[best] * 0.15f }
        .maxByOrNull { weight[it] }
    val secondary = if (second != null) colorOf(second) else shiftHue(primary, 35f)
    val primaryHsv = FloatArray(3).also { AndroidColor.colorToHSV(primary.toArgbInt(), it) }
    val deep = Color(AndroidColor.HSVToColor(floatArrayOf(primaryHsv[0], (primaryHsv[1] * 0.8f).coerceAtMost(0.9f), 0.16f)))
    return CoverPalette(primary, secondary, deep)
}

private fun distance(a: Int, b: Int, size: Int): Int {
    val d = kotlin.math.abs(a - b)
    return minOf(d, size - d)
}

private fun shiftHue(color: Color, degrees: Float): Color {
    val hsv = FloatArray(3)
    AndroidColor.colorToHSV(color.toArgbInt(), hsv)
    hsv[0] = (hsv[0] + degrees) % 360f
    return Color(AndroidColor.HSVToColor(hsv))
}

/** Pochette sans couleur marquée (noir et blanc) : teinte neutre dérivée de la luminosité moyenne. */
private fun grayPalette(pixels: IntArray): CoverPalette {
    val avg = pixels.map { AndroidColor.red(it) }.average().toFloat() / 255f
    val light = (0.55f + avg * 0.35f).coerceIn(0f, 1f)
    return CoverPalette(Color(light, light, light), Color(light * 0.7f, light * 0.7f, light * 0.75f), Color(0.08f, 0.08f, 0.09f))
}

private fun Color.toArgbInt(): Int =
    AndroidColor.argb((alpha * 255).toInt(), (red * 255).toInt(), (green * 255).toInt(), (blue * 255).toInt())
