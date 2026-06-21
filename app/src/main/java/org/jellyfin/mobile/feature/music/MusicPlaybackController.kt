@file:Suppress("LargeClass", "LongMethod", "TooManyFunctions")

package org.jellyfin.mobile.feature.music

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.net.toUri
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.MediaSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.jellyfin.mobile.feature.music.auto.PtvMusicAutoResumeStore
import org.jellyfin.mobile.feature.music.auto.PtvMusicService
import org.jellyfin.mobile.player.deviceprofile.DeviceProfileBuilder
import org.jellyfin.mobile.player.source.MediaSourceResolver
import org.jellyfin.mobile.player.source.RemoteJellyfinMediaSource
import org.jellyfin.mobile.utils.applyDefaultAudioAttributes
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.audioApi
import org.jellyfin.sdk.api.client.extensions.universalAudioApi
import org.jellyfin.sdk.model.api.MediaProtocol
import org.jellyfin.sdk.model.api.MediaStreamProtocol
import org.jellyfin.sdk.model.api.PlayMethod
import timber.log.Timber
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

class MusicPlaybackController(
    context: Context,
    private val apiClient: ApiClient,
    private val mediaSourceResolver: MediaSourceResolver,
    deviceProfileBuilder: DeviceProfileBuilder,
    private val mediaSourceFactory: MediaSource.Factory,
    private val autoResumeStore: PtvMusicAutoResumeStore,
) : Player.Listener,
    DefaultLifecycleObserver {
    val instanceId: String = runtimeInstanceId(this)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val appContext = context.applicationContext
    private val deviceProfile = deviceProfileBuilder.getDeviceProfile()
    private val audioApi = apiClient.audioApi
    private val universalAudioApi = apiClient.universalAudioApi
    private val player = ExoPlayer.Builder(appContext)
        .setWakeMode(C.WAKE_MODE_NETWORK)
        .build().apply {
            addListener(this@MusicPlaybackController)
            applyDefaultAudioAttributes(C.AUDIO_CONTENT_TYPE_MUSIC)
            setHandleAudioBecomingNoisy(true)
        }

    private val _state = MutableStateFlow(MusicPlaybackState())
    val state: StateFlow<MusicPlaybackState> get() = _state
    val mediaSessionPlayer: Player get() = player

    private var originalQueue: List<MusicItem> = emptyList()
    private var playbackQueue: List<MusicItem> = emptyList()
    private var currentIndex = -1
    private var loadVersion = 0
    private var loadJob: Job? = null
    private var progressJob: Job? = null
    private var released = false
    private var lastPersistedAtMs = 0L
    private var lastPersistedItemId: java.util.UUID? = null
    private var lastPersistedPositionMs = 0L
    private var pendingItemId: java.util.UUID? = null
    private var pendingPrepareStartedAtMs = 0L
    private var pendingReadyItemId: java.util.UUID? = null
    private var pendingReadyPlayWhenReady = false
    private var prefetchJob: Job? = null
    private var lastHandledTrackEndItemId: java.util.UUID? = null
    private var playbackServiceStartRequested = false
    private var lastPlaybackServiceStartRequestAtMs = 0L
    private val prefetchedSources = ConcurrentHashMap<java.util.UUID, RemoteJellyfinMediaSource>()
    private val failedTrackIds = mutableSetOf<java.util.UUID>()
    private val trackRetryCounts = mutableMapOf<java.util.UUID, Int>()
    val playerInstanceId: String
        get() = runtimeInstanceId(player)

    init {
        Timber.i(
            "PTV music playback controller instance created controllerId=$instanceId " +
                "playerId=$playerInstanceId apiClientId=${runtimeInstanceId(apiClient)} " +
                "host=${apiClient.safeBaseHost()}",
        )
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
    }

    fun play(item: MusicItem, queue: List<MusicItem>, startPositionMs: Long = 0, shuffle: Boolean = false) {
        if (!item.isPlayable || released) return

        ensurePlaybackServiceStarted(source = "play")
        scope.launch {
            val shuffleEnabled = shuffle || _state.value.shuffleEnabled
            failedTrackIds.clear()
            trackRetryCounts.clear()
            val playableQueue = queue
                .filter(MusicItem::isPlayable)
                .distinctBy(MusicItem::id)
                .ifEmpty { listOf(item) }
                .ensureContains(item)

            originalQueue = playableQueue
            playbackQueue = if (shuffleEnabled) playableQueue.shuffledFrom(item) else playableQueue
            currentIndex = playbackQueue.indexOfFirst { queuedItem -> queuedItem.id == item.id }.coerceAtLeast(0)

            _state.update { it.copy(shuffleEnabled = shuffleEnabled) }
            publishPendingItemState(item = item, startPositionMs = startPositionMs)
            beginLoadCurrent(startPositionMs = startPositionMs, playWhenReady = true)
        }
    }

    fun updateItem(item: MusicItem) {
        originalQueue = originalQueue.replaceItem(item)
        playbackQueue = playbackQueue.replaceItem(item)
        _state.update { state ->
            state.copy(
                queue = state.queue.replaceItem(item),
                currentItem = state.currentItem?.let { currentItem ->
                    if (currentItem.id == item.id) item else currentItem
                },
            )
        }
        persistPlaybackState(force = true)
    }

    fun replaceQueueKeepingCurrent(queue: List<MusicItem>, source: String): Boolean {
        if (released) return false
        val currentItem = state.value.currentItem ?: return false
        val playableQueue = queue
            .filter(MusicItem::isPlayable)
            .distinctBy(MusicItem::id)
            .ensureContains(currentItem)
        if (playableQueue.isEmpty()) return false

        originalQueue = playableQueue
        playbackQueue = if (state.value.shuffleEnabled) {
            playableQueue.shuffledFrom(currentItem)
        } else {
            playableQueue
        }
        currentIndex = playbackQueue.indexOfFirst { item -> item.id == currentItem.id }.coerceAtLeast(0)

        _state.update {
            it.copy(
                queue = playbackQueue,
                currentItem = playbackQueue.getOrNull(currentIndex),
                currentIndex = currentIndex,
            )
        }
        persistPlaybackState(force = true)
        prefetchNextItem()
        Timber.i(
            "PTV music queue replaced source=$source currentItemId=${currentItem.id} " +
                "queueSize=${playbackQueue.size} currentIndex=$currentIndex shuffle=${state.value.shuffleEnabled}",
        )
        return true
    }

    fun playNext(item: MusicItem): MusicQueueUpdateResult {
        if (!item.isPlayable) {
            return MusicQueueUpdateResult(playbackQueue, currentIndex, "This item is not playable.")
        }
        if (released) {
            return MusicQueueUpdateResult(playbackQueue, currentIndex, "PTV Music playback is not available.")
        }

        val result = MusicQueueActions.playNext(
            queue = playbackQueue,
            currentIndex = currentIndex,
            item = item,
        )
        if (result.shouldStartPlayback) {
            play(item = item, queue = result.queue)
        } else {
            applyQueueUpdate(result)
        }
        Timber.i(
            "PTV music command playNext source=controller itemId=${item.id} " +
                "result=success queueSize=${result.queue.size} currentIndex=${result.currentIndex}",
        )
        return result
    }

    fun addToQueue(item: MusicItem): MusicQueueUpdateResult {
        if (!item.isPlayable) {
            return MusicQueueUpdateResult(playbackQueue, currentIndex, "This item is not playable.")
        }
        if (released) {
            return MusicQueueUpdateResult(playbackQueue, currentIndex, "PTV Music playback is not available.")
        }

        val result = MusicQueueActions.addToQueue(
            queue = playbackQueue,
            currentIndex = currentIndex,
            item = item,
        )
        applyQueueUpdate(result)
        Timber.i(
            "PTV music command addToQueue source=controller itemId=${item.id} " +
                "result=success queueSize=${result.queue.size} currentIndex=${result.currentIndex}",
        )
        return result
    }

    fun togglePlayPause() {
        scope.launch {
            if (!state.value.hasCurrent || released) return@launch
            if (player.isPlaying) {
                player.pause()
            } else {
                playPreparedOrLoadCurrent()
            }
            publishPlayerState()
        }
    }

    private fun applyQueueUpdate(result: MusicQueueUpdateResult) {
        val updatedQueue = result.queue.filter(MusicItem::isPlayable)
        if (updatedQueue.isEmpty()) return

        val currentItem = playbackQueue.getOrNull(currentIndex) ?: state.value.currentItem
        originalQueue = updatedQueue
        playbackQueue = updatedQueue
        currentIndex = currentItem?.let { item ->
            updatedQueue.indexOfFirst { queuedItem -> queuedItem.id == item.id }
        }?.takeIf { index -> index >= 0 }
            ?: result.currentIndex.coerceIn(0, updatedQueue.lastIndex)

        _state.update { playbackState ->
            playbackState.copy(
                queue = updatedQueue,
                currentItem = updatedQueue.getOrNull(currentIndex),
                currentIndex = currentIndex,
                errorMessage = null,
                failedItem = null,
                canRetry = false,
            )
        }
        persistPlaybackState(force = true)
        prefetchNextItem()
    }

    fun playCurrent() {
        ensurePlaybackServiceStarted(source = "playCurrent")
        scope.launch {
            if (!state.value.hasCurrent || released) return@launch
            playPreparedOrLoadCurrent()
            publishPlayerState()
        }
    }

    fun refreshPlaybackNotification(source: String) {
        if (released) return
        Timber.i(
            "PTV music notification refresh requested source=$source " +
                "currentItemId=${state.value.currentItem?.id} queueSize=${state.value.queue.size} " +
                "playing=${state.value.isPlaying} buffering=${state.value.isBuffering}",
        )
        ensurePlaybackServiceStarted(source = source)
        publishPlayerState()
    }

    fun prepareCurrent() {
        scope.launch {
            if (!state.value.hasCurrent || released || hasPreparedCurrentSource()) return@launch
            val resumePositionMs = state.value.positionMs
            playbackQueue.getOrNull(currentIndex)?.let { item ->
                publishPendingItemState(item = item, startPositionMs = resumePositionMs)
                beginLoadCurrent(startPositionMs = resumePositionMs, playWhenReady = false)
            }
        }
    }

    fun pause() {
        scope.launch {
            if (released) return@launch
            player.pause()
            publishPlayerState()
        }
    }

    fun stop(source: String = COMMAND_SOURCE_CONTROLLER) {
        scope.launch {
            if (released) return@launch
            Timber.i(
                "PTV music command stop source=$source currentItemId=${state.value.currentItem?.id} " +
                    "queueSize=${state.value.queue.size}",
            )
            loadJob?.cancel()
            prefetchJob?.cancel()
            progressJob?.cancel()
            prefetchedSources.clear()
            failedTrackIds.clear()
            trackRetryCounts.clear()
            originalQueue = emptyList()
            playbackQueue = emptyList()
            currentIndex = -1
            pendingItemId = null
            pendingReadyItemId = null
            lastHandledTrackEndItemId = null
            player.stop()
            player.clearMediaItems()
            _state.update { currentState ->
                MusicPlaybackState(isAppInForeground = currentState.isAppInForeground)
            }
            persistPlaybackState(force = true)
        }
    }

    fun previous() {
        scope.launch {
            if (!state.value.hasCurrent || released) return@launch
            val commandStartedAt = System.currentTimeMillis()
            Timber.i(
                "PTV music command previous received source=controller currentIndex=$currentIndex " +
                    "position=${player.currentPosition}ms queueSize=${playbackQueue.size}",
            )
            if (player.currentPosition > RESTART_TRACK_THRESHOLD_MS) {
                player.seekTo(0)
                Timber.i(
                    "PTV music command previous restarted current itemId=${state.value.currentItem?.id} " +
                        "in ${System.currentTimeMillis() - commandStartedAt}ms",
                )
                publishPlayerState()
                return@launch
            }

            val previousIndex = MusicQueueNavigation.previousIndex(
                currentIndex = currentIndex,
                queueSize = playbackQueue.size,
                repeatMode = state.value.repeatMode,
            )
            when {
                previousIndex != null -> startAt(previousIndex)

                else -> player.seekTo(0)
            }
        }
    }

    fun next() {
        scope.launch {
            if (!state.value.hasCurrent || released) return@launch
            Timber.i(
                "PTV music command next received source=controller currentIndex=$currentIndex " +
                    "queueSize=${playbackQueue.size}",
            )
            advanceToNext(fromTrackEnd = false)
        }
    }

    fun seekTo(positionMs: Long) {
        scope.launch {
            seekToInternal(positionMs)
        }
    }

    fun seekBy(deltaMs: Long) {
        scope.launch {
            seekToInternal(state.value.positionMs + deltaMs)
        }
    }

    fun seekToFraction(fraction: Float) {
        val durationMs = state.value.durationMs
        if (durationMs <= 0) return
        seekTo((durationMs * fraction.coerceIn(0f, 1f)).toLong())
    }

    fun seekToQueueItem(index: Int, positionMs: Long) {
        scope.launch {
            if (released || playbackQueue.isEmpty()) return@launch
            val safeIndex = index.coerceIn(0, playbackQueue.lastIndex)
            val safePositionMs = positionMs.takeIf { it != C.TIME_UNSET }?.coerceAtLeast(0) ?: 0
            Timber.i(
                "PTV music queue seek requested index=$safeIndex position=${safePositionMs}ms " +
                    "currentIndex=$currentIndex queueSize=${playbackQueue.size}",
            )

            if (safeIndex == currentIndex) {
                seekToInternal(safePositionMs)
            } else {
                startAt(index = safeIndex, startPositionMs = safePositionMs)
            }
        }
    }

    fun toggleShuffle(source: String = COMMAND_SOURCE_CONTROLLER) {
        if (released) return
        setShuffleEnabledInternal(enabled = !state.value.shuffleEnabled, source = source)
    }

    fun cycleRepeatMode(source: String = COMMAND_SOURCE_CONTROLLER) {
        val oldMode = state.value.repeatMode
        setRepeatMode(
            when (oldMode) {
                MusicRepeatMode.NONE -> MusicRepeatMode.ALL
                MusicRepeatMode.ALL -> MusicRepeatMode.ONE
                MusicRepeatMode.ONE -> MusicRepeatMode.NONE
            },
            source = source,
        )
    }

    fun setShuffleEnabled(enabled: Boolean, source: String = COMMAND_SOURCE_CONTROLLER) {
        if (released) return
        setShuffleEnabledInternal(enabled, source = source)
    }

    fun setRepeatMode(repeatMode: MusicRepeatMode, source: String = COMMAND_SOURCE_CONTROLLER) {
        val oldMode = state.value.repeatMode
        Timber.i(
            "PTV music command repeat source=$source result=success " +
                "old=$oldMode updated=$repeatMode currentItemId=${state.value.currentItem?.id}",
        )
        _state.update { it.copy(repeatMode = repeatMode) }
        persistPlaybackState(force = true)
        prefetchNextItem()
    }

    fun clearError() {
        _state.update { it.copy(errorMessage = null) }
    }

    fun retryFailed() {
        val failedItem = state.value.failedItem ?: state.value.currentItem ?: return
        failedTrackIds.remove(failedItem.id)
        trackRetryCounts.remove(failedItem.id)
        play(
            item = failedItem,
            queue = originalQueue.ifEmpty { playbackQueue.ifEmpty { listOf(failedItem) } },
            startPositionMs = 0,
            shuffle = false,
        )
    }

    fun skipFailed() {
        scope.launch {
            _state.update { it.copy(errorMessage = null, failedItem = null, canRetry = false) }
            advanceToNext(fromTrackEnd = false, skipFailed = true)
        }
    }

    fun release() {
        if (released) return
        Timber.i("PTV music playback controller releasing shared player")
        persistPlaybackState(force = true)
        released = true
        ProcessLifecycleOwner.get().lifecycle.removeObserver(this)
        loadJob?.cancel()
        prefetchJob?.cancel()
        progressJob?.cancel()
        prefetchedSources.clear()
        player.removeListener(this)
        player.release()
        _state.value = MusicPlaybackState(isReleased = true)
    }

    fun restoreQueue(
        queue: List<MusicItem>,
        currentIndex: Int,
        positionMs: Long,
        shuffleEnabled: Boolean,
        repeatMode: MusicRepeatMode,
    ): Boolean {
        if (released) return false
        val playableQueue = queue
            .filter(MusicItem::isPlayable)
            .distinctBy(MusicItem::id)
        if (playableQueue.isEmpty()) return false

        val restoredIndex = currentIndex.coerceIn(0, playableQueue.lastIndex)
        originalQueue = playableQueue
        playbackQueue = playableQueue
        this.currentIndex = restoredIndex
        failedTrackIds.clear()
        trackRetryCounts.clear()
        player.stop()
        player.clearMediaItems()
        _state.update {
            it.copy(
                queue = playableQueue,
                currentItem = playableQueue[restoredIndex],
                currentIndex = restoredIndex,
                isPlaying = false,
                isBuffering = false,
                isEnded = false,
                positionMs = positionMs.coerceAtLeast(0),
                bufferedPositionMs = 0,
                shuffleEnabled = shuffleEnabled,
                repeatMode = repeatMode,
                canSeek = false,
                codecCapability = null,
                errorMessage = null,
                failedItem = null,
                canRetry = false,
            )
        }
        persistPlaybackState(force = true)
        return true
    }

    override fun onStart(owner: LifecycleOwner) {
        Timber.i(
            "PTV music app moved to foreground currentItemId=${state.value.currentItem?.id} " +
                "queueSize=${state.value.queue.size} playing=${state.value.isPlaying}",
        )
        _state.update { it.copy(isAppInForeground = true) }
        publishPlayerState()
    }

    override fun onStop(owner: LifecycleOwner) {
        Timber.i(
            "PTV music app moved to background; keeping shared player alive " +
                "currentItemId=${state.value.currentItem?.id} queueSize=${state.value.queue.size} " +
                "playing=${state.value.isPlaying} buffering=${state.value.isBuffering}",
        )
        _state.update { it.copy(isAppInForeground = false) }
        publishPlayerState()
    }

    override fun onDestroy(owner: LifecycleOwner) {
        Timber.i(
            "PTV music process lifecycle destroy observed; shared player remains service-owned " +
                "currentItemId=${state.value.currentItem?.id} queueSize=${state.value.queue.size}",
        )
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
        Timber.d(
            "PTV music playback state changed: ${playbackState.toPlaybackStateName()} " +
                "isPlaying=${player.isPlaying} playWhenReady=${player.playWhenReady} " +
                "suppression=${player.playbackSuppressionReason}",
        )
        when (playbackState) {
            Player.STATE_READY -> {
                logReadyTiming()
                publishPlayerState()
            }

            Player.STATE_ENDED -> handleTrackEnded(trigger = "playerStateEnded")
            else -> publishPlayerState()
        }
        ensureProgressTicker()
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        Timber.d("PTV music isPlaying changed: $isPlaying playWhenReady=${player.playWhenReady}")
        publishPlayerState()
        ensureProgressTicker()
    }

    override fun onEvents(player: Player, events: Player.Events) {
        Timber.d(
            "PTV music player events changed events=$events " +
                "state=${player.playbackState.toPlaybackStateName()} isPlaying=${player.isPlaying} " +
                "playWhenReady=${player.playWhenReady} duration=${player.duration} " +
                "position=${player.currentPosition} buffered=${player.bufferedPosition}",
        )
        publishPlayerState()
        ensureProgressTicker()
    }

    override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
        Timber.d("PTV music playWhenReady changed: $playWhenReady reason=$reason")
        publishPlayerState()
        ensureProgressTicker()
    }

    override fun onPlaybackSuppressionReasonChanged(playbackSuppressionReason: Int) {
        Timber.d("PTV music playback suppression changed: $playbackSuppressionReason")
        publishPlayerState()
        ensureProgressTicker()
    }

    override fun onPositionDiscontinuity(
        oldPosition: Player.PositionInfo,
        newPosition: Player.PositionInfo,
        reason: Int,
    ) {
        Timber.d(
            "PTV music position discontinuity reason=$reason " +
                "oldIndex=${oldPosition.mediaItemIndex} oldPosition=${oldPosition.positionMs} " +
                "newIndex=${newPosition.mediaItemIndex} newPosition=${newPosition.positionMs}",
        )
        syncCurrentIndexFromPlayer()
        publishPlayerState()
        ensureProgressTicker()
    }

    override fun onPlayerError(error: PlaybackException) {
        val failedItem = playbackQueue.getOrNull(currentIndex)
        Timber.e(
            error,
            "PTV music playback failed code=${error.errorCodeName} codeValue=${error.errorCode} " +
                "queueIndex=$currentIndex queueSize=${playbackQueue.size} itemId=${failedItem?.id} " +
                "mediaItemId=${player.currentMediaItem?.mediaId} hasMediaItem=${player.currentMediaItem != null} " +
                "baseHost=${apiClient.safeBaseHost()}",
        )
        handleTrackFailure(
            item = failedItem,
            message = error.message?.let { "PTV could not play this track: $it" }
                ?: "PTV could not play this track. Skipping to the next song.",
        )
    }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        Timber.d(
            "PTV music media item transition mediaId=${mediaItem?.mediaId} reason=$reason " +
                "currentIndex=$currentIndex queueSize=${playbackQueue.size}",
        )
        publishPlayerState()
    }

    private fun startAt(index: Int, startPositionMs: Long = 0) {
        currentIndex = index.coerceIn(0, playbackQueue.lastIndex)
        playbackQueue.getOrNull(currentIndex)?.let { item ->
            trackRetryCounts.remove(item.id)
            resetTrackEndGuard()
            Timber.i(
                "PTV music queue index selected index=$currentIndex itemId=${item.id} " +
                    "title=${item.title} startPosition=${startPositionMs}ms",
            )
            publishPendingItemState(item = item, startPositionMs = startPositionMs)
        }
        beginLoadCurrent(startPositionMs = startPositionMs, playWhenReady = true)
    }

    private fun playPreparedOrLoadCurrent() {
        if (hasPreparedCurrentSource()) {
            player.play()
        } else {
            val resumePositionMs = state.value.positionMs
            playbackQueue.getOrNull(currentIndex)?.let { item ->
                publishPendingItemState(item = item, startPositionMs = resumePositionMs)
            } ?: publishPlayerState(errorMessage = null)
            beginLoadCurrent(startPositionMs = resumePositionMs, playWhenReady = true)
        }
    }

    private fun hasPreparedCurrentSource(): Boolean {
        val currentItemId = state.value.currentItem?.id?.toString() ?: return false
        return player.mediaItemCount > 0 &&
            player.currentMediaItem?.mediaId == currentItemId &&
            player.playbackState != Player.STATE_IDLE
    }

    private fun seekToInternal(positionMs: Long): Boolean {
        if (!state.value.hasCurrent || released) return false
        val knownDurationMs = currentDurationMs()
        val targetPositionMs = when {
            positionMs <= 0 -> 0
            knownDurationMs != null -> positionMs.coerceIn(0, knownDurationMs)
            else -> {
                Timber.w(
                    "PTV music seek ignored until duration is known; " +
                        "requested=${positionMs}ms queueIndex=$currentIndex itemId=${state.value.currentItem?.id}",
                )
                publishPlayerState()
                return false
            }
        }

        Timber.i("PTV music seek accepted target=${targetPositionMs}ms duration=${knownDurationMs ?: -1}")
        player.seekTo(targetPositionMs)
        publishPlayerState()
        return true
    }

    private fun currentDurationMs(): Long? = player.duration
        .takeIf { duration -> duration != C.TIME_UNSET && duration > 0 }
        ?: state.value.durationMs.takeIf { duration -> duration > 0 }

    private fun handleTrackEnded(trigger: String) {
        val endedItemId = playbackQueue.getOrNull(currentIndex)?.id ?: state.value.currentItem?.id
        if (endedItemId != null && lastHandledTrackEndItemId == endedItemId) {
            Timber.d("PTV music ignored duplicate track-end trigger=$trigger itemId=$endedItemId")
            return
        }
        lastHandledTrackEndItemId = endedItemId

        val failedIndexes = failedQueueIndexes()
        val decision = MusicQueueNavigation.trackEndDecision(
            currentIndex = currentIndex,
            queueSize = playbackQueue.size,
            repeatMode = state.value.repeatMode,
            failedIndexes = failedIndexes,
        )
        Timber.i(
            "PTV music track-end decision trigger=$trigger currentIndex=$currentIndex " +
                "queueSize=${playbackQueue.size} repeat=${state.value.repeatMode} " +
                "failedIndexes=$failedIndexes decision=$decision",
        )
        when (decision) {
            MusicTrackEndDecision.RestartCurrent -> {
                Timber.i("PTV music track ended repeat=one restarting itemId=${state.value.currentItem?.id}")
                player.seekTo(0)
                player.play()
                publishPlayerState()
            }

            is MusicTrackEndDecision.AdvanceTo -> startAt(decision.index)
            MusicTrackEndDecision.Stop -> {
                Timber.i("PTV music queue ended repeat=off stopping cleanly")
                player.stop()
                publishPlayerState()
            }
        }
    }

    private fun advanceToNext(fromTrackEnd: Boolean, skipFailed: Boolean = true) {
        val commandStartedAt = System.currentTimeMillis()
        val nextIndex = findNextIndex(
            allowWrap = state.value.repeatMode == MusicRepeatMode.ALL,
            skipFailed = skipFailed,
        )
        when {
            nextIndex != null -> {
                Timber.i(
                    "PTV music queue advance selected index=$nextIndex fromTrackEnd=$fromTrackEnd " +
                        "skipFailed=$skipFailed in ${System.currentTimeMillis() - commandStartedAt}ms",
                )
                startAt(nextIndex)
            }

            fromTrackEnd -> {
                Timber.i("PTV music queue ended repeat=off stopping cleanly")
                player.stop()
                publishPlayerState()
            }

            else -> Unit
        }
    }

    private fun findNextIndex(allowWrap: Boolean, skipFailed: Boolean): Int? {
        val repeatMode = if (allowWrap) MusicRepeatMode.ALL else MusicRepeatMode.NONE
        val failedIndexes = if (skipFailed) failedQueueIndexes() else emptySet()
        return MusicQueueNavigation.nextIndex(
            currentIndex = currentIndex,
            queueSize = playbackQueue.size,
            repeatMode = repeatMode,
            failedIndexes = failedIndexes,
        )
    }

    private fun failedQueueIndexes(): Set<Int> =
        playbackQueue.mapIndexedNotNull { index, item ->
            index.takeIf { item.id in failedTrackIds }
        }.toSet()

    private fun ensurePlaybackServiceStarted(source: String) {
        if (released) return

        val alreadyRequested = playbackServiceStartRequested
        val playbackActive = state.value.hasCurrent || player.isPlaying || player.playWhenReady
        val now = System.currentTimeMillis()
        if (
            alreadyRequested &&
            source == "publishPlayerState" &&
            (!playbackActive || now - lastPlaybackServiceStartRequestAtMs < SERVICE_RESTART_REFRESH_MS)
        ) {
            return
        }
        val notificationsGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                appContext,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
        val intent = Intent(PtvMusicService.ACTION_KEEP_ALIVE)
            .setClass(appContext, PtvMusicService::class.java)
            .putExtra(PtvMusicService.EXTRA_START_SOURCE, source)

        runCatching {
            appContext.startService(intent)
        }.onSuccess {
            playbackServiceStartRequested = true
            lastPlaybackServiceStartRequestAtMs = now
            Timber.i(
                "PTV music media service start requested source=$source " +
                    "foreground=false media3WillPromoteWhenNotificationPosts=true " +
                    "alreadyRequested=$alreadyRequested " +
                    "notificationPermissionGranted=$notificationsGranted " +
                    "currentItemId=${state.value.currentItem?.id} queueSize=${state.value.queue.size}",
            )
            if (!notificationsGranted) {
                Timber.w(
                    "PTV music notification permission is missing; playback service can continue but " +
                        "lockscreen notification controls may be hidden by Android.",
                )
            }
        }.onFailure { error ->
            Timber.e(
                error,
                "PTV music media service start failed source=$source " +
                    "notificationPermissionGranted=$notificationsGranted",
            )
        }
    }

    private fun beginLoadCurrent(startPositionMs: Long, playWhenReady: Boolean) {
        loadJob?.cancel()
        loadJob = scope.launch {
            loadCurrent(startPositionMs = startPositionMs, playWhenReady = playWhenReady)
        }
    }

    private suspend fun loadCurrent(startPositionMs: Long, playWhenReady: Boolean) {
        val item = playbackQueue.getOrNull(currentIndex) ?: return
        val version = ++loadVersion
        val restorePositionMs = startPositionMs.coerceAtLeast(0)
        val startedAt = System.currentTimeMillis()

        publishPendingItemState(item = item, startPositionMs = restorePositionMs)
        Timber.i(
            "PTV music preparing stream for ${item.title} (${item.id}); " +
                "baseUrl=${apiClient.safeBaseUrl()} authenticated=${apiClient.hasAccessToken()}",
        )

        resolveMediaSource(item).onSuccess { jellyfinMediaSource ->
            if (version != loadVersion) return@onSuccess
            val resolvedInMs = System.currentTimeMillis() - startedAt
            Timber.i(
                "PTV music resolved stream for ${item.title} (${item.id}) in ${resolvedInMs}ms; " +
                    "playMethod=${jellyfinMediaSource.playMethod} " +
                    "protocol=${jellyfinMediaSource.sourceInfo.protocol} " +
                    "container=${jellyfinMediaSource.sourceInfo.container}",
            )
            runCatching {
                val mediaSource = createAudioMediaSource(jellyfinMediaSource, item)
                Timber.d("PTV music setting ExoPlayer media source for ${item.title} (${item.id})")
                pendingPrepareStartedAtMs = System.currentTimeMillis()
                pendingReadyItemId = item.id
                pendingReadyPlayWhenReady = playWhenReady
                player.setMediaSource(mediaSource)
                player.prepare()
                Timber.i(
                    "PTV music ExoPlayer prepare called for ${item.title} (${item.id}) " +
                        "in ${System.currentTimeMillis() - pendingPrepareStartedAtMs}ms",
                )
                if (restorePositionMs > 0) player.seekTo(restorePositionMs)
                player.playWhenReady = playWhenReady
                pendingItemId = null
                failedTrackIds.remove(item.id)
                trackRetryCounts.remove(item.id)
                _state.update {
                    it.copy(
                        currentItem = item,
                        currentIndex = currentIndex,
                        queue = playbackQueue,
                        isBuffering = player.playbackState == Player.STATE_BUFFERING && !player.isPlaying,
                        isPlaying = player.isPlaying,
                        isEnded = player.playbackState == Player.STATE_ENDED,
                        codecCapability = jellyfinMediaSource.toCodecCapability(),
                        errorMessage = null,
                        failedItem = null,
                        canRetry = false,
                    )
                }
                Timber.i(
                    "PTV music stream prepared for ${item.title} (${item.id}); " +
                        "playWhenReady=$playWhenReady playMethod=${jellyfinMediaSource.playMethod}",
                )
                publishPlayerState(errorMessage = null)
                ensureProgressTicker()
                prefetchNextItem()
            }.onFailure { error ->
                Timber.e(error, "Failed to load PTV music source")
                handleTrackFailure(
                    item = item,
                    message = error.message?.let { "PTV could not start ${item.title}: $it" }
                        ?: "PTV could not start ${item.title}. Skipping ahead.",
                )
            }
        }.onFailure { error ->
            if (version != loadVersion) return@onFailure
            Timber.e(
                error,
                "Failed to resolve PTV music source for ${item.title} (${item.id}); " +
                    "baseUrl=${apiClient.safeBaseUrl()} authenticated=${apiClient.hasAccessToken()}",
            )
            handleTrackFailure(
                item = item,
                message = error.message?.let { "PTV could not prepare ${item.title}: $it" }
                    ?: "PTV could not prepare ${item.title}. Skipping ahead.",
            )
        }
    }

    private fun createAudioMediaSource(source: RemoteJellyfinMediaSource, item: MusicItem): MediaSource {
        val audioUrl = source.toAudioPlaybackUrl()
        val audioUri = audioUrl.toUri()
        Timber.i(
            "PTV music created ${source.playMethod} audio URL for ${item.title} (${item.id}); " +
                "scheme=${audioUri.scheme} host=${audioUri.host} hasQuery=${!audioUri.query.isNullOrBlank()}",
        )
        val mediaItem = MediaItem.Builder()
            .setMediaId(source.itemId.toString())
            .setUri(audioUrl)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(item.title)
                    .setArtist(item.artist)
                    .setAlbumTitle(item.album)
                    .setArtworkUri(item.posterUrl?.toUri())
                    .build(),
            )
            .build()

        return mediaSourceFactory.createMediaSource(mediaItem)
    }

    private fun RemoteJellyfinMediaSource.toAudioPlaybackUrl(): String = when (playMethod) {
        PlayMethod.DIRECT_PLAY -> when (sourceInfo.protocol) {
            MediaProtocol.FILE -> audioApi.getAudioStreamUrl(
                itemId = itemId,
                container = sourceInfo.container.toPreferredAudioContainer(),
                static = true,
                playSessionId = playSessionId,
                mediaSourceId = id,
                deviceId = apiClient.deviceInfo.id,
            )

            MediaProtocol.HTTP -> sourceInfo.path ?: toUniversalTranscodedAudioUrl()

            else -> toUniversalTranscodedAudioUrl()
        }

        PlayMethod.DIRECT_STREAM -> audioApi.getAudioStreamByContainerUrl(
            itemId = itemId,
            container = sourceInfo.container.toPreferredAudioContainer()
                ?: selectedAudioStream?.codec.toPreferredAudioContainer()
                ?: DEFAULT_DIRECT_STREAM_CONTAINER,
            playSessionId = playSessionId,
            mediaSourceId = id,
            deviceId = apiClient.deviceInfo.id,
            enableAutoStreamCopy = true,
            allowAudioStreamCopy = true,
        )

        PlayMethod.TRANSCODE -> {
            sourceInfo.transcodingUrl
                ?.let(apiClient::createUrl)
                ?: toUniversalTranscodedAudioUrl()
        }
    }

    private fun RemoteJellyfinMediaSource.toUniversalTranscodedAudioUrl(): String =
        universalAudioApi.getUniversalAudioStreamUrl(
            itemId = itemId,
            deviceId = apiClient.deviceInfo.id,
            maxStreamingBitrate = maxStreamingBitrate ?: DEFAULT_AUDIO_STREAMING_BITRATE,
            container = AUDIO_CONTAINERS,
            transcodingProtocol = MediaStreamProtocol.HLS,
            transcodingContainer = "ts",
            audioCodec = "aac",
            enableRemoteMedia = true,
        )

    private fun setShuffleEnabledInternal(enabled: Boolean, source: String) {
        val oldEnabled = state.value.shuffleEnabled
        val oldIndex = currentIndex
        val currentItem = state.value.currentItem
        playbackQueue = when {
            currentItem == null -> playbackQueue
            enabled -> originalQueue.shuffledFrom(currentItem)
            else -> originalQueue
        }
        currentIndex = currentItem?.let { item ->
            playbackQueue.indexOfFirst { queuedItem -> queuedItem.id == item.id }
        } ?: currentIndex
        _state.update {
            it.copy(
                queue = playbackQueue,
                currentIndex = currentIndex,
                shuffleEnabled = enabled,
            )
        }
        Timber.i(
            "PTV music command shuffle source=$source result=success currentItemId=${currentItem?.id} " +
                "old=$oldEnabled updated=$enabled queueSize=${playbackQueue.size} " +
                "currentIndexBefore=$oldIndex currentIndexAfter=$currentIndex",
        )
        persistPlaybackState(force = true)
        prefetchNextItem()
    }

    private fun handleTrackFailure(item: MusicItem?, message: String) {
        val failedItem = item ?: playbackQueue.getOrNull(currentIndex)
        val retryAttempt = failedItem?.let { nextRetryAttempt(it) }

        if (failedItem != null && retryAttempt != null) {
            val retryMessage = "PTV lost the stream for ${failedItem.title}. Retrying (${retryAttempt + 1}/$MAX_TRACK_RETRY_ATTEMPTS)..."
            Timber.w("PTV music retrying failed track ${failedItem.id}; attempt=${retryAttempt + 1}")
            autoResumeStore.markLastPlaybackError(retryMessage)
            player.pause()
            _state.update {
                it.copy(
                    currentItem = failedItem,
                    currentIndex = currentIndex,
                    queue = playbackQueue,
                    isBuffering = true,
                    isPlaying = false,
                    isEnded = false,
                    errorMessage = retryMessage,
                    failedItem = failedItem,
                    canRetry = false,
                )
            }
            persistPlaybackState()
            scheduleTrackRetry(failedItem, retryAttempt)
            return
        }

        failedItem?.let { failedTrackIds += it.id }
        failedItem?.let { trackRetryCounts.remove(it.id) }
        player.pause()
        autoResumeStore.markLastPlaybackError(message)
        publishPlayerState(errorMessage = message)
        _state.update {
            it.copy(
                failedItem = failedItem,
                canRetry = failedItem != null,
                isBuffering = false,
                isPlaying = false,
                errorMessage = message,
            )
        }
        persistPlaybackState()

        if (failedItem != null) {
            scope.launch {
                delay(ERROR_AUTO_SKIP_DELAY_MS)
                val stillShowingFailure = state.value.failedItem?.id == failedItem.id &&
                    state.value.currentItem?.id == failedItem.id
                if (stillShowingFailure) advanceToNext(fromTrackEnd = true, skipFailed = true)
            }
        }
    }

    private fun publishPendingItemState(item: MusicItem, startPositionMs: Long) {
        pendingItemId = item.id
        _state.update {
            it.copy(
                queue = playbackQueue,
                currentItem = item,
                currentIndex = currentIndex,
                isPlaying = false,
                isBuffering = true,
                isEnded = false,
                durationMs = 0,
                positionMs = startPositionMs.coerceAtLeast(0),
                bufferedPositionMs = 0,
                canSeek = false,
                codecCapability = null,
                errorMessage = null,
                failedItem = null,
                canRetry = false,
            )
        }
        persistPlaybackState()
    }

    private fun nextRetryAttempt(item: MusicItem): Int? {
        val currentAttempt = trackRetryCounts[item.id] ?: 0
        if (currentAttempt >= MAX_TRACK_RETRY_ATTEMPTS) return null
        trackRetryCounts[item.id] = currentAttempt + 1
        return currentAttempt
    }

    private fun scheduleTrackRetry(item: MusicItem, retryAttempt: Int) {
        val retryDelayMs = TRACK_RETRY_BASE_DELAY_MS * (retryAttempt + 1)
        scope.launch {
            delay(retryDelayMs)
            val stillCurrent = !released &&
                playbackQueue.getOrNull(currentIndex)?.id == item.id &&
                state.value.failedItem?.id == item.id
            if (!stillCurrent) return@launch

            beginLoadCurrent(startPositionMs = state.value.positionMs, playWhenReady = true)
        }
    }

    private fun publishPlayerState(errorMessage: String? = state.value.errorMessage) {
        if (released) return
        syncCurrentIndexFromPlayer()
        val playbackState = player.playbackState
        val playerIsPlaying = player.isPlaying
        val durationMs = player.duration.sanitizeDurationMs()
        val rawPositionMs = player.currentPosition.sanitizePositionMs(fallbackMs = state.value.positionMs)
        val positionMs = rawPositionMs.coerceAtMost(durationMs.takeIf { duration -> duration > 0 } ?: rawPositionMs)
        val bufferedPositionMs = player.bufferedPosition
            .sanitizePositionMs(fallbackMs = state.value.bufferedPositionMs)
            .coerceAtLeast(positionMs)
        val bufferActive = playbackState == Player.STATE_BUFFERING && !playerIsPlaying
        val ended = playbackState == Player.STATE_ENDED
        if (playerIsPlaying || bufferActive || player.playWhenReady) {
            ensurePlaybackServiceStarted(source = "publishPlayerState")
        }
        _state.update {
            it.copy(
                queue = playbackQueue,
                currentItem = playbackQueue.getOrNull(currentIndex),
                currentIndex = currentIndex,
                isPlaying = playerIsPlaying,
                isBuffering = bufferActive,
                isEnded = ended,
                durationMs = durationMs,
                positionMs = positionMs,
                bufferedPositionMs = bufferedPositionMs,
                canSeek = durationMs > 0 && player.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM),
                errorMessage = errorMessage,
                isReleased = released,
            )
        }
        persistPlaybackState()
        resetTrackEndGuardIfPlaybackMovedAway(currentItemId = playbackQueue.getOrNull(currentIndex)?.id)
        maybeHandlePlaybackEndFallback(
            playbackState = playbackState,
            positionMs = positionMs,
            durationMs = durationMs,
            isBuffering = bufferActive,
        )
    }

    private fun ensureProgressTicker() {
        if (!shouldRunProgressTicker() || progressJob?.isActive == true) return

        progressJob = scope.launch {
            while (shouldRunProgressTicker()) {
                publishPlayerState()
                delay(PROGRESS_TICK_MS)
            }
            publishPlayerState()
        }
    }

    private fun shouldRunProgressTicker(): Boolean =
        !released &&
            state.value.hasCurrent &&
            (state.value.isPlaying || state.value.isBuffering || player.playbackState == Player.STATE_BUFFERING)

    private fun syncCurrentIndexFromPlayer() {
        val mediaId = player.currentMediaItem?.mediaId ?: return
        val pendingId = pendingItemId
        if (pendingId != null && mediaId != pendingId.toString()) {
            Timber.d(
                "PTV music ignored stale player mediaId=$mediaId while pending itemId=$pendingId " +
                    "currentIndex=$currentIndex",
            )
            return
        }
        val playerIndex = playbackQueue.indexOfFirst { item -> item.id.toString() == mediaId }
        if (playerIndex >= 0 && playerIndex != currentIndex) {
            Timber.i("PTV music synced queue index from player mediaId=$mediaId old=$currentIndex new=$playerIndex")
            currentIndex = playerIndex
        }
    }

    private suspend fun resolveMediaSource(item: MusicItem): Result<RemoteJellyfinMediaSource> {
        prefetchedSources.remove(item.id)?.let { source ->
            Timber.i("PTV music stream prefetch hit itemId=${item.id}")
            return Result.success(source)
        }

        return mediaSourceResolver.resolveMediaSource(
            itemId = item.id,
            mediaSourceId = null,
            deviceProfile = deviceProfile,
            maxStreamingBitrate = null,
            startTime = null,
            audioStreamIndex = null,
            subtitleStreamIndex = null,
        )
    }

    private fun prefetchNextItem() {
        if (released || playbackQueue.isEmpty()) return
        val nextIndex = findNextIndex(
            allowWrap = state.value.repeatMode == MusicRepeatMode.ALL,
            skipFailed = true,
        ) ?: return
        val item = playbackQueue.getOrNull(nextIndex) ?: return
        if (prefetchedSources.containsKey(item.id)) return

        prefetchJob?.cancel()
        prefetchJob = scope.launch {
            val startedAt = System.currentTimeMillis()
            resolveMediaSource(item).onSuccess { source ->
                prefetchedSources[item.id] = source
                trimPrefetchedSources()
                Timber.i(
                    "PTV music stream prefetched itemId=${item.id} index=$nextIndex " +
                        "in ${System.currentTimeMillis() - startedAt}ms",
                )
            }.onFailure { error ->
                Timber.w(error, "PTV music stream prefetch failed itemId=${item.id} index=$nextIndex")
            }
        }
    }

    private fun trimPrefetchedSources() {
        val keepIds = playbackQueue
            .drop(currentIndex.coerceAtLeast(0))
            .take(PREFETCH_CACHE_SIZE + 1)
            .mapTo(mutableSetOf(), MusicItem::id)
        prefetchedSources.keys.removeIf { itemId -> itemId !in keepIds }
    }

    private fun logReadyTiming() {
        val readyItemId = pendingReadyItemId ?: return
        val prepareStartedAt = pendingPrepareStartedAtMs.takeIf { it > 0 } ?: return
        Timber.i(
            "PTV music playback ready itemId=$readyItemId playWhenReady=$pendingReadyPlayWhenReady " +
                "after ${System.currentTimeMillis() - prepareStartedAt}ms",
        )
        pendingReadyItemId = null
        pendingPrepareStartedAtMs = 0L
    }

    private fun maybeHandlePlaybackEndFallback(
        playbackState: Int,
        positionMs: Long,
        durationMs: Long,
        isBuffering: Boolean,
    ) {
        if (released ||
            isBuffering ||
            playbackState == Player.STATE_ENDED ||
            playbackQueue.isEmpty() ||
            pendingItemId != null
        ) {
            return
        }

        if (!MusicQueueNavigation.hasReachedPlaybackEnd(
                positionMs = positionMs,
                durationMs = durationMs,
                toleranceMs = TRACK_END_FALLBACK_TOLERANCE_MS,
            )
        ) {
            return
        }

        Timber.w(
            "PTV music detected playback end by position fallback " +
                "position=${positionMs}ms duration=${durationMs}ms state=${playbackState.toPlaybackStateName()} " +
                "currentIndex=$currentIndex queueSize=${playbackQueue.size}",
        )
        handleTrackEnded(trigger = "positionFallback")
    }

    private fun resetTrackEndGuardIfPlaybackMovedAway(currentItemId: java.util.UUID?) {
        if (currentItemId == null || lastHandledTrackEndItemId != currentItemId) return
        val durationMs = state.value.durationMs
        val positionMs = state.value.positionMs
        if (durationMs <= 0 || positionMs < durationMs - TRACK_END_GUARD_RESET_DISTANCE_MS) {
            resetTrackEndGuard()
        }
    }

    private fun resetTrackEndGuard() {
        lastHandledTrackEndItemId = null
    }

    private fun Long.sanitizeDurationMs(): Long = when {
        this == C.TIME_UNSET || this <= 0 -> 0
        else -> this
    }

    private fun Long.sanitizePositionMs(fallbackMs: Long = 0): Long = when {
        this == C.TIME_UNSET || this < 0 -> fallbackMs.coerceAtLeast(0)
        else -> this
    }

    private fun persistPlaybackState(force: Boolean = false) {
        val playbackState = state.value
        val currentItemId = playbackState.currentItem?.id
        if (currentItemId == null || playbackState.queue.isEmpty()) return

        val nowMs = System.currentTimeMillis()
        val itemChanged = currentItemId != lastPersistedItemId
        val positionChanged = abs(playbackState.positionMs - lastPersistedPositionMs) >= PERSIST_POSITION_DELTA_MS
        val intervalElapsed = nowMs - lastPersistedAtMs >= PERSIST_MIN_INTERVAL_MS
        val shouldPersist = force || itemChanged || positionChanged || intervalElapsed

        if (!shouldPersist) return

        autoResumeStore.savePlaybackState(playbackState = playbackState, nowMs = nowMs)
        lastPersistedAtMs = nowMs
        lastPersistedItemId = currentItemId
        lastPersistedPositionMs = playbackState.positionMs
    }

    private fun RemoteJellyfinMediaSource.toCodecCapability(): MusicCodecCapability {
        val audioStream = selectedAudioStream ?: audioStreams.firstOrNull()
        val codec = audioStream?.codec?.cleanFormatName()
        val container = sourceInfo.container?.cleanFormatName()
        val bitrate = audioStream?.bitRate?.let { bitRate -> "${bitRate / 1000} kbps" }
        val label = listOfNotNull(codec, container)
            .distinct()
            .joinToString(" / ")
            .ifBlank { "Audio stream" }
        val detail = listOfNotNull(bitrate, audioStream?.sampleRate?.let { sampleRate -> "${sampleRate / 1000} kHz" })
            .joinToString(" - ")
        val status = codec.toCodecStatus(playMethod)
        val pathMessage = when (status) {
            MusicCodecStatus.NATIVE -> "native playback expected"
            MusicCodecStatus.DIRECT_STREAM -> "direct stream expected"
            MusicCodecStatus.TRANSCODE -> "server transcode to AAC/HLS"
            MusicCodecStatus.LIMITED -> "limited support; server transcode may be required"
            MusicCodecStatus.UNKNOWN -> "unknown codec; PTV will try the Jellyfin stream"
            MusicCodecStatus.UNSUPPORTED -> "unsupported by native playback"
        }

        return when (playMethod) {
            PlayMethod.DIRECT_PLAY -> MusicCodecCapability(
                label = label,
                message = listOf(label, detail, pathMessage).filter(String::isNotBlank).joinToString(" - "),
                isDirectPath = true,
                status = status,
                container = container,
                codec = codec,
                nativePlaybackExpected = status == MusicCodecStatus.NATIVE,
            )

            PlayMethod.DIRECT_STREAM -> MusicCodecCapability(
                label = label,
                message = listOf(label, detail, pathMessage).filter(String::isNotBlank).joinToString(" - "),
                isDirectPath = true,
                status = status,
                container = container,
                codec = codec,
                nativePlaybackExpected = status == MusicCodecStatus.DIRECT_STREAM,
            )

            PlayMethod.TRANSCODE -> MusicCodecCapability(
                label = label,
                message = listOf(label, detail, pathMessage).filter(String::isNotBlank).joinToString(" - "),
                isDirectPath = false,
                status = status,
                container = container,
                codec = codec,
                nativePlaybackExpected = false,
            )
        }
    }

    private fun List<MusicItem>.ensureContains(item: MusicItem): List<MusicItem> = when {
        any { queuedItem -> queuedItem.id == item.id } -> this
        else -> listOf(item) + this
    }

    private fun List<MusicItem>.replaceItem(item: MusicItem): List<MusicItem> = map { queuedItem ->
        if (queuedItem.id == item.id) item else queuedItem
    }

    private fun List<MusicItem>.shuffledFrom(item: MusicItem): List<MusicItem> {
        val remaining = filterNot { queuedItem -> queuedItem.id == item.id }.shuffled()
        return listOf(item) + remaining
    }

    private fun String.cleanFormatName() = trim()
        .takeIf(String::isNotBlank)
        ?.uppercase(Locale.US)

    private fun String?.toPreferredAudioContainer(): String? = this
        ?.split(',', '|')
        ?.asSequence()
        ?.map { value -> value.trim().lowercase(Locale.US).substringBefore('|') }
        ?.mapNotNull { value ->
            when (value) {
                "mpeg", "mpga" -> "mp3"
                "aac", "m4a", "m4b", "alac", "flac", "mp3", "ogg", "opus", "wav", "webm", "webma" -> value
                else -> value.takeIf { it in directAudioContainers }
            }
        }
        ?.firstOrNull()

    private fun String?.toCodecStatus(playMethod: PlayMethod): MusicCodecStatus {
        val codec = this?.lowercase(Locale.US).orEmpty()

        return when {
            codec.isBlank() -> MusicCodecStatus.UNKNOWN

            codec in limitedCodecs -> MusicCodecStatus.LIMITED

            codec !in expectedNativeCodecs && playMethod != PlayMethod.TRANSCODE -> MusicCodecStatus.UNKNOWN

            else -> when (playMethod) {
                PlayMethod.DIRECT_PLAY -> MusicCodecStatus.NATIVE
                PlayMethod.DIRECT_STREAM -> MusicCodecStatus.DIRECT_STREAM
                PlayMethod.TRANSCODE -> MusicCodecStatus.TRANSCODE
            }
        }
    }

    private fun Int.toPlaybackStateName(): String = when (this) {
        Player.STATE_IDLE -> "IDLE"
        Player.STATE_BUFFERING -> "BUFFERING"
        Player.STATE_READY -> "READY"
        Player.STATE_ENDED -> "ENDED"
        else -> "UNKNOWN($this)"
    }

    private fun ApiClient.safeBaseUrl(): String = baseUrl?.substringBefore("?") ?: "<none>"

    private fun ApiClient.safeBaseHost(): String = baseUrl
        ?.let { url -> runCatching { url.toUri().host }.getOrNull() }
        ?: "<none>"

    private fun ApiClient.hasAccessToken(): Boolean = !accessToken.isNullOrBlank()

    private fun runtimeInstanceId(value: Any): String = System.identityHashCode(value).toString(16)

    private companion object {
        const val DEFAULT_AUDIO_STREAMING_BITRATE = 140_000_000
        const val DEFAULT_DIRECT_STREAM_CONTAINER = "mp3"
        const val ERROR_AUTO_SKIP_DELAY_MS = 1_400L
        const val MAX_TRACK_RETRY_ATTEMPTS = 1
        const val TRACK_RETRY_BASE_DELAY_MS = 1_000L
        const val PROGRESS_TICK_MS = 500L
        const val PERSIST_MIN_INTERVAL_MS = 5_000L
        const val PERSIST_POSITION_DELTA_MS = 5_000L
        const val PREFETCH_CACHE_SIZE = 3
        const val RESTART_TRACK_THRESHOLD_MS = 5_000L
        const val SERVICE_RESTART_REFRESH_MS = 15_000L
        const val COMMAND_SOURCE_CONTROLLER = "controller"
        const val TRACK_END_FALLBACK_TOLERANCE_MS = 750L
        const val TRACK_END_GUARD_RESET_DISTANCE_MS = 2_000L
        val expectedNativeCodecs = setOf(
            "mp3",
            "flac",
            "opus",
            "aac",
            "alac",
            "vorbis",
            "pcm_s16le",
            "pcm_s24le",
            "wav",
        )
        val limitedCodecs = setOf(
            "wma",
            "wmav1",
            "wmav2",
            "wmapro",
            "wmalossless",
        )
        val directAudioContainers = setOf(
            "aac",
            "alac",
            "flac",
            "m4a",
            "m4b",
            "mp3",
            "ogg",
            "opus",
            "wav",
            "webm",
            "webma",
        )
        val AUDIO_CONTAINERS = listOf(
            "opus",
            "mp3|mp3",
            "aac",
            "m4a",
            "m4b|aac",
            "flac",
            "webma",
            "webm",
            "wav",
            "ogg",
        )
    }
}
