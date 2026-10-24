package org.jellyfin.mobile.feature.music.auto

import android.content.Context
import android.content.res.Resources
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.text.TextUtils
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.mockkStatic
import io.mockk.unmockkConstructor
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.jellyfin.mobile.auth.AuthFailureCategory
import org.jellyfin.mobile.auth.AuthMethod
import org.jellyfin.mobile.auth.MobileAuthState
import org.jellyfin.mobile.feature.music.MusicBrowseKind
import org.jellyfin.mobile.feature.music.MusicItem
import org.jellyfin.mobile.feature.music.MusicListeningMode
import org.jellyfin.mobile.feature.music.MusicPage
import org.jellyfin.mobile.feature.music.MusicPlaybackController
import org.jellyfin.mobile.feature.music.MusicPlaybackState
import org.jellyfin.mobile.feature.music.MusicPlaybackTransitionEvent
import org.jellyfin.mobile.feature.music.MusicPlaybackTransitionReason
import org.jellyfin.mobile.feature.music.MusicRecommendationBatch
import org.jellyfin.mobile.feature.music.MusicRecommendationSessionContext
import org.jellyfin.mobile.feature.music.MusicRecommendationSessionOwner
import org.jellyfin.mobile.feature.music.MusicRepository
import org.jellyfin.mobile.feature.music.MusicSongActionHandler
import org.jellyfin.sdk.model.api.BaseItemKind
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class PtvMusicAutoBrowseRecoveryTest : PtvMusicAutoBrowseFixture() {
    @Test
    fun `missing saved login shows a recoverable root instead of unusable music rows`() {
        val rows = ptvMusicAutoRootItemsFor(MobileAuthState.Unauthenticated)

        rows.map(PtvMusicAutoRootItem::mediaId) shouldBe listOf("ptv:auto:status:sign-in")
        rows.single().isPlayable shouldBe false
        rows.single().isBrowsable shouldBe false
    }

    @Test
    fun `temporary connectivity failure does not appear as an empty music library`() {
        val rows = ptvMusicAutoRootItemsFor(
            MobileAuthState.ConnectivityError(AuthFailureCategory.NETWORK_UNAVAILABLE),
        )

        rows.map(PtvMusicAutoRootItem::mediaId) shouldBe listOf("ptv:auto:status:retry")
    }

    @Test
    fun `authenticated session restores normal browse root without an active player`() {
        val rows = ptvMusicAutoRootItemsFor(authenticated("server-1", "user-1"))

        rows.map(PtvMusicAutoRootItem::mediaId) shouldBe PTV_MUSIC_AUTO_ROOT_ITEMS.map(PtvMusicAutoRootItem::mediaId)
    }

    @Test
    fun `Auto browse offers three listening mode folders with Balanced selected by default`() {
        val callback = callback(MutableStateFlow(authenticated("server-1", "user-1")))
        val root = callback.onGetChildren(
            mockk<MediaLibraryService.MediaLibrarySession>(),
            mockk<MediaSession.ControllerInfo>(),
            PtvMusicAutoIds.ROOT,
            0,
            20,
            null,
        ).get(5, TimeUnit.SECONDS)
        root.value?.map(MediaItem::mediaId)?.contains(PtvMusicAutoIds.LISTENING_MODES) shouldBe true

        val modes = callback.onGetChildren(
            mockk<MediaLibraryService.MediaLibrarySession>(),
            mockk<MediaSession.ControllerInfo>(),
            PtvMusicAutoIds.LISTENING_MODES,
            0,
            10,
            null,
        ).get(5, TimeUnit.SECONDS)
        modes.resultCode shouldBe SessionResult.RESULT_SUCCESS
        modes.value?.map(MediaItem::mediaId) shouldBe PtvMusicAutoListeningMode.entries.map { it.mediaId }
        modes.value?.map { it.mediaMetadata.isPlayable } shouldBe listOf(false, false, false)
        modes.value?.map { it.mediaMetadata.isBrowsable } shouldBe listOf(true, true, true)
        modes.value?.single { it.mediaId == PtvMusicAutoListeningMode.BALANCED_DISCOVERY.mediaId }
            ?.mediaMetadata?.subtitle?.toString()?.contains("Selected") shouldBe true
    }

    @Test
    fun `opening a listening mode changes generated queue and does not start playback`() {
        val balancedTrack = track().copy(id = UUID(8L, 1L))
        val stayTrack = track().copy(id = UUID(8L, 2L))
        val repository = mockk<MusicRepository>()
        every { repository.cachedBrowsePage(any(), any(), any()) } returns null
        coEvery {
            repository.loadAutoPicksQueue(null, any(), MusicListeningMode.BALANCED_DISCOVERY)
        } returns listOf(balancedTrack)
        coEvery {
            repository.loadAutoPicksQueue(null, any(), MusicListeningMode.STAY_IN_THE_VIBE)
        } returns listOf(stayTrack)
        val playbackController = mockk<MusicPlaybackController>()
        every { playbackController.state } returns MutableStateFlow(MusicPlaybackState())
        every { playbackController.isCurrentQueueOwnedByAuthenticatedUser() } returns false
        val callback = callback(
            MutableStateFlow(authenticated("server-1", "user-1")),
            repository,
            playbackController,
        )

        fun browse(parentId: String) = callback.onGetChildren(
            mockk<MediaLibraryService.MediaLibrarySession>(),
            mockk<MediaSession.ControllerInfo>(),
            parentId,
            0,
            10,
            null,
        ).get(5, TimeUnit.SECONDS)

        browse(PtvMusicAutoIds.AUTO_PICKS).value?.single()?.mediaId shouldBe PtvMusicAutoIds.track(balancedTrack.id)
        browse(PtvMusicAutoListeningMode.STAY_IN_THE_VIBE.mediaId).value?.single()?.mediaId shouldBe
            PtvMusicAutoIds.track(stayTrack.id)
        browse(PtvMusicAutoIds.AUTO_PICKS).value?.single()?.mediaId shouldBe PtvMusicAutoIds.track(stayTrack.id)
        browse(PtvMusicAutoIds.LISTENING_MODES).value
            ?.single { it.mediaId == PtvMusicAutoListeningMode.STAY_IN_THE_VIBE.mediaId }
            ?.mediaMetadata?.subtitle?.toString()?.contains("Selected") shouldBe true
        verify(exactly = 0) { playbackController.play(any(), any(), any(), any(), any()) }
        coVerify(exactly = 1) {
            repository.loadAutoPicksQueue(null, any(), MusicListeningMode.BALANCED_DISCOVERY)
        }
        coVerify(exactly = 1) {
            repository.loadAutoPicksQueue(null, any(), MusicListeningMode.STAY_IN_THE_VIBE)
        }
    }

    @Test
    fun `changing the playing seed evicts generated queues from the prior seed`() {
        val firstSeed = track().copy(id = UUID(14L, 1L))
        val secondSeed = track().copy(id = UUID(14L, 2L))
        val owner = PtvMusicAutoQueueOwner(1, 2)
        val resumeStore = mockk<PtvMusicAutoResumeStore>(relaxed = true)
        every { resumeStore.currentLocalQueueOwner() } returns owner
        val state = MutableStateFlow(
            MusicPlaybackState(
                queue = listOf(firstSeed),
                currentItem = firstSeed,
                currentIndex = 0,
                queueOwner = owner,
            ),
        )
        val playbackController = mockk<MusicPlaybackController>()
        every { playbackController.state } returns state
        every { playbackController.isCurrentQueueOwnedByAuthenticatedUser() } returns true
        val repository = mockk<MusicRepository>()
        coEvery { repository.loadAutoPicksQueue(any(), any(), MusicListeningMode.BALANCED_DISCOVERY) } answers {
            listOf(track())
        }
        val callback = callback(
            MutableStateFlow(recommendationAuthenticated()),
            repository,
            playbackController,
            resumeStore = resumeStore,
        )
        fun browse() = callback.onGetChildren(
            mockk<MediaLibraryService.MediaLibrarySession>(),
            mockk<MediaSession.ControllerInfo>(),
            PtvMusicAutoIds.AUTO_PICKS,
            0,
            10,
            null,
        ).get(5, TimeUnit.SECONDS)

        browse().resultCode shouldBe SessionResult.RESULT_SUCCESS
        state.value = state.value.copy(queue = listOf(secondSeed), currentItem = secondSeed)
        browse().resultCode shouldBe SessionResult.RESULT_SUCCESS
        state.value = state.value.copy(queue = listOf(firstSeed), currentItem = firstSeed)
        browse().resultCode shouldBe SessionResult.RESULT_SUCCESS

        coVerify(exactly = 2) {
            repository.loadAutoPicksQueue(firstSeed, any(), MusicListeningMode.BALANCED_DISCOVERY)
        }
        coVerify(exactly = 1) {
            repository.loadAutoPicksQueue(secondSeed, any(), MusicListeningMode.BALANCED_DISCOVERY)
        }
    }

    @Test
    fun `changing listening mode evicts the prior generated queue`() {
        val repository = mockk<MusicRepository>()
        coEvery { repository.loadAutoPicksQueue(null, any(), any()) } answers { listOf(track()) }
        val playbackController = mockk<MusicPlaybackController>()
        every { playbackController.state } returns MutableStateFlow(MusicPlaybackState())
        every { playbackController.isCurrentQueueOwnedByAuthenticatedUser() } returns false
        val callback = callback(
            MutableStateFlow(authenticated("server-1", "user-1")),
            repository,
            playbackController,
        )
        fun browse(parentId: String) = callback.onGetChildren(
            mockk<MediaLibraryService.MediaLibrarySession>(),
            mockk<MediaSession.ControllerInfo>(),
            parentId,
            0,
            10,
            null,
        ).get(5, TimeUnit.SECONDS)

        browse(PtvMusicAutoIds.AUTO_PICKS).resultCode shouldBe SessionResult.RESULT_SUCCESS
        browse(PtvMusicAutoListeningMode.STAY_IN_THE_VIBE.mediaId).resultCode shouldBe SessionResult.RESULT_SUCCESS
        browse(PtvMusicAutoListeningMode.BALANCED_DISCOVERY.mediaId).resultCode shouldBe SessionResult.RESULT_SUCCESS

        coVerify(exactly = 2) {
            repository.loadAutoPicksQueue(null, any(), MusicListeningMode.BALANCED_DISCOVERY)
        }
        coVerify(exactly = 1) {
            repository.loadAutoPicksQueue(null, any(), MusicListeningMode.STAY_IN_THE_VIBE)
        }
    }

    @Test
    fun `listening mode selection is reset when the authenticated account changes`() {
        val auth = MutableStateFlow<MobileAuthState>(authenticated("server-1", "user-1"))
        val localOwner = AtomicReference(PtvMusicAutoQueueOwner(1, 2))
        val resumeStore = mockk<PtvMusicAutoResumeStore>(relaxed = true)
        every { resumeStore.currentLocalQueueOwner() } answers { localOwner.get() }
        val stayTrack = track().copy(id = UUID(9L, 1L))
        val balancedTrack = track().copy(id = UUID(9L, 2L))
        val repository = mockk<MusicRepository>()
        every { repository.cachedBrowsePage(any(), any(), any()) } returns null
        coEvery {
            repository.loadAutoPicksQueue(null, any(), MusicListeningMode.STAY_IN_THE_VIBE)
        } returns listOf(stayTrack)
        coEvery {
            repository.loadAutoPicksQueue(null, any(), MusicListeningMode.BALANCED_DISCOVERY)
        } returns listOf(balancedTrack)
        val playbackController = mockk<MusicPlaybackController>()
        every { playbackController.state } returns MutableStateFlow(MusicPlaybackState())
        every { playbackController.isCurrentQueueOwnedByAuthenticatedUser() } returns false
        val callback = callback(auth, repository, playbackController, resumeStore = resumeStore)

        fun browse(parentId: String) = callback.onGetChildren(
            mockk<MediaLibraryService.MediaLibrarySession>(),
            mockk<MediaSession.ControllerInfo>(),
            parentId,
            0,
            10,
            null,
        ).get(5, TimeUnit.SECONDS)

        browse(PtvMusicAutoListeningMode.STAY_IN_THE_VIBE.mediaId).value?.single()?.mediaId shouldBe
            PtvMusicAutoIds.track(stayTrack.id)
        auth.value = authenticated("server-1", "user-2")
        callback.onAuthenticationChanged()
        browse(PtvMusicAutoIds.LISTENING_MODES).resultCode shouldBe SessionError.ERROR_NOT_SUPPORTED
        localOwner.set(PtvMusicAutoQueueOwner(1, 3))
        callback.onAuthenticationChanged()
        browse(PtvMusicAutoIds.LISTENING_MODES).value
            ?.single { it.mediaId == PtvMusicAutoListeningMode.BALANCED_DISCOVERY.mediaId }
            ?.mediaMetadata?.subtitle?.toString()?.contains("Selected") shouldBe true
        browse(PtvMusicAutoIds.AUTO_PICKS).value?.single()?.mediaId shouldBe PtvMusicAutoIds.track(balancedTrack.id)
        verify(exactly = 0) { playbackController.play(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `Auto Picks session refills once near queue tail without inferring a skip`() = runBlocking {
        val queue = (0 until 5).map { track().copy(id = UUID(10L, it.toLong())) }
        val next = track().copy(id = UUID(10L, 5L))
        val owner = PtvMusicAutoQueueOwner(1, 2)
        val resumeStore = mockk<PtvMusicAutoResumeStore>(relaxed = true)
        every { resumeStore.currentLocalQueueOwner() } returns owner
        val state = MutableStateFlow(MusicPlaybackState())
        val transitions = MutableSharedFlow<MusicPlaybackTransitionEvent>()
        val playbackController = mockk<MusicPlaybackController>(relaxed = true)
        every { playbackController.state } returns state
        every { playbackController.transitionEvents } returns transitions
        every { playbackController.isCurrentQueueOwnedByAuthenticatedUser() } returns true
        val sessionContext = MusicRecommendationSessionContext.start(
            MusicRecommendationSessionOwner("https://server-1", UUID(1L, 2L)),
            queue.first(),
            queue,
            MusicListeningMode.BALANCED_DISCOVERY,
        )
        val repository = mockk<MusicRepository>()
        coEvery { repository.loadAutoPicksQueue(null, any(), MusicListeningMode.BALANCED_DISCOVERY) } returns queue
        coEvery {
            repository.beginAutoRecommendationSession(queue.first(), queue, MusicListeningMode.BALANCED_DISCOVERY)
        } returns sessionContext
        coEvery { repository.replenishAutoRecommendations(sessionContext, queue[2], any()) } returns
            MusicRecommendationBatch(listOf(next), sessionContext.withBatch(listOf(next), 25, false))
        val appended = CompletableDeferred<List<MusicItem>>()
        every { playbackController.replaceQueueKeepingCurrent(any(), "autoRecommendationRefill", true) } answers {
            val replacement = firstArg<List<MusicItem>>()
            state.value = state.value.copy(queue = replacement)
            appended.complete(replacement)
            true
        }
        val callback = callback(
            MutableStateFlow(recommendationAuthenticated()),
            repository,
            playbackController,
            resumeStore = resumeStore,
        )

        val selected = callback.onSetMediaItems(
            mockk<MediaSession>(),
            mockk<MediaSession.ControllerInfo>(),
            listOf(MediaItem.Builder().setMediaId(PtvMusicAutoIds.AUTO_PICKS).build()),
            0,
            C.TIME_UNSET,
        ).get(5, TimeUnit.SECONDS)
        selected.mediaItems.size shouldBe 5
        state.value = MusicPlaybackState(
            queue = queue,
            currentItem = queue[2],
            currentIndex = 2,
            isPlaying = true,
            queueOwner = owner,
        )

        withTimeout(5_000) { appended.await() } shouldBe queue + next
        sessionContext.recentPlayedIds shouldBe emptyList()
        sessionContext.skippedIds shouldBe emptyList()
        coVerify(exactly = 1) {
            repository.replenishAutoRecommendations(sessionContext, queue[2], any())
        }
    }

    @Test
    fun `failed selections keep the active Auto Picks session available for refill`() = runBlocking {
        val queue = (0 until 50).map { track().copy(id = UUID(20L, it.toLong())) }
        val owner = PtvMusicAutoQueueOwner(1, 2)
        val resumeStore = mockk<PtvMusicAutoResumeStore>(relaxed = true)
        every { resumeStore.currentLocalQueueOwner() } returns owner
        val state = MutableStateFlow(MusicPlaybackState())
        val controller = mockk<MusicPlaybackController>(relaxed = true)
        every { controller.state } returns state
        every { controller.transitionEvents } returns MutableSharedFlow()
        every { controller.isCurrentQueueOwnedByAuthenticatedUser() } returns true
        val playbackCalls = AtomicInteger()
        every { controller.play(any(), any(), any(), any(), any(), any()) } answers {
            if (playbackCalls.incrementAndGet() == 2) throw IOException("could not start replacement queue")
        }
        val context = MusicRecommendationSessionContext.start(
            MusicRecommendationSessionOwner("https://server-1", UUID(1L, 2L)),
            queue.first(),
            queue,
            MusicListeningMode.BALANCED_DISCOVERY,
        )
        val missingId = UUID(20L, 100L)
        val refillRequested = CompletableDeferred<Unit>()
        val repository = mockk<MusicRepository>()
        coEvery { repository.loadAutoPicksQueue(any(), any(), MusicListeningMode.BALANCED_DISCOVERY) } returns queue
        coEvery { repository.beginAutoRecommendationSession(any(), any(), any()) } returns context
        coEvery { repository.loadItem(missingId) } returns null
        coEvery { repository.replenishAutoRecommendations(context, queue[47], any()) } coAnswers {
            refillRequested.complete(Unit)
            MusicRecommendationBatch(emptyList(), context.withBatch(emptyList(), 25, true))
        }
        val auth = MutableStateFlow(recommendationAuthenticated())
        val callback = callback(auth, repository, controller, resumeStore = resumeStore)
        callback.onSetMediaItems(
            mockk<MediaSession>(),
            mockk<MediaSession.ControllerInfo>(),
            listOf(MediaItem.Builder().setMediaId(PtvMusicAutoIds.AUTO_PICKS).build()),
            0,
            C.TIME_UNSET,
        ).get(5, TimeUnit.SECONDS)
        playAutoQueue(state, queue, 0, owner)

        val fallback = callback.onSetMediaItems(
            mockk<MediaSession>(),
            mockk<MediaSession.ControllerInfo>(),
            listOf(MediaItem.Builder().setMediaId(PtvMusicAutoIds.track(missingId)).build()),
            0,
            C.TIME_UNSET,
        ).get(5, TimeUnit.SECONDS)
        fallback.mediaItems.size shouldBe queue.size
        val failedReplacement = callback.onSetMediaItems(
            mockk<MediaSession>(),
            mockk<MediaSession.ControllerInfo>(),
            listOf(MediaItem.Builder().setMediaId(PtvMusicAutoIds.AUTO_PICKS).build()),
            0,
            C.TIME_UNSET,
        ).get(5, TimeUnit.SECONDS)
        failedReplacement.mediaItems.size shouldBe queue.size
        playbackCalls.get() shouldBe 2
        state.value = state.value.copy(currentItem = queue[47], currentIndex = 47, positionMs = 1L)

        withTimeout(5_000) { refillRequested.await() }
    }

    @Test
    fun `transient refill failure on last song retries without a track transition`() = runBlocking {
        val queue = (0 until 5).map { track().copy(id = UUID(21L, it.toLong())) }
        val next = track().copy(id = UUID(21L, 5L))
        val owner = PtvMusicAutoQueueOwner(1, 2)
        val resumeStore = mockk<PtvMusicAutoResumeStore>(relaxed = true)
        every { resumeStore.currentLocalQueueOwner() } returns owner
        val state = MutableStateFlow(MusicPlaybackState())
        val controller = mockk<MusicPlaybackController>(relaxed = true)
        every { controller.state } returns state
        every { controller.transitionEvents } returns MutableSharedFlow()
        every { controller.isCurrentQueueOwnedByAuthenticatedUser() } returns true
        val context = MusicRecommendationSessionContext.start(
            MusicRecommendationSessionOwner("https://server-1", UUID(1L, 2L)),
            queue.first(),
            queue,
            MusicListeningMode.BALANCED_DISCOVERY,
        )
        val calls = AtomicInteger()
        val appended = CompletableDeferred<Unit>()
        val repository = mockk<MusicRepository>()
        coEvery { repository.loadAutoPicksQueue(null, any(), MusicListeningMode.BALANCED_DISCOVERY) } returns queue
        coEvery { repository.beginAutoRecommendationSession(any(), any(), any()) } returns context
        coEvery { repository.replenishAutoRecommendations(context, queue.last(), any()) } coAnswers {
            if (calls.incrementAndGet() == 1) throw IOException("temporary catalog failure")
            MusicRecommendationBatch(listOf(next), context.withBatch(listOf(next), 25, false))
        }
        every { controller.replaceQueueKeepingCurrent(any(), "autoRecommendationRefill", true) } answers {
            val replacement = firstArg<List<MusicItem>>()
            state.value = state.value.copy(queue = replacement, currentIndex = replacement.indexOf(queue.last()))
            appended.complete(Unit)
            true
        }
        val callback = callback(
            MutableStateFlow(recommendationAuthenticated()),
            repository,
            controller,
            resumeStore = resumeStore,
        )
        callback.onSetMediaItems(
            mockk<MediaSession>(),
            mockk<MediaSession.ControllerInfo>(),
            listOf(MediaItem.Builder().setMediaId(PtvMusicAutoIds.AUTO_PICKS).build()),
            0,
            C.TIME_UNSET,
        ).get(5, TimeUnit.SECONDS)
        playAutoQueue(state, queue, queue.lastIndex, owner)

        withTimeout(5_000) { appended.await() }
        calls.get() shouldBe 2
        Unit
    }

    @Test
    fun `last-song refill stops retrying after a bounded number of failures`() = runBlocking {
        val queue = (0 until 5).map { track().copy(id = UUID(22L, it.toLong())) }
        val owner = PtvMusicAutoQueueOwner(1, 2)
        val resumeStore = mockk<PtvMusicAutoResumeStore>(relaxed = true)
        every { resumeStore.currentLocalQueueOwner() } returns owner
        val state = MutableStateFlow(MusicPlaybackState())
        val controller = mockk<MusicPlaybackController>(relaxed = true)
        every { controller.state } returns state
        every { controller.transitionEvents } returns MutableSharedFlow()
        every { controller.isCurrentQueueOwnedByAuthenticatedUser() } returns true
        val context = MusicRecommendationSessionContext.start(
            MusicRecommendationSessionOwner("https://server-1", UUID(1L, 2L)),
            queue.first(),
            queue,
            MusicListeningMode.BALANCED_DISCOVERY,
        )
        val calls = AtomicInteger()
        val thirdAttempt = CompletableDeferred<Unit>()
        val repository = mockk<MusicRepository>()
        coEvery { repository.loadAutoPicksQueue(null, any(), MusicListeningMode.BALANCED_DISCOVERY) } returns queue
        coEvery { repository.beginAutoRecommendationSession(any(), any(), any()) } returns context
        coEvery { repository.replenishAutoRecommendations(context, queue.last(), any()) } coAnswers {
            if (calls.incrementAndGet() == 3) thirdAttempt.complete(Unit)
            throw IOException("catalog unavailable")
        }
        val callback = callback(
            MutableStateFlow(recommendationAuthenticated()),
            repository,
            controller,
            resumeStore = resumeStore,
        )
        callback.onSetMediaItems(
            mockk<MediaSession>(),
            mockk<MediaSession.ControllerInfo>(),
            listOf(MediaItem.Builder().setMediaId(PtvMusicAutoIds.AUTO_PICKS).build()),
            0,
            C.TIME_UNSET,
        ).get(5, TimeUnit.SECONDS)
        playAutoQueue(state, queue, queue.lastIndex, owner)

        withTimeout(5_000) { thirdAttempt.await() }
        delay(1_800)
        calls.get() shouldBe 3
        Unit
    }

    @Test
    fun `Auto Picks refuses a repository session owned by another server or user`() {
        val queue = listOf(track())
        val expectedUser = UUID(1L, 2L)
        for (wrongOwner in listOf(
            MusicRecommendationSessionOwner("https://other-server", expectedUser),
            MusicRecommendationSessionOwner("https://server-1", UUID(1L, 3L)),
        )) {
            val owner = PtvMusicAutoQueueOwner(1, 2)
            val resumeStore = mockk<PtvMusicAutoResumeStore>(relaxed = true)
            every { resumeStore.currentLocalQueueOwner() } returns owner
            val playbackController = mockk<MusicPlaybackController>(relaxed = true)
            every { playbackController.state } returns MutableStateFlow(MusicPlaybackState())
            every { playbackController.isCurrentQueueOwnedByAuthenticatedUser() } returns false
            val repository = mockk<MusicRepository>()
            coEvery { repository.loadAutoPicksQueue(null, any(), MusicListeningMode.BALANCED_DISCOVERY) } returns queue
            coEvery { repository.beginAutoRecommendationSession(any(), any(), any()) } returns
                MusicRecommendationSessionContext.start(
                    wrongOwner,
                    queue.first(),
                    queue,
                    MusicListeningMode.BALANCED_DISCOVERY,
                )
            val callback = callback(
                MutableStateFlow(recommendationAuthenticated()),
                repository,
                playbackController,
                resumeStore = resumeStore,
            )

            val selected = callback.onSetMediaItems(
                mockk<MediaSession>(),
                mockk<MediaSession.ControllerInfo>(),
                listOf(MediaItem.Builder().setMediaId(PtvMusicAutoIds.AUTO_PICKS).build()),
                0,
                C.TIME_UNSET,
            ).get(5, TimeUnit.SECONDS)

            selected.mediaItems.isEmpty() shouldBe true
            verify(exactly = 0) { playbackController.play(any(), any(), any(), any(), any()) }
        }
    }

    @Test
    fun `empty Auto refill advances catalog and exhausted result prevents another scan`() = runBlocking {
        val queue = (0 until 5).map { track().copy(id = UUID(30L, it.toLong())) }
        val owner = PtvMusicAutoQueueOwner(1, 2)
        val resumeStore = mockk<PtvMusicAutoResumeStore>(relaxed = true)
        every { resumeStore.currentLocalQueueOwner() } returns owner
        val state = MutableStateFlow(MusicPlaybackState())
        val playbackController = mockk<MusicPlaybackController>(relaxed = true)
        every { playbackController.state } returns state
        every { playbackController.transitionEvents } returns MutableSharedFlow()
        every { playbackController.isCurrentQueueOwnedByAuthenticatedUser() } returns true
        val initial = MusicRecommendationSessionContext.start(
            MusicRecommendationSessionOwner("https://server-1", UUID(1L, 2L)),
            queue.first(),
            queue,
            MusicListeningMode.BALANCED_DISCOVERY,
        )
        val advanced = initial.withBatch(emptyList(), 25, false)
        val exhausted = advanced.withBatch(emptyList(), 50, true)
        val calls = AtomicInteger()
        val firstContext = CompletableDeferred<MusicRecommendationSessionContext>()
        val secondContext = CompletableDeferred<MusicRecommendationSessionContext>()
        val repository = mockk<MusicRepository>()
        coEvery { repository.loadAutoPicksQueue(null, any(), MusicListeningMode.BALANCED_DISCOVERY) } returns queue
        coEvery { repository.beginAutoRecommendationSession(any(), any(), any()) } returns initial
        coEvery { repository.replenishAutoRecommendations(any(), any(), any()) } coAnswers {
            val context = firstArg<MusicRecommendationSessionContext>()
            when (calls.incrementAndGet()) {
                1 -> {
                    firstContext.complete(context)
                    MusicRecommendationBatch(emptyList(), advanced)
                }

                2 -> {
                    secondContext.complete(context)
                    MusicRecommendationBatch(emptyList(), exhausted)
                }

                else -> error("Exhausted Auto catalog was scanned again")
            }
        }
        beginAutoPicksSession(repository, playbackController, resumeStore)
        playAutoQueue(state, queue, 2, owner)
        withTimeout(5_000) { firstContext.await() } shouldBe initial
        withTimeout(5_000) {
            var position = 0L
            while (!secondContext.isCompleted) {
                state.value = state.value.copy(currentItem = queue[3], currentIndex = 3, positionMs = ++position)
                delay(10)
            }
        }
        secondContext.await() shouldBe advanced
        delay(50)
        repeat(15) { index ->
            state.value = state.value.copy(currentItem = queue[4], currentIndex = 4, positionMs = index.toLong())
            delay(10)
        }

        calls.get() shouldBe 2
        verify(exactly = 0) { playbackController.replaceQueueKeepingCurrent(any(), any()) }
    }

    @Test
    fun `repeated Auto refills keep current song and a previous window in a bounded resident queue`() = runBlocking {
        val initialQueue = (0 until 50).map { track().copy(id = UUID(31L, it.toLong())) }
        val owner = PtvMusicAutoQueueOwner(1, 2)
        val resumeStore = mockk<PtvMusicAutoResumeStore>(relaxed = true)
        every { resumeStore.currentLocalQueueOwner() } returns owner
        val state = MutableStateFlow(MusicPlaybackState())
        val playbackController = mockk<MusicPlaybackController>(relaxed = true)
        every { playbackController.state } returns state
        every { playbackController.transitionEvents } returns MutableSharedFlow()
        every { playbackController.isCurrentQueueOwnedByAuthenticatedUser() } returns true
        val initialContext = MusicRecommendationSessionContext.start(
            MusicRecommendationSessionOwner("https://server-1", UUID(1L, 2L)),
            initialQueue.first(),
            initialQueue,
            MusicListeningMode.BALANCED_DISCOVERY,
        )
        val batches = AtomicInteger()
        val repository = mockk<MusicRepository>()
        coEvery { repository.loadAutoPicksQueue(null, any(), MusicListeningMode.BALANCED_DISCOVERY) } returns
            initialQueue
        coEvery { repository.beginAutoRecommendationSession(any(), any(), any()) } returns initialContext
        coEvery { repository.replenishAutoRecommendations(any(), any(), any()) } coAnswers {
            val context = firstArg<MusicRecommendationSessionContext>()
            val batch = batches.getAndIncrement()
            val additions = (0 until 25).map { track().copy(id = UUID(32L, (batch * 25 + it).toLong())) }
            MusicRecommendationBatch(additions, context.withBatch(additions, context.catalogOffset + 25, false))
        }
        val appended = Channel<Pair<List<MusicItem>, UUID>>(Channel.UNLIMITED)
        every { playbackController.replaceQueueKeepingCurrent(any(), "autoRecommendationRefill", true) } answers {
            val replacement = firstArg<List<MusicItem>>()
            val current = requireNotNull(state.value.currentItem)
            state.value = state.value.copy(
                queue = replacement,
                currentIndex = replacement.indexOfFirst { it.id == current.id },
            )
            appended.trySend(replacement to current.id)
            true
        }
        beginAutoPicksSession(repository, playbackController, resumeStore)
        playAutoQueue(state, initialQueue, 47, owner)

        repeat(5) { iteration ->
            val (replacement, currentId) = withTimeout(5_000) { appended.receive() }
            (replacement.size <= 100) shouldBe true
            val currentIndex = replacement.indexOfFirst { it.id == currentId }
            (currentIndex in 10..20) shouldBe true
            replacement.map(MusicItem::id).distinct().size shouldBe replacement.size
            if (iteration < 4) {
                val nextIndex = replacement.lastIndex - 2
                state.value = state.value.copy(
                    currentItem = replacement[nextIndex],
                    currentIndex = nextIndex,
                    positionMs = iteration.toLong() + 1,
                )
            }
        }
        batches.get() shouldBe 5
        Unit
    }
}

class PtvMusicAutoBrowseRefillCancellationTest : PtvMusicAutoBrowseFixture() {
    @Test
    fun `account change cancels one in flight Auto refill before queue mutation`() = runBlocking {
        val auth = MutableStateFlow<MobileAuthState>(recommendationAuthenticated())
        val owner = AtomicReference(PtvMusicAutoQueueOwner(1, 2))
        val resumeStore = mockk<PtvMusicAutoResumeStore>(relaxed = true)
        every { resumeStore.currentLocalQueueOwner() } answers { owner.get() }
        val queue = (0 until 5).map { track().copy(id = UUID(11L, it.toLong())) }
        val state = MutableStateFlow(MusicPlaybackState())
        val transitions = MutableSharedFlow<MusicPlaybackTransitionEvent>()
        val playbackController = mockk<MusicPlaybackController>(relaxed = true)
        every { playbackController.state } returns state
        every { playbackController.transitionEvents } returns transitions
        every { playbackController.isCurrentQueueOwnedByAuthenticatedUser() } returns true
        val sessionContext = MusicRecommendationSessionContext.start(
            MusicRecommendationSessionOwner("https://server-1", UUID(1L, 2L)),
            queue.first(),
            queue,
            MusicListeningMode.BALANCED_DISCOVERY,
        )
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<MusicRecommendationBatch>()
        val cancelled = CompletableDeferred<Unit>()
        val repository = mockk<MusicRepository>()
        coEvery { repository.loadAutoPicksQueue(null, any(), MusicListeningMode.BALANCED_DISCOVERY) } returns queue
        coEvery { repository.beginAutoRecommendationSession(any(), any(), any()) } returns sessionContext
        coEvery { repository.replenishAutoRecommendations(any(), any(), any()) } coAnswers {
            entered.complete(Unit)
            try {
                release.await()
            } finally {
                cancelled.complete(Unit)
            }
        }
        val callback = callback(auth, repository, playbackController, resumeStore = resumeStore)
        callback.onSetMediaItems(
            mockk<MediaSession>(),
            mockk<MediaSession.ControllerInfo>(),
            listOf(MediaItem.Builder().setMediaId(PtvMusicAutoIds.AUTO_PICKS).build()),
            0,
            C.TIME_UNSET,
        ).get(5, TimeUnit.SECONDS)
        state.value = MusicPlaybackState(
            queue = queue,
            currentItem = queue[2],
            currentIndex = 2,
            isPlaying = true,
            queueOwner = owner.get(),
        )
        withTimeout(5_000) { entered.await() }
        state.value = state.value.copy(positionMs = 1_000)
        auth.value = recommendationAuthenticated(UUID(1L, 3L))
        owner.set(PtvMusicAutoQueueOwner(1, 3))
        callback.onAuthenticationChanged()

        withTimeout(5_000) { cancelled.await() }
        delay(50)
        coVerify(exactly = 1) { repository.replenishAutoRecommendations(any(), any(), any()) }
        verify(exactly = 0) { playbackController.replaceQueueKeepingCurrent(any(), any()) }
    }

    @Test
    fun `source queue change cancels one in flight Auto refill`() = runBlocking {
        val owner = PtvMusicAutoQueueOwner(1, 2)
        val resumeStore = mockk<PtvMusicAutoResumeStore>(relaxed = true)
        every { resumeStore.currentLocalQueueOwner() } returns owner
        val queue = (0 until 5).map { track().copy(id = UUID(13L, it.toLong())) }
        val state = MutableStateFlow(MusicPlaybackState())
        val playbackController = mockk<MusicPlaybackController>(relaxed = true)
        every { playbackController.state } returns state
        every { playbackController.transitionEvents } returns MutableSharedFlow()
        every { playbackController.isCurrentQueueOwnedByAuthenticatedUser() } returns true
        val sessionContext = MusicRecommendationSessionContext.start(
            MusicRecommendationSessionOwner("https://server-1", UUID(1L, 2L)),
            queue.first(),
            queue,
            MusicListeningMode.BALANCED_DISCOVERY,
        )
        val entered = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val release = CompletableDeferred<MusicRecommendationBatch>()
        val repository = mockk<MusicRepository>()
        coEvery { repository.loadAutoPicksQueue(null, any(), MusicListeningMode.BALANCED_DISCOVERY) } returns queue
        coEvery { repository.beginAutoRecommendationSession(any(), any(), any()) } returns sessionContext
        coEvery { repository.replenishAutoRecommendations(any(), any(), any()) } coAnswers {
            entered.complete(Unit)
            try {
                release.await()
            } finally {
                cancelled.complete(Unit)
            }
        }
        val callback = callback(
            MutableStateFlow(recommendationAuthenticated()),
            repository,
            playbackController,
            resumeStore = resumeStore,
        )
        callback.onSetMediaItems(
            mockk<MediaSession>(),
            mockk<MediaSession.ControllerInfo>(),
            listOf(MediaItem.Builder().setMediaId(PtvMusicAutoIds.AUTO_PICKS).build()),
            0,
            C.TIME_UNSET,
        ).get(5, TimeUnit.SECONDS)
        state.value = MusicPlaybackState(
            queue = queue,
            currentItem = queue[2],
            currentIndex = 2,
            isPlaying = true,
            queueOwner = owner,
        )
        withTimeout(5_000) { entered.await() }
        state.value = state.value.copy(
            queue = listOf(queue[1], queue[2], track()),
            currentIndex = 1,
        )

        withTimeout(5_000) { cancelled.await() }
        verify(exactly = 0) { playbackController.replaceQueueKeepingCurrent(any(), any()) }
    }

    @Test
    fun `only verified natural and explicit skip transitions enter Auto recommendation context`() = runBlocking {
        for (reason in MusicPlaybackTransitionReason.entries) {
            val queue = (0 until 5).map { track().copy(id = UUID(12L, it.toLong())) }
            val owner = PtvMusicAutoQueueOwner(1, 2)
            val resumeStore = mockk<PtvMusicAutoResumeStore>(relaxed = true)
            every { resumeStore.currentLocalQueueOwner() } returns owner
            val state = MutableStateFlow(MusicPlaybackState())
            val transitions = MutableSharedFlow<MusicPlaybackTransitionEvent>()
            val playbackController = mockk<MusicPlaybackController>(relaxed = true)
            every { playbackController.state } returns state
            every { playbackController.transitionEvents } returns transitions
            every { playbackController.isCurrentQueueOwnedByAuthenticatedUser() } returns true
            val initialContext = MusicRecommendationSessionContext.start(
                MusicRecommendationSessionOwner("https://server-1", UUID(1L, 2L)),
                queue.first(),
                queue,
                MusicListeningMode.BALANCED_DISCOVERY,
            )
            val refillContext = CompletableDeferred<MusicRecommendationSessionContext>()
            val repository = mockk<MusicRepository>()
            coEvery { repository.loadAutoPicksQueue(null, any(), MusicListeningMode.BALANCED_DISCOVERY) } returns queue
            coEvery { repository.beginAutoRecommendationSession(any(), any(), any()) } returns initialContext
            coEvery { repository.replenishAutoRecommendations(any(), queue[2], any()) } coAnswers {
                val context = firstArg<MusicRecommendationSessionContext>()
                refillContext.complete(context)
                MusicRecommendationBatch(emptyList(), context)
            }
            beginAutoPicksSession(repository, playbackController, resumeStore)
            withTimeout(5_000) { transitions.subscriptionCount.first { it > 0 } }
            state.value = MusicPlaybackState(
                queue = queue,
                currentItem = queue[2],
                currentIndex = 2,
                queueOwner = owner,
            )
            val verifiedEvent = MusicPlaybackTransitionEvent(
                owner,
                queue[1].id,
                queue[2].id,
                1,
                2,
                reason,
            )
            transitions.emit(verifiedEvent)
            transitions.emit(verifiedEvent.copy(owner = PtvMusicAutoQueueOwner(1, 3)))
            state.value = state.value.copy(isPlaying = true)

            val captured = withTimeout(5_000) { refillContext.await() }
            captured.revision shouldBe 1
            captured.recentPlayedIds shouldBe
                if (reason == MusicPlaybackTransitionReason.NATURAL) listOf(queue[1].id) else emptyList()
            captured.skippedIds shouldBe
                if (reason == MusicPlaybackTransitionReason.EXPLICIT_SKIP) listOf(queue[1].id) else emptyList()
            verify(exactly = 0) { playbackController.replaceQueueKeepingCurrent(any(), any()) }
        }
    }

    @Test
    fun `browse scope changes when server user or credential changes`() {
        val first = authenticated("server-1", "user-1")
        val firstScope = ptvMusicAutoBrowseScope(first)

        (firstScope == ptvMusicAutoBrowseScope(authenticated("server-2", "user-1"))) shouldBe false
        (firstScope == ptvMusicAutoBrowseScope(authenticated("server-1", "user-2"))) shouldBe false
        (firstScope == ptvMusicAutoBrowseScope(authenticated("server-1", "user-1"))) shouldBe false
        ptvMusicAutoBrowseScope(MobileAuthState.Unauthenticated) shouldBe null
    }

    @Test
    fun `switching authenticated users evicts previously browsed rows`() {
        val first = authenticated("server-1", "user-1")
        val authState = MutableStateFlow<MobileAuthState>(first)
        val callback = PtvMusicLibrarySessionCallback(
            context = mockk<Context>(),
            repository = mockk<MusicRepository>(),
            playbackController = mockk<MusicPlaybackController>(),
            songActionHandler = mockk<MusicSongActionHandler>(),
            resumeStore = mockk<PtvMusicAutoResumeStore>(relaxed = true),
            resumeCoordinator = mockk<PtvMusicAutoResumeCoordinator>(),
            savedSessionReady = CompletableDeferred(UUID.randomUUID()),
            authState = authState,
        )
        callback.onAuthenticationChanged()
        @Suppress("UNCHECKED_CAST")
        val cache = PtvMusicLibrarySessionCallback::class.java.getDeclaredField("browseCache").apply {
            isAccessible = true
        }.get(callback) as ConcurrentHashMap<Any, Any>
        cache[requireNotNull(ptvMusicAutoBrowseScope(first)) to "category:playlists"] = emptyList<Any>()

        authState.value = authenticated("server-1", "user-2")
        callback.onAuthenticationChanged()

        cache.isEmpty() shouldBe true
    }

    @Test
    fun `valid login can browse before initial saved session restore completes`() {
        val authState = MutableStateFlow<MobileAuthState>(authenticated("server-1", "user-1"))
        val repository = mockk<MusicRepository>()
        coEvery { repository.loadBrowsePage(MusicBrowseKind.ALBUMS, 0, 10) } returns MusicPage(
            emptyList(),
            totalCount = 0,
            startIndex = 0,
        )
        val callback = callback(authState, repository = repository, savedSessionReady = CompletableDeferred())

        val request = callback.onGetChildren(
            mockk<MediaLibraryService.MediaLibrarySession>(),
            mockk<MediaSession.ControllerInfo>(),
            PtvMusicAutoCategory.ALBUMS.mediaId,
            0,
            10,
            null,
        )

        val result = request.get(1, TimeUnit.SECONDS)
        result.resultCode shouldBe SessionResult.RESULT_SUCCESS
        result.value?.size shouldBe 0
        coVerify(exactly = 1) { repository.loadBrowsePage(MusicBrowseKind.ALBUMS, 0, 10) }
    }

    @Test
    fun `unavailable authentication returns a controlled item error instead of an exceptional future`() {
        val callback = callback(MutableStateFlow<MobileAuthState>(MobileAuthState.Unauthenticated))

        val result = callback.onGetItem(
            mockk<MediaLibraryService.MediaLibrarySession>(),
            mockk<MediaSession.ControllerInfo>(),
            PtvMusicAutoIds.item(UUID.randomUUID()),
        ).get(2, TimeUnit.SECONDS)

        result.resultCode shouldBe SessionError.ERROR_NOT_SUPPORTED
        result.value shouldBe null
    }

    @Test
    fun `unavailable authentication returns empty playback resumption instead of an exceptional future`() {
        val callback = callback(MutableStateFlow<MobileAuthState>(MobileAuthState.Unauthenticated))

        val result = callback.onPlaybackResumption(
            mockk<MediaSession>(),
            mockk<MediaSession.ControllerInfo>(),
        ).get(2, TimeUnit.SECONDS)

        result.mediaItems.size shouldBe 0
        result.startIndex shouldBe C.INDEX_UNSET
    }

    @Test
    fun `selection begun during restore returns its queue after a valid login completes`() {
        val liveState = MutableStateFlow<MobileAuthState>(MobileAuthState.Restoring)
        val initialRead = CountDownLatch(1)
        val observedState = mockk<StateFlow<MobileAuthState>>()
        every { observedState.value } answers {
            liveState.value.also { state -> if (state == MobileAuthState.Restoring) initialRead.countDown() }
        }
        val savedSessionReady = CompletableDeferred<UUID?>()
        val localOwner = AtomicReference<PtvMusicAutoQueueOwner?>(null)
        val resumeStore = mockk<PtvMusicAutoResumeStore>(relaxed = true)
        every { resumeStore.currentLocalQueueOwner() } answers { localOwner.get() }
        val selectedTrack = track()
        val repository = mockk<MusicRepository>()
        coEvery { repository.loadBrowsePage(MusicBrowseKind.SONGS, 0, 50) } returns MusicPage(
            listOf(selectedTrack),
            totalCount = 1,
            startIndex = 0,
        )
        val playbackController = mockk<MusicPlaybackController>(relaxed = true)
        every { playbackController.state } returns MutableStateFlow(MusicPlaybackState())
        val playbackErrors = mutableListOf<String?>()
        every { resumeStore.markLastPlaybackError(any()) } answers { playbackErrors += firstArg<String?>() }
        val selectedMediaItem = MediaItem.Builder().setMediaId(PtvMusicAutoIds.SHUFFLE_ALL).build()
        val callback = callback(observedState, repository, playbackController, savedSessionReady, resumeStore)
        val request = callback.onSetMediaItems(
            mockk<MediaSession>(),
            mockk<MediaSession.ControllerInfo>(),
            listOf(selectedMediaItem),
            0,
            C.TIME_UNSET,
        )
        initialRead.await(2, TimeUnit.SECONDS) shouldBe true
        localOwner.set(PtvMusicAutoQueueOwner(1, 2))
        liveState.value = authenticated("server-1", "user-1")
        savedSessionReady.complete(UUID.randomUUID())

        val result = request.get(5, TimeUnit.SECONDS)
        playbackErrors shouldBe listOf(null)
        result.mediaItems.map(MediaItem::mediaId) shouldBe listOf(PtvMusicAutoIds.track(selectedTrack.id))
        verify(exactly = 1) {
            playbackController.play(any(), any(), any(), any(), PtvMusicAutoQueueOwner(1, 2))
        }
    }

    @Test
    fun `in flight Auto selection cannot play old browse items after local account changes`() = runBlocking<Unit> {
        val authState = MutableStateFlow<MobileAuthState>(authenticated("server-1", "user-1"))
        val localOwner = AtomicReference(PtvMusicAutoQueueOwner(1, 2))
        val resumeStore = mockk<PtvMusicAutoResumeStore>(relaxed = true)
        every { resumeStore.currentLocalQueueOwner() } answers { localOwner.get() }
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<MusicPage>()
        val repository = mockk<MusicRepository>()
        coEvery { repository.loadBrowsePage(MusicBrowseKind.SONGS, 0, 50) } coAnswers {
            entered.complete(Unit)
            release.await()
        }
        val playbackController = mockk<MusicPlaybackController>(relaxed = true)
        every { playbackController.state } returns MutableStateFlow(MusicPlaybackState())
        val callback = callback(authState, repository, playbackController, resumeStore = resumeStore)
        val selectedMediaItem = MediaItem.Builder().setMediaId(PtvMusicAutoIds.SHUFFLE_ALL).build()
        val request = callback.onSetMediaItems(
            mockk<MediaSession>(),
            mockk<MediaSession.ControllerInfo>(),
            listOf(selectedMediaItem),
            0,
            C.TIME_UNSET,
        )
        withTimeout(2_000) { entered.await() }
        localOwner.set(PtvMusicAutoQueueOwner(1, 3))
        release.complete(MusicPage(listOf(track()), totalCount = 1, startIndex = 0))

        val result = request.get(5, TimeUnit.SECONDS)
        result.mediaItems.size shouldBe 0
        verify(exactly = 0) { playbackController.play(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `Auto start mix does not play a prior account item after local owner changes during mix preparation`() {
        val authState = MutableStateFlow<MobileAuthState>(authenticated("server-1", "user-1"))
        val ownerA = PtvMusicAutoQueueOwner(1, 2)
        val localOwner = AtomicReference(ownerA)
        val resumeStore = mockk<PtvMusicAutoResumeStore>(relaxed = true)
        every { resumeStore.currentLocalQueueOwner() } answers { localOwner.get() }
        val currentTrack = track()
        val playbackController = mockk<MusicPlaybackController>(relaxed = true)
        every { playbackController.state } returns MutableStateFlow(
            MusicPlaybackState(queue = listOf(currentTrack), currentItem = currentTrack, queueOwner = ownerA),
        )
        every { playbackController.isCurrentQueueOwnedByAuthenticatedUser() } returns true
        val repository = mockk<MusicRepository>()
        every { repository.loadCachedGeneratedQueue(currentTrack, any()) } answers {
            localOwner.set(PtvMusicAutoQueueOwner(1, 3))
            emptyList()
        }
        coEvery { repository.loadAutoGeneratedQueue(currentTrack, any()) } returns listOf(currentTrack)
        val callback = PtvMusicLibrarySessionCallback(
            context = mockk(),
            repository = repository,
            playbackController = playbackController,
            songActionHandler = MusicSongActionHandler(repository, playbackController),
            resumeStore = resumeStore,
            resumeCoordinator = mockk(),
            savedSessionReady = CompletableDeferred<UUID?>().apply { complete(null) },
            authState = authState,
        )
        val command = SessionCommand(PtvMusicAutoCommand.ACTION_GENERATE_QUEUE, Bundle())
        val controller = mockk<MediaSession.ControllerInfo>()
        every { controller.packageName } returns "android.auto.test"

        val result = callback.onCustomCommand(mockk(), controller, command, mockk()).get(5, TimeUnit.SECONDS)

        result.resultCode shouldBe SessionError.ERROR_NOT_SUPPORTED
        verify(exactly = 0) { playbackController.play(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `recommendation fallback cannot return the prior users active queue after account switch`() {
        val authState = MutableStateFlow<MobileAuthState>(authenticated("server-1", "user-1"))
        val priorTrack = track()
        val playbackController = mockk<MusicPlaybackController>()
        every { playbackController.state } answers {
            authState.value = authenticated("server-1", "user-2")
            MutableStateFlow(MusicPlaybackState(queue = listOf(priorTrack), currentItem = priorTrack))
        }
        val repository = mockk<MusicRepository>()
        coEvery { repository.loadBrowsePage(MusicBrowseKind.RECOMMENDATIONS, 0, 10) } returns MusicPage(
            emptyList(),
            totalCount = 0,
            startIndex = 0,
        )
        val callback = callback(authState, repository, playbackController)

        val request = callback.onGetChildren(
            mockk<MediaLibraryService.MediaLibrarySession>(),
            mockk<MediaSession.ControllerInfo>(),
            PtvMusicAutoCategory.RECOMMENDATIONS.mediaId,
            0,
            10,
            null,
        )
        val result = request.get(2, TimeUnit.SECONDS)
        result.resultCode shouldBe SessionError.ERROR_NOT_SUPPORTED

        @Suppress("UNCHECKED_CAST")
        val itemCache = PtvMusicLibrarySessionCallback::class.java.getDeclaredField("itemCache").apply {
            isAccessible = true
        }.get(callback) as ConcurrentHashMap<Any, Any>
        itemCache.isEmpty() shouldBe true
    }

    @Test
    fun `recommendation fallback accepts a queue verified for the newly authenticated user`() {
        val authState = MutableStateFlow<MobileAuthState>(authenticated("server-1", "user-1"))
        val localOwner = AtomicReference(PtvMusicAutoQueueOwner(1, 2))
        val resumeStore = mockk<PtvMusicAutoResumeStore>(relaxed = true)
        every { resumeStore.currentLocalQueueOwner() } answers { localOwner.get() }
        val newUserTrack = track()
        val playbackController = mockk<MusicPlaybackController>()
        every { playbackController.state } returns MutableStateFlow(
            MusicPlaybackState(queue = listOf(newUserTrack), currentItem = newUserTrack, currentIndex = 0),
        )
        every { playbackController.isCurrentQueueOwnedByAuthenticatedUser() } returns true
        val repository = mockk<MusicRepository>()
        coEvery { repository.loadBrowsePage(MusicBrowseKind.RECOMMENDATIONS, 0, 10) } returns MusicPage(
            emptyList(),
            totalCount = 0,
            startIndex = 0,
        )
        val callback = callback(authState, repository, playbackController, resumeStore = resumeStore)
        callback.onAuthenticationChanged()
        localOwner.set(PtvMusicAutoQueueOwner(1, 3))
        authState.value = authenticated("server-1", "user-2")
        callback.onAuthenticationChanged()

        val result = callback.onGetChildren(
            mockk<MediaLibraryService.MediaLibrarySession>(),
            mockk<MediaSession.ControllerInfo>(),
            PtvMusicAutoCategory.RECOMMENDATIONS.mediaId,
            0,
            10,
            null,
        ).get(2, TimeUnit.SECONDS)

        result.resultCode shouldBe SessionResult.RESULT_SUCCESS
        result.value?.map(MediaItem::mediaId) shouldBe listOf(PtvMusicAutoIds.track(newUserTrack.id))
    }

    @Test
    fun `playback resumption accepts a queue verified for the newly authenticated user`() {
        val authState = MutableStateFlow<MobileAuthState>(authenticated("server-1", "user-1"))
        val localOwner = AtomicReference(PtvMusicAutoQueueOwner(1, 2))
        val resumeStore = mockk<PtvMusicAutoResumeStore>(relaxed = true)
        every { resumeStore.currentLocalQueueOwner() } answers { localOwner.get() }
        val newUserTrack = track()
        val playbackController = mockk<MusicPlaybackController>()
        every { playbackController.state } returns MutableStateFlow(
            MusicPlaybackState(queue = listOf(newUserTrack), currentItem = newUserTrack, currentIndex = 0),
        )
        every { playbackController.isCurrentQueueOwnedByAuthenticatedUser() } returns true
        val resumeCoordinator = mockk<PtvMusicAutoResumeCoordinator>()
        coEvery { resumeCoordinator.restoreQueueIfNeeded() } returns PtvMusicAutoRestoreResult.AlreadyActive
        val callback = callback(
            authState,
            playbackController = playbackController,
            resumeStore = resumeStore,
            resumeCoordinator = resumeCoordinator,
        )
        callback.onAuthenticationChanged()
        localOwner.set(PtvMusicAutoQueueOwner(1, 3))
        authState.value = authenticated("server-1", "user-2")
        callback.onAuthenticationChanged()

        val result = callback.onPlaybackResumption(
            mockk<MediaSession>(),
            mockk<MediaSession.ControllerInfo>(),
        ).get(2, TimeUnit.SECONDS)

        result.mediaItems.map(MediaItem::mediaId) shouldBe listOf(PtvMusicAutoIds.track(newUserTrack.id))
        coVerify(exactly = 1) { resumeCoordinator.restoreQueueIfNeeded() }
    }
}

class PtvMusicAutoBrowseAccountRecoveryTest : PtvMusicAutoBrowseFixture() {
    @Test
    fun `playback resumption restores queue on the player dispatcher`() {
        val dispatcher = Executors.newSingleThreadExecutor { task ->
            Thread(task, "ptv-auto-player-test")
        }.asCoroutineDispatcher()
        try {
            val currentTrack = track()
            val playbackController = mockk<MusicPlaybackController>()
            every { playbackController.state } returns MutableStateFlow(
                MusicPlaybackState(queue = listOf(currentTrack), currentItem = currentTrack),
            )
            every { playbackController.isCurrentQueueOwnedByAuthenticatedUser() } returns true
            val restoreThread = AtomicReference<String>()
            val resumeCoordinator = mockk<PtvMusicAutoResumeCoordinator>()
            coEvery { resumeCoordinator.restoreQueueIfNeeded() } coAnswers {
                restoreThread.set(Thread.currentThread().name)
                PtvMusicAutoRestoreResult.AlreadyActive
            }
            val callback = callback(
                authState = MutableStateFlow(authenticated("server-1", "user-1")),
                playbackController = playbackController,
                resumeCoordinator = resumeCoordinator,
                playerDispatcher = dispatcher,
            )

            val result = callback.onPlaybackResumption(
                mockk<MediaSession>(),
                mockk<MediaSession.ControllerInfo>(),
            ).get(5, TimeUnit.SECONDS)

            result.mediaItems.map(MediaItem::mediaId) shouldBe listOf(PtvMusicAutoIds.track(currentTrack.id))
            restoreThread.get()?.startsWith("ptv-auto-player-test") shouldBe true
        } finally {
            dispatcher.close()
        }
    }

    @Test
    fun `resident playback queue is not reused after a completed account switch`() {
        val authState = MutableStateFlow<MobileAuthState>(authenticated("server-1", "user-1"))
        val localOwner = AtomicReference(PtvMusicAutoQueueOwner(1, 2))
        val resumeStore = mockk<PtvMusicAutoResumeStore>(relaxed = true)
        every { resumeStore.currentLocalQueueOwner() } answers { localOwner.get() }
        val priorTrack = track()
        val playbackController = mockk<MusicPlaybackController>()
        every { playbackController.state } returns MutableStateFlow(
            MusicPlaybackState(queue = listOf(priorTrack), currentItem = priorTrack),
        )
        every { playbackController.isCurrentQueueOwnedByAuthenticatedUser() } returns false
        val repository = mockk<MusicRepository>()
        coEvery { repository.loadBrowsePage(MusicBrowseKind.RECOMMENDATIONS, 0, 10) } returns MusicPage(
            emptyList(),
            totalCount = 0,
            startIndex = 0,
        )
        val callback = callback(authState, repository, playbackController, resumeStore = resumeStore)
        callback.onAuthenticationChanged()
        localOwner.set(PtvMusicAutoQueueOwner(1, 3))
        authState.value = authenticated("server-1", "user-2")
        callback.onAuthenticationChanged()

        val request = callback.onGetChildren(
            mockk<MediaLibraryService.MediaLibrarySession>(),
            mockk<MediaSession.ControllerInfo>(),
            PtvMusicAutoCategory.RECOMMENDATIONS.mediaId,
            0,
            10,
            null,
        )
        val result = request.get(2, TimeUnit.SECONDS)
        result.resultCode shouldBe SessionResult.RESULT_SUCCESS
        result.value?.size shouldBe 0

        @Suppress("UNCHECKED_CAST")
        val itemCache = PtvMusicLibrarySessionCallback::class.java.getDeclaredField("itemCache").apply {
            isAccessible = true
        }.get(callback) as ConcurrentHashMap<Any, Any>
        itemCache.isEmpty() shouldBe true
    }

    @Test
    fun `auth switch before local owner update cannot expose prior account queue`() {
        val authState = MutableStateFlow<MobileAuthState>(authenticated("server-1", "user-1"))
        val localOwner = AtomicReference(PtvMusicAutoQueueOwner(1, 2))
        val resumeStore = mockk<PtvMusicAutoResumeStore>(relaxed = true)
        every { resumeStore.currentLocalQueueOwner() } answers { localOwner.get() }
        val priorTrack = track()
        val nextTrack = track()
        val playerState = MutableStateFlow(
            MusicPlaybackState(queue = listOf(priorTrack), currentItem = priorTrack, queueOwner = localOwner.get()),
        )
        val playbackController = mockk<MusicPlaybackController>()
        every { playbackController.state } returns playerState
        every { playbackController.isCurrentQueueOwnedByAuthenticatedUser() } returns true
        val repository = mockk<MusicRepository>()
        coEvery { repository.loadBrowsePage(MusicBrowseKind.RECOMMENDATIONS, 0, 10) } returns MusicPage(
            emptyList(),
            totalCount = 0,
            startIndex = 0,
        )
        val callback = callback(authState, repository, playbackController, resumeStore = resumeStore)
        callback.onAuthenticationChanged()

        authState.value = authenticated("server-1", "user-2")
        callback.onAuthenticationChanged()
        val pendingResult = callback.onGetChildren(
            mockk<MediaLibraryService.MediaLibrarySession>(),
            mockk<MediaSession.ControllerInfo>(),
            PtvMusicAutoCategory.RECOMMENDATIONS.mediaId,
            0,
            10,
            null,
        ).get(2, TimeUnit.SECONDS)
        pendingResult.resultCode shouldBe SessionError.ERROR_NOT_SUPPORTED
        verify(exactly = 0) { playbackController.play(any(), any(), any(), any(), any()) }

        localOwner.set(PtvMusicAutoQueueOwner(1, 3))
        playerState.value = MusicPlaybackState(
            queue = listOf(nextTrack),
            currentItem = nextTrack,
            queueOwner = localOwner.get(),
        )
        callback.onAuthenticationChanged()
        val currentResult = callback.onGetChildren(
            mockk<MediaLibraryService.MediaLibrarySession>(),
            mockk<MediaSession.ControllerInfo>(),
            PtvMusicAutoCategory.RECOMMENDATIONS.mediaId,
            0,
            10,
            null,
        ).get(2, TimeUnit.SECONDS)
        currentResult.resultCode shouldBe SessionResult.RESULT_SUCCESS
        currentResult.value?.map(MediaItem::mediaId) shouldBe listOf(PtvMusicAutoIds.track(nextTrack.id))
    }

    @Test
    fun `in flight browse cannot restore previous account items to the new account cache`() = runBlocking {
        val authState = MutableStateFlow<MobileAuthState>(authenticated("server-1", "user-1"))
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<MusicPage>()
        val repository = mockk<MusicRepository>()
        coEvery { repository.loadBrowsePage(MusicBrowseKind.ALBUMS, 0, 10) } coAnswers {
            entered.complete(Unit)
            release.await()
        }
        val callback = callback(authState, repository)
        val request = callback.onGetChildren(
            mockk<MediaLibraryService.MediaLibrarySession>(),
            mockk<MediaSession.ControllerInfo>(),
            PtvMusicAutoCategory.ALBUMS.mediaId,
            0,
            10,
            null,
        )
        withTimeout(2_000) { entered.await() }
        authState.value = authenticated("server-1", "user-2")
        callback.onAuthenticationChanged()
        release.complete(MusicPage(listOf(track()), totalCount = 1, startIndex = 0))
        val result = request.get(5, TimeUnit.SECONDS)
        result.resultCode shouldBe SessionError.ERROR_NOT_SUPPORTED

        @Suppress("UNCHECKED_CAST")
        val itemCache = PtvMusicLibrarySessionCallback::class.java.getDeclaredField("itemCache").apply {
            isAccessible = true
        }.get(callback) as ConcurrentHashMap<Any, Any>
        itemCache.isEmpty() shouldBe true
        Unit
    }

    @Test
    fun `in flight generated mix cannot restore previous account tracks to the new account cache`() = runBlocking {
        val authState = MutableStateFlow<MobileAuthState>(authenticated("server-1", "user-1"))
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<List<MusicItem>>()
        val repository = mockk<MusicRepository>()
        val playbackController = mockk<MusicPlaybackController>()
        every { playbackController.state } returns MutableStateFlow(MusicPlaybackState())
        every { playbackController.isCurrentQueueOwnedByAuthenticatedUser() } returns false
        every { repository.cachedBrowsePage(any(), any(), any()) } returns null
        coEvery { repository.loadRecentlyAddedTracks(any()) } coAnswers {
            entered.complete(Unit)
            release.await()
        }
        val callback = callback(authState, repository, playbackController)
        val request = callback.onGetChildren(
            mockk<MediaLibraryService.MediaLibrarySession>(),
            mockk<MediaSession.ControllerInfo>(),
            PtvMusicAutoMix.LATEST.mediaId,
            0,
            10,
            null,
        )
        withTimeout(2_000) { entered.await() }
        authState.value = authenticated("server-1", "user-2")
        callback.onAuthenticationChanged()
        release.complete(listOf(track()))
        val result = request.get(5, TimeUnit.SECONDS)
        result.resultCode shouldBe SessionError.ERROR_NOT_SUPPORTED

        @Suppress("UNCHECKED_CAST")
        val cache = PtvMusicLibrarySessionCallback::class.java.getDeclaredField("generatedQueueCache").apply {
            isAccessible = true
        }.get(callback) as ConcurrentHashMap<Any, Any>
        cache.isEmpty() shouldBe true
        Unit
    }

    @Test
    fun `later playlist page selection restores full authored queue after Auto callback reconnect`() {
        val auth = MutableStateFlow<MobileAuthState>(authenticated("server-1", "user-1"))
        val playlist = playlist()
        val tracks = (0 until 101).map { index ->
            track().copy(id = UUID(2L, index.toLong()), title = "Track $index")
        }
        val selected = tracks[100]
        val repository = mockk<MusicRepository>()
        coEvery { repository.loadItem(playlist.id) } returns playlist
        coEvery { repository.loadItem(selected.id) } returns selected
        coEvery { repository.loadChildrenPage(playlist, 100, 1) } returns MusicPage(
            listOf(selected),
            totalCount = 101,
            startIndex = 100,
        )
        coEvery { repository.loadPlaylistQueue(playlist) } returns tracks
        val owner = PtvMusicAutoQueueOwner(1, 2)
        val resumeStore = mockk<PtvMusicAutoResumeStore>(relaxed = true)
        every { resumeStore.currentLocalQueueOwner() } returns owner
        val playbackController = mockk<MusicPlaybackController>(relaxed = true)
        every { playbackController.state } returns MutableStateFlow(MusicPlaybackState())
        every { playbackController.isCurrentQueueOwnedByAuthenticatedUser() } returns false
        val firstCallback = callback(auth, repository, playbackController, resumeStore = resumeStore)

        val browsed = firstCallback.onGetChildren(
            mockk<MediaLibraryService.MediaLibrarySession>(),
            mockk<MediaSession.ControllerInfo>(),
            PtvMusicAutoIds.item(playlist.id),
            100,
            1,
            null,
        ).get(5, TimeUnit.SECONDS)
        browsed.resultCode shouldBe SessionResult.RESULT_SUCCESS
        val selectedMediaItem = requireNotNull(browsed.value).single()
        (selectedMediaItem.mediaId == PtvMusicAutoIds.track(selected.id)) shouldBe false

        val reconnectedCallback = callback(auth, repository, playbackController, resumeStore = resumeStore)
        val started = reconnectedCallback.onSetMediaItems(
            mockk<MediaSession>(),
            mockk<MediaSession.ControllerInfo>(),
            listOf(selectedMediaItem),
            0,
            C.TIME_UNSET,
        ).get(5, TimeUnit.SECONDS)

        started.mediaItems.size shouldBe 101
        started.startIndex shouldBe 100
        started.mediaItems[100].mediaId shouldBe selectedMediaItem.mediaId
        (started.mediaItems[0].mediaId == started.mediaItems[100].mediaId) shouldBe false
        coVerify(exactly = 1) { repository.loadPlaylistQueue(playlist) }
        verify(exactly = 1) { playbackController.play(selected, tracks, any(), any(), owner, 100) }
    }

    @Test
    fun `selecting playlist folder loads full authored queue once before playback`() {
        val auth = MutableStateFlow<MobileAuthState>(authenticated("server-1", "user-1"))
        val playlist = playlist()
        val tracks = (0 until 101).map { index -> track().copy(id = UUID(3L, index.toLong())) }
        val repository = mockk<MusicRepository>()
        coEvery { repository.loadItem(playlist.id) } returns playlist
        coEvery { repository.loadPlaylistQueue(playlist) } returns tracks
        val owner = PtvMusicAutoQueueOwner(1, 2)
        val resumeStore = mockk<PtvMusicAutoResumeStore>(relaxed = true)
        every { resumeStore.currentLocalQueueOwner() } returns owner
        val playbackController = mockk<MusicPlaybackController>(relaxed = true)
        every { playbackController.state } returns MutableStateFlow(MusicPlaybackState())
        every { playbackController.isCurrentQueueOwnedByAuthenticatedUser() } returns false
        val callback = callback(auth, repository, playbackController, resumeStore = resumeStore)

        val started = callback.onSetMediaItems(
            mockk<MediaSession>(),
            mockk<MediaSession.ControllerInfo>(),
            listOf(MediaItem.Builder().setMediaId(PtvMusicAutoIds.item(playlist.id)).build()),
            0,
            C.TIME_UNSET,
        ).get(5, TimeUnit.SECONDS)

        started.mediaItems.size shouldBe 101
        started.startIndex shouldBe 0
        coVerify(exactly = 1) { repository.loadPlaylistQueue(playlist) }
        verify(exactly = 1) { playbackController.play(tracks.first(), tracks, any(), any(), owner, 0) }
    }

    @Test
    fun `failed uncached playlist browse reports retryable error then succeeds on retry`() {
        val auth = MutableStateFlow<MobileAuthState>(authenticated("server-1", "user-1"))
        val repository = mockk<MusicRepository>()
        val playlist = playlist()
        var requests = 0
        coEvery { repository.loadBrowsePage(MusicBrowseKind.PLAYLISTS, 0, 10) } coAnswers {
            if (++requests <= 2) throw IOException("Jellyfin temporarily unavailable")
            MusicPage(listOf(playlist), totalCount = 1, startIndex = 0)
        }
        val callback = callback(auth, repository)

        val failed = callback.onGetChildren(
            mockk<MediaLibraryService.MediaLibrarySession>(),
            mockk<MediaSession.ControllerInfo>(),
            PtvMusicAutoCategory.PLAYLISTS.mediaId,
            0,
            10,
            null,
        ).get(5, TimeUnit.SECONDS)
        failed.resultCode shouldBe SessionError.ERROR_IO
        failed.value shouldBe null

        val retried = callback.onGetChildren(
            mockk<MediaLibraryService.MediaLibrarySession>(),
            mockk<MediaSession.ControllerInfo>(),
            PtvMusicAutoCategory.PLAYLISTS.mediaId,
            0,
            10,
            null,
        ).get(5, TimeUnit.SECONDS)
        retried.resultCode shouldBe SessionResult.RESULT_SUCCESS
        retried.value?.single()?.mediaId shouldBe PtvMusicAutoIds.item(playlist.id)
    }

    @Test
    fun `current genre mix refreshes when the playing seed changes`() {
        val auth = MutableStateFlow<MobileAuthState>(authenticated("server-1", "user-1"))
        val metalSeed = track().copy(genres = listOf("Metal"))
        val jazzSeed = track().copy(genres = listOf("Jazz"))
        val metalTrack = track().copy(id = UUID(5L, 1L))
        val jazzTrack = track().copy(id = UUID(5L, 2L))
        val playbackState = MutableStateFlow(MusicPlaybackState(currentItem = metalSeed))
        val playbackController = mockk<MusicPlaybackController>()
        every { playbackController.state } returns playbackState
        every { playbackController.isCurrentQueueOwnedByAuthenticatedUser() } returns true
        val repository = mockk<MusicRepository>()
        every { repository.cachedBrowsePage(any(), any(), any()) } returns null
        coEvery { repository.loadGenreTracks("Metal", any()) } returns listOf(metalTrack)
        coEvery { repository.loadGenreTracks("Jazz", any()) } returns listOf(jazzTrack)
        val callback = callback(auth, repository, playbackController)

        val first = callback.onGetChildren(
            mockk<MediaLibraryService.MediaLibrarySession>(),
            mockk<MediaSession.ControllerInfo>(),
            PtvMusicAutoMix.CURRENT_GENRE.mediaId,
            0,
            10,
            null,
        ).get(5, TimeUnit.SECONDS)
        first.value?.single()?.mediaId shouldBe PtvMusicAutoIds.track(metalTrack.id)

        playbackState.value = MusicPlaybackState(currentItem = jazzSeed)
        val second = callback.onGetChildren(
            mockk<MediaLibraryService.MediaLibrarySession>(),
            mockk<MediaSession.ControllerInfo>(),
            PtvMusicAutoMix.CURRENT_GENRE.mediaId,
            0,
            10,
            null,
        ).get(5, TimeUnit.SECONDS)

        second.value?.single()?.mediaId shouldBe PtvMusicAutoIds.track(jazzTrack.id)
    }
}

abstract class PtvMusicAutoBrowseFixture {
    @BeforeEach
    fun stubAndroidClock() {
        mockkStatic(SystemClock::class)
        every { SystemClock.elapsedRealtime() } returns 1L
        mockkStatic(TextUtils::class)
        every { TextUtils.isEmpty(any()) } answers { firstArg<CharSequence?>().isNullOrEmpty() }
        mockkConstructor(Bundle::class)
        every { anyConstructed<Bundle>().putInt(any(), any()) } returns Unit
        mockkConstructor(Uri.Builder::class)
        val uriBuilder = mockk<Uri.Builder>()
        every { anyConstructed<Uri.Builder>().scheme(any()) } returns uriBuilder
        every { uriBuilder.authority(any()) } returns uriBuilder
        every { uriBuilder.appendPath(any()) } returns uriBuilder
        every { uriBuilder.build() } returns mockk<Uri>()
    }

    @AfterEach
    fun restoreAndroidClock() {
        unmockkStatic(SystemClock::class)
        unmockkStatic(TextUtils::class)
        unmockkConstructor(Bundle::class)
        unmockkConstructor(Uri.Builder::class)
    }

    protected fun beginAutoPicksSession(
        repository: MusicRepository,
        playbackController: MusicPlaybackController,
        resumeStore: PtvMusicAutoResumeStore,
    ) {
        val callback = callback(
            MutableStateFlow(recommendationAuthenticated()),
            repository,
            playbackController,
            resumeStore = resumeStore,
        )
        callback.onSetMediaItems(
            mockk<MediaSession>(),
            mockk<MediaSession.ControllerInfo>(),
            listOf(MediaItem.Builder().setMediaId(PtvMusicAutoIds.AUTO_PICKS).build()),
            0,
            C.TIME_UNSET,
        ).get(5, TimeUnit.SECONDS)
    }

    protected fun playAutoQueue(
        state: MutableStateFlow<MusicPlaybackState>,
        queue: List<MusicItem>,
        currentIndex: Int,
        owner: PtvMusicAutoQueueOwner,
    ) {
        state.value = MusicPlaybackState(
            queue = queue,
            currentItem = queue[currentIndex],
            currentIndex = currentIndex,
            isPlaying = true,
            queueOwner = owner,
        )
    }

    protected fun playlist() = track().copy(
        id = UUID(4L, 1L),
        title = "Driving playlist",
        type = BaseItemKind.PLAYLIST,
        isFolder = true,
        isPlayable = false,
    )

    internal fun callback(
        authState: StateFlow<MobileAuthState>,
        repository: MusicRepository = mockk(),
        playbackController: MusicPlaybackController = mockk(),
        savedSessionReady: CompletableDeferred<UUID?> = CompletableDeferred<UUID?>().apply { complete(null) },
        resumeStore: PtvMusicAutoResumeStore = mockk(relaxed = true),
        resumeCoordinator: PtvMusicAutoResumeCoordinator = mockk(),
        playerDispatcher: CoroutineDispatcher = Dispatchers.Unconfined,
        serverUrlForLocalId: (Long) -> String? = { id -> if (id == 1L) "https://server-1" else null },
    ) = PtvMusicLibrarySessionCallback(
        context = mockAutoContext(),
        repository = repository,
        playbackController = playbackController,
        songActionHandler = mockk<MusicSongActionHandler>(),
        resumeStore = resumeStore,
        resumeCoordinator = resumeCoordinator,
        savedSessionReady = savedSessionReady,
        authState = authState,
        playerDispatcher = playerDispatcher,
        serverUrlForLocalId = serverUrlForLocalId,
    )

    protected fun recommendationAuthenticated(userId: UUID = UUID(1L, 2L)) = authenticated("1", userId.toString())

    protected fun mockAutoContext(): Context {
        val resources = mockk<Resources>()
        every { resources.getResourcePackageName(any()) } returns "org.jellyfin.mobile"
        every { resources.getResourceTypeName(any()) } returns "drawable"
        every { resources.getResourceEntryName(any()) } returns "ic_music_note_white_24dp"
        val context = mockk<Context>()
        every { context.resources } returns resources
        return context
    }

    protected fun track() = MusicItem(
        id = UUID.randomUUID(),
        title = "Prior account track",
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

    protected fun authenticated(server: String, user: String): MobileAuthState.Authenticated =
        MobileAuthState.Authenticated(
            serverId = server,
            userId = user,
            credentialInstanceId = UUID.randomUUID(),
            method = AuthMethod.RESTORE,
            credentialEpoch = 1,
        )
}
