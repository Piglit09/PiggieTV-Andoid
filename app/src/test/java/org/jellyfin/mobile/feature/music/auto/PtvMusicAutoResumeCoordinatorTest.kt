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
import org.jellyfin.mobile.feature.music.MusicPlaybackQueueOrder
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

    @Test
    fun `playlist restore reloads the full authorized queue beyond the saved window`() = runBlocking<Unit> {
        val playlistId = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")
        val playlist = playableItem(playlistId).copy(type = BaseItemKind.PLAYLIST, isPlayable = false, isFolder = true)
        val tracks = (0 until 120).map { index ->
            playableItem(UUID.fromString("00000000-0000-0000-0000-${index.toString().padStart(12, '0')}"))
        }
        val owner = PtvMusicAutoQueueOwner(1, 2)
        val source = PtvMusicAutoQueueProvenance.playlist(playlistId, tracks, fullCurrentIndex = 110)
        val saved = savedState().copy(
            queueItemIds = tracks.drop(20).map { it.id.toString() },
            currentIndex = 90,
            fullQueueSha256 = source.queueSha256,
            fullCurrentIndex = 110,
            queueProvenance = source,
        )
        val repository = mockk<MusicRepository>()
        val playbackController = mockk<MusicPlaybackController>()
        val resumeStore = mockk<PtvMusicAutoResumeStore>(relaxed = true)
        every { playbackController.state } returns MutableStateFlow(MusicPlaybackState())
        every { resumeStore.currentLocalQueueOwner() } returns owner
        every { resumeStore.loadPlaybackState() } returns saved
        coEvery { repository.loadItem(playlistId) } returns playlist
        coEvery { repository.loadPlaylistQueue(playlist) } returns tracks
        every { playbackController.restoreQueue(any(), any(), any(), any(), any(), any()) } returns true

        val result = PtvMusicAutoResumeCoordinator(repository, playbackController, resumeStore)
            .restoreQueueIfNeeded().shouldBeInstanceOf<PtvMusicAutoRestoreResult.Restored>()

        result.restoredCount shouldBe 120
        verify(exactly = 1) {
            playbackController.restoreQueue(tracks, 110, 12_000, false, MusicRepeatMode.NONE, owner)
        }
        coVerify(exactly = 0) { repository.loadItems(any()) }
    }

    @Test
    fun `shuffled playlist cold restore retains full queue and selected repeated occurrence`() = runBlocking<Unit> {
        val playlistId = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")
        val playlist = playableItem(playlistId).copy(type = BaseItemKind.PLAYLIST, isPlayable = false, isFolder = true)
        val authored = (0 until 120).map { index ->
            playableItem(UUID.fromString("00000000-0000-0000-0000-${index.toString().padStart(12, '0')}"))
        }.toMutableList().apply { this[5] = this[110] }
        val selected = authored[110]
        val shuffled = listOf(selected) + authored.take(110) + authored.drop(111)
        val source = PtvMusicAutoQueueProvenance.playlist(playlistId, authored, fullCurrentIndex = 110)
            .forQueue(shuffled, 0, authored, 110)
        val saved = savedState().copy(
            queueItemIds = shuffled.take(100).map { it.id.toString() },
            currentIndex = 0,
            shuffleEnabled = true,
            fullQueueSha256 = source.queueSha256,
            fullCurrentIndex = 0,
            queueProvenance = source,
        )
        val owner = PtvMusicAutoQueueOwner(1, 2)
        val repository = mockk<MusicRepository>()
        val playbackController = mockk<MusicPlaybackController>()
        val resumeStore = mockk<PtvMusicAutoResumeStore>(relaxed = true)
        every { playbackController.state } returns MutableStateFlow(MusicPlaybackState())
        every { resumeStore.currentLocalQueueOwner() } returns owner
        every { resumeStore.loadPlaybackState() } returns saved
        coEvery { repository.loadItem(playlistId) } returns playlist
        coEvery { repository.loadPlaylistQueue(playlist) } returns authored
        every { playbackController.restoreShuffledPlaylistQueue(any(), any(), any(), any(), any(), any()) } answers {
            val authenticatedQueue = firstArg<List<MusicItem>>()
            val order = secondArg<MusicPlaybackQueueOrder>()
            val restoredSource = arg<PtvMusicAutoQueueProvenance>(5)
            authenticatedQueue == authored && order.queue.size == 120 &&
                order.originalIndices.sorted() == authored.indices.toList() &&
                order.originalIndices[order.currentIndex] == 110 &&
                order.queue[order.currentIndex].id == selected.id &&
                order.queue.count { it.id == selected.id } == 2 &&
                restoredSource.selectedAuthoredIndex == 110
        }

        val result = PtvMusicAutoResumeCoordinator(repository, playbackController, resumeStore).restoreQueueIfNeeded()

        result.shouldBeInstanceOf<PtvMusicAutoRestoreResult.Restored>().restoredCount shouldBe authored.size
        verify(exactly = 1) {
            playbackController.restoreShuffledPlaylistQueue(
                authored,
                any(),
                12_000,
                MusicRepeatMode.NONE,
                owner,
                source,
            )
        }
        verify(exactly = 0) { playbackController.restoreQueue(any(), any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { repository.loadItems(any()) }
    }

    @Test
    fun `failed shuffled playlist restore does not fall back to the saved queue window`() = runBlocking<Unit> {
        val playlistId = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")
        val playlist = playableItem(playlistId).copy(type = BaseItemKind.PLAYLIST, isPlayable = false, isFolder = true)
        val authored = listOf(playableItem(UUID.fromString(ITEM_ID)))
        val source = PtvMusicAutoQueueProvenance.playlist(playlistId, authored, 0)
        val saved = savedState().copy(
            shuffleEnabled = true,
            fullQueueSha256 = source.queueSha256,
            fullCurrentIndex = 0,
            queueProvenance = source,
        )
        val owner = PtvMusicAutoQueueOwner(1, 2)
        val repository = mockk<MusicRepository>()
        val playbackController = mockk<MusicPlaybackController>()
        val resumeStore = mockk<PtvMusicAutoResumeStore>(relaxed = true)
        every { playbackController.state } returns MutableStateFlow(MusicPlaybackState())
        every { resumeStore.currentLocalQueueOwner() } returns owner
        every { resumeStore.loadPlaybackState() } returns saved
        coEvery { repository.loadItem(playlistId) } returns playlist
        coEvery { repository.loadPlaylistQueue(playlist) } returns authored
        every {
            playbackController.restoreShuffledPlaylistQueue(
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
            )
        } returns false

        PtvMusicAutoResumeCoordinator(repository, playbackController, resumeStore)
            .restoreQueueIfNeeded() shouldBe PtvMusicAutoRestoreResult.Failed

        verify(exactly = 1) {
            playbackController.restoreShuffledPlaylistQueue(
                authored,
                any(),
                12_000,
                MusicRepeatMode.NONE,
                owner,
                source,
            )
        }
        verify(exactly = 0) { playbackController.restoreQueue(any(), any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { repository.loadItems(any()) }
    }

    @Test
    fun `changed playlist window cannot restore a misleading partial queue`() = runBlocking<Unit> {
        val playlistId = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")
        val playlist = playableItem(playlistId).copy(type = BaseItemKind.PLAYLIST, isPlayable = false, isFolder = true)
        val tracks = (0 until 120).map { index ->
            playableItem(UUID.fromString("00000000-0000-0000-0000-${index.toString().padStart(12, '0')}"))
        }
        val source = PtvMusicAutoQueueProvenance.playlist(playlistId, tracks, fullCurrentIndex = 110)
        val saved = savedState().copy(
            queueItemIds = tracks.drop(20).map { it.id.toString() },
            currentIndex = 90,
            fullQueueSha256 = source.queueSha256,
            fullCurrentIndex = 110,
            queueProvenance = source,
        )
        val repository = mockk<MusicRepository>()
        val playbackController = mockk<MusicPlaybackController>()
        val resumeStore = mockk<PtvMusicAutoResumeStore>(relaxed = true)
        every { playbackController.state } returns MutableStateFlow(MusicPlaybackState())
        every { resumeStore.currentLocalQueueOwner() } returns PtvMusicAutoQueueOwner(1, 2)
        every { resumeStore.loadPlaybackState() } returns saved
        coEvery { repository.loadItem(playlistId) } returns playlist
        coEvery { repository.loadPlaylistQueue(playlist) } returns tracks.drop(1)

        PtvMusicAutoResumeCoordinator(repository, playbackController, resumeStore)
            .restoreQueueIfNeeded() shouldBe PtvMusicAutoRestoreResult.Failed
        verify(exactly = 0) { playbackController.restoreQueue(any(), any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { repository.loadItems(any()) }
    }

    @Test
    fun `playlist restore rejects provenance inconsistent with the saved full queue`() = runBlocking<Unit> {
        val playlistId = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")
        val playlist = playableItem(playlistId).copy(type = BaseItemKind.PLAYLIST, isPlayable = false, isFolder = true)
        val track = playableItem(UUID.fromString(ITEM_ID))
        val source = PtvMusicAutoQueueProvenance.playlist(playlistId, listOf(track), fullCurrentIndex = 0)
        val saved = savedState().copy(
            fullQueueSha256 = "unrelated-queue",
            fullCurrentIndex = 0,
            queueProvenance = source,
        )
        val repository = mockk<MusicRepository>()
        val playbackController = mockk<MusicPlaybackController>()
        val resumeStore = mockk<PtvMusicAutoResumeStore>(relaxed = true)
        every { playbackController.state } returns MutableStateFlow(MusicPlaybackState())
        every { resumeStore.currentLocalQueueOwner() } returns PtvMusicAutoQueueOwner(1, 2)
        every { resumeStore.loadPlaybackState() } returns saved
        coEvery { repository.loadItem(playlistId) } returns playlist
        coEvery { repository.loadPlaylistQueue(playlist) } returns listOf(track)
        every { playbackController.restoreQueue(any(), any(), any(), any(), any(), any()) } returns true

        PtvMusicAutoResumeCoordinator(repository, playbackController, resumeStore)
            .restoreQueueIfNeeded() shouldBe PtvMusicAutoRestoreResult.Failed
        verify(exactly = 0) { playbackController.restoreQueue(any(), any(), any(), any(), any(), any()) }
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
