package org.jellyfin.mobile.feature.music.auto

import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import org.jellyfin.mobile.feature.music.MusicPlaybackState
import org.junit.jupiter.api.Test

class PtvMusicTaskRemovalTest {
    @Test
    fun `task removal keeps service while playback is active before foreground promotion`() {
        shouldKeepPtvMusicServiceOnTaskRemoved(
            foregroundStartRequested = false,
            playbackState = MusicPlaybackState(isPlaying = true),
        ).shouldBeTrue()
        shouldKeepPtvMusicServiceOnTaskRemoved(
            foregroundStartRequested = false,
            playbackState = MusicPlaybackState(isBuffering = true),
        ).shouldBeTrue()
    }

    @Test
    fun `task removal keeps foreground service and releases inactive background service`() {
        shouldKeepPtvMusicServiceOnTaskRemoved(
            foregroundStartRequested = true,
            playbackState = MusicPlaybackState(),
        ).shouldBeTrue()
        shouldKeepPtvMusicServiceOnTaskRemoved(
            foregroundStartRequested = false,
            playbackState = MusicPlaybackState(),
        ).shouldBeFalse()
    }
}
