package org.jellyfin.mobile.feature.music.auto

import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
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
    fun `foreign resident queue is discarded before second account resume`() = runBlocking<Unit> {
        val repository = mockk<MusicRepository>()
        val playbackController = mockk<MusicPlaybackController>()
        val resumeStore = mockk<PtvMusicAutoResumeStore>(relaxed = true)
        val item = playableItem(UUID.fromString(ITEM_ID))
        val state = MutableStateFlow(
            MusicPlaybackState(queue = listOf(item), currentItem = item, queueOwner = PtvMusicAutoQueueOwner(1, 2)),
        )
        every { playbackController.state } returns state
        every { playbackController.isCurrentQueueOwnedByAuthenticatedUser() } returns false
        every { playbackController.discardQueueIfOwnershipChanged() } answers {
            state.value = MusicPlaybackState()
            true
        }
        every { resumeStore.currentLocalQueueOwner() } returns PtvMusicAutoQueueOwner(1, 3)
        every { resumeStore.loadPlaybackState() } returns savedState(ownerUserId = 3)
        coEvery { repository.loadItems(any()) } returns listOf(item)
        every { playbackController.restoreQueue(any(), any(), any(), any(), any(), any()) } returns true

        PtvMusicAutoResumeCoordinator(repository, playbackController, resumeStore)
            .restoreQueueIfNeeded().shouldBeInstanceOf<PtvMusicAutoRestoreResult.Restored>()
        verify(exactly = 1) { playbackController.discardQueueIfOwnershipChanged() }
        verify(exactly = 0) { resumeStore.clearPlaybackState() }
    }

    @Test
    fun `completed restore check for first account does not block second account`() = runBlocking<Unit> {
        val repository = mockk<MusicRepository>()
        val playbackController = mockk<MusicPlaybackController>()
        val resumeStore = mockk<PtvMusicAutoResumeStore>(relaxed = true)
        var currentOwner = PtvMusicAutoQueueOwner(1, 2)
        every { playbackController.state } returns MutableStateFlow(MusicPlaybackState())
        every { resumeStore.currentLocalQueueOwner() } answers { currentOwner }
        every { resumeStore.loadPlaybackState() } answers {
            if (currentOwner.userId == 2L) null else savedState(ownerUserId = 3)
        }
        coEvery { repository.loadItems(any()) } returns listOf(playableItem(UUID.fromString(ITEM_ID)))
        every { playbackController.restoreQueue(any(), any(), any(), any(), any(), any()) } returns true
        val coordinator = PtvMusicAutoResumeCoordinator(repository, playbackController, resumeStore)

        coordinator.restoreQueueIfNeeded().shouldBeInstanceOf<PtvMusicAutoRestoreResult.NoSavedState>()
        currentOwner = PtvMusicAutoQueueOwner(1, 3)
        coordinator.restoreQueueIfNeeded().shouldBeInstanceOf<PtvMusicAutoRestoreResult.Restored>()
        verify { playbackController.restoreQueue(any(), any(), any(), any(), any(), PtvMusicAutoQueueOwner(1, 3)) }
    }

    @Test
    fun `transient restore failure can retry and then latches success`() = runBlocking<Unit> {
        val item = playableItem(UUID.fromString(ITEM_ID))
        val repository = mockk<MusicRepository>()
        val playbackController = mockk<MusicPlaybackController>()
        val resumeStore = mockk<PtvMusicAutoResumeStore>(relaxed = true)
        every { playbackController.state } returns MutableStateFlow(MusicPlaybackState())
        every { resumeStore.loadPlaybackState() } returns savedState()
        every { resumeStore.currentLocalQueueOwner() } returns PtvMusicAutoQueueOwner(1, 2)
        every { playbackController.restoreQueue(any(), any(), any(), any(), any(), any()) } returns true
        var attempts = 0
        coEvery { repository.loadItems(any()) } answers {
            if (attempts++ == 0) throw IOException("temporary") else listOf(item)
        }
        val coordinator = PtvMusicAutoResumeCoordinator(repository, playbackController, resumeStore)

        coordinator.restoreQueueIfNeeded().shouldBeInstanceOf<PtvMusicAutoRestoreResult.Failed>()
        coordinator.restoreQueueIfNeeded().shouldBeInstanceOf<PtvMusicAutoRestoreResult.Restored>()
        coordinator.restoreQueueIfNeeded().shouldBeInstanceOf<PtvMusicAutoRestoreResult.AlreadyAttempted>()
        verify { playbackController.restoreQueue(any(), any(), any(), any(), any(), PtvMusicAutoQueueOwner(1, 2)) }
    }

    @Test
    fun `rejected queue restore remains retryable`() = runBlocking<Unit> {
        val repository = mockk<MusicRepository>()
        val playbackController = mockk<MusicPlaybackController>()
        val resumeStore = mockk<PtvMusicAutoResumeStore>(relaxed = true)
        every { playbackController.state } returns MutableStateFlow(MusicPlaybackState())
        every { resumeStore.loadPlaybackState() } returns savedState()
        coEvery { repository.loadItems(any()) } returns listOf(playableItem(UUID.fromString(ITEM_ID)))
        every { resumeStore.currentLocalQueueOwner() } returns PtvMusicAutoQueueOwner(1, 2)
        every { playbackController.restoreQueue(any(), any(), any(), any(), any(), any()) } returns false
        val coordinator = PtvMusicAutoResumeCoordinator(repository, playbackController, resumeStore)

        coordinator.restoreQueueIfNeeded().shouldBeInstanceOf<PtvMusicAutoRestoreResult.Failed>()
        coordinator.restoreQueueIfNeeded().shouldBeInstanceOf<PtvMusicAutoRestoreResult.Failed>()
    }

    @Test
    fun `account switch while loading saved tracks cannot restore old queue`() = runBlocking<Unit> {
        val repository = mockk<MusicRepository>()
        val playbackController = mockk<MusicPlaybackController>()
        val resumeStore = mockk<PtvMusicAutoResumeStore>(relaxed = true)
        var currentOwner = PtvMusicAutoQueueOwner(1, 2)
        every { playbackController.state } returns MutableStateFlow(MusicPlaybackState())
        every { resumeStore.loadPlaybackState() } returns savedState()
        every { resumeStore.currentLocalQueueOwner() } answers { currentOwner }
        coEvery { repository.loadItems(any()) } coAnswers {
            currentOwner = PtvMusicAutoQueueOwner(1, 3)
            listOf(playableItem(UUID.fromString(ITEM_ID)))
        }
        val coordinator = PtvMusicAutoResumeCoordinator(repository, playbackController, resumeStore)

        coordinator.restoreQueueIfNeeded().shouldBeInstanceOf<PtvMusicAutoRestoreResult.Failed>()
        verify(exactly = 0) { playbackController.restoreQueue(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `restore rebuilds repeated occurrences in saved order from unique repository items`() = runBlocking<Unit> {
        val firstId = UUID.fromString(ITEM_ID)
        val secondId = UUID.fromString("22222222-2222-2222-2222-222222222222")
        val thirdId = UUID.fromString("33333333-3333-3333-3333-333333333333")
        val first = playableItem(firstId)
        val second = playableItem(secondId)
        val third = playableItem(thirdId)
        val repository = mockk<MusicRepository>()
        val playbackController = mockk<MusicPlaybackController>()
        val resumeStore = mockk<PtvMusicAutoResumeStore>(relaxed = true)
        val owner = PtvMusicAutoQueueOwner(1, 2)
        every { playbackController.state } returns MutableStateFlow(MusicPlaybackState())
        every { resumeStore.currentLocalQueueOwner() } returns owner
        every { resumeStore.loadPlaybackState() } returns savedState().copy(
            queueItemIds = listOf(firstId, secondId, firstId, thirdId).map(UUID::toString),
            currentIndex = 2,
        )
        coEvery { repository.loadItems(any()) } returns listOf(third, first, second)
        every { playbackController.restoreQueue(any(), any(), any(), any(), any(), any()) } returns true

        val result = PtvMusicAutoResumeCoordinator(repository, playbackController, resumeStore)
            .restoreQueueIfNeeded().shouldBeInstanceOf<PtvMusicAutoRestoreResult.Restored>()

        result.restoredCount shouldBe 4
        result.missingCount shouldBe 0
        coVerify(exactly = 1) { repository.loadItems(listOf(firstId, secondId, thirdId)) }
        verify(exactly = 1) {
            playbackController.restoreQueue(
                listOf(first, second, first, third),
                2,
                12_000,
                false,
                MusicRepeatMode.NONE,
                owner,
            )
        }
    }

    @Test
    fun `missing earlier item rebases selected repeated occurrence without deduping it`() = runBlocking<Unit> {
        val firstId = UUID.fromString(ITEM_ID)
        val missingId = UUID.fromString("22222222-2222-2222-2222-222222222222")
        val first = playableItem(firstId)
        val repository = mockk<MusicRepository>()
        val playbackController = mockk<MusicPlaybackController>()
        val resumeStore = mockk<PtvMusicAutoResumeStore>(relaxed = true)
        val owner = PtvMusicAutoQueueOwner(1, 2)
        every { playbackController.state } returns MutableStateFlow(MusicPlaybackState())
        every { resumeStore.currentLocalQueueOwner() } returns owner
        every { resumeStore.loadPlaybackState() } returns savedState().copy(
            queueItemIds = listOf(firstId, missingId, firstId).map(UUID::toString),
            currentIndex = 2,
        )
        coEvery { repository.loadItems(any()) } returns listOf(first)
        every { playbackController.restoreQueue(any(), any(), any(), any(), any(), any()) } returns true

        val result = PtvMusicAutoResumeCoordinator(repository, playbackController, resumeStore)
            .restoreQueueIfNeeded().shouldBeInstanceOf<PtvMusicAutoRestoreResult.Restored>()

        result.restoredCount shouldBe 2
        result.missingCount shouldBe 1
        verify(exactly = 1) {
            playbackController.restoreQueue(listOf(first, first), 1, 12_000, false, MusicRepeatMode.NONE, owner)
        }
    }

    private fun savedState(ownerUserId: Long = 2) = PtvMusicAutoResumeState(
        queueItemIds = listOf(ITEM_ID),
        currentIndex = 0,
        positionMs = 12_000,
        shuffleEnabled = false,
        repeatMode = MusicRepeatMode.NONE.name,
        lastPlayedTimestampMs = 1,
        ownerServerId = 1,
        ownerUserId = ownerUserId,
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
