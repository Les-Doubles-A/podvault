package com.podvault.app.data.repository

import com.podvault.app.data.database.entities.PlaybackStateEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackRulesTest {

    private val twoHours = 2 * 60 * 60 * 1000L

    @Test
    fun completionThresholdIsBoundedBetweenFiveAndThirtySeconds() {
        assertEquals(5_000L, PlaybackRules.completionThresholdMs(60_000L))
        assertEquals(9_000L, PlaybackRules.completionThresholdMs(300_000L))
        assertEquals(30_000L, PlaybackRules.completionThresholdMs(twoHours))
    }

    @Test
    fun episodeStoppedAtOrJustBeforeTheEndIsCompleted() {
        assertTrue(PlaybackRules.isCompleted(twoHours, twoHours))
        assertTrue(PlaybackRules.isCompleted(twoHours - 29_000L, twoHours))
        assertFalse(PlaybackRules.isCompleted(twoHours - 31_000L, twoHours))
    }

    @Test
    fun unknownDurationIsNeverCompleted() {
        assertFalse(PlaybackRules.isCompleted(10_000L, 0L))
    }

    @Test
    fun smartRewindGrowsWithPauseDuration() {
        assertEquals(0L, PlaybackRules.smartRewindMs(3_000L))
        assertEquals(2_000L, PlaybackRules.smartRewindMs(30_000L))
        assertEquals(5_000L, PlaybackRules.smartRewindMs(2 * 60_000L))
        assertEquals(10_000L, PlaybackRules.smartRewindMs(10 * 60_000L))
        assertEquals(15_000L, PlaybackRules.smartRewindMs(2 * 60 * 60_000L))
    }

    @Test
    fun resumePositionBacksUpSlightlyAndIgnoresFinishedEpisodes() {
        fun state(position: Long, completed: Boolean = false) =
            PlaybackStateEntity(episodeId = 1, positionMs = position, lastPlayedAt = 0L, completed = completed)

        assertEquals(0L, PlaybackRules.resumePosition(null, twoHours, true))
        assertEquals(0L, PlaybackRules.resumePosition(state(600_000L, completed = true), twoHours, true))
        assertEquals(0L, PlaybackRules.resumePosition(state(twoHours - 10_000L), twoHours, true))
        assertEquals(0L, PlaybackRules.resumePosition(state(3_000L), twoHours, true))
        assertEquals(597_000L, PlaybackRules.resumePosition(state(600_000L), twoHours, true))
        assertEquals(600_000L, PlaybackRules.resumePosition(state(600_000L), twoHours, false))
    }
}
