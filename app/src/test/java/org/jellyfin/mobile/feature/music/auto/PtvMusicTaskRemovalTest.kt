package org.jellyfin.mobile.feature.music.auto

import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import org.jellyfin.mobile.feature.music.MusicItem
import org.jellyfin.mobile.feature.music.MusicPlaybackState
import org.jellyfin.sdk.model.api.BaseItemKind
import org.junit.jupiter.api.Test
import java.util.UUID

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

    @Test
    fun `transient focus suppression retains requested playback but intentional pause releases it`() {
        val item = MusicItem(
            id = UUID.randomUUID(), title = "Track", subtitle = null, album = null, albumId = null,
            artist = null, artistIds = emptyList(), genres = emptyList(), type = BaseItemKind.AUDIO,
            collectionType = null, posterUrl = null, backdropUrl = null, container = null,
            codec = null, playCount = 0, progress = null, isFavorite = false,
            isFolder = false, isPlayable = true,
        )
        val suppressed = MusicPlaybackState(queue = listOf(item), currentItem = item, currentIndex = 0)

        shouldKeepPtvMusicServiceOnTaskRemoved(
            foregroundStartRequested = false,
            playbackState = suppressed,
            playbackRequested = true,
        ).shouldBeTrue()
        requiresPtvMusicForeground(
            media3Required = false,
            playbackState = suppressed,
            playbackRequested = true,
        ).shouldBeTrue()
        shouldKeepPtvMusicServiceOnTaskRemoved(
            foregroundStartRequested = false,
            playbackState = suppressed,
            playbackRequested = false,
        ).shouldBeFalse()
        requiresPtvMusicForeground(
            media3Required = false,
            playbackState = suppressed,
            playbackRequested = false,
        ).shouldBeFalse()
        requiresPtvMusicForeground(
            media3Required = false,
            playbackState = MusicPlaybackState(),
            playbackRequested = true,
        ).shouldBeFalse()
    }

    @Test
    fun `task removal retains a deferred Play only while matching proof remains pending`() {
        shouldKeepPtvMusicServiceOnTaskRemoved(
            foregroundStartRequested = false,
            playbackState = MusicPlaybackState(),
            playbackRequested = false,
            pendingPlayAwaitingProof = true,
        ).shouldBeTrue()
        shouldKeepPtvMusicServiceOnTaskRemoved(
            foregroundStartRequested = false,
            playbackState = MusicPlaybackState(),
            playbackRequested = false,
            pendingPlayAwaitingProof = false,
        ).shouldBeFalse()
        requiresPtvMusicForeground(
            media3Required = false,
            playbackState = MusicPlaybackState(),
            playbackRequested = false,
        ).shouldBeFalse()
    }
}
