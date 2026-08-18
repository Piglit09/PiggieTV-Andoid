package org.jellyfin.mobile.feature.music.auto

import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.jellyfin.mobile.feature.music.MusicItem
import org.jellyfin.mobile.feature.music.MusicPlaybackController
import org.jellyfin.mobile.feature.music.MusicPlaybackState
import org.jellyfin.mobile.feature.music.MusicRepeatMode
import org.jellyfin.mobile.feature.music.MusicRepository
import org.jellyfin.sdk.model.api.BaseItemKind
import org.junit.jupiter.api.Test
import java.io.IOException
import java.util.UUID

class PtvMusicAutoResumeCoordinatorTest {
    @Test
    fun `transient restore failure can retry and then latches success`() = runBlocking<Unit> {
        val item = playableItem(UUID.fromString(ITEM_ID))
        val repository = mockk<MusicRepository>()
        val playbackController = mockk<MusicPlaybackController>()
        val resumeStore = mockk<PtvMusicAutoResumeStore>(relaxed = true)
        every { playbackController.state } returns MutableStateFlow(MusicPlaybackState())
        every { resumeStore.loadPlaybackState() } returns savedState()
        every { playbackController.restoreQueue(any(), any(), any(), any(), any()) } returns true
        var attempts = 0
        coEvery { repository.loadItems(any()) } answers {
            if (attempts++ == 0) throw IOException("temporary") else listOf(item)
        }
        val coordinator = PtvMusicAutoResumeCoordinator(repository, playbackController, resumeStore)

        coordinator.restoreQueueIfNeeded().shouldBeInstanceOf<PtvMusicAutoRestoreResult.Failed>()
        coordinator.restoreQueueIfNeeded().shouldBeInstanceOf<PtvMusicAutoRestoreResult.Restored>()
        coordinator.restoreQueueIfNeeded().shouldBeInstanceOf<PtvMusicAutoRestoreResult.AlreadyAttempted>()
    }

    @Test
    fun `rejected queue restore remains retryable`() = runBlocking<Unit> {
        val repository = mockk<MusicRepository>()
        val playbackController = mockk<MusicPlaybackController>()
        val resumeStore = mockk<PtvMusicAutoResumeStore>(relaxed = true)
        every { playbackController.state } returns MutableStateFlow(MusicPlaybackState())
        every { resumeStore.loadPlaybackState() } returns savedState()
        coEvery { repository.loadItems(any()) } returns listOf(playableItem(UUID.fromString(ITEM_ID)))
        every { playbackController.restoreQueue(any(), any(), any(), any(), any()) } returns false
        val coordinator = PtvMusicAutoResumeCoordinator(repository, playbackController, resumeStore)

        coordinator.restoreQueueIfNeeded().shouldBeInstanceOf<PtvMusicAutoRestoreResult.Failed>()
        coordinator.restoreQueueIfNeeded().shouldBeInstanceOf<PtvMusicAutoRestoreResult.Failed>()
    }

    private fun savedState() = PtvMusicAutoResumeState(
        queueItemIds = listOf(ITEM_ID),
        currentIndex = 0,
        positionMs = 12_000,
        shuffleEnabled = false,
        repeatMode = MusicRepeatMode.NONE.name,
        lastPlayedTimestampMs = 1,
        ownerServerId = 1,
        ownerUserId = 2,
    )

    private fun playableItem(id: UUID) = MusicItem(
        id = id,
        title = "Track",
        subtitle = "Artist",
        album = "Album",
        albumId = null,
        artist = "Artist",
        artistIds = emptyList(),
        genres = emptyList(),
        type = BaseItemKind.AUDIO,
        collectionType = null,
        posterUrl = null,
        backdropUrl = null,
        container = "mp3",
        codec = "mp3",
        playCount = 0,
        progress = null,
        isFavorite = false,
        isFolder = false,
        isPlayable = true,
    )

    private companion object {
        const val ITEM_ID = "11111111-1111-1111-1111-111111111111"
    }
}
