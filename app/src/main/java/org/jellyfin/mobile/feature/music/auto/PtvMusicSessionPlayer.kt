package org.jellyfin.mobile.feature.music.auto

import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.core.net.toUri
import org.jellyfin.mobile.feature.music.MusicItem
import org.jellyfin.mobile.feature.music.MusicPlaybackController
import org.jellyfin.mobile.feature.music.MusicPlaybackState
import org.jellyfin.mobile.feature.music.MusicRepeatMode
import timber.log.Timber
import java.util.concurrent.CopyOnWriteArraySet

@UnstableApi
internal class PtvMusicSessionPlayer(private val playbackController: MusicPlaybackController) :
    ForwardingPlayer(playbackController.mediaSessionPlayer) {
    val instanceId: String = runtimeInstanceId(this)
    private val sessionListeners = CopyOnWriteArraySet<Player.Listener>()
    private var lastNotifiedSnapshot: PtvMusicAutoPlayerStateSnapshot? = null

    init {
        Timber.i(
            "PTV Music Auto session player created sessionPlayerId=$instanceId " +
                "controllerId=${playbackController.instanceId} " +
                "playerId=${playbackController.playerInstanceId}",
        )
    }

    override fun addListener(listener: Player.Listener) {
        sessionListeners += listener
        super.addListener(listener)
    }

    override fun removeListener(listener: Player.Listener) {
        sessionListeners -= listener
        super.removeListener(listener)
    }

    override fun getAvailableCommands(): Player.Commands = ptvMusicAutoPlayerCommands(
        canSeek = canSeekInCurrentMediaItem(),
    )

    override fun isCommandAvailable(command: Int): Boolean = command in getAvailableCommands()

    override fun getCurrentMediaItemIndex(): Int = playbackController.state.value.safeCurrentIndex()

    @Suppress("DEPRECATION")
    @Deprecated("Deprecated in Media3")
    override fun getCurrentWindowIndex(): Int = currentMediaItemIndex

    override fun getNextMediaItemIndex(): Int {
        val state = playbackController.state.value
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
        val state = playbackController.state.value
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

    override fun getCurrentMediaItem(): MediaItem? = playbackController.state.value.currentItem?.toSessionMediaItem()
        ?: super.getCurrentMediaItem()

    override fun getMediaItemCount(): Int = playbackController.state.value.queue.size
        .takeIf { count -> count > 0 }
        ?: super.getMediaItemCount()

    override fun getMediaItemAt(index: Int): MediaItem = playbackController.state.value.queue
        .getOrNull(index)
        ?.toSessionMediaItem()
        ?: super.getMediaItemAt(index)

    override fun getCurrentTimeline(): Timeline {
        val state = playbackController.state.value
        return if (state.queue.isEmpty()) {
            super.getCurrentTimeline()
        } else {
            PtvMusicQueueTimeline(
                queue = state.queue,
                currentIndex = state.safeCurrentIndex().takeIf { index -> index != C.INDEX_UNSET } ?: 0,
                currentDurationMs = liveDurationMs().takeIf { duration -> duration > 0 } ?: state.durationMs,
            )
        }
    }

    override fun getMediaMetadata(): MediaMetadata = playbackController.state.value.currentItem
        ?.toSessionMediaItem()
        ?.mediaMetadata
        ?: super.getMediaMetadata()

    override fun getDuration(): Long = liveDurationMs()
        .takeIf { duration -> duration > 0 }
        ?: playbackController.state.value.durationMs.takeIf { duration -> duration > 0 }
        ?: C.TIME_UNSET

    override fun getContentDuration(): Long = duration

    override fun getCurrentPosition(): Long = liveCurrentPositionMs()
        ?: playbackController.state.value.positionMs.coerceAtLeast(0)

    override fun getContentPosition(): Long = currentPosition

    override fun getBufferedPosition(): Long = liveBufferedPositionMs()
        ?.coerceAtLeast(currentPosition)
        ?: playbackController.state.value.bufferedPositionMs.coerceAtLeast(currentPosition)

    override fun getContentBufferedPosition(): Long = bufferedPosition

    override fun getTotalBufferedDuration(): Long = (bufferedPosition - currentPosition).coerceAtLeast(0)

    override fun play() {
        playbackController.playCurrent()
    }

    override fun pause() {
        playbackController.pause()
    }

    override fun prepare() {
        playbackController.prepareCurrent()
    }

    override fun seekTo(positionMs: Long) {
        playbackController.seekTo(positionMs)
    }

    override fun seekTo(mediaItemIndex: Int, positionMs: Long) {
        if (mediaItemIndex == C.INDEX_UNSET) {
            playbackController.seekTo(positionMs)
        } else {
            playbackController.seekToQueueItem(mediaItemIndex, positionMs)
        }
    }

    override fun seekToDefaultPosition() {
        playbackController.seekTo(0)
    }

    override fun seekToDefaultPosition(mediaItemIndex: Int) {
        playbackController.seekToQueueItem(mediaItemIndex, 0)
    }

    override fun seekBack() {
        playbackController.seekBy(-seekBackIncrement)
    }

    override fun seekForward() {
        playbackController.seekBy(seekForwardIncrement)
    }

    override fun seekToNext() {
        playbackController.next()
    }

    override fun seekToNextMediaItem() {
        playbackController.next()
    }

    override fun seekToPrevious() {
        playbackController.previous()
    }

    override fun seekToPreviousMediaItem() {
        playbackController.previous()
    }

    override fun setShuffleModeEnabled(shuffleModeEnabled: Boolean) {
        playbackController.setShuffleEnabled(shuffleModeEnabled, source = "mediaSession")
    }

    override fun getShuffleModeEnabled(): Boolean = playbackController.state.value.shuffleEnabled

    override fun setRepeatMode(repeatMode: Int) {
        playbackController.setRepeatMode(repeatMode.toMusicRepeatMode(), source = "mediaSession")
    }

    override fun getRepeatMode(): Int = playbackController.state.value.repeatMode.toPlayerRepeatMode()

    override fun setMediaItem(mediaItem: MediaItem) = Unit

    override fun setMediaItem(mediaItem: MediaItem, startPositionMs: Long) = Unit

    override fun setMediaItem(mediaItem: MediaItem, resetPosition: Boolean) = Unit

    override fun setMediaItems(mediaItems: List<MediaItem>) = Unit

    override fun setMediaItems(mediaItems: List<MediaItem>, resetPosition: Boolean) = Unit

    override fun setMediaItems(mediaItems: List<MediaItem>, startIndex: Int, startPositionMs: Long) = Unit

    override fun stop() {
        playbackController.pause()
    }

    fun notifyControllerStateChanged(state: MusicPlaybackState, force: Boolean = false) {
        val snapshot = state.toAutoPlayerStateSnapshot()
        if (!force && snapshot == lastNotifiedSnapshot) return
        lastNotifiedSnapshot = snapshot

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
            listener.onTimelineChanged(timeline, Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED)
            listener.onMediaItemTransition(mediaItem, Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED)
            listener.onMediaMetadataChanged(metadata)
            listener.onAvailableCommandsChanged(commands)
            listener.onRepeatModeChanged(repeatMode)
            listener.onShuffleModeEnabledChanged(shuffleModeEnabled)
            listener.onPlaybackStateChanged(playbackState)
            listener.onIsPlayingChanged(isPlaying)
        }
    }

    private fun canSeekInCurrentMediaItem(): Boolean =
        liveDurationMs() > 0 &&
            playbackController.mediaSessionPlayer.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)

    private fun liveDurationMs(): Long = playbackController.mediaSessionPlayer.duration.sanitizeDurationMs()

    private fun liveCurrentPositionMs(): Long? = playbackController.mediaSessionPlayer.currentPosition.sanitizePositionMs()

    private fun liveBufferedPositionMs(): Long? = playbackController.mediaSessionPlayer.bufferedPosition.sanitizePositionMs()

    private fun MusicPlaybackState.toAutoPlayerStateSnapshot(): PtvMusicAutoPlayerStateSnapshot =
        PtvMusicAutoPlayerStateSnapshot(
            currentItemId = currentItem?.id?.toString(),
            currentIndex = currentIndex,
            queueSize = queue.size,
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

private data class PtvMusicAutoPlayerStateSnapshot(
    val currentItemId: String?,
    val currentIndex: Int,
    val queueSize: Int,
    val isPlaying: Boolean,
    val isBuffering: Boolean,
    val isEnded: Boolean,
    val durationMs: Long,
    val repeatMode: MusicRepeatMode,
    val shuffleEnabled: Boolean,
    val isFavorite: Boolean,
)

@UnstableApi
internal fun ptvMusicAutoPlayerCommands(canSeek: Boolean = true): Player.Commands {
    return Player.Commands.Builder()
        .addAll(*ptvMusicAutoPlayerCommandIds(canSeek))
        .build()
}

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
        Player.COMMAND_CHANGE_MEDIA_ITEMS,
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
    private val periodUids = queue.map { item -> "ptv-period-${item.id}" }

    override fun getWindowCount(): Int = queue.size

    override fun getWindow(
        windowIndex: Int,
        window: Window,
        defaultPositionProjectionUs: Long,
    ): Window {
        val durationUs = durationUsFor(windowIndex)
        return window.set(
            queue[windowIndex].id.toString(),
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
            if (setIds) queue[periodIndex].id.toString() else null,
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
