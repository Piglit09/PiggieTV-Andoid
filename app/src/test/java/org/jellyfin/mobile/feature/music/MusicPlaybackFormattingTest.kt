package org.jellyfin.mobile.feature.music

import io.kotest.matchers.floats.shouldBeExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class MusicPlaybackFormattingTest {
    @Test
    fun `unknown duration uses placeholder instead of negative time`() {
        MusicPlaybackFormatting.durationTime(0) shouldBe "--:--"
        MusicPlaybackFormatting.durationTime(-1) shouldBe "--:--"
        MusicPlaybackFormatting.remainingTime(durationMs = 0, positionMs = 12_000) shouldBe "--:--"
    }

    @Test
    fun `elapsed time clamps negative values`() {
        MusicPlaybackFormatting.elapsedTime(-5_000) shouldBe "0:00"
    }

    @Test
    fun `remaining time never goes negative`() {
        MusicPlaybackFormatting.remainingTime(durationMs = 10_000, positionMs = 12_000) shouldBe "0:00"
        MusicPlaybackState(durationMs = 10_000, positionMs = 12_000).remainingMs shouldBe 0
    }

    @Test
    fun `progress fraction clamps to valid range`() {
        MusicPlaybackFormatting.progressFraction(positionMs = 15_000, durationMs = 10_000) shouldBeExactly 1f
        MusicPlaybackFormatting.progressFraction(positionMs = -5_000, durationMs = 10_000) shouldBeExactly 0f
        MusicPlaybackFormatting.progressFraction(positionMs = 5_000, durationMs = 0) shouldBeExactly 0f
    }
}
