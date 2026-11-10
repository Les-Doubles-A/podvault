package com.podvault.app.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.podvault.app.ui.podVaultViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatisticsScreen(onBack: () -> Unit) {
    val viewModel = podVaultViewModel { StatisticsViewModel(it) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    Scaffold(topBar = {
        TopAppBar(title = { Text("Statistiques d'écoute") }, navigationIcon = {
            IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, contentDescription = "Retour") }
        })
    }) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize(), contentPadding = PaddingValues(16.dp)) {
            item {
                Text("Temps d'écoute total", style = MaterialTheme.typography.titleMedium)
                Text(formatListeningTime(state.totalListenedMs), style = MaterialTheme.typography.headlineMedium)
                Spacer(Modifier.height(16.dp))
                Text("Série actuelle", style = MaterialTheme.typography.titleMedium)
                Text("${state.streakDays} jour(s)", style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(24.dp))
                Text("Par podcast", style = MaterialTheme.typography.titleLarge)
            }
            items(state.podcasts, key = { it.podcastId }) { stat ->
                ListItem(
                    headlineContent = { Text(stat.podcastTitle) },
                    supportingContent = { Text("${formatListeningTime(stat.listenedMs)} · ${stat.episodesPlayed} épisodes écoutés") }
                )
            }
        }
    }
}

private fun formatListeningTime(ms: Long): String {
    val totalMinutes = ms.coerceAtLeast(0L) / 60_000L
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    return if (hours > 0) "$hours h $minutes min" else "$minutes min"
}
