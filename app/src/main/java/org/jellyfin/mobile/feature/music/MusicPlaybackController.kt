@file:Suppress("LargeClass", "LongMethod", "TooManyFunctions")

package org.jellyfin.mobile.feature.music

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.MediaSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.jellyfin.mobile.feature.music.auto.PtvMusicAutoQueueOwner
import org.jellyfin.mobile.feature.music.auto.PtvMusicAutoQueueProvenance
import org.jellyfin.mobile.feature.music.auto.PtvMusicAutoResumeStore
import org.jellyfin.mobile.feature.music.auto.PtvMusicService
import org.jellyfin.mobile.feature.music.auto.PtvMusicSessionInvalidator
import org.jellyfin.mobile.player.deviceprofile.DeviceProfileBuilder
import org.jellyfin.mobile.player.source.MediaSourceResolver
import org.jellyfin.mobile.player.source.RemoteJellyfinMediaSource
import org.jellyfin.mobile.utils.applyDefaultAudioAttributes
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.audioApi
import org.jellyfin.sdk.api.client.extensions.playStateApi
import org.jellyfin.sdk.api.client.extensions.universalAudioApi
import org.jellyfin.sdk.model.api.MediaProtocol
import org.jellyfin.sdk.model.api.MediaStreamProtocol
import org.jellyfin.sdk.model.api.PlayMethod
import org.jellyfin.sdk.model.api.PlaybackOrder
import org.jellyfin.sdk.model.api.PlaybackProgressInfo
import org.jellyfin.sdk.model.api.PlaybackStartInfo
import org.jellyfin.sdk.model.api.PlaybackStopInfo
import org.jellyfin.sdk.model.api.RepeatMode
import timber.log.Timber
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs

class MusicPlaybackController(
    context: Context,
    private val apiClient: ApiClient,
    private val mediaSourceResolver: MediaSourceResolver,
    deviceProfileBuilder: DeviceProfileBuilder,
    private val mediaSourceFactory: MediaSource.Factory,
    private val autoResumeStore: PtvMusicAutoResumeStore,
    private val musicSessionInvalidator: PtvMusicSessionInvalidator,
) : Player.Listener,
    DefaultLifecycleObserver {
    val instanceId: String = runtimeInstanceId(this)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val appContext = context.applicationContext
    private val deviceProfile = deviceProfileBuilder.getDeviceProfile()
    private val audioApi = apiClient.audioApi
    private val playStateApi = apiClient.playStateApi
    private val universalAudioApi = apiClient.universalAudioApi
    private val audioManager = checkNotNull(
        ContextCompat.getSystemService(appContext, AudioManager::class.java),
    )
    private val player = ExoPlayer.Builder(appContext)
        .setWakeMode(C.WAKE_MODE_NETWORK)
        .setLoadControl(
            DefaultLoadControl.Builder()
                .setBufferDurationsMs(
                    MUSIC_MIN_BUFFER_MS,
                    MUSIC_MAX_BUFFER_MS,
                    MUSIC_PLAYBACK_BUFFER_MS,
                    MUSIC_REBUFFER_MS,
                )
                .setPrioritizeTimeOverSizeThresholds(true)
                .build(),
        )
        .build().apply {
            addListener(this@MusicPlaybackController)
            applyDefaultAudioAttributes(C.AUDIO_CONTENT_TYPE_MUSIC)
            setHandleAudioBecomingNoisy(true)
        }

    private val _state = MutableStateFlow(MusicPlaybackState())
    val state: StateFlow<MusicPlaybackState> get() = _state
    val mediaSessionPlayer: Player get() = player
    private val transitionEmitter = MusicPlaybackTransitionEmitter()
    internal val transitionEvents: SharedFlow<MusicPlaybackTransitionEvent> get() = transitionEmitter.events

    fun isCurrentQueueOwnedByAuthenticatedUser(): Boolean =
        !released && isMusicPlaybackQueueOwnedBy(state.value, autoResumeStore.currentLocalQueueOwner())

    private fun canExecuteOwnedQueueCommand(requestedOwner: PtvMusicAutoQueueOwner?): Boolean {
        val currentOwner = autoResumeStore.currentLocalQueueOwner()
        val allowed = !released && isMusicPlaybackCommandAuthorized(requestedOwner, state.value, currentOwner)
        if (!allowed && shouldDiscardUnownedMusicQueue(state.value, currentOwner)) {
            discardQueueIfOwnershipChanged()
        }
        return allowed
    }

    private fun canMutateResidentQueue(): Boolean {
        val currentOwner = autoResumeStore.currentLocalQueueOwner()
        val allowed = !released && isMusicPlaybackResidentMutationAuthorized(
            hasResidentQueue = playbackQueue.isNotEmpty(),
            playbackState = state.value,
            currentOwner = currentOwner,
        )
        if (!allowed && shouldDiscardUnownedMusicQueue(state.value, currentOwner)) {
            discardQueueIfOwnershipChanged()
        }
        return allowed
    }

    private var originalQueue: List<MusicItem> = emptyList()
    private var playbackQueue: List<MusicItem> = emptyList()
    private var playbackQueueOriginalIndices: List<Int> = emptyList()
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
    private val reportTracker = MusicPlaybackReportTracker()
    private val failedTrackIds = mutableSetOf<java.util.UUID>()
    private val recoveryState = MusicPlaybackRecoveryState(MAX_TRACK_RETRY_ATTEMPTS)
    private val commandOrder = MusicPlaybackCommandOrder()
    private var stablePlaybackJob: Job? = null
    private var stablePlaybackItemId: java.util.UUID? = null
    val playerInstanceId: String
        get() = runtimeInstanceId(player)

    init {
        Timber.i(
            "PTV music playback controller instance created controllerId=$instanceId " +
                "playerId=$playerInstanceId apiClientId=${runtimeInstanceId(apiClient)} " +
                "host=${apiClient.safeBaseHost()}",
        )
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
        musicSessionInvalidator.attach { reason -> stop(source = "session:$reason") }
    }

    fun play(
        item: MusicItem,
        queue: List<MusicItem>,
        startPositionMs: Long = 0,
        shuffle: Boolean = false,
        expectedOwner: PtvMusicAutoQueueOwner? = null,
        authoredQueueIndex: Int? = null,
        queueProvenance: PtvMusicAutoQueueProvenance? = null,
    ) {
        if (!item.isPlayable || released) return
        val requestedOwner = autoResumeStore.currentLocalQueueOwner() ?: return
        if (expectedOwner != null && expectedOwner != requestedOwner) return
        val authoredSelection = authoredQueueIndex?.let { selectedIndex ->
            selectAuthoredMusicPlaybackQueue(item, queue, selectedIndex, shuffleEnabled = false) ?: return
        }
        val command = commandOrder.issue()

        scope.launch {
            if (!commandOrder.isCurrent(command) ||
                autoResumeStore.currentLocalQueueOwner() != requestedOwner
            ) {
                return@launch
            }
            if (commandOrder.hasPendingResidentClear() || shouldDiscardUnownedMusicQueue(state.value, requestedOwner)) {
                clearResidentPlayback(dispatchStopReport = false)
            }
            val shuffleEnabled = shuffle || _state.value.shuffleEnabled
            stablePlaybackJob?.cancel()
            stablePlaybackItemId = null
            failedTrackIds.clear()
            recoveryState.resetAll()
            val selectedItem = configurePlaybackQueue(item, queue, authoredSelection, shuffleEnabled)

            _state.update { it.copy(shuffleEnabled = shuffleEnabled) }
            publishPendingItemState(
                item = selectedItem,
                startPositionMs = startPositionMs,
                queueOwner = requestedOwner,
                retainQueueProvenance = false,
            )
            if (!attachSelectedQueueProvenance(queueProvenance, requestedOwner)) return@launch
            beginLoadCurrent(startPositionMs = startPositionMs, playWhenReady = true)
        }
    }

    private fun attachSelectedQueueProvenance(
        source: PtvMusicAutoQueueProvenance?,
        owner: PtvMusicAutoQueueOwner,
    ): Boolean {
        if (source == null) return true
        val attached = autoResumeStore.attachQueueProvenance(
            source.forQueue(
                queue = state.value.queue,
                currentIndex = state.value.currentIndex,
                authoredQueue = originalQueue,
                authoredIndex = playbackQueueOriginalIndices.getOrNull(currentIndex),
            ),
            state.value,
            owner,
        )
        if (!attached) {
            clearResidentPlayback(dispatchStopReport = false)
            if (autoResumeStore.currentLocalQueueOwner() == owner) autoResumeStore.clearPlaybackState()
        }
        return attached
    }

    private fun configurePlaybackQueue(
        item: MusicItem,
        queue: List<MusicItem>,
        authoredSelection: AuthoredMusicPlaybackQueueSelection?,
        shuffleEnabled: Boolean,
    ): MusicItem = if (authoredSelection != null) {
        val selection = if (shuffleEnabled) {
            checkNotNull(
                selectAuthoredMusicPlaybackQueue(
                    item,
                    authoredSelection.originalQueue,
                    authoredSelection.selectedIndex,
                    shuffleEnabled = true,
                ),
            )
        } else {
            authoredSelection
        }
        originalQueue = selection.originalQueue
        playbackQueue = selection.playbackQueue
        playbackQueueOriginalIndices = selection.playbackOriginalIndices
        currentIndex = selection.currentIndex
        selection.selectedItem
    } else {
        val playableQueue = queue
            .filter(MusicItem::isPlayable)
            .distinctBy(MusicItem::id)
            .ifEmpty { listOf(item) }
            .ensureContains(item)
        val selectedIndex = playableQueue.indexOfFirst { queuedItem -> queuedItem.id == item.id }
        val order = checkNotNull(
            reorderMusicPlaybackQueue(
                playableQueue,
                playableQueue.indices.toList(),
                selectedIndex,
                shuffleEnabled,
            ),
        )
        originalQueue = playableQueue
        playbackQueue = order.queue
        playbackQueueOriginalIndices = order.originalIndices
        currentIndex = order.currentIndex
        item
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

    fun replaceQueueKeepingCurrent(
        queue: List<MusicItem>,
        source: String,
        preservePlaybackOrder: Boolean = false,
    ): Boolean {
        if (!canMutateResidentQueue() || playbackQueue.isEmpty()) return false
        val currentItem = state.value.currentItem ?: return false
        val playableQueue = if (preservePlaybackOrder) {
            queue
        } else {
            queue
                .filter(MusicItem::isPlayable)
                .distinctBy(MusicItem::id)
                .ensureContains(currentItem)
        }
        if (playableQueue.isEmpty()) return false
        val order = if (preservePlaybackOrder) {
            if (playbackQueue.getOrNull(currentIndex)?.id == currentItem.id) {
                selectPlaybackOrderPreservingAppend(playbackQueue, currentIndex, playableQueue)
            } else {
                null
            }
        } else {
            val selectedIndex = playableQueue.indexOfFirst { item -> item.id == currentItem.id }
            reorderMusicPlaybackQueue(
                playableQueue,
                playableQueue.indices.toList(),
                selectedIndex,
                state.value.shuffleEnabled,
            )
        } ?: return false

        originalQueue = playableQueue
        playbackQueue = order.queue
        playbackQueueOriginalIndices = order.originalIndices
        currentIndex = order.currentIndex

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
        if (!canMutateResidentQueue()) {
            return MusicQueueUpdateResult(emptyList(), -1, "Music queue is unavailable.")
        }
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
        if (!canMutateResidentQueue()) {
            return MusicQueueUpdateResult(emptyList(), -1, "Music queue is unavailable.")
        }
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
        val requestedOwner = state.value.queueOwner
        scope.launch {
            if (!canExecuteOwnedQueueCommand(requestedOwner)) return@launch
            // Transient audio-focus suppression makes isPlaying false while the user's Play
            // request remains active. A toggle in that state is an intentional Pause.
            if (player.playWhenReady) {
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
        val updatedCurrentIndex = selectMusicQueueCurrentIndexAfterUpdate(
            resultQueue = result.queue,
            previousCurrentItem = currentItem,
            suggestedIndex = result.currentIndex,
        ) ?: return
        originalQueue = updatedQueue
        playbackQueue = updatedQueue
        playbackQueueOriginalIndices = updatedQueue.indices.toList()
        currentIndex = updatedCurrentIndex

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
        val requestedOwner = state.value.queueOwner
        scope.launch {
            if (!canExecuteOwnedQueueCommand(requestedOwner)) return@launch
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
        if (state.value.isPlaying || state.value.isBuffering) {
            ensurePlaybackServiceStarted(source = source)
        }
        publishPlayerState()
    }

    fun prepareCurrent() {
        val requestedOwner = state.value.queueOwner
        scope.launch {
            if (!canExecuteOwnedQueueCommand(requestedOwner) || hasPreparedCurrentSource()) return@launch
            val resumePositionMs = state.value.positionMs
            playbackQueue.getOrNull(currentIndex)?.let { item ->
                publishPendingItemState(item = item, startPositionMs = resumePositionMs)
                beginLoadCurrent(startPositionMs = resumePositionMs, playWhenReady = false)
            }
        }
    }

    fun pause() {
        val requestedOwner = state.value.queueOwner
        scope.launch {
            if (!canExecuteOwnedQueueCommand(requestedOwner)) return@launch
            loadJob?.cancel()
            loadVersion++
            pendingItemId = null
            pendingReadyItemId = null
            player.pause()
            publishPlayerState()
        }
    }

    fun stop(source: String = COMMAND_SOURCE_CONTROLLER) {
        val activeState = state.value
        val currentOwner = autoResumeStore.currentLocalQueueOwner()
        val reportStop = isMusicPlaybackQueueOwnedBy(activeState, currentOwner)
        val clearSavedResume = shouldClearSavedMusicQueueOnStop(
            activeState,
            currentOwner,
            pendingResidentClear = commandOrder.hasPendingResidentClear(),
        )
        val command = commandOrder.issueStop()
        _state.update { currentState ->
            if (commandOrder.isCurrent(command)) emptyMusicPlaybackStateAfterStop(currentState) else currentState
        }
        if (clearSavedResume && commandOrder.isCurrent(command) &&
            autoResumeStore.currentLocalQueueOwner() == currentOwner
        ) {
            autoResumeStore.clearPlaybackState()
        }
        lastPersistedAtMs = 0L
        lastPersistedItemId = null
        lastPersistedPositionMs = 0L
        scope.launch {
            if (released || !commandOrder.isCurrent(command)) return@launch
            Timber.i(
                "PTV music command stop source=$source currentItemId=${state.value.currentItem?.id} " +
                    "queueSize=${state.value.queue.size}",
            )
            clearResidentPlayback(
                dispatchStopReport = reportStop && autoResumeStore.currentLocalQueueOwner() == currentOwner,
            )
        }
    }

    /** Drops a queue from another local account without deleting the current account's saved resume. */
    fun discardQueueIfOwnershipChanged(): Boolean {
        if (released) return false
        val isForeign = shouldDiscardUnownedMusicQueue(state.value, autoResumeStore.currentLocalQueueOwner())
        if (!isForeign && !commandOrder.hasPendingForeignDiscard()) {
            return false
        }
        val command = commandOrder.markForeignDiscardPending()
        _state.update(::emptyMusicPlaybackStateAfterStop)
        scope.launch {
            if (!released && commandOrder.isCurrent(command) && commandOrder.hasPendingForeignDiscard()) {
                clearResidentPlayback(dispatchStopReport = false)
            }
        }
        return true
    }

    private fun clearResidentPlayback(dispatchStopReport: Boolean) {
        commandOrder.completeResidentClear()
        loadJob?.cancel()
        loadVersion++
        prefetchJob?.cancel()
        progressJob?.cancel()
        stablePlaybackJob?.cancel()
        stablePlaybackItemId = null
        val stopReports = reportTracker.finish(player.currentPosition.toPlaybackTicks())
        if (dispatchStopReport) dispatchPlaybackReports(stopReports)
        prefetchedSources.clear()
        failedTrackIds.clear()
        recoveryState.resetAll()
        originalQueue = emptyList()
        playbackQueue = emptyList()
        playbackQueueOriginalIndices = emptyList()
        currentIndex = -1
        pendingItemId = null
        pendingReadyItemId = null
        lastHandledTrackEndItemId = null
        lastPersistedAtMs = 0L
        lastPersistedItemId = null
        lastPersistedPositionMs = 0L
        _state.update(::emptyMusicPlaybackStateAfterStop)
        player.stop()
        player.clearMediaItems()
    }

    fun previous() {
        val requestedOwner = state.value.queueOwner
        scope.launch {
            if (!canExecuteOwnedQueueCommand(requestedOwner)) return@launch
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
        val requestedOwner = state.value.queueOwner
        scope.launch {
            if (!canExecuteOwnedQueueCommand(requestedOwner)) return@launch
            Timber.i(
                "PTV music command next received source=controller currentIndex=$currentIndex " +
                    "queueSize=${playbackQueue.size}",
            )
            advanceToNext(fromTrackEnd = false, transitionReason = MusicPlaybackTransitionReason.EXPLICIT_SKIP)
        }
    }

    fun seekTo(positionMs: Long) {
        val requestedOwner = state.value.queueOwner
        scope.launch {
            if (!canExecuteOwnedQueueCommand(requestedOwner)) return@launch
            seekToInternal(positionMs)
        }
    }

    fun seekBy(deltaMs: Long) {
        val requestedOwner = state.value.queueOwner
        scope.launch {
            if (!canExecuteOwnedQueueCommand(requestedOwner)) return@launch
            seekToInternal(state.value.positionMs + deltaMs)
        }
    }

    fun seekToFraction(fraction: Float) {
        val durationMs = state.value.durationMs
        if (durationMs <= 0) return
        seekTo((durationMs * fraction.coerceIn(0f, 1f)).toLong())
    }

    fun seekToQueueItem(index: Int, positionMs: Long) {
        val requestedOwner = state.value.queueOwner
        scope.launch {
            if (!canExecuteOwnedQueueCommand(requestedOwner) || playbackQueue.isEmpty()) return@launch
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
        if (!canExecuteOwnedQueueCommand(state.value.queueOwner)) return
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
        if (!canExecuteOwnedQueueCommand(state.value.queueOwner)) return
        setShuffleEnabledInternal(enabled, source = source)
    }

    fun setRepeatMode(repeatMode: MusicRepeatMode, source: String = COMMAND_SOURCE_CONTROLLER) {
        if (!canExecuteOwnedQueueCommand(state.value.queueOwner)) return
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

    fun suspendForPlaybackServiceFailure() {
        if (released) return
        loadJob?.cancel()
        loadVersion++
        pendingItemId = null
        pendingReadyItemId = null
        player.playWhenReady = false
        player.pause()
        playbackServiceStartRequested = false
        val message = "PTV could not start protected background playback. Please try again."
        autoResumeStore.markLastPlaybackError(message)
        _state.update {
            it.copy(
                isPlaying = false,
                isBuffering = false,
                errorMessage = message,
                canRetry = it.hasCurrent,
            )
        }
        persistPlaybackState(force = true)
    }

    fun retryFailed() {
        val requestedOwner = state.value.queueOwner
        if (!canExecuteOwnedQueueCommand(requestedOwner)) return
        val failedItem = state.value.failedItem ?: state.value.currentItem ?: return
        val authoredQueueIndex = retryAuthoredMusicQueueIndex(
            originalQueue,
            playbackQueueOriginalIndices,
            currentIndex,
            failedItem,
        )
        if (originalQueue.map(MusicItem::id).distinct().size != originalQueue.size && authoredQueueIndex == null) return
        failedTrackIds.remove(failedItem.id)
        recoveryState.reset(failedItem.id)
        play(
            item = failedItem,
            queue = originalQueue.ifEmpty { playbackQueue.ifEmpty { listOf(failedItem) } },
            startPositionMs = state.value.positionMs,
            shuffle = false,
            expectedOwner = requestedOwner,
            authoredQueueIndex = authoredQueueIndex,
        )
    }

    fun skipFailed() {
        val requestedOwner = state.value.queueOwner
        scope.launch {
            if (!canExecuteOwnedQueueCommand(requestedOwner)) return@launch
            _state.update { it.copy(errorMessage = null, failedItem = null, canRetry = false) }
            advanceToNext(
                fromTrackEnd = false,
                skipFailed = true,
                transitionReason = MusicPlaybackTransitionReason.EXPLICIT_SKIP,
            )
        }
    }

    fun release() {
        if (released) return
        Timber.i("PTV music playback controller releasing shared player")
        persistPlaybackState(force = true)
        val finalReports = reportTracker.finish(player.currentPosition.toPlaybackTicks())
        if (!shouldDiscardUnownedMusicQueue(state.value, autoResumeStore.currentLocalQueueOwner()) &&
            !commandOrder.hasPendingResidentClear()
        ) {
            dispatchPlaybackReports(finalReports)
        }
        released = true
        ProcessLifecycleOwner.get().lifecycle.removeObserver(this)
        musicSessionInvalidator.detach()
        loadJob?.cancel()
        prefetchJob?.cancel()
        progressJob?.cancel()
        stablePlaybackJob?.cancel()
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
        expectedOwner: PtvMusicAutoQueueOwner,
    ): Boolean = restoreQueueInternal(queue, currentIndex, positionMs, shuffleEnabled, repeatMode, expectedOwner, true)

    private fun restoreQueueInternal(
        queue: List<MusicItem>,
        currentIndex: Int,
        positionMs: Long,
        shuffleEnabled: Boolean,
        repeatMode: MusicRepeatMode,
        expectedOwner: PtvMusicAutoQueueOwner,
        persistRestoredState: Boolean,
    ): Boolean {
        if (released || autoResumeStore.currentLocalQueueOwner() != expectedOwner) return false
        val selection = selectRestoredMusicPlaybackQueue(queue, currentIndex) ?: return false
        val playableQueue = selection.queue

        commandOrder.issue()
        if (commandOrder.hasPendingResidentClear() || shouldDiscardUnownedMusicQueue(state.value, expectedOwner)) {
            clearResidentPlayback(dispatchStopReport = false)
        }

        val restoredIndex = selection.currentIndex
        originalQueue = playableQueue
        playbackQueue = playableQueue
        playbackQueueOriginalIndices = playableQueue.indices.toList()
        this.currentIndex = restoredIndex
        failedTrackIds.clear()
        recoveryState.resetAll()
        stablePlaybackJob?.cancel()
        stablePlaybackItemId = null
        player.pause()
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
                queueOwner = expectedOwner,
            )
        }
        if (persistRestoredState) persistPlaybackState(force = true)
        return true
    }

    /** Restores a full, newly shuffled playlist while retaining its authenticated authored order. */
    internal fun restoreShuffledPlaylistQueue(
        authoredQueue: List<MusicItem>,
        order: MusicPlaybackQueueOrder,
        positionMs: Long,
        repeatMode: MusicRepeatMode,
        expectedOwner: PtvMusicAutoQueueOwner,
        provenance: PtvMusicAutoQueueProvenance,
    ): Boolean {
        if (order.originalIndices.sorted() != authoredQueue.indices.toList() ||
            order.queue.map(MusicItem::id) != order.originalIndices.map { authoredQueue[it].id }
        ) {
            return false
        }
        if (!restoreQueueInternal(
                order.queue,
                order.currentIndex,
                positionMs,
                true,
                repeatMode,
                expectedOwner,
                false,
            )
        ) {
            return false
        }
        originalQueue = authoredQueue.toList()
        playbackQueueOriginalIndices = order.originalIndices.toList()
        val restoredState = state.value
        val persisted = autoResumeStore.savePlaylistQueueTransition(
            playbackState = restoredState,
            expectedOwner = expectedOwner,
            previousSource = provenance,
            updatedSource = provenance.forQueue(
                queue = restoredState.queue,
                currentIndex = restoredState.currentIndex,
                authoredQueue = authoredQueue,
                authoredIndex = order.originalIndices[restoredState.currentIndex],
            ),
        )
        if (!persisted) {
            clearResidentPlayback(dispatchStopReport = false)
            if (autoResumeStore.currentLocalQueueOwner() == expectedOwner) autoResumeStore.clearPlaybackState()
        }
        return persisted
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
        updateStablePlaybackReset()
        ensureProgressTicker()
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        Timber.d("PTV music isPlaying changed: $isPlaying playWhenReady=${player.playWhenReady}")
        publishPlayerState()
        updateStablePlaybackReset()
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
        syncCurrentIndexFromPlayer()
        val transitionedItem = playbackQueue.getOrNull(currentIndex)
        val transitionedSource = transitionedItem?.let { item -> prefetchedSources.remove(item.id) }
        if (transitionedSource != null) {
            dispatchPlaybackReports(
                reportTracker.transitionTo(
                    session = transitionedSource.toPlaybackReportSession(),
                    positionTicks = 0L,
                    isPaused = !player.playWhenReady,
                    nowMs = System.currentTimeMillis(),
                ),
            )
        }
        resetTrackEndGuard()
        publishPlayerState()
        prefetchNextItem()
    }

    private fun startAt(index: Int, startPositionMs: Long = 0) {
        currentIndex = index.coerceIn(0, playbackQueue.lastIndex)
        playbackQueue.getOrNull(currentIndex)?.let { item ->
            recoveryState.reset(item.id)
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
            if (!ensurePlaybackServiceStarted(source = "playPrepared")) return
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
        endedItemId?.let(recoveryState::markTrackCompleted)

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

            is MusicTrackEndDecision.AdvanceTo -> {
                val previousIndex = currentIndex
                startAt(decision.index)
                emitTransition(previousIndex, decision.index, MusicPlaybackTransitionReason.NATURAL)
            }

            MusicTrackEndDecision.Stop -> {
                Timber.i("PTV music queue ended repeat=off stopping cleanly")
                dispatchPlaybackReports(reportTracker.finish(player.currentPosition.toPlaybackTicks()))
                stopMusicPlayerAtNaturalQueueEnd(player)
                publishPlayerState()
            }
        }
    }

    private fun advanceToNext(
        fromTrackEnd: Boolean,
        skipFailed: Boolean = true,
        transitionReason: MusicPlaybackTransitionReason? = null,
    ) {
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
                val previousIndex = currentIndex
                startAt(nextIndex)
                transitionReason?.let { reason -> emitTransition(previousIndex, nextIndex, reason) }
            }

            fromTrackEnd -> {
                Timber.i("PTV music queue ended repeat=off stopping cleanly")
                dispatchPlaybackReports(reportTracker.finish(player.currentPosition.toPlaybackTicks()))
                stopMusicPlayerAtNaturalQueueEnd(player)
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

    private fun failedQueueIndexes(): Set<Int> = playbackQueue.mapIndexedNotNull { index, item ->
        index.takeIf { item.id in failedTrackIds }
    }.toSet()

    private fun ensurePlaybackServiceStarted(source: String): Boolean {
        if (released) return false
        if (PtvMusicService.isRunning) {
            playbackServiceStartRequested = true
            return true
        }

        val alreadyRequested = playbackServiceStartRequested
        val playbackActive = state.value.hasCurrent || player.isPlaying || player.playWhenReady
        val now = System.currentTimeMillis()
        if (
            alreadyRequested &&
            source == "publishPlayerState" &&
            (!playbackActive || now - lastPlaybackServiceStartRequestAtMs < SERVICE_RESTART_REFRESH_MS)
        ) {
            return true
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

        return runCatching {
            ContextCompat.startForegroundService(appContext, intent)
        }.onSuccess {
            playbackServiceStartRequested = true
            lastPlaybackServiceStartRequestAtMs = now
            Timber.i(
                "PTV music media service start requested source=$source " +
                    "foreground=true placeholderNotificationExpected=true " +
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
            suspendForPlaybackServiceFailure()
        }.isSuccess
    }

    private fun beginLoadCurrent(startPositionMs: Long, playWhenReady: Boolean) {
        if (playWhenReady && !ensurePlaybackServiceStarted(source = "loadCurrent")) return
        stablePlaybackJob?.cancel()
        stablePlaybackItemId = null
        loadJob?.cancel()
        loadJob = scope.launch {
            loadCurrent(startPositionMs = startPositionMs, playWhenReady = playWhenReady)
        }
    }

    private suspend fun loadCurrent(startPositionMs: Long, playWhenReady: Boolean) {
        val item = playbackQueue.getOrNull(currentIndex) ?: return
        val version = ++loadVersion
        val expectedOwner = state.value.queueOwner
        val restorePositionMs = startPositionMs.coerceAtLeast(0)
        val startedAt = System.currentTimeMillis()

        publishPendingItemState(item = item, startPositionMs = restorePositionMs)
        Timber.i(
            "PTV music preparing stream for ${item.title} (${item.id}); " +
                "baseUrl=${apiClient.safeBaseUrl()} authenticated=${apiClient.hasAccessToken()}",
        )

        resolveMediaSource(item).onSuccess { jellyfinMediaSource ->
            if (!isResolvedMusicSourceStillOwned(
                    expectedOwner = expectedOwner,
                    currentOwner = autoResumeStore.currentLocalQueueOwner(),
                    residentOwner = state.value.queueOwner,
                    currentLoad = !released && version == loadVersion,
                )
            ) {
                discardQueueIfOwnershipChanged()
                return@onSuccess
            }
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
                dispatchPlaybackReports(
                    reportTracker.transitionTo(
                        session = jellyfinMediaSource.toPlaybackReportSession(),
                        positionTicks = restorePositionMs.toPlaybackTicks(),
                        isPaused = !playWhenReady,
                        nowMs = System.currentTimeMillis(),
                    ),
                )
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
                recoveryState.markPrepared(item.id)
                _state.update {
                    it.copy(
                        currentItem = item,
                        currentIndex = currentIndex,
                        queue = playbackQueue,
                        isBuffering = isActiveMusicBuffering(
                            playbackState = player.playbackState,
                            playWhenReady = player.playWhenReady,
                            isPlaying = player.isPlaying,
                        ),
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
            if (!isResolvedMusicSourceStillOwned(
                    expectedOwner = expectedOwner,
                    currentOwner = autoResumeStore.currentLocalQueueOwner(),
                    residentOwner = state.value.queueOwner,
                    currentLoad = !released && version == loadVersion,
                )
            ) {
                discardQueueIfOwnershipChanged()
                return@onFailure
            }
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
        val priorState = state.value
        val oldEnabled = priorState.shuffleEnabled
        val oldIndex = currentIndex
        val currentItem = priorState.currentItem
        val savedPlaylistSource = autoResumeStore.loadPlaybackState()?.queueProvenance
            ?.takeIf { it.kind == PtvMusicAutoQueueProvenance.PLAYLIST }
        val playlistSource = savedPlaylistSource?.takeIf { saved ->
            saved.matchesQueue(priorState.queue) && saved.matchesAuthoredQueue(originalQueue)
        }
        if (currentItem != null) {
            val order = reorderMusicPlaybackQueue(
                originalQueue,
                playbackQueueOriginalIndices,
                currentIndex,
                enabled,
            ) ?: return
            playbackQueue = order.queue
            playbackQueueOriginalIndices = order.originalIndices
            currentIndex = order.currentIndex
        }
        _state.update {
            it.copy(
                queue = playbackQueue,
                currentItem = currentItem?.let { playbackQueue.getOrNull(currentIndex) },
                currentIndex = currentIndex,
                shuffleEnabled = enabled,
            )
        }
        Timber.i(
            "PTV music command shuffle source=$source result=success currentItemId=${currentItem?.id} " +
                "old=$oldEnabled updated=$enabled queueSize=${playbackQueue.size} " +
                "currentIndexBefore=$oldIndex currentIndexAfter=$currentIndex",
        )
        if (savedPlaylistSource != null) {
            val currentOwner = autoResumeStore.currentLocalQueueOwner()
            val persisted = if (playlistSource != null && currentOwner != null) {
                autoResumeStore.savePlaylistQueueTransition(
                    playbackState = state.value,
                    expectedOwner = currentOwner,
                    previousSource = playlistSource,
                    updatedSource = playlistSource.forQueue(
                        queue = state.value.queue,
                        currentIndex = state.value.currentIndex,
                        authoredQueue = originalQueue,
                        authoredIndex = playbackQueueOriginalIndices.getOrNull(currentIndex),
                    ),
                )
            } else {
                false
            }
            if (!persisted) {
                clearResidentPlayback(dispatchStopReport = false)
                if (currentOwner != null && currentOwner == priorState.queueOwner) {
                    autoResumeStore.clearPlaybackState()
                }
                return
            }
        } else {
            persistPlaybackState(force = true)
        }
        prefetchNextItem()
    }

    private fun handleTrackFailure(item: MusicItem?, message: String) {
        val failedItem = item ?: playbackQueue.getOrNull(currentIndex)
        val failedPositionMs = failedItem?.let { failed ->
            recoveryState.resumePositionMs(
                itemId = failed.id,
                playerMediaItemId = player.currentMediaItem?.mediaId,
                playerPositionMs = player.currentPosition,
                savedPositionMs = state.value.positionMs,
            )
        } ?: state.value.positionMs.coerceAtLeast(0)
        val retryAttempt = failedItem?.let { nextRetryAttempt(it) }
        stablePlaybackJob?.cancel()
        stablePlaybackJob = null
        stablePlaybackItemId = null

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
                    positionMs = failedPositionMs,
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
        player.pause()
        autoResumeStore.markLastPlaybackError(message)
        publishPlayerState(errorMessage = message)
        _state.update {
            it.copy(
                failedItem = failedItem,
                canRetry = failedItem != null,
                isBuffering = false,
                isPlaying = false,
                positionMs = failedPositionMs,
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

    private fun publishPendingItemState(
        item: MusicItem,
        startPositionMs: Long,
        queueOwner: PtvMusicAutoQueueOwner? = state.value.queueOwner,
        retainQueueProvenance: Boolean = true,
    ) {
        pendingItemId = item.id
        _state.update {
            it.copy(
                queue = playbackQueue,
                currentItem = item,
                queueOwner = queueOwner,
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
        persistPlaybackState(force = !retainQueueProvenance, retainQueueProvenance = retainQueueProvenance)
    }

    private fun nextRetryAttempt(item: MusicItem): Int? = recoveryState.nextRetryAttempt(item.id)

    private fun updateStablePlaybackReset() {
        val itemId = playbackQueue.getOrNull(currentIndex)?.id
        if (!player.isPlaying || player.playbackState != Player.STATE_READY || itemId == null) {
            stablePlaybackJob?.cancel()
            stablePlaybackJob = null
            stablePlaybackItemId = null
            return
        }
        if (stablePlaybackItemId == itemId && stablePlaybackJob?.isActive == true) return

        stablePlaybackJob?.cancel()
        stablePlaybackItemId = itemId
        stablePlaybackJob = scope.launch {
            delay(STABLE_PLAYBACK_RESET_DELAY_MS)
            if (player.isPlaying && player.playbackState == Player.STATE_READY &&
                playbackQueue.getOrNull(currentIndex)?.id == itemId
            ) {
                recoveryState.markStablePlayback(itemId)
            }
        }
    }

    private fun emitTransition(previousIndex: Int, nextIndex: Int, reason: MusicPlaybackTransitionReason) {
        transitionEmitter.emit(
            queue = playbackQueue,
            previousIndex = previousIndex,
            nextIndex = nextIndex,
            queueOwner = state.value.queueOwner,
            currentOwner = autoResumeStore.currentLocalQueueOwner(),
            reason = reason,
        )
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
        if (commandOrder.hasPendingStop()) return
        if (discardQueueIfOwnershipChanged()) return
        syncCurrentIndexFromPlayer()
        val playbackState = player.playbackState
        val playerIsPlaying = player.isPlaying
        val durationMs = player.duration.sanitizeDurationMs()
        val rawPositionMs = player.currentPosition.sanitizePositionMs(fallbackMs = state.value.positionMs)
        val positionMs = rawPositionMs.coerceAtMost(durationMs.takeIf { duration -> duration > 0 } ?: rawPositionMs)
        val bufferedPositionMs = player.bufferedPosition
            .sanitizePositionMs(fallbackMs = state.value.bufferedPositionMs)
            .coerceAtLeast(positionMs)
        val bufferActive = isActiveMusicBuffering(
            playbackState = playbackState,
            playWhenReady = player.playWhenReady,
            isPlaying = playerIsPlaying,
        )
        val ended = playbackState == Player.STATE_ENDED
        if (playerIsPlaying || bufferActive || player.playWhenReady) {
            if (!ensurePlaybackServiceStarted(source = "publishPlayerState")) return
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
        dispatchPlaybackReports(
            reportTracker.progress(
                positionTicks = positionMs.toPlaybackTicks(),
                isPaused = !player.playWhenReady,
                nowMs = System.currentTimeMillis(),
            ),
        )
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

    private fun shouldRunProgressTicker(): Boolean = !released &&
        state.value.hasCurrent &&
        (
            state.value.isPlaying ||
                state.value.isBuffering ||
                (player.playWhenReady && player.playbackState == Player.STATE_BUFFERING)
            )

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
        val playerIndex = musicQueueIndexForMediaId(playbackQueue, currentIndex, mediaId)
        if (playerIndex >= 0 && playerIndex != currentIndex) {
            Timber.i("PTV music synced queue index from player mediaId=$mediaId old=$currentIndex new=$playerIndex")
            currentIndex = playerIndex
        }
    }

    private suspend fun resolveMediaSource(item: MusicItem): Result<RemoteJellyfinMediaSource> {
        prefetchedSources.remove(item.id)?.let { source ->
            Timber.i("PTV music stream prefetch cache hit")
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
        prefetchedSources[item.id]?.let { source ->
            bufferResolvedNextItem(item = item, nextIndex = nextIndex, source = source)
            return
        }

        prefetchJob?.cancel()
        prefetchJob = scope.launch {
            val startedAt = System.currentTimeMillis()
            resolveMediaSource(item).onSuccess { source ->
                prefetchedSources[item.id] = source
                trimPrefetchedSources()
                bufferResolvedNextItem(item = item, nextIndex = nextIndex, source = source)
                Timber.i(
                    "PTV music stream prefetched index=$nextIndex " +
                        "in ${System.currentTimeMillis() - startedAt}ms",
                )
            }.onFailure { error ->
                Timber.w(error, "PTV music stream prefetch failed index=$nextIndex")
            }
        }
    }

    private fun bufferResolvedNextItem(item: MusicItem, nextIndex: Int, source: RemoteJellyfinMediaSource) {
        if (!canBufferResolvedNextItem(item, nextIndex)) return

        val playerIndex = player.currentMediaItemIndex
        val currentMediaId = player.currentMediaItem?.mediaId
        val expectedCurrentId = playbackQueue.getOrNull(currentIndex)?.id?.toString()
        if (playerIndex < 0 || currentMediaId != expectedCurrentId || player.playbackState == Player.STATE_IDLE) return

        val bufferedIndex = playerIndex + 1
        val bufferedMediaId = if (bufferedIndex < player.mediaItemCount) {
            player.getMediaItemAt(bufferedIndex).mediaId
        } else {
            null
        }
        if (bufferedMediaId == item.id.toString()) return
        if (player.mediaItemCount > bufferedIndex) {
            player.removeMediaItems(bufferedIndex, player.mediaItemCount)
        }
        player.addMediaSource(createAudioMediaSource(source, item))
        Timber.i(
            "PTV music Media3 next-source buffer queued queueIndex=$nextIndex " +
                "playerIndex=${player.currentMediaItemIndex} policy=min30s-max120s",
        )
    }

    private fun canBufferResolvedNextItem(item: MusicItem, nextIndex: Int): Boolean {
        if (released || playbackQueue.getOrNull(nextIndex)?.id != item.id) return false
        val expectedNextIndex = findNextIndex(
            allowWrap = state.value.repeatMode == MusicRepeatMode.ALL,
            skipFailed = true,
        )
        // Identical adjacent media IDs cannot identify which playlist occurrence auto-transition selected.
        return expectedNextIndex == nextIndex &&
            shouldBufferNextMusicQueueOccurrence(playbackQueue, currentIndex, nextIndex)
    }

    private fun trimPrefetchedSources() {
        val keepIds = playbackQueue
            .drop(currentIndex.coerceAtLeast(0))
            .take(PREFETCH_CACHE_SIZE + 1)
            .mapTo(mutableSetOf(), MusicItem::id)
        prefetchedSources.keys.removeIf { itemId -> itemId !in keepIds }
    }

    private fun dispatchPlaybackReports(events: List<MusicPlaybackReportEvent>) {
        events.forEach { event ->
            scope.launch(Dispatchers.IO) {
                val startedAt = System.currentTimeMillis()
                runCatching {
                    when (event) {
                        is MusicPlaybackReportEvent.Playing -> playStateApi.reportPlaybackStart(
                            PlaybackStartInfo(
                                itemId = event.session.itemId,
                                playMethod = event.session.playMethod,
                                playSessionId = event.session.playSessionId,
                                liveStreamId = event.session.liveStreamId,
                                audioStreamIndex = event.session.audioStreamIndex,
                                subtitleStreamIndex = null,
                                isPaused = event.isPaused,
                                isMuted = false,
                                canSeek = true,
                                positionTicks = event.positionTicks,
                                volumeLevel = currentVolumePercent(),
                                repeatMode = RepeatMode.REPEAT_NONE,
                                playbackOrder = PlaybackOrder.DEFAULT,
                            ),
                        )

                        is MusicPlaybackReportEvent.Progress -> playStateApi.reportPlaybackProgress(
                            PlaybackProgressInfo(
                                itemId = event.session.itemId,
                                playMethod = event.session.playMethod,
                                playSessionId = event.session.playSessionId,
                                liveStreamId = event.session.liveStreamId,
                                audioStreamIndex = event.session.audioStreamIndex,
                                subtitleStreamIndex = null,
                                isPaused = event.isPaused,
                                isMuted = false,
                                canSeek = true,
                                positionTicks = event.positionTicks,
                                volumeLevel = currentVolumePercent(),
                                repeatMode = RepeatMode.REPEAT_NONE,
                                playbackOrder = PlaybackOrder.DEFAULT,
                            ),
                        )

                        is MusicPlaybackReportEvent.Stopped -> playStateApi.reportPlaybackStopped(
                            PlaybackStopInfo(
                                itemId = event.session.itemId,
                                positionTicks = event.positionTicks,
                                playSessionId = event.session.playSessionId,
                                liveStreamId = event.session.liveStreamId,
                                failed = false,
                            ),
                        )
                    }
                }.onSuccess {
                    Timber.d(
                        "PTV music playback report type=${event::class.simpleName} " +
                            "latencyMs=${System.currentTimeMillis() - startedAt}",
                    )
                }.onFailure { error ->
                    Timber.w(
                        error,
                        "PTV music playback report failed event=${event::class.simpleName} " +
                            "latencyMs=${System.currentTimeMillis() - startedAt}",
                    )
                }
            }
        }
    }

    private fun RemoteJellyfinMediaSource.toPlaybackReportSession(): MusicPlaybackReportSession =
        MusicPlaybackReportSession(
            itemId = itemId,
            playMethod = playMethod,
            playSessionId = playSessionId,
            liveStreamId = liveStreamId,
            audioStreamIndex = selectedAudioStream?.index,
        )

    private fun currentVolumePercent(): Int {
        val maximum = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        if (maximum <= 0) return 0
        return (audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) * 100 / maximum).coerceIn(0, 100)
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

    private fun persistPlaybackState(force: Boolean = false, retainQueueProvenance: Boolean = true) {
        val playbackState = state.value
        val currentOwner = autoResumeStore.currentLocalQueueOwner()
        if (!isMusicPlaybackQueueOwnedBy(playbackState, currentOwner)) return
        val queueOwner = checkNotNull(playbackState.queueOwner)
        val currentItemId = checkNotNull(playbackState.currentItem).id

        val nowMs = System.currentTimeMillis()
        val itemChanged = currentItemId != lastPersistedItemId
        val positionChanged = abs(playbackState.positionMs - lastPersistedPositionMs) >= PERSIST_POSITION_DELTA_MS
        val intervalElapsed = nowMs - lastPersistedAtMs >= PERSIST_MIN_INTERVAL_MS
        val shouldPersist = force || itemChanged || positionChanged || intervalElapsed

        if (!shouldPersist) return

        val persisted = autoResumeStore.savePlaybackState(
            playbackState = playbackState,
            expectedOwner = queueOwner,
            nowMs = nowMs,
            retainQueueProvenance = retainQueueProvenance,
            selectedAuthoredIndex = playbackQueueOriginalIndices.getOrNull(currentIndex),
        )
        if (!persisted) return
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
        const val STABLE_PLAYBACK_RESET_DELAY_MS = 30_000L
        const val PROGRESS_TICK_MS = 500L
        const val PERSIST_MIN_INTERVAL_MS = 5_000L
        const val PERSIST_POSITION_DELTA_MS = 5_000L
        const val PREFETCH_CACHE_SIZE = 3
        const val MUSIC_MIN_BUFFER_MS = 30_000
        const val MUSIC_MAX_BUFFER_MS = 120_000
        const val MUSIC_PLAYBACK_BUFFER_MS = 1_000
        const val MUSIC_REBUFFER_MS = 2_500
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

internal fun isMusicPlaybackQueueOwnedBy(
    playbackState: MusicPlaybackState,
    currentOwner: PtvMusicAutoQueueOwner?,
): Boolean = currentOwner != null && playbackState.queueOwner == currentOwner &&
    playbackState.hasCurrent && playbackState.queue.isNotEmpty()

internal fun isResolvedMusicSourceStillOwned(
    expectedOwner: PtvMusicAutoQueueOwner?,
    currentOwner: PtvMusicAutoQueueOwner?,
    residentOwner: PtvMusicAutoQueueOwner?,
    currentLoad: Boolean,
): Boolean = currentLoad && expectedOwner != null && expectedOwner == currentOwner &&
    expectedOwner == residentOwner

internal fun isMusicPlaybackCommandAuthorized(
    requestedOwner: PtvMusicAutoQueueOwner?,
    playbackState: MusicPlaybackState,
    currentOwner: PtvMusicAutoQueueOwner?,
): Boolean = requestedOwner != null && requestedOwner == currentOwner &&
    isMusicPlaybackQueueOwnedBy(playbackState, currentOwner)

internal fun isMusicPlaybackResidentMutationAuthorized(
    hasResidentQueue: Boolean,
    playbackState: MusicPlaybackState,
    currentOwner: PtvMusicAutoQueueOwner?,
): Boolean = !hasResidentQueue || isMusicPlaybackQueueOwnedBy(playbackState, currentOwner)

internal fun shouldDiscardUnownedMusicQueue(
    playbackState: MusicPlaybackState,
    currentOwner: PtvMusicAutoQueueOwner?,
): Boolean = playbackState.hasCurrent && playbackState.queue.isNotEmpty() &&
    (currentOwner == null || playbackState.queueOwner != currentOwner)

internal fun emptyMusicPlaybackStateAfterStop(playbackState: MusicPlaybackState): MusicPlaybackState =
    MusicPlaybackState(isAppInForeground = playbackState.isAppInForeground)

internal fun shouldClearSavedMusicQueueOnStop(
    playbackState: MusicPlaybackState,
    currentOwner: PtvMusicAutoQueueOwner?,
    pendingResidentClear: Boolean = false,
): Boolean {
    if (pendingResidentClear) return false
    if (!playbackState.hasCurrent || playbackState.queue.isEmpty()) return true
    return currentOwner != null && playbackState.queueOwner == currentOwner
}

internal class MusicPlaybackCommandOrder {
    private val sequence = AtomicLong()

    @Volatile
    private var foreignDiscardPending = false

    @Volatile
    private var stopPending = false

    @Synchronized
    fun issue(): Long = sequence.incrementAndGet()

    @Synchronized
    fun issueStop(): Long {
        stopPending = true
        return sequence.incrementAndGet()
    }

    fun current(): Long = sequence.get()

    fun isCurrent(command: Long): Boolean = sequence.get() == command

    @Synchronized
    fun markForeignDiscardPending(): Long {
        foreignDiscardPending = true
        return current()
    }

    fun hasPendingForeignDiscard(): Boolean = foreignDiscardPending

    fun hasPendingStop(): Boolean = stopPending

    fun hasPendingResidentClear(): Boolean = foreignDiscardPending || stopPending

    @Synchronized
    fun completeResidentClear() {
        foreignDiscardPending = false
        stopPending = false
    }
}

internal class MusicPlaybackRecoveryState(private val maxRetryAttempts: Int) {
    private val retryCounts = mutableMapOf<java.util.UUID, Int>()
    private var preparedItemId: java.util.UUID? = null

    fun nextRetryAttempt(itemId: java.util.UUID): Int? {
        val attempt = retryCounts[itemId] ?: 0
        if (attempt >= maxRetryAttempts) return null
        retryCounts[itemId] = attempt + 1
        return attempt
    }

    fun markPrepared(itemId: java.util.UUID) {
        preparedItemId = itemId
    }

    fun markStablePlayback(itemId: java.util.UUID) {
        if (preparedItemId == itemId) retryCounts.remove(itemId)
    }

    fun markTrackCompleted(itemId: java.util.UUID) {
        markStablePlayback(itemId)
        if (preparedItemId == itemId) preparedItemId = null
    }

    fun reset(itemId: java.util.UUID) {
        retryCounts.remove(itemId)
        if (preparedItemId == itemId) preparedItemId = null
    }

    fun resetAll() {
        retryCounts.clear()
        preparedItemId = null
    }

    fun resumePositionMs(
        itemId: java.util.UUID,
        playerMediaItemId: String?,
        playerPositionMs: Long,
        savedPositionMs: Long,
    ): Long {
        val savedPosition = savedPositionMs.coerceAtLeast(0)
        return if (playerMediaItemId == itemId.toString() &&
            playerPositionMs > 0 && playerPositionMs != C.TIME_UNSET
        ) {
            playerPositionMs
        } else {
            savedPosition
        }
    }
}

private fun Long.toPlaybackTicks(): Long = coerceIn(0L, Long.MAX_VALUE / 10_000L) * 10_000L

internal data class RestoredMusicPlaybackQueueSelection(val queue: List<MusicItem>, val currentIndex: Int)

internal fun selectRestoredMusicPlaybackQueue(
    queue: List<MusicItem>,
    currentIndex: Int,
): RestoredMusicPlaybackQueueSelection? {
    if (queue.isEmpty() || queue.any { !it.isPlayable }) return null
    return RestoredMusicPlaybackQueueSelection(queue.toList(), currentIndex.coerceIn(0, queue.lastIndex))
}

internal data class AuthoredMusicPlaybackQueueSelection(
    val originalQueue: List<MusicItem>,
    val playbackQueue: List<MusicItem>,
    val playbackOriginalIndices: List<Int>,
    val selectedIndex: Int,
    val currentIndex: Int,
    val selectedItem: MusicItem,
)

internal fun selectAuthoredMusicPlaybackQueue(
    item: MusicItem,
    queue: List<MusicItem>,
    selectedIndex: Int,
    shuffleEnabled: Boolean,
): AuthoredMusicPlaybackQueueSelection? {
    val selectedItem = queue.getOrNull(selectedIndex) ?: return null
    val matchingPlayableSelection = item.isPlayable && selectedItem.isPlayable && selectedItem.id == item.id
    if (!matchingPlayableSelection || queue.any { !it.isPlayable }) {
        return null
    }
    val authoredQueue = queue.toList()
    val order = reorderMusicPlaybackQueue(
        authoredQueue,
        authoredQueue.indices.toList(),
        selectedIndex,
        shuffleEnabled,
    ) ?: return null
    return AuthoredMusicPlaybackQueueSelection(
        originalQueue = authoredQueue,
        playbackQueue = order.queue,
        playbackOriginalIndices = order.originalIndices,
        selectedIndex = selectedIndex,
        currentIndex = order.currentIndex,
        selectedItem = selectedItem,
    )
}

internal data class MusicPlaybackQueueOrder(
    val queue: List<MusicItem>,
    val originalIndices: List<Int>,
    val currentIndex: Int,
)

internal fun selectMusicQueueCurrentIndexAfterUpdate(
    resultQueue: List<MusicItem>,
    previousCurrentItem: MusicItem?,
    suggestedIndex: Int,
): Int? {
    val playableQueue = resultQueue.filter(MusicItem::isPlayable)
    if (playableQueue.isEmpty()) return null
    val suggestedItem = resultQueue.getOrNull(suggestedIndex)
    if (suggestedItem?.isPlayable == true &&
        (previousCurrentItem == null || suggestedItem.id == previousCurrentItem.id)
    ) {
        return resultQueue.take(suggestedIndex).count(MusicItem::isPlayable)
    }
    return previousCurrentItem?.let { item ->
        playableQueue.indexOfFirst { it.id == item.id }.takeIf { index -> index >= 0 }
    } ?: suggestedIndex.coerceIn(0, playableQueue.lastIndex)
}

internal fun selectPlaybackOrderPreservingAppend(
    currentQueue: List<MusicItem>,
    currentIndex: Int,
    proposedQueue: List<MusicItem>,
): MusicPlaybackQueueOrder? {
    if (currentQueue.isEmpty() || currentIndex !in currentQueue.indices) return null
    if (proposedQueue.isEmpty() || proposedQueue.any { !it.isPlayable }) return null
    if (currentQueue.distinctBy(MusicItem::id).size != currentQueue.size ||
        proposedQueue.distinctBy(MusicItem::id).size != proposedQueue.size
    ) {
        return null
    }
    val currentIds = currentQueue.map(MusicItem::id)
    val proposedIds = proposedQueue.map(MusicItem::id)
    val trimmedPrefixSize = currentIds.indexOf(proposedIds.first())
    if (trimmedPrefixSize !in 0..currentIndex) return null
    val retainedIds = currentIds.drop(trimmedPrefixSize)
    if (proposedIds.size < retainedIds.size || proposedIds.take(retainedIds.size) != retainedIds ||
        proposedIds.drop(retainedIds.size).any(currentIds.toSet()::contains)
    ) {
        return null
    }
    return MusicPlaybackQueueOrder(
        queue = proposedQueue.toList(),
        originalIndices = proposedQueue.indices.toList(),
        currentIndex = currentIndex - trimmedPrefixSize,
    )
}

internal fun reorderMusicPlaybackQueue(
    originalQueue: List<MusicItem>,
    currentOriginalIndices: List<Int>,
    currentIndex: Int,
    shuffleEnabled: Boolean,
): MusicPlaybackQueueOrder? {
    if (originalQueue.isEmpty() || currentIndex !in currentOriginalIndices.indices ||
        currentOriginalIndices.sorted() != originalQueue.indices.toList()
    ) {
        return null
    }
    val selectedOriginalIndex = currentOriginalIndices[currentIndex]
    val newIndices = if (shuffleEnabled) {
        val remainingIndices = originalQueue.indices
            .filter { index -> index != selectedOriginalIndex }
            .shuffled()
        listOf(selectedOriginalIndex) + remainingIndices
    } else {
        originalQueue.indices.toList()
    }
    return MusicPlaybackQueueOrder(
        queue = newIndices.map(originalQueue::get),
        originalIndices = newIndices,
        currentIndex = if (shuffleEnabled) 0 else selectedOriginalIndex,
    )
}

internal fun retryAuthoredMusicQueueIndex(
    originalQueue: List<MusicItem>,
    playbackOriginalIndices: List<Int>,
    currentIndex: Int,
    failedItem: MusicItem,
): Int? {
    if (originalQueue.distinctBy(MusicItem::id).size == originalQueue.size ||
        playbackOriginalIndices.sorted() != originalQueue.indices.toList()
    ) {
        return null
    }
    return playbackOriginalIndices.getOrNull(currentIndex)
        ?.takeIf { index -> originalQueue[index].id == failedItem.id }
}

internal fun musicQueueIndexForMediaId(queue: List<MusicItem>, currentIndex: Int, mediaId: String): Int {
    if (queue.getOrNull(currentIndex)?.id?.toString() == mediaId) return currentIndex
    val nextOccurrence = queue.indices.firstOrNull { index ->
        index > currentIndex && queue[index].id.toString() == mediaId
    }
    val firstOccurrence = queue.indexOfFirst { item -> item.id.toString() == mediaId }
    return nextOccurrence ?: firstOccurrence.takeIf { index -> index >= 0 } ?: currentIndex
}

internal fun shouldBufferNextMusicQueueOccurrence(queue: List<MusicItem>, currentIndex: Int, nextIndex: Int): Boolean {
    val current = queue.getOrNull(currentIndex) ?: return false
    val next = queue.getOrNull(nextIndex) ?: return false
    return current.id != next.id
}

internal enum class MusicPlaybackTransitionReason { NATURAL, EXPLICIT_SKIP }

internal data class MusicPlaybackTransitionEvent(
    val owner: PtvMusicAutoQueueOwner,
    val previousItemId: java.util.UUID,
    val nextItemId: java.util.UUID,
    val previousIndex: Int,
    val nextIndex: Int,
    val reason: MusicPlaybackTransitionReason,
)

internal class MusicPlaybackTransitionEmitter {
    private val mutableEvents = MutableSharedFlow<MusicPlaybackTransitionEvent>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val events: SharedFlow<MusicPlaybackTransitionEvent> = mutableEvents.asSharedFlow()

    fun emit(
        queue: List<MusicItem>,
        previousIndex: Int,
        nextIndex: Int,
        queueOwner: PtvMusicAutoQueueOwner?,
        currentOwner: PtvMusicAutoQueueOwner?,
        reason: MusicPlaybackTransitionReason,
    ): Boolean {
        val owner = queueOwner?.takeIf { it == currentOwner } ?: return false
        if (previousIndex == nextIndex) return false
        val previousItem = queue.getOrNull(previousIndex) ?: return false
        val nextItem = queue.getOrNull(nextIndex) ?: return false
        return mutableEvents.tryEmit(
            MusicPlaybackTransitionEvent(
                owner = owner,
                previousItemId = previousItem.id,
                nextItemId = nextItem.id,
                previousIndex = previousIndex,
                nextIndex = nextIndex,
                reason = reason,
            ),
        )
    }
}

/** ExoPlayer.stop() leaves playWhenReady unchanged, so clear the request before stopping. */
internal fun stopMusicPlayerAtNaturalQueueEnd(player: Player) {
    player.pause()
    player.stop()
}

internal fun isActiveMusicBuffering(playbackState: Int, playWhenReady: Boolean, isPlaying: Boolean): Boolean =
    playbackState == Player.STATE_BUFFERING && playWhenReady && !isPlaying
