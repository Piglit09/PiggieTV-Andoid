package org.jellyfin.mobile.feature.music.auto

import android.content.Context
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.jellyfin.mobile.auth.AuthFailureCategory
import org.jellyfin.mobile.auth.AuthMethod
import org.jellyfin.mobile.auth.MobileAuthState
import org.jellyfin.mobile.feature.music.MusicBrowseKind
import org.jellyfin.mobile.feature.music.MusicItem
import org.jellyfin.mobile.feature.music.MusicPage
import org.jellyfin.mobile.feature.music.MusicPlaybackController
import org.jellyfin.mobile.feature.music.MusicPlaybackState
import org.jellyfin.mobile.feature.music.MusicRepository
import org.jellyfin.mobile.feature.music.MusicSongActionHandler
import org.jellyfin.sdk.model.api.BaseItemKind
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class PtvMusicAutoBrowseRecoveryTest {
    @BeforeEach
    fun stubAndroidClock() {
        mockkStatic(SystemClock::class)
        every { SystemClock.elapsedRealtime() } returns 1L
        mockkStatic(TextUtils::class)
        every { TextUtils.isEmpty(any()) } answers { firstArg<CharSequence?>().isNullOrEmpty() }
        mockkConstructor(Bundle::class)
        every { anyConstructed<Bundle>().putInt(any(), any()) } returns Unit
    }

    @AfterEach
    fun restoreAndroidClock() {
        unmockkStatic(SystemClock::class)
        unmockkStatic(TextUtils::class)
        unmockkConstructor(Bundle::class)
    }

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

    private fun callback(
        authState: StateFlow<MobileAuthState>,
        repository: MusicRepository = mockk(),
        playbackController: MusicPlaybackController = mockk(),
        savedSessionReady: CompletableDeferred<UUID?> = CompletableDeferred<UUID?>().apply { complete(null) },
        resumeStore: PtvMusicAutoResumeStore = mockk(relaxed = true),
        resumeCoordinator: PtvMusicAutoResumeCoordinator = mockk(),
    ) = PtvMusicLibrarySessionCallback(
        context = mockk<Context>(),
        repository = repository,
        playbackController = playbackController,
        songActionHandler = mockk<MusicSongActionHandler>(),
        resumeStore = resumeStore,
        resumeCoordinator = resumeCoordinator,
        savedSessionReady = savedSessionReady,
        authState = authState,
    )

    private fun track() = MusicItem(
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

    private fun authenticated(server: String, user: String): MobileAuthState.Authenticated =
        MobileAuthState.Authenticated(
            serverId = server,
            userId = user,
            credentialInstanceId = UUID.randomUUID(),
            method = AuthMethod.RESTORE,
            credentialEpoch = 1,
        )
}
