package org.jellyfin.mobile.feature.music.auto

import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.Tracks
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.UnstableApi
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import org.jellyfin.mobile.feature.music.MusicItem
import org.jellyfin.mobile.feature.music.MusicPlaybackController
import org.jellyfin.mobile.feature.music.MusicPlaybackState
import org.jellyfin.mobile.feature.music.MusicRepeatMode
import org.jellyfin.sdk.model.api.BaseItemKind
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

@UnstableApi
class PtvMusicSessionPlayerStateTest {
    @AfterEach
    fun resetMedia3CommandsMock() {
        unmockkStatic("org.jellyfin.mobile.feature.music.auto.PtvMusicSessionPlayerKt")
    }

    @Test
    fun `three consecutive tracks produce exactly one transition per actual advance`() {
        val a = track(1)
        val b = track(2)
        val c = track(3)
        val queue = listOf(a, b, c)
        val initial = state(queue, index = 0)
        val (player, controllerState) = player(initial)
        val listener = RecordingListener()
        player.addListener(listener)
        player.notifyControllerStateChanged(initial)
        listener.clear()

        announce(player, controllerState, initial.copy(isBuffering = true))
        announce(player, controllerState, state(queue, index = 1, isPlaying = true))
        announce(player, controllerState, state(queue, index = 1, isPlaying = false))
        announce(player, controllerState, state(queue, index = 2, isPlaying = true))

        listener.transitions shouldBe listOf(PtvMusicAutoIds.track(b.id), PtvMusicAutoIds.track(c.id))
    }

    @Test
    fun `same track metadata refresh does not announce a new track`() {
        val original = track(1)
        val initial = state(listOf(original), index = 0)
        val (player, controllerState) = player(initial)
        val listener = RecordingListener()
        player.addListener(listener)
        player.notifyControllerStateChanged(initial)
        listener.clear()

        announce(player, controllerState, state(listOf(original.copy(title = "Corrected title")), index = 0))

        listener.transitions shouldBe emptyList()
        listener.metadataTitles shouldBe listOf("Corrected title")
    }

    @Test
    fun `same size queue replacement announces timeline without a track transition`() {
        val a = track(1)
        val b = track(2)
        val c = track(3)
        val initial = state(listOf(a, b), index = 0)
        val (player, controllerState) = player(initial)
        val listener = RecordingListener()
        player.addListener(listener)
        player.notifyControllerStateChanged(initial)
        listener.clear()

        announce(player, controllerState, state(listOf(a, c), index = 0))

        listener.timelineChanges shouldBe 1
        listener.transitions shouldBe emptyList()
    }

    @Test
    fun `service observes metadata and artwork changes for the same track id`() {
        val original = track(1)
        val before = state(listOf(original), index = 0).toAutoRuntimeSnapshot()
        val after = state(
            listOf(original.copy(title = "Corrected title", posterUrl = "https://example.invalid/new-cover.jpg")),
            index = 0,
        ).toAutoRuntimeSnapshot()

        after shouldNotBe before
        after.currentTrack?.title shouldBe "Corrected title"
        after.currentTrack?.posterUrl shouldBe "https://example.invalid/new-cover.jpg"
    }

    @Test
    fun `service observes queue replacement even when the size and current item are unchanged`() {
        val a = track(1)
        val before = state(listOf(a, track(2)), index = 0).toAutoRuntimeSnapshot()
        val after = state(listOf(a, track(3)), index = 0).toAutoRuntimeSnapshot()

        after shouldNotBe before
        after.queue.map(PtvMusicAutoMediaItemSnapshot::id) shouldBe listOf(a.id.toString(), track(3).id.toString())
    }

    @Test
    fun `underlying player transition and controller advance do not duplicate the session transition`() {
        val a = track(1)
        val b = track(2)
        val initial = state(listOf(a, b), index = 0)
        val underlying = mockk<Player>(relaxed = true)
        val underlyingListener = slot<Player.Listener>()
        every { underlying.addListener(capture(underlyingListener)) } just Runs
        val (player, controllerState) = player(initial, underlying)
        val listener = RecordingListener()
        player.addListener(listener)
        player.notifyControllerStateChanged(initial)
        listener.clear()

        underlyingListener.captured.onMediaItemTransition(null, Player.MEDIA_ITEM_TRANSITION_REASON_AUTO)
        announce(player, controllerState, state(listOf(a, b), index = 1, isPlaying = true))

        listener.transitions shouldBe listOf(PtvMusicAutoIds.track(b.id))
    }

    @Test
    fun `underlying playback and aggregate callbacks still forward and listener removal is symmetric`() {
        val initial = state(listOf(track(1)), index = 0)
        val underlying = mockk<Player>(relaxed = true)
        val underlyingListener = slot<Player.Listener>()
        every { underlying.addListener(capture(underlyingListener)) } just Runs
        val (player, _) = player(initial, underlying)
        val listener = RecordingListener()
        player.addListener(listener)
        val registered = underlyingListener.captured
        val events = mockk<Player.Events>(relaxed = true)

        registered.onPlaybackStateChanged(Player.STATE_READY)
        registered.onEvents(underlying, events)
        player.removeListener(listener)

        listener.playbackStates shouldBe listOf(Player.STATE_READY)
        listener.eventBatches shouldBe 1
        verify(exactly = 1) { underlying.removeListener(registered) }
    }

    @Test
    fun `cleared controller queue never exposes stale underlying media or seek state`() {
        val previousTrack = track(1)
        val initial = state(listOf(previousTrack), index = 0)
        val underlying = mockk<Player>(relaxed = true)
        val (player, controllerState) = player(initial, underlying)
        val staleMediaItem = MediaItem.Builder().setMediaId(PtvMusicAutoIds.track(previousTrack.id)).build()
        val staleTimeline = mockk<Timeline>(relaxed = true)
        every { staleTimeline.windowCount } returns 1
        every { underlying.currentMediaItem } returns staleMediaItem
        every { underlying.mediaItemCount } returns 1
        every { underlying.getMediaItemAt(0) } returns staleMediaItem
        every { underlying.currentTimeline } returns staleTimeline
        every { underlying.mediaMetadata } returns MediaMetadata.Builder().setTitle("Previous account track").build()
        every { underlying.duration } returns 180_000L
        every { underlying.currentPosition } returns 42_000L
        every { underlying.bufferedPosition } returns 60_000L
        every { underlying.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM) } returns true
        var seekAllowed: Boolean? = null
        every { ptvMusicAutoPlayerCommands(any()) } answers {
            seekAllowed = firstArg()
            mockk(relaxed = true)
        }

        controllerState.value = MusicPlaybackState()

        player.currentMediaItem shouldBe null
        player.mediaItemCount shouldBe 0
        shouldThrow<IndexOutOfBoundsException> { player.getMediaItemAt(0) }
        player.currentTimeline.windowCount shouldBe 0
        player.mediaMetadata.title shouldBe null
        player.duration shouldBe C.TIME_UNSET
        player.currentPosition shouldBe 0
        player.bufferedPosition shouldBe 0
        player.availableCommands
        seekAllowed shouldBe false
    }

    @Test
    fun `account change hides resident queue before auth collector discards it`() {
        val previousTrack = track(1)
        val residentState = state(listOf(previousTrack), index = 0, isPlaying = true)
        val underlying = mockk<Player>(relaxed = true)
        val underlyingListener = slot<Player.Listener>()
        every { underlying.addListener(capture(underlyingListener)) } just Runs
        val ownerAllowed = AtomicBoolean(true)
        val (player, _) = player(residentState, underlying, ownerAllowed)
        val listener = RecordingListener()
        player.addListener(listener)
        player.notifyControllerStateChanged(residentState)
        listener.clear()
        every { underlying.duration } returns 180_000L
        every { underlying.currentPosition } returns 42_000L
        every { underlying.isPlaying } returns true
        every { underlying.playbackState } returns Player.STATE_READY
        every { underlying.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM) } returns true
        val previousPlaylistMetadata = MediaMetadata.Builder()
            .setTitle("Previous account playlist")
            .build()
        every { underlying.playlistMetadata } returns previousPlaylistMetadata
        every { underlying.currentTracks } returns mockk<Tracks>(relaxed = true)
        every { underlying.currentManifest } returns "previous-account-manifest"
        every { underlying.playerError } returns mockk(relaxed = true)
        every { underlying.currentCues } returns mockk(relaxed = true)

        // Local account identity changed; the service's auth collector has not run yet.
        ownerAllowed.set(false)
        player.notifyControllerStateChanged(residentState)
        underlyingListener.captured.onPlaybackStateChanged(Player.STATE_READY)

        player.currentMediaItem shouldBe null
        player.mediaItemCount shouldBe 0
        player.currentTimeline.windowCount shouldBe 0
        player.mediaMetadata.title shouldBe null
        player.duration shouldBe C.TIME_UNSET
        player.currentPosition shouldBe 0
        player.isPlaying shouldBe false
        player.playbackState shouldBe Player.STATE_IDLE
        player.playlistMetadata.title shouldBe null
        player.currentTracks shouldBe Tracks.EMPTY
        player.currentManifest shouldBe null
        player.playerError shouldBe null
        player.currentCues shouldBe CueGroup.EMPTY_TIME_ZERO
        listener.transitions shouldBe listOf(null)
        listener.metadataTitles shouldBe listOf(null)
        listener.playbackStates shouldBe listOf(Player.STATE_IDLE)
    }

    @Test
    fun `new authenticated account hides old queue while local owner is still unchanged`() {
        val residentState = state(listOf(track(1)), index = 0, isPlaying = true)
        val ownerAllowed = AtomicBoolean(true)
        val authenticatedOwnerAllowed = AtomicBoolean(true)
        val controller = mockk<MusicPlaybackController>(relaxed = true)
        val (player, _) = player(
            initial = residentState,
            ownerAllowed = ownerAllowed,
            authenticatedOwnerAllowed = authenticatedOwnerAllowed,
            controller = controller,
        )
        val listener = RecordingListener()
        player.addListener(listener)
        player.notifyControllerStateChanged(residentState)
        listener.clear()

        // Authenticated identity changed before the local owner preference and service collector caught up.
        authenticatedOwnerAllowed.set(false)
        ownerAllowed.get() shouldBe true
        player.notifyControllerStateChanged(residentState)

        player.currentMediaItem shouldBe null
        player.mediaItemCount shouldBe 0
        player.currentTimeline.windowCount shouldBe 0
        player.mediaMetadata.title shouldBe null
        player.playbackState shouldBe Player.STATE_IDLE
        listener.transitions shouldBe listOf(null)
        player.play()
        verify(exactly = 0) { controller.playCurrent() }
    }

    @Test
    fun `foreign queue cannot resume advance or seek while pause and stop remain available`() {
        val ownerAllowed = AtomicBoolean(false)
        val controller = mockk<MusicPlaybackController>(relaxed = true)
        val underlying = mockk<Player>(relaxed = true)
        val (player, _) = player(
            state(listOf(track(1), track(2)), index = 0),
            underlying = underlying,
            ownerAllowed = ownerAllowed,
            controller = controller,
        )

        player.play()
        player.playWhenReady = true
        player.prepare()
        player.seekTo(42_000L)
        player.seekTo(1, 42_000L)
        player.seekToDefaultPosition()
        player.seekToDefaultPosition(1)
        player.seekBack()
        player.seekForward()
        player.seekToNext()
        player.seekToNextMediaItem()
        player.seekToPrevious()
        player.seekToPreviousMediaItem()
        player.shuffleModeEnabled = true
        player.repeatMode = Player.REPEAT_MODE_ALL
        verify(exactly = 0) { controller.playCurrent() }
        verify(exactly = 0) { controller.prepareCurrent() }
        verify(exactly = 0) { controller.seekTo(any()) }
        verify(exactly = 0) { controller.seekToQueueItem(any(), any()) }
        verify(exactly = 0) { controller.seekBy(any()) }
        verify(exactly = 0) { controller.next() }
        verify(exactly = 0) { controller.previous() }
        verify(exactly = 0) { controller.setShuffleEnabled(any(), any()) }
        verify(exactly = 0) { controller.setRepeatMode(any(), any()) }
        verify(exactly = 0) { underlying.playWhenReady = true }

        player.pause()
        player.stop()
        verify(exactly = 1) { controller.pause() }
        verify(exactly = 1) { controller.stop(source = "mediaSession") }

        ownerAllowed.set(true)
        player.play()
        player.prepare()
        player.seekTo(42_000L)
        player.seekToNextMediaItem()
        player.seekToPreviousMediaItem()
        player.shuffleModeEnabled = true
        player.repeatMode = Player.REPEAT_MODE_ALL
        verify(exactly = 1) { controller.playCurrent() }
        verify(exactly = 1) { controller.prepareCurrent() }
        verify(exactly = 1) { controller.seekTo(42_000L) }
        verify(exactly = 1) { controller.next() }
        verify(exactly = 1) { controller.previous() }
        verify(exactly = 1) { controller.setShuffleEnabled(true, "mediaSession") }
        verify(exactly = 1) { controller.setRepeatMode(MusicRepeatMode.ALL, "mediaSession") }
    }

    private fun player(
        initial: MusicPlaybackState,
        underlying: Player = mockk(relaxed = true),
        ownerAllowed: AtomicBoolean = AtomicBoolean(true),
        authenticatedOwnerAllowed: AtomicBoolean = AtomicBoolean(true),
        controller: MusicPlaybackController = mockk(relaxed = true),
    ): Pair<PtvMusicSessionPlayer, MutableStateFlow<MusicPlaybackState>> {
        // Media3's command builder uses Android SparseBooleanArray, unavailable in local JVM tests.
        mockkStatic("org.jellyfin.mobile.feature.music.auto.PtvMusicSessionPlayerKt")
        every { ptvMusicAutoPlayerCommands(any()) } returns mockk(relaxed = true)
        val state = MutableStateFlow(initial)
        every { controller.state } returns state
        every { controller.isCurrentQueueOwnedByAuthenticatedUser() } answers { ownerAllowed.get() }
        every { controller.mediaSessionPlayer } returns underlying
        every { underlying.duration } returns C.TIME_UNSET
        every { underlying.currentPosition } returns C.TIME_UNSET
        every { underlying.bufferedPosition } returns C.TIME_UNSET
        every { underlying.isCommandAvailable(any()) } returns false
        return PtvMusicSessionPlayer(controller) { authenticatedOwnerAllowed.get() } to state
    }

    private fun announce(
        player: PtvMusicSessionPlayer,
        controllerState: MutableStateFlow<MusicPlaybackState>,
        newState: MusicPlaybackState,
    ) {
        controllerState.value = newState
        player.notifyControllerStateChanged(newState)
    }

    private fun state(queue: List<MusicItem>, index: Int, isPlaying: Boolean = false): MusicPlaybackState =
        MusicPlaybackState(queue = queue, currentItem = queue[index], currentIndex = index, isPlaying = isPlaying)

    private fun track(suffix: Int): MusicItem = MusicItem(
        id = UUID.fromString("00000000-0000-0000-0000-${suffix.toString().padStart(12, '0')}"),
        title = "Track $suffix",
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

    private class RecordingListener : Player.Listener {
        val transitions = mutableListOf<String?>()
        val metadataTitles = mutableListOf<String?>()
        var timelineChanges = 0
        val playbackStates = mutableListOf<Int>()
        var eventBatches = 0

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            transitions += mediaItem?.mediaId
        }

        override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
            metadataTitles += mediaMetadata.title?.toString()
        }

        override fun onTimelineChanged(timeline: Timeline, reason: Int) {
            timelineChanges++
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            playbackStates += playbackState
        }

        override fun onEvents(player: Player, events: Player.Events) {
            eventBatches++
        }

        fun clear() {
            transitions.clear()
            metadataTitles.clear()
            timelineChanges = 0
            playbackStates.clear()
            eventBatches = 0
        }
    }
}
