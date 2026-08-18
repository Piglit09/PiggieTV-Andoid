package org.jellyfin.mobile.feature.music.auto

import io.kotest.matchers.shouldBe
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test

class PtvMusicSessionInvalidatorTest {
    @Test
    fun `invalidating clears saved state and stops an attached player`() {
        val resumeStore = mockk<PtvMusicAutoResumeStore>(relaxed = true)
        val invalidator = PtvMusicSessionInvalidator(resumeStore)
        val reasons = mutableListOf<String>()
        invalidator.attach(reasons::add)

        invalidator.invalidate("logout")

        verify(exactly = 1) { resumeStore.clearPlaybackState() }
        reasons shouldBe listOf("logout")
    }

    @Test
    fun `invalidating without a player still clears saved state`() {
        val resumeStore = mockk<PtvMusicAutoResumeStore>(relaxed = true)
        val invalidator = PtvMusicSessionInvalidator(resumeStore)

        invalidator.invalidate("serverChanged")

        verify(exactly = 1) { resumeStore.clearPlaybackState() }
    }
}
