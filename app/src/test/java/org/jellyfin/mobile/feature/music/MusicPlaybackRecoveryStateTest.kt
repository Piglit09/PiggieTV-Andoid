package org.jellyfin.mobile.feature.music

import androidx.media3.common.C
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.UUID

class MusicPlaybackRecoveryStateTest {
    @Test
    fun `preparing a retry does not reset its failure budget`() {
        val itemId = UUID.randomUUID()
        val recovery = MusicPlaybackRecoveryState(maxRetryAttempts = 1)

        recovery.nextRetryAttempt(itemId) shouldBe 0
        recovery.markPrepared(itemId)
        recovery.nextRetryAttempt(itemId) shouldBe null

        recovery.markTrackCompleted(itemId)
        recovery.nextRetryAttempt(itemId) shouldBe 0
    }

    @Test
    fun `stable playback restores the retry budget only for the prepared track`() {
        val itemId = UUID.randomUUID()
        val otherItemId = UUID.randomUUID()
        val recovery = MusicPlaybackRecoveryState(maxRetryAttempts = 1)

        recovery.nextRetryAttempt(itemId) shouldBe 0
        recovery.markPrepared(itemId)
        recovery.markStablePlayback(otherItemId)
        recovery.nextRetryAttempt(itemId) shouldBe null
        recovery.markStablePlayback(itemId)
        recovery.nextRetryAttempt(itemId) shouldBe 0
    }

    @Test
    fun `failed track resumes at the live position only when player owns it`() {
        val itemId = UUID.randomUUID()
        val recovery = MusicPlaybackRecoveryState(maxRetryAttempts = 1)

        recovery.resumePositionMs(itemId, itemId.toString(), 133_000L, 132_500L) shouldBe 133_000L
        recovery.resumePositionMs(itemId, UUID.randomUUID().toString(), 44_000L, 132_500L) shouldBe 132_500L
        recovery.resumePositionMs(itemId, itemId.toString(), C.TIME_UNSET, 132_500L) shouldBe 132_500L
    }
}
