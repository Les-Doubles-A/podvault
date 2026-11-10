package com.podvault.app.ui.player

import android.net.Uri
import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.Forward30
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import com.podvault.app.service.AudioLevel
import com.podvault.app.ui.components.rememberCoverPalette
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.podvault.app.R
import com.podvault.app.data.database.entities.BookmarkEntity
import com.podvault.app.data.database.entities.EpisodeEntity
import com.podvault.app.data.repository.PlaybackProgress
import com.podvault.app.data.repository.SleepTimer
import com.podvault.app.ui.components.Cover
import com.podvault.app.ui.components.NowPlayingIndicator
import com.podvault.app.ui.components.formatDuration
import com.podvault.app.ui.podVaultViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.ceil

private val SPEEDS = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f, 2.5f, 3.0f)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerScreen(episodeId: Long, initialPositionMs: Long? = null, onBack: () -> Unit, onOpenQueue: () -> Unit) {
    val viewModel = podVaultViewModel { PlayerViewModel(it) }
    val episode by viewModel.episode.collectAsStateWithLifecycle()
    val playbackState by viewModel.playbackState.collectAsStateWithLifecycle()
    val bookmarks by viewModel.bookmarkList.collectAsStateWithLifecycle()
    val queue by viewModel.queue.collectAsStateWithLifecycle()
    val rewindSeconds by viewModel.rewindSeconds.collectAsStateWithLifecycle()
    val forwardSeconds by viewModel.forwardSeconds.collectAsStateWithLifecycle()
    val feedback by viewModel.lastSavedFeedback.collectAsStateWithLifecycle()
    val pendingNaming by viewModel.pendingNamingBookmark.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val haptic = LocalHapticFeedback.current
    val ambient by viewModel.ambientMode.collectAsStateWithLifecycle()
    val palette = rememberCoverPalette(playbackState.coverUri)
    // Lu uniquement dans graphicsLayer : la pulsation de la pochette ne recompose rien.
    val audioLevel = AudioLevel.level.collectAsStateWithLifecycle()

    LaunchedEffect(episodeId, initialPositionMs) { viewModel.openAndPlay(episodeId, initialPositionMs) }

    LaunchedEffect(feedback) {
        feedback?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearFeedback()
        }
    }

    var editingBookmark by remember { mutableStateOf<BookmarkEntity?>(null) }
    var sleepTimerDialog by remember { mutableStateOf(false) }
    var editingEpisodeMetadata by remember { mutableStateOf(false) }
    var selectedEpisodeCoverUri by remember(playbackState.episodeId) { mutableStateOf<String?>(null) }
    val coverPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { selectedEpisodeCoverUri = it.toString() }
    }

    // L'erreur est consommée après affichage pour ne pas réapparaître à chaque ouverture du lecteur.
    LaunchedEffect(playbackState.errorMessage) {
        playbackState.errorMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearError()
        }
    }

    // Right after a quick-add, offer the optional naming form automatically.
    LaunchedEffect(pendingNaming) {
        pendingNaming?.let { editingBookmark = it }
    }

    AmbientHost(enabled = ambient, palette = palette, isPlaying = playbackState.isPlaying) {
    Scaffold(
        containerColor = Color.Transparent,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                title = {
                    Text(
                        playbackState.podcastTitle,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.KeyboardArrowDown, contentDescription = stringResource(R.string.player_close))
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.toggleAmbientMode() }) {
                        Icon(
                            Icons.Filled.AutoAwesome,
                            contentDescription = stringResource(R.string.ambient_mode),
                            tint = if (ambient) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(onClick = onOpenQueue) {
                        BadgedBox(badge = { if (queue.upNext.isNotEmpty()) Badge { Text(queue.upNext.size.toString()) } }) {
                            Icon(Icons.Filled.QueueMusic, contentDescription = stringResource(R.string.queue_title))
                        }
                    }
                    IconButton(onClick = { selectedEpisodeCoverUri = null; editingEpisodeMetadata = true }, enabled = episode != null) {
                        Icon(Icons.Filled.Edit, contentDescription = "Modifier les informations de l'épisode")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).fillMaxSize().padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            item(key = "header") {
                Spacer(Modifier.height(8.dp))
                Cover(
                    playbackState.coverUri,
                    cornerRadius = 24.dp,
                    modifier = Modifier.size(240.dp).graphicsLayer {
                        if (ambient && playbackState.isPlaying) {
                            val scale = 1f + 0.06f * audioLevel.value
                            scaleX = scale
                            scaleY = scale
                        }
                    }.then(
                        if (ambient) Modifier.shadow(24.dp, RoundedCornerShape(24.dp), ambientColor = palette.primary, spotColor = palette.primary)
                        else Modifier
                    ),
                    contentDescription = "Pochette de ${playbackState.episodeTitle}"
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    playbackState.episodeTitle.ifBlank { episode?.displayTitle.orEmpty() },
                    style = MaterialTheme.typography.titleLarge,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(12.dp))
                SeekBar(viewModel.progress, onSeek = viewModel::seekTo)
                Spacer(Modifier.height(4.dp))
            }

            item(key = "transport") {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    SkipButton(Icons.Filled.Replay, rewindSeconds, "Reculer de $rewindSeconds secondes", mirrored = false) { viewModel.skipBackward() }
                    FilledIconButton(onClick = { viewModel.togglePlayPause() }, modifier = Modifier.size(76.dp)) {
                        Icon(
                            if (playbackState.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            contentDescription = if (playbackState.isPlaying) "Pause" else "Lecture",
                            modifier = Modifier.size(36.dp)
                        )
                    }
                    SkipButton(Icons.Filled.Replay, forwardSeconds, "Avancer de $forwardSeconds secondes", mirrored = true) { viewModel.skipForward() }
                    IconButton(onClick = { viewModel.skipToNext() }, enabled = queue.upNext.isNotEmpty(), modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Filled.SkipNext, contentDescription = stringResource(R.string.player_next))
                    }
                }
                Spacer(Modifier.height(8.dp))
            }

            item(key = "actions") {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    SpeedSelector(current = playbackState.speed, onSelect = { viewModel.setSpeed(it) })
                    SleepTimerChip(playbackState.sleepTimer, onClick = { sleepTimerDialog = true })
                    IconButton(onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        viewModel.quickAddBookmark()
                    }) {
                        Icon(Icons.Filled.BookmarkAdd, contentDescription = "Ajouter un point mémoire")
                    }
                    IconButton(onClick = { viewModel.toggleFavorite() }, enabled = episode != null) {
                        Icon(
                            if (episode?.isFavorite == true) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                            contentDescription = "Favori",
                            tint = if (episode?.isFavorite == true) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                if (ambient) {
                    Spacer(Modifier.height(24.dp))
                    LevelBars(audioLevel, playbackState.isPlaying, palette.primary)
                }
                Spacer(Modifier.height(16.dp))
                Text(
                    "Points mémoire (${bookmarks.size})",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
            }

            items(bookmarks, key = { it.id }) { bookmark ->
                BookmarkRow(
                    bookmark = bookmark,
                    onClick = { viewModel.seekTo(bookmark.positionMs) },
                    onEdit = { editingBookmark = bookmark },
                    onDelete = { viewModel.deleteBookmark(bookmark) }
                )
            }

            val description = episode?.description
            if (!description.isNullOrBlank()) {
                item(key = "description") {
                    Spacer(Modifier.height(24.dp))
                    Text("Description", style = MaterialTheme.typography.titleMedium, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    Text(description, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(48.dp))
                }
            } else {
                item(key = "bottom_space") { Spacer(Modifier.height(48.dp)) }
            }
        }
    }

    editingBookmark?.let { bookmark ->
        EditBookmarkDialog(
            bookmark = bookmark,
            onDismiss = {
                editingBookmark = null
                viewModel.dismissPendingNaming()
            },
            onSave = { title, note ->
                viewModel.updateBookmark(bookmark, title, note)
                editingBookmark = null
                viewModel.dismissPendingNaming()
            }
        )
    }

    if (sleepTimerDialog) {
        AlertDialog(
            onDismissRequest = { sleepTimerDialog = false },
            title = { Text("Minuterie d'arrêt") },
            text = {
                Column {
                    listOf(15, 30, 45, 60).forEach { minutes ->
                        TextButton(onClick = {
                            viewModel.startSleepTimer(minutes)
                            sleepTimerDialog = false
                        }) { Text("Arrêter dans $minutes min") }
                    }
                    TextButton(onClick = {
                        viewModel.sleepAtEpisodeEnd()
                        sleepTimerDialog = false
                    }) { Text("À la fin de l'épisode") }
                    TextButton(
                        onClick = {
                            viewModel.cancelSleepTimer()
                            sleepTimerDialog = false
                        },
                        enabled = playbackState.sleepTimer != SleepTimer.Off
                    ) { Text("Désactiver la minuterie") }
                }
            },
            confirmButton = { TextButton(onClick = { sleepTimerDialog = false }) { Text("Fermer") } }
        )
    }

    if (editingEpisodeMetadata) episode?.let { currentEpisode ->
        EpisodeMetadataDialog(
            episode = currentEpisode,
            selectedCoverUri = selectedEpisodeCoverUri,
            onChooseCover = { coverPicker.launch(arrayOf("image/*")) },
            onDismiss = { editingEpisodeMetadata = false },
            onSave = { title, author, cover ->
                val effectiveCover = cover ?: currentEpisode.userCoverUri
                effectiveCover?.let { viewModel.persistCoverPermission(Uri.parse(it)) }
                viewModel.saveEpisodeMetadata(title, author, effectiveCover)
                editingEpisodeMetadata = false
            }
        )
    }
    }
}

/**
 * Barre de progression. Pendant le glissement la valeur reste locale et le déplacement n'est
 * appliqué qu'au relâchement : un seek par pixel saccadait la lecture. La position (mise à jour
 * deux fois par seconde) n'est lue qu'ici, sans recomposer le reste de l'écran.
 */
@Composable
private fun SeekBar(progress: StateFlow<PlaybackProgress>, onSeek: (Long) -> Unit) {
    val current by progress.collectAsStateWithLifecycle()
    var dragValue by remember { mutableStateOf<Float?>(null) }
    val duration = current.durationMs.coerceAtLeast(1L).toFloat()
    val shown = (dragValue ?: current.positionMs.toFloat()).coerceIn(0f, duration)

    Column(Modifier.fillMaxWidth()) {
        Slider(
            value = shown,
            onValueChange = { dragValue = it },
            onValueChangeFinished = {
                dragValue?.let { onSeek(it.toLong()) }
                dragValue = null
            },
            valueRange = 0f..duration
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(formatDuration(shown.toLong()), style = MaterialTheme.typography.labelMedium)
            Text(
                "-" + formatDuration((current.durationMs - shown.toLong()).coerceAtLeast(0L)),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** Barres d'ambiance : leur hauteur suit le niveau sonore, lu uniquement au dessin. */
@Composable
private fun LevelBars(level: androidx.compose.runtime.State<Float>, isPlaying: Boolean, color: Color) {
    val phase = androidx.compose.animation.core.rememberInfiniteTransition(label = "bars").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = androidx.compose.animation.core.infiniteRepeatable(
            androidx.compose.animation.core.tween(1600, easing = androidx.compose.animation.core.LinearEasing)
        ),
        label = "barPhase"
    )
    androidx.compose.foundation.Canvas(Modifier.fillMaxWidth().height(34.dp)) {
        val count = 5
        val barWidth = 5.dp.toPx()
        val gap = 6.dp.toPx()
        val totalWidth = count * barWidth + (count - 1) * gap
        val startX = (size.width - totalWidth) / 2f
        for (i in 0 until count) {
            val wave = 0.5f + 0.5f * kotlin.math.sin((phase.value + i * 0.2f) * 2f * Math.PI.toFloat())
            val fraction = if (isPlaying) (0.15f + level.value * (0.45f + 0.55f * wave)).coerceIn(0.12f, 1f) else 0.12f
            val h = size.height * fraction
            drawRoundRect(
                color = color,
                topLeft = androidx.compose.ui.geometry.Offset(startX + i * (barWidth + gap), size.height - h),
                size = androidx.compose.ui.geometry.Size(barWidth, h),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(barWidth / 2f)
            )
        }
    }
}

@Composable
private fun SkipButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    seconds: Int,
    description: String,
    mirrored: Boolean,
    onClick: () -> Unit
) {
    // Une seule icône sans chiffre, retournée pour l'avance : Forward30 affichait toujours "30"
    // et se superposait au nombre de secondes réglé dans les paramètres.
    IconButton(onClick = onClick, modifier = Modifier.size(56.dp)) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                icon,
                contentDescription = description,
                modifier = Modifier.size(40.dp).graphicsLayer { if (mirrored) scaleX = -1f }
            )
            Text(seconds.toString(), style = MaterialTheme.typography.labelMedium.copy(fontSize = 11.sp))
        }
    }
}

@Composable
private fun SleepTimerChip(timer: SleepTimer, onClick: () -> Unit) {
    val label = when (timer) {
        SleepTimer.Off -> stringResource(R.string.sleep_timer)
        SleepTimer.EndOfEpisode -> stringResource(R.string.sleep_timer_end)
        is SleepTimer.Countdown -> {
            var now by remember(timer) { mutableLongStateOf(SystemClock.elapsedRealtime()) }
            LaunchedEffect(timer) {
                while (true) {
                    now = SystemClock.elapsedRealtime()
                    delay(15_000)
                }
            }
            val minutes = ceil((timer.endsAtElapsedMs - now) / 60_000.0).toInt().coerceAtLeast(1)
            stringResource(R.string.sleep_timer_active, minutes)
        }
    }
    AssistChip(
        onClick = onClick,
        label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        leadingIcon = { Icon(Icons.Filled.Timer, contentDescription = null, modifier = Modifier.size(18.dp)) }
    )
}

@Composable
private fun EpisodeMetadataDialog(
    episode: EpisodeEntity,
    selectedCoverUri: String?,
    onChooseCover: () -> Unit,
    onDismiss: () -> Unit,
    onSave: (String?, String?, String?) -> Unit
) {
    var title by remember(episode.id) { mutableStateOf(episode.userTitle ?: episode.title) }
    var author by remember(episode.id) { mutableStateOf(episode.userAuthor.orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Informations de l'épisode") },
        text = {
            Column {
                OutlinedTextField(title, { title = it.take(512) }, label = { Text("Titre") }, singleLine = true)
                OutlinedTextField(author, { author = it.take(512) }, label = { Text("Auteur") }, singleLine = true)
                TextButton(onClick = onChooseCover) { Text(if (selectedCoverUri == null) "Choisir une pochette" else "Pochette sélectionnée") }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(title.ifBlank { null }, author.ifBlank { null }, selectedCoverUri) }) { Text("Enregistrer") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } }
    )
}

@Composable
private fun SpeedSelector(current: Float, onSelect: (Float) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        AssistChip(onClick = { expanded = true }, label = { Text("${formatSpeed(current)}x") })
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            SPEEDS.forEach { speed ->
                DropdownMenuItem(text = { Text("${formatSpeed(speed)}x") }, onClick = {
                    onSelect(speed)
                    expanded = false
                })
            }
        }
    }
}

private fun formatSpeed(speed: Float): String =
    if (speed % 1f == 0f) speed.toInt().toString() else speed.toString().trimEnd('0')

@Composable
private fun BookmarkRow(
    bookmark: BookmarkEntity,
    onClick: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f).clickable(onClick = onClick)) {
            Text(formatDuration(bookmark.positionMs), style = MaterialTheme.typography.bodyLarge)
            (bookmark.title ?: bookmark.note)?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        IconButton(onClick = onEdit) { Icon(Icons.Filled.Edit, contentDescription = "Modifier") }
        IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, contentDescription = "Supprimer") }
    }
}

@Composable
private fun EditBookmarkDialog(bookmark: BookmarkEntity, onDismiss: () -> Unit, onSave: (String?, String?) -> Unit) {
    var title by remember(bookmark.id) { mutableStateOf(bookmark.title ?: "") }
    var note by remember(bookmark.id) { mutableStateOf(bookmark.note ?: "") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Point mémoire — ${formatDuration(bookmark.positionMs)}") },
        text = {
            Column {
                OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("Titre") }, singleLine = true)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(value = note, onValueChange = { note = it }, label = { Text("Note") })
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(title.ifBlank { null }, note.ifBlank { null }) }) { Text("Enregistrer") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Annuler") }
        }
    )
}
