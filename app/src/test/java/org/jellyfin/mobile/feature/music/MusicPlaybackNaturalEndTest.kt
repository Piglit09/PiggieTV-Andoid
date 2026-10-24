package org.jellyfin.mobile.feature.music

import androidx.media3.common.Player
import io.mockk.mockk
import io.mockk.verifyOrder
import org.junit.jupiter.api.Test

class MusicPlaybackNaturalEndTest {
    @Test
    fun `natural queue end clears the play request before stopping`() {
        val player = mockk<Player>(relaxed = true)

        stopMusicPlayerAtNaturalQueueEnd(player)

        verifyOrder {
            player.pause()
            player.stop()
        }
    }
}
