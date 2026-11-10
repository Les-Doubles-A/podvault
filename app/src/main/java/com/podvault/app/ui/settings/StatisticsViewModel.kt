package com.podvault.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.podvault.app.PodVaultApplication
import com.podvault.app.data.database.dao.PodcastListeningStat
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import java.time.LocalDate

data class StatisticsUiState(
    val totalListenedMs: Long = 0L,
    val streakDays: Int = 0,
    val podcasts: List<PodcastListeningStat> = emptyList()
)

class StatisticsViewModel(app: PodVaultApplication) : ViewModel() {
    val state = combine(app.libraryRepository.podcastListeningStats(), app.libraryRepository.listeningDays()) { podcasts, days ->
        val dates = days.mapNotNull { runCatching { LocalDate.parse(it.day) }.getOrNull() }.toSet()
        var streak = 0
        var date = LocalDate.now()
        if (date !in dates) date = date.minusDays(1)
        while (date in dates) {
            streak++
            date = date.minusDays(1)
        }
        StatisticsUiState(podcasts.sumOf { it.listenedMs }, streak, podcasts.filter { it.listenedMs > 0L })
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), StatisticsUiState())
}
