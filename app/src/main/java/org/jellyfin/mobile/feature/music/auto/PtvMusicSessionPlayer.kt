package org.jellyfin.mobile.feature.music.auto

import androidx.core.net.toUri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.DeviceInfo
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Metadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.text.Cue
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import org.jellyfin.mobile.feature.music.MusicItem
import org.jellyfin.mobile.feature.music.MusicPlaybackController
import org.jellyfin.mobile.feature.music.MusicPlaybackState
import org.jellyfin.mobile.feature.music.MusicRepeatMode
import timber.log.Timber
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet

@UnstableApi
internal class PtvMusicSessionPlayer(
    private val playbackController: MusicPlaybackController,
    private val isAuthenticatedQueueOwner: () -> Boolean,
    private val pendingQueueAuthenticationProof: () -> PtvMusicQueueAuthenticationProof? = { null },
) : ForwardingPlayer(playbackController.mediaSessionPlayer) {
    val instanceId: String = runtimeInstanceId(this)
    private val sessionListeners = CopyOnWriteArraySet<Player.Listener>()
    private val underlyingListeners = ConcurrentHashMap<Player.Listener, Player.Listener>()
    private var lastNotifiedSnapshot: PtvMusicAutoPlayerStateSnapshot? = null
    private var pendingPlay: PendingPlayIntent? = null

    init {
        Timber.i(
            "PTV Music Auto session player created sessionPlayerId=$instanceId " +
                "controllerId=${playbackController.instanceId} " +
                "playerId=${playbackController.playerInstanceId}",
        )
    }

    override fun addListener(listener: Player.Listener) {
        if (!sessionListeners.add(listener)) return
        val underlyingListener = PtvMusicUnderlyingListener(listener) {
            playbackController.isCurrentQueueOwnedByAuthenticatedUser() && isAuthenticatedQueueOwner()
        }
        underlyingListeners[listener] = underlyingListener
        super.addListener(underlyingListener)
    }

    override fun removeListener(listener: Player.Listener) {
        if (!sessionListeners.remove(listener)) return
        underlyingListeners.remove(listener)?.let { underlyingListener -> super.removeListener(underlyingListener) }
    }

    override fun getAvailableCommands(): Player.Commands = ptvMusicAutoPlayerCommands(
        canSeek = canSeekInCurrentMediaItem(),
    )

    override fun isCommandAvailable(command: Int): Boolean = command in getAvailableCommands()

    private fun visibleState(state: MusicPlaybackState = playbackController.state.value): MusicPlaybackState =
        if (playbackController.state.value === state &&
            playbackController.isCurrentQueueOwnedByAuthenticatedUser() &&
            isAuthenticatedQueueOwner()
        ) {
            state
        } else {
            MusicPlaybackState()
        }

    override fun getCurrentMediaItemIndex(): Int = visibleState().safeCurrentIndex()

    @Suppress("DEPRECATION")
    @Deprecated("Deprecated in Media3")
    override fun getCurrentWindowIndex(): Int = currentMediaItemIndex

    override fun getNextMediaItemIndex(): Int {
        val state = visibleState()
        val queue = state.queue
        val index = state.safeCurrentIndex()
        return when {
            queue.isEmpty() || index == C.INDEX_UNSET -> C.INDEX_UNSET
            index < queue.lastIndex -> index + 1
            state.repeatMode == MusicRepeatMode.ALL -> 0
            else -> C.INDEX_UNSET
        }
    }

    @Suppress("DEPRECATION")
    @Deprecated("Deprecated in Media3")
    override fun getNextWindowIndex(): Int = nextMediaItemIndex

    override fun getPreviousMediaItemIndex(): Int {
        val state = visibleState()
        val queue = state.queue
        val index = state.safeCurrentIndex()
        return when {
            queue.isEmpty() || index == C.INDEX_UNSET -> C.INDEX_UNSET
            index > 0 -> index - 1
            state.repeatMode == MusicRepeatMode.ALL -> queue.lastIndex
            else -> C.INDEX_UNSET
        }
    }

    @Suppress("DEPRECATION")
    @Deprecated("Deprecated in Media3")
    override fun getPreviousWindowIndex(): Int = previousMediaItemIndex

    override fun hasNextMediaItem(): Boolean = nextMediaItemIndex != C.INDEX_UNSET

    override fun hasPreviousMediaItem(): Boolean = previousMediaItemIndex != C.INDEX_UNSET

    override fun getCurrentMediaItem(): MediaItem? = visibleState().currentItem?.toSessionMediaItem()

    override fun getMediaItemCount(): Int = visibleState().queue.size

    override fun getMediaItemAt(index: Int): MediaItem = visibleState().queue[index].toSessionMediaItem()

    override fun getCurrentTimeline(): Timeline {
        val state = visibleState()
        return if (state.queue.isEmpty()) {
            Timeline.EMPTY
        } else {
            PtvMusicQueueTimeline(
                queue = state.queue,
                currentIndex = state.safeCurrentIndex().takeIf { index -> index != C.INDEX_UNSET } ?: 0,
                currentDurationMs = liveDurationMs().takeIf { duration -> duration > 0 } ?: state.durationMs,
            )
        }
    }

    override fun getMediaMetadata(): MediaMetadata = visibleState().currentItem
        ?.toSessionMediaItem()
        ?.mediaMetadata
        ?: MediaMetadata.EMPTY

    override fun getPlaylistMetadata(): MediaMetadata =
        if (visibleState().hasCurrent) super.getPlaylistMetadata() else MediaMetadata.EMPTY

    override fun getCurrentTracks(): Tracks = if (visibleState().hasCurrent) super.getCurrentTracks() else Tracks.EMPTY

    override fun getCurrentManifest(): Any? = if (visibleState().hasCurrent) super.getCurrentManifest() else null

    override fun getPlayerError(): PlaybackException? = if (visibleState().hasCurrent) super.getPlayerError() else null

    override fun getCurrentCues(): CueGroup =
        if (visibleState().hasCurrent) super.getCurrentCues() else CueGroup.EMPTY_TIME_ZERO

    override fun getDuration(): Long = if (!visibleState().hasCurrent) {
        C.TIME_UNSET
    } else {
        liveDurationMs().takeIf { duration -> duration > 0 }
            ?: visibleState().durationMs.takeIf { duration -> duration > 0 }
            ?: C.TIME_UNSET
    }

    override fun getContentDuration(): Long = duration

    override fun getCurrentPosition(): Long = if (!visibleState().hasCurrent) {
        0
    } else {
        liveCurrentPositionMs() ?: visibleState().positionMs.coerceAtLeast(0)
    }

    override fun getContentPosition(): Long = currentPosition

    override fun getBufferedPosition(): Long = if (!visibleState().hasCurrent) {
        0
    } else {
        liveBufferedPositionMs()?.coerceAtLeast(currentPosition)
            ?: visibleState().bufferedPositionMs.coerceAtLeast(currentPosition)
    }

    override fun getContentBufferedPosition(): Long = bufferedPosition

    override fun getTotalBufferedDuration(): Long = (bufferedPosition - currentPosition).coerceAtLeast(0)

    override fun getPlaybackState(): Int =
        if (visibleState().hasCurrent) super.getPlaybackState() else Player.STATE_IDLE

    override fun isPlaying(): Boolean = visibleState().hasCurrent && super.isPlaying()

    override fun getPlayWhenReady(): Boolean = visibleState().hasCurrent && super.getPlayWhenReady()

    override fun isLoading(): Boolean = visibleState().hasCurrent && super.isLoading()

    override fun getBufferedPercentage(): Int = if (visibleState().hasCurrent) super.getBufferedPercentage() else 0

    override fun play() {
        val state = playbackController.state.value
        if (visibleState(state).hasCurrent) {
            pendingPlay = null
            playbackController.playCurrent()
            return
        }

        // A hidden queue may be awaiting the service's account proof. Retain only this Play,
        // and only for the local owner and the exact proof request currently in flight.
        pendingPlay = null
        val proof = pendingQueueAuthenticationProof()
        if (proof != null) {
            val currentItem = state.currentItem
            if (currentItem != null && canDeferPlay(state, currentItem, proof)) {
                pendingPlay = PendingPlayIntent(
                    requestedProof = proof,
                    currentItemId = currentItem.id,
                    currentIndex = state.currentIndex,
                    queueSize = state.queue.size,
                )
            }
        }
    }

    private fun canDeferPlay(
        state: MusicPlaybackState,
        currentItem: MusicItem,
        proof: PtvMusicQueueAuthenticationProof,
    ): Boolean {
        val selectedItemMatchesQueue = state.queueOwner == proof.owner &&
            state.currentIndex in state.queue.indices &&
            state.queue[state.currentIndex].id == currentItem.id
        return selectedItemMatchesQueue && playbackController.isCurrentQueueOwnedByAuthenticatedUser()
    }

    override fun setPlayWhenReady(playWhenReady: Boolean) {
        if (playWhenReady) play() else pause()
    }

    override fun pause() {
        cancelPendingPlay()
        playbackController.pause()
    }

    override fun prepare() {
        if (visibleState().hasCurrent) playbackController.prepareCurrent()
    }

    override fun seekTo(positionMs: Long) {
        if (visibleState().hasCurrent) playbackController.seekTo(positionMs)
    }

    override fun seekTo(mediaItemIndex: Int, positionMs: Long) {
        if (!visibleState().hasCurrent) return
        if (mediaItemIndex == C.INDEX_UNSET) {
            playbackController.seekTo(positionMs)
        } else {
            playbackController.seekToQueueItem(mediaItemIndex, positionMs)
        }
    }

    override fun seekToDefaultPosition() {
        if (visibleState().hasCurrent) playbackController.seekTo(0)
    }

    override fun seekToDefaultPosition(mediaItemIndex: Int) {
        if (visibleState().hasCurrent) playbackController.seekToQueueItem(mediaItemIndex, 0)
    }

    override fun seekBack() {
        if (visibleState().hasCurrent) playbackController.seekBy(-seekBackIncrement)
    }

    override fun seekForward() {
        if (visibleState().hasCurrent) playbackController.seekBy(seekForwardIncrement)
    }

    override fun seekToNext() {
        if (visibleState().hasCurrent) playbackController.next()
    }

    override fun seekToNextMediaItem() {
        if (visibleState().hasCurrent) playbackController.next()
    }

    override fun seekToPrevious() {
        if (visibleState().hasCurrent) playbackController.previous()
    }

    override fun seekToPreviousMediaItem() {
        if (visibleState().hasCurrent) playbackController.previous()
    }

    override fun setShuffleModeEnabled(shuffleModeEnabled: Boolean) {
        if (visibleState().hasCurrent) playbackController.setShuffleEnabled(shuffleModeEnabled, source = "mediaSession")
    }

    override fun getShuffleModeEnabled(): Boolean = visibleState().shuffleEnabled

    override fun setRepeatMode(repeatMode: Int) {
        if (visibleState().hasCurrent) {
            playbackController.setRepeatMode(
                repeatMode.toMusicRepeatMode(),
                source = "mediaSession",
            )
        }
    }

    override fun getRepeatMode(): Int = visibleState().repeatMode.toPlayerRepeatMode()

    override fun setMediaItem(mediaItem: MediaItem) = Unit

    override fun setMediaItem(mediaItem: MediaItem, startPositionMs: Long) = Unit

    override fun setMediaItem(mediaItem: MediaItem, resetPosition: Boolean) = Unit

    override fun setMediaItems(mediaItems: List<MediaItem>) = Unit

    override fun setMediaItems(mediaItems: List<MediaItem>, resetPosition: Boolean) = Unit

    override fun setMediaItems(mediaItems: List<MediaItem>, startIndex: Int, startPositionMs: Long) = Unit

    override fun stop() {
        cancelPendingPlay()
        playbackController.stop(source = "mediaSession")
    }

    /** Called for direct notification actions and authentication failures outside this Player. */
    fun cancelPendingPlay() {
        pendingPlay = null
    }

    /** Task removal may retain the service only for this exact live proof request and Play intent. */
    fun hasPendingPlayFor(requestedProof: PtvMusicQueueAuthenticationProof?): Boolean {
        if (requestedProof == null) return false
        discardStalePendingPlay()
        return pendingPlay?.requestedProof === requestedProof
    }

    /** A deferred Play may start only after the service publishes the matching verified proof. */
    fun onQueueAuthenticationVerified(
        requestedProof: PtvMusicQueueAuthenticationProof,
        verifiedProof: PtvMusicQueueAuthenticationProof,
    ) {
        val intent = pendingPlay ?: return
        pendingPlay = null
        val state = playbackController.state.value
        val proofMatches = intent.requestedProof === requestedProof && requestedProof == verifiedProof
        if (!proofMatches || !intent.matches(state) || !visibleState(state).hasCurrent) {
            return
        }
        playbackController.playCurrent()
    }

    fun notifyControllerStateChanged(state: MusicPlaybackState, force: Boolean = false) {
        discardStalePendingPlay()
        val snapshot = visibleState(state).toAutoPlayerStateSnapshot()
        val previous = lastNotifiedSnapshot
        if (!force && snapshot == previous) return
        lastNotifiedSnapshot = snapshot

        val timelineChanged = timelineChanged(previous, snapshot)
        val currentItemChanged = currentItemChanged(previous, snapshot)
        val metadataChanged = force || previous == null || snapshot.currentItem != previous.currentItem

        val mediaItem = currentMediaItem
        val metadata = mediaMetadata
        val timeline = currentTimeline
        val commands = availableCommands
        Timber.d(
            "PTV Music Auto publishing shared controller state " +
                "sessionPlayerId=$instanceId controllerId=${playbackController.instanceId} " +
                "track=${snapshot.currentItemId ?: "<none>"} index=${snapshot.currentIndex} " +
                "queue=${snapshot.queueSize} playing=${snapshot.isPlaying} buffering=${snapshot.isBuffering} " +
                "repeat=${snapshot.repeatMode} shuffle=${snapshot.shuffleEnabled} favorite=${snapshot.isFavorite}",
        )
        Timber.i(
            "PTV Music lockscreen metadata update " +
                "title=${metadata.title ?: "<none>"} artist=${metadata.artist ?: "<none>"} " +
                "album=${metadata.albumTitle ?: "<none>"} artwork=${metadata.artworkUri != null} " +
                "force=$force",
        )
        sessionListeners.forEach { listener ->
            if (timelineChanged) {
                listener.onTimelineChanged(timeline, Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED)
            }
            if (currentItemChanged) {
                listener.onMediaItemTransition(mediaItem, Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED)
            }
            if (metadataChanged) {
                listener.onMediaMetadataChanged(metadata)
            }
            listener.onAvailableCommandsChanged(commands)
            listener.onRepeatModeChanged(repeatMode)
            listener.onShuffleModeEnabledChanged(shuffleModeEnabled)
            listener.onPlaybackStateChanged(playbackState)
            listener.onIsPlayingChanged(isPlaying)
        }
    }

    private fun timelineChanged(
        previous: PtvMusicAutoPlayerStateSnapshot?,
        current: PtvMusicAutoPlayerStateSnapshot,
    ): Boolean = previous == null ||
        current.queue != previous.queue ||
        current.currentIndex != previous.currentIndex ||
        current.durationMs != previous.durationMs

    private fun currentItemChanged(
        previous: PtvMusicAutoPlayerStateSnapshot?,
        current: PtvMusicAutoPlayerStateSnapshot,
    ): Boolean = previous == null ||
        current.currentItemId != previous.currentItemId ||
        current.currentIndex != previous.currentIndex

    private fun discardStalePendingPlay() {
        val intent = pendingPlay ?: return
        val state = playbackController.state.value
        val currentPendingProof = pendingQueueAuthenticationProof()
        val queueIsCurrent = intent.matches(state) &&
            playbackController.isCurrentQueueOwnedByAuthenticatedUser()
        val proofIsCurrent = queueIsCurrent &&
            (currentPendingProof?.let { it === intent.requestedProof } ?: isAuthenticatedQueueOwner())
        if (!proofIsCurrent) {
            pendingPlay = null
        }
    }

    private data class PendingPlayIntent(
        val requestedProof: PtvMusicQueueAuthenticationProof,
        val currentItemId: UUID,
        val currentIndex: Int,
        val queueSize: Int,
    ) {
        fun matches(state: MusicPlaybackState): Boolean = state.queueOwner == requestedProof.owner &&
            state.queue.size == queueSize &&
            state.currentIndex == currentIndex &&
            state.currentItem?.id == currentItemId &&
            state.queue.getOrNull(currentIndex)?.id == currentItemId
    }

    private fun canSeekInCurrentMediaItem(): Boolean = visibleState().hasCurrent &&
        liveDurationMs() > 0 &&
        playbackController.mediaSessionPlayer.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)

    private fun liveDurationMs(): Long = playbackController.mediaSessionPlayer.duration.sanitizeDurationMs()

    private fun liveCurrentPositionMs(): Long? =
        playbackController.mediaSessionPlayer.currentPosition.sanitizePositionMs()

    private fun liveBufferedPositionMs(): Long? =
        playbackController.mediaSessionPlayer.bufferedPosition.sanitizePositionMs()

    private fun MusicPlaybackState.toAutoPlayerStateSnapshot(): PtvMusicAutoPlayerStateSnapshot =
        PtvMusicAutoPlayerStateSnapshot(
            currentItemId = currentItem?.id?.toString(),
            currentItem = currentItem?.toAutoMediaItemSnapshot(),
            currentIndex = currentIndex,
            queue = queue.map(MusicItem::toAutoMediaItemSnapshot),
            isPlaying = isPlaying,
            isBuffering = isBuffering,
            isEnded = isEnded,
            durationMs = durationMs,
            repeatMode = repeatMode,
            shuffleEnabled = shuffleEnabled,
            isFavorite = currentItem?.isFavorite == true,
        )

    private fun Int.toMusicRepeatMode(): MusicRepeatMode = when (this) {
        Player.REPEAT_MODE_ONE -> MusicRepeatMode.ONE
        Player.REPEAT_MODE_ALL -> MusicRepeatMode.ALL
        else -> MusicRepeatMode.NONE
    }

    private fun MusicRepeatMode.toPlayerRepeatMode(): Int = when (this) {
        MusicRepeatMode.NONE -> Player.REPEAT_MODE_OFF
        MusicRepeatMode.ALL -> Player.REPEAT_MODE_ALL
        MusicRepeatMode.ONE -> Player.REPEAT_MODE_ONE
    }
}

/** Relays ExoPlayer events while the shared controller remains the source for Auto queue events. */
@Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
private class PtvMusicUnderlyingListener(
    private val delegate: Player.Listener,
    private val ownerIsCurrent: () -> Boolean,
) : Player.Listener {
    private val visibleDelegate: Player.Listener
        get() = if (ownerIsCurrent()) delegate else HIDDEN_LISTENER

    // The controller emits these from its full queue state. Relaying ExoPlayer's single-item
    // versions would duplicate transitions and replace the Auto queue with a partial timeline.
    override fun onTimelineChanged(timeline: Timeline, reason: Int) = Unit

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) = Unit

    override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) = Unit

    // Keep every other callback, including the single aggregate onEvents callback, intact.
    override fun onEvents(player: Player, events: Player.Events) = visibleDelegate.onEvents(player, events)

    override fun onTracksChanged(tracks: Tracks) = visibleDelegate.onTracksChanged(tracks)

    override fun onPlaylistMetadataChanged(mediaMetadata: MediaMetadata) =
        visibleDelegate.onPlaylistMetadataChanged(mediaMetadata)

    override fun onIsLoadingChanged(isLoading: Boolean) = visibleDelegate.onIsLoadingChanged(isLoading)

    override fun onLoadingChanged(isLoading: Boolean) = visibleDelegate.onLoadingChanged(isLoading)

    override fun onAvailableCommandsChanged(availableCommands: Player.Commands) =
        visibleDelegate.onAvailableCommandsChanged(availableCommands)

    override fun onTrackSelectionParametersChanged(parameters: TrackSelectionParameters) =
        visibleDelegate.onTrackSelectionParametersChanged(parameters)

    override fun onPlayerStateChanged(playWhenReady: Boolean, playbackState: Int) =
        visibleDelegate.onPlayerStateChanged(playWhenReady, playbackState)

    override fun onPlaybackStateChanged(playbackState: Int) = visibleDelegate.onPlaybackStateChanged(playbackState)

    override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) =
        visibleDelegate.onPlayWhenReadyChanged(playWhenReady, reason)

    override fun onPlaybackSuppressionReasonChanged(playbackSuppressionReason: Int) =
        visibleDelegate.onPlaybackSuppressionReasonChanged(playbackSuppressionReason)

    override fun onIsPlayingChanged(isPlaying: Boolean) = visibleDelegate.onIsPlayingChanged(isPlaying)

    override fun onRepeatModeChanged(repeatMode: Int) = visibleDelegate.onRepeatModeChanged(repeatMode)

    override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) =
        visibleDelegate.onShuffleModeEnabledChanged(shuffleModeEnabled)

    override fun onPlayerError(error: PlaybackException) = visibleDelegate.onPlayerError(error)

    override fun onPlayerErrorChanged(error: PlaybackException?) = visibleDelegate.onPlayerErrorChanged(error)

    override fun onPositionDiscontinuity(reason: Int) = visibleDelegate.onPositionDiscontinuity(reason)

    override fun onPositionDiscontinuity(
        oldPosition: Player.PositionInfo,
        newPosition: Player.PositionInfo,
        reason: Int,
    ) {
        visibleDelegate.onPositionDiscontinuity(oldPosition, newPosition, reason)
    }

    override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) =
        visibleDelegate.onPlaybackParametersChanged(playbackParameters)

    override fun onSeekBackIncrementChanged(seekBackIncrementMs: Long) =
        visibleDelegate.onSeekBackIncrementChanged(seekBackIncrementMs)

    override fun onSeekForwardIncrementChanged(seekForwardIncrementMs: Long) =
        visibleDelegate.onSeekForwardIncrementChanged(seekForwardIncrementMs)

    override fun onMaxSeekToPreviousPositionChanged(maxSeekToPreviousPositionMs: Long) =
        visibleDelegate.onMaxSeekToPreviousPositionChanged(maxSeekToPreviousPositionMs)

    override fun onAudioSessionIdChanged(audioSessionId: Int) = visibleDelegate.onAudioSessionIdChanged(audioSessionId)

    override fun onAudioAttributesChanged(audioAttributes: AudioAttributes) =
        visibleDelegate.onAudioAttributesChanged(audioAttributes)

    override fun onVolumeChanged(volume: Float) = visibleDelegate.onVolumeChanged(volume)

    override fun onSkipSilenceEnabledChanged(skipSilenceEnabled: Boolean) =
        visibleDelegate.onSkipSilenceEnabledChanged(skipSilenceEnabled)

    override fun onDeviceInfoChanged(deviceInfo: DeviceInfo) = visibleDelegate.onDeviceInfoChanged(deviceInfo)

    override fun onDeviceVolumeChanged(volume: Int, muted: Boolean) =
        visibleDelegate.onDeviceVolumeChanged(volume, muted)

    override fun onVideoSizeChanged(videoSize: VideoSize) = visibleDelegate.onVideoSizeChanged(videoSize)

    override fun onSurfaceSizeChanged(width: Int, height: Int) = visibleDelegate.onSurfaceSizeChanged(width, height)

    override fun onRenderedFirstFrame() = visibleDelegate.onRenderedFirstFrame()

    override fun onCues(cues: List<Cue>) = visibleDelegate.onCues(cues)

    override fun onCues(cueGroup: CueGroup) = visibleDelegate.onCues(cueGroup)

    override fun onMetadata(metadata: Metadata) = visibleDelegate.onMetadata(metadata)

    private companion object {
        val HIDDEN_LISTENER = object : Player.Listener {}
    }
}

private data class PtvMusicAutoPlayerStateSnapshot(
    val currentItemId: String?,
    val currentItem: PtvMusicAutoMediaItemSnapshot?,
    val currentIndex: Int,
    val queue: List<PtvMusicAutoMediaItemSnapshot>,
    val isPlaying: Boolean,
    val isBuffering: Boolean,
    val isEnded: Boolean,
    val durationMs: Long,
    val repeatMode: MusicRepeatMode,
    val shuffleEnabled: Boolean,
    val isFavorite: Boolean,
) {
    val queueSize: Int get() = queue.size
}

internal data class PtvMusicAutoMediaItemSnapshot(
    val id: String,
    val title: String,
    val subtitle: String?,
    val artist: String?,
    val album: String?,
    val posterUrl: String?,
)

internal fun MusicItem.toAutoMediaItemSnapshot(): PtvMusicAutoMediaItemSnapshot = PtvMusicAutoMediaItemSnapshot(
    id = id.toString(),
    title = title,
    subtitle = subtitle,
    artist = artist,
    album = album,
    posterUrl = posterUrl,
)

@UnstableApi
internal fun ptvMusicAutoPlayerCommands(canSeek: Boolean = true): Player.Commands = Player.Commands.Builder()
    .addAll(*ptvMusicAutoPlayerCommandIds(canSeek))
    .build()

internal fun ptvMusicAutoPlayerCommandIds(canSeek: Boolean = true): IntArray {
    val baseCommands = intArrayOf(
        Player.COMMAND_PLAY_PAUSE,
        Player.COMMAND_PREPARE,
        Player.COMMAND_STOP,
        Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
        Player.COMMAND_SEEK_TO_PREVIOUS,
        Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
        Player.COMMAND_SEEK_TO_NEXT,
        Player.COMMAND_SET_REPEAT_MODE,
        Player.COMMAND_SET_SHUFFLE_MODE,
        Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
        Player.COMMAND_GET_TIMELINE,
        Player.COMMAND_GET_METADATA,
        Player.COMMAND_SET_MEDIA_ITEM,
        Player.COMMAND_GET_AUDIO_ATTRIBUTES,
        Player.COMMAND_GET_VOLUME,
        Player.COMMAND_SET_VOLUME,
        Player.COMMAND_GET_TRACKS,
        Player.COMMAND_SET_TRACK_SELECTION_PARAMETERS,
    )

    return if (canSeek) {
        baseCommands + intArrayOf(
            Player.COMMAND_SEEK_TO_DEFAULT_POSITION,
            Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
            Player.COMMAND_SEEK_TO_MEDIA_ITEM,
            Player.COMMAND_SEEK_BACK,
            Player.COMMAND_SEEK_FORWARD,
        )
    } else {
        baseCommands
    }
}

internal fun Long.sanitizeAutoDurationMs(): Long = when {
    this == C.TIME_UNSET || this <= 0 -> 0
    else -> this
}

internal fun Long.sanitizeAutoPositionMs(): Long? = when {
    this == C.TIME_UNSET || this < 0 -> null
    else -> this
}

private fun Long.sanitizeDurationMs(): Long = sanitizeAutoDurationMs()

private fun Long.sanitizePositionMs(): Long? = sanitizeAutoPositionMs()

private fun runtimeInstanceId(value: Any): String = System.identityHashCode(value).toString(16)

private fun org.jellyfin.mobile.feature.music.MusicPlaybackState.safeCurrentIndex(): Int {
    val index = currentIndex.takeIf { value -> value in queue.indices }
    return index ?: currentItem?.let { item -> queue.indexOfFirst { queuedItem -> queuedItem.id == item.id } }
        ?.takeIf { value -> value >= 0 }
        ?: C.INDEX_UNSET
}

private fun MusicItem.toSessionMediaItem(): MediaItem = MediaItem.Builder()
    .setMediaId(PtvMusicAutoIds.track(id))
    .setMediaMetadata(
        MediaMetadata.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            .setArtist(artist)
            .setAlbumTitle(album)
            .setArtworkUri(posterUrl?.toUri())
            .setIsPlayable(true)
            .setIsBrowsable(false)
            .build(),
    )
    .build()

@UnstableApi
private class PtvMusicQueueTimeline(
    private val queue: List<MusicItem>,
    private val currentIndex: Int,
    private val currentDurationMs: Long,
) : Timeline() {
    private val windowUids = queue.mapIndexed { index, item -> "ptv-window-${item.id}-$index" }
    private val periodUids = queue.mapIndexed { index, item -> "ptv-period-${item.id}-$index" }

    override fun getWindowCount(): Int = queue.size

    override fun getWindow(windowIndex: Int, window: Window, defaultPositionProjectionUs: Long): Window {
        val durationUs = durationUsFor(windowIndex)
        return window.set(
            windowUids[windowIndex],
            queue[windowIndex].toSessionMediaItem(),
            null,
            C.TIME_UNSET,
            C.TIME_UNSET,
            C.TIME_UNSET,
            durationUs != C.TIME_UNSET,
            false,
            null,
            0,
            durationUs,
            windowIndex,
            windowIndex,
            0,
        )
    }

    override fun getPeriodCount(): Int = queue.size

    override fun getPeriod(periodIndex: Int, period: Period, setIds: Boolean): Period {
        val uid = periodUids[periodIndex]
        return period.set(
            if (setIds) uid else null,
            uid,
            periodIndex,
            durationUsFor(periodIndex),
            0,
        )
    }

    override fun getIndexOfPeriod(uid: Any): Int = periodUids.indexOf(uid)

    override fun getUidOfPeriod(periodIndex: Int): Any = periodUids[periodIndex]

    private fun durationUsFor(index: Int): Long = when {
        index == currentIndex && currentDurationMs > 0 -> Util.msToUs(currentDurationMs)
        else -> C.TIME_UNSET
    }
}
