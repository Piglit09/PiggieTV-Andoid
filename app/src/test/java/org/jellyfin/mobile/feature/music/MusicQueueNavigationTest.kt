package org.jellyfin.mobile.feature.music

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class MusicQueueNavigationTest {
    @Test
    fun `natural track end advances to next track`() {
        MusicQueueNavigation.trackEndDecision(
            currentIndex = 0,
            queueSize = 3,
            repeatMode = MusicRepeatMode.NONE,
        ) shouldBe MusicTrackEndDecision.AdvanceTo(1)
    }

    @Test
    fun `repeat one restarts current track`() {
        MusicQueueNavigation.trackEndDecision(
            currentIndex = 1,
            queueSize = 3,
            repeatMode = MusicRepeatMode.ONE,
        ) shouldBe MusicTrackEndDecision.RestartCurrent
    }

    @Test
    fun `repeat all wraps at queue end`() {
        MusicQueueNavigation.trackEndDecision(
            currentIndex = 2,
            queueSize = 3,
            repeatMode = MusicRepeatMode.ALL,
        ) shouldBe MusicTrackEndDecision.AdvanceTo(0)
    }

    @Test
    fun `queue end with repeat off stops cleanly`() {
        MusicQueueNavigation.trackEndDecision(
            currentIndex = 2,
            queueSize = 3,
            repeatMode = MusicRepeatMode.NONE,
        ) shouldBe MusicTrackEndDecision.Stop
    }

    @Test
    fun `next skips failed queue items`() {
        MusicQueueNavigation.nextIndex(
            currentIndex = 0,
            queueSize = 4,
            repeatMode = MusicRepeatMode.NONE,
            failedIndexes = setOf(1, 2),
        ) shouldBe 3
    }

    @Test
    fun `previous wraps only when repeat all is enabled`() {
        MusicQueueNavigation.previousIndex(
            currentIndex = 0,
            queueSize = 3,
            repeatMode = MusicRepeatMode.NONE,
        ) shouldBe null

        MusicQueueNavigation.previousIndex(
            currentIndex = 0,
            queueSize = 3,
            repeatMode = MusicRepeatMode.ALL,
        ) shouldBe 2
    }

    @Test
    fun `position fallback detects playback end near duration`() {
        MusicQueueNavigation.hasReachedPlaybackEnd(
            positionMs = 249_500,
            durationMs = 250_000,
            toleranceMs = 750,
        ) shouldBe true

        MusicQueueNavigation.hasReachedPlaybackEnd(
            positionMs = 248_000,
            durationMs = 250_000,
            toleranceMs = 750,
        ) shouldBe false
    }

    @Test
    fun `position fallback ignores unknown duration`() {
        MusicQueueNavigation.hasReachedPlaybackEnd(
            positionMs = 250_000,
            durationMs = 0,
            toleranceMs = 750,
        ) shouldBe false
    }
}
