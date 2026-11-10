package com.podvault.app.ui.home

import com.podvault.app.data.database.entities.EpisodeEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeRecommenderTest {

    private val day = 86_400_000L
    private val now = 100 * day

    private fun episode(
        id: Long,
        podcastId: Long = 1,
        number: Int? = null,
        fileName: String = "ep$id.mp3",
        addedDaysAgo: Int = 1,
        favorite: Boolean = false
    ) = EpisodeEntity(
        id = id, podcastId = podcastId, title = "Episode $id", audioUri = "content://x/$id",
        fileName = fileName, fileSize = 1, durationMs = 60_000, episodeNumber = number,
        addedAt = now - addedDaysAgo * day, isFavorite = favorite
    )

    @Test
    fun nextInSeriesFollowsEpisodeNumbers() {
        val last = episode(1, number = 3)
        val pool = listOf(episode(2, number = 7), episode(3, number = 4), episode(4, number = 2))
        assertEquals(3L, HomeRecommender.nextInSeries(last, pool)?.id)
    }

    @Test
    fun nextInSeriesUsesNaturalFileNameOrderWithoutMetadata() {
        val last = episode(1, fileName = "show 2.mp3")
        val pool = listOf(episode(2, fileName = "show 10.mp3"), episode(3, fileName = "show 3.mp3"))
        assertEquals(3L, HomeRecommender.nextInSeries(last, pool)?.id)
    }

    @Test
    fun nextInSeriesIsNullWhenNothingFollows() {
        val last = episode(1, number = 9)
        assertNull(HomeRecommender.nextInSeries(last, listOf(episode(2, number = 1))))
    }

    @Test
    fun listenedPodcastOutranksUnexploredOneAtEqualFreshness() {
        val signals = mapOf(1L to PodcastSignal(1, listenedMs = 5 * 3_600_000L, completedCount = 6, lastPlayedAt = now - day, favoriteCount = 1))
        val result = HomeRecommender.recommend(
            candidates = listOf(episode(10, podcastId = 2), episode(11, podcastId = 1)),
            signals = signals, excludeIds = emptySet(), now = now, limit = 2
        )
        assertEquals(11L, result.first().id)
    }

    @Test
    fun capsEpisodesPerPodcastForDiversity() {
        val candidates = (1L..6L).map { episode(it, podcastId = 1) } + episode(20, podcastId = 2, addedDaysAgo = 30)
        val result = HomeRecommender.recommend(candidates, emptyMap(), emptySet(), now, limit = 5, maxPerPodcast = 2)
        assertEquals(2, result.count { it.podcastId == 1L })
        assertTrue(result.any { it.podcastId == 2L })
    }

    @Test
    fun excludedAndMissingEpisodesAreNeverRecommended() {
        val result = HomeRecommender.recommend(
            candidates = listOf(episode(1), episode(2).copy(isMissing = true), episode(3)),
            signals = emptyMap(), excludeIds = setOf(1L), now = now
        )
        assertEquals(listOf(3L), result.map { it.id })
    }

    @Test
    fun selectionIsStableWithinTheSameDay() {
        val candidates = (1L..20L).map { episode(it, podcastId = it % 4) }
        val first = HomeRecommender.recommend(candidates, emptyMap(), emptySet(), now)
        val second = HomeRecommender.recommend(candidates, emptyMap(), emptySet(), now + 3_600_000L)
        assertEquals(first.map { it.id }, second.map { it.id })
    }
}
