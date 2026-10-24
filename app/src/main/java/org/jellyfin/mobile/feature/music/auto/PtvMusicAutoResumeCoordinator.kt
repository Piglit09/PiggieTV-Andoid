package org.jellyfin.mobile.feature.music.auto

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.jellyfin.mobile.feature.music.MusicItem
import org.jellyfin.mobile.feature.music.MusicPlaybackController
import org.jellyfin.mobile.feature.music.MusicRepository
import org.jellyfin.mobile.feature.music.reorderMusicPlaybackQueue
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.serializer.toUUIDOrNull
import timber.log.Timber

class PtvMusicAutoResumeCoordinator(
    private val repository: MusicRepository,
    private val playbackController: MusicPlaybackController,
    private val resumeStore: PtvMusicAutoResumeStore,
) {
    private val restoreMutex = Mutex()
    private var restoreAttempted = false
    private var restoreAttemptedOwner: PtvMusicAutoQueueOwner? = null

    suspend fun restoreQueueIfNeeded(): PtvMusicAutoRestoreResult = restoreMutex.withLock {
        val currentOwner = resumeStore.currentLocalQueueOwner()
        alignRestoreAttemptOwner(currentOwner)
        if (playbackController.state.value.hasCurrent) {
            if (playbackController.isCurrentQueueOwnedByAuthenticatedUser()) {
                return@withLock PtvMusicAutoRestoreResult.AlreadyActive
            }
            playbackController.discardQueueIfOwnershipChanged()
            if (playbackController.state.value.hasCurrent) {
                return@withLock PtvMusicAutoRestoreResult.Failed
            }
        }
        if (restoreAttempted) {
            return@withLock PtvMusicAutoRestoreResult.AlreadyAttempted
        }
        val savedState = resumeStore.loadPlaybackState()
        if (savedState == null) {
            restoreAttempted = true
            return@withLock PtvMusicAutoRestoreResult.NoSavedState.also {
                resumeStore.markRestoreStatus(it.message)
            }
        }
        val expectedOwner = savedState.queueOwnerOrNull()
        if (expectedOwner == null || resumeStore.currentLocalQueueOwner() != expectedOwner) {
            return@withLock PtvMusicAutoRestoreResult.Failed.also {
                resumeStore.markRestoreStatus(it.message)
            }
        }

        val savedIds = savedState.queueUuids
        if (savedIds.isEmpty()) {
            restoreAttempted = true
            resumeStore.clearPlaybackState()
            return@withLock PtvMusicAutoRestoreResult.NoPlayableItems.also {
                resumeStore.markRestoreStatus(it.message)
            }
        }

        runCatching {
            restoreSavedItems(savedState, expectedOwner)
        }.fold(
            onSuccess = { result ->
                restoreAttempted = result != PtvMusicAutoRestoreResult.Failed
                resumeStore.markRestoreStatus(result.message)
                result
            },
            onFailure = { error ->
                restoreAttempted = false
                Timber.e(error, "PTV Music Auto queue restore failed")
                PtvMusicAutoRestoreResult.Failed.also {
                    resumeStore.markRestoreStatus(it.message)
                    resumeStore.markLastPlaybackError(error.message ?: it.message)
                }
            },
        )
    }

    private fun alignRestoreAttemptOwner(currentOwner: PtvMusicAutoQueueOwner?) {
        if (restoreAttemptedOwner == currentOwner) return
        restoreAttemptedOwner = currentOwner
        restoreAttempted = false
    }

    @Suppress("ReturnCount") // Security checks fail closed at each restore boundary.
    private suspend fun restoreSavedItems(
        savedState: PtvMusicAutoResumeState,
        expectedOwner: PtvMusicAutoQueueOwner,
    ): PtvMusicAutoRestoreResult {
        val source = savedState.queueProvenance
        if (source != null && source.kind == PtvMusicAutoQueueProvenance.PLAYLIST) {
            return restoreSavedPlaylist(savedState, source, expectedOwner)
        }
        if (source != null && source.kind != PtvMusicAutoQueueProvenance.RECOMMENDATION) {
            return PtvMusicAutoRestoreResult.Failed
        }
        val savedEntries = savedState.queueItemIds.mapIndexedNotNull { index, rawId ->
            rawId.toUUIDOrNull()?.let { id -> index to id }
        }
        val fetchedItems = repository.loadItems(savedEntries.map { (_, id) -> id }.distinct())
            .filter(MusicItem::isPlayable)
            .associateBy(MusicItem::id)
        if (resumeStore.currentLocalQueueOwner() != expectedOwner) return PtvMusicAutoRestoreResult.Failed
        val restoredEntries = savedEntries.mapNotNull { (savedIndex, id) ->
            fetchedItems[id]?.let { item -> savedIndex to item }
        }
        val missingCount = savedState.queueItemIds.size - restoredEntries.size
        val restoredItems = restoredEntries.map { (_, item) -> item }
        if (restoredItems.isEmpty()) {
            resumeStore.clearPlaybackState()
            return PtvMusicAutoRestoreResult.NoPlayableItems
        }

        val restoredCurrentIndex = restoredEntries.indexOfFirst { (savedIndex, _) ->
            savedIndex == savedState.currentIndex
        }.takeIf { index -> index >= 0 }
            ?: restoredEntries.indexOfFirst { (savedIndex, _) -> savedIndex > savedState.currentIndex }
                .takeIf { index -> index >= 0 }
            ?: restoredItems.lastIndex

        val restored = playbackController.restoreQueue(
            queue = restoredItems,
            currentIndex = restoredCurrentIndex,
            positionMs = savedState.positionMs,
            shuffleEnabled = savedState.shuffleEnabled,
            repeatMode = savedState.parsedRepeatMode,
            expectedOwner = expectedOwner,
        )
        return if (restored) {
            PtvMusicAutoRestoreResult.Restored(
                restoredCount = restoredItems.size,
                missingCount = missingCount.coerceAtLeast(0),
            )
        } else {
            PtvMusicAutoRestoreResult.Failed
        }
    }

    @Suppress("CyclomaticComplexMethod", "ReturnCount") // Fail closed at each playlist identity check.
    private suspend fun restoreSavedPlaylist(
        savedState: PtvMusicAutoResumeState,
        source: PtvMusicAutoQueueProvenance,
        expectedOwner: PtvMusicAutoQueueOwner,
    ): PtvMusicAutoRestoreResult {
        val playlistId = source.playlistId?.toUUIDOrNull() ?: return PtvMusicAutoRestoreResult.Failed
        val fullIndex = source.fullCurrentIndex ?: return PtvMusicAutoRestoreResult.Failed
        if (savedState.fullQueueSha256 != source.queueSha256 || savedState.fullCurrentIndex != fullIndex) {
            return PtvMusicAutoRestoreResult.Failed
        }
        val playlist = repository.loadItem(playlistId)
            ?.takeIf { it.type == BaseItemKind.PLAYLIST } ?: return PtvMusicAutoRestoreResult.Failed
        val fullQueue = repository.loadPlaylistQueue(playlist)
        val queueIsValid = fullQueue.isNotEmpty() && fullQueue.all(MusicItem::isPlayable)
        if (resumeStore.currentLocalQueueOwner() != expectedOwner || !queueIsValid) {
            return PtvMusicAutoRestoreResult.Failed
        }
        if (savedState.shuffleEnabled) {
            return restoreShuffledPlaylist(savedState, source, fullQueue, expectedOwner)
        }
        val selectionIsValid = fullIndex in fullQueue.indices && source.matchesQueue(fullQueue)
        if (!selectionIsValid) {
            return PtvMusicAutoRestoreResult.Failed
        }
        val windowStart = fullIndex - savedState.currentIndex
        val savedWindow = savedState.queueItemIds
        val restoredWindow = if (windowStart >= 0) {
            fullQueue.drop(windowStart).take(savedWindow.size).map { item -> item.id.toString() }
        } else {
            emptyList()
        }
        val windowIsValid = windowStart >= 0 && restoredWindow == savedWindow &&
            fullQueue[fullIndex].id.toString() == savedState.currentItemId
        if (!windowIsValid) {
            return PtvMusicAutoRestoreResult.Failed
        }

        val restored = playbackController.restoreQueue(
            queue = fullQueue,
            currentIndex = fullIndex,
            positionMs = savedState.positionMs,
            shuffleEnabled = savedState.shuffleEnabled,
            repeatMode = savedState.parsedRepeatMode,
            expectedOwner = expectedOwner,
        )
        return if (restored) {
            PtvMusicAutoRestoreResult.Restored(fullQueue.size, 0)
        } else {
            PtvMusicAutoRestoreResult.Failed
        }
    }

    @Suppress("ReturnCount") // Each persisted identity must be verified before restoring a shuffled queue.
    private fun restoreShuffledPlaylist(
        savedState: PtvMusicAutoResumeState,
        source: PtvMusicAutoQueueProvenance,
        authoredQueue: List<MusicItem>,
        expectedOwner: PtvMusicAutoQueueOwner,
    ): PtvMusicAutoRestoreResult {
        val selectedAuthoredIndex = source.selectedAuthoredIndex ?: return PtvMusicAutoRestoreResult.Failed
        if (!source.matchesAuthoredQueue(authoredQueue) || selectedAuthoredIndex !in authoredQueue.indices) {
            return PtvMusicAutoRestoreResult.Failed
        }
        if (authoredQueue[selectedAuthoredIndex].id.toString() != savedState.currentItemId) {
            return PtvMusicAutoRestoreResult.Failed
        }
        val authorizedCounts = authoredQueue.groupingBy { item -> item.id.toString() }.eachCount()
        val savedCounts = savedState.queueItemIds.groupingBy(String::toString).eachCount()
        if (savedCounts.any { (id, count) -> count > authorizedCounts.getOrDefault(id, 0) }) {
            return PtvMusicAutoRestoreResult.Failed
        }
        val order = reorderMusicPlaybackQueue(
            authoredQueue,
            authoredQueue.indices.toList(),
            selectedAuthoredIndex,
            shuffleEnabled = true,
        ) ?: return PtvMusicAutoRestoreResult.Failed
        val restored = playbackController.restoreShuffledPlaylistQueue(
            authoredQueue = authoredQueue,
            order = order,
            positionMs = savedState.positionMs,
            repeatMode = savedState.parsedRepeatMode,
            expectedOwner = expectedOwner,
            provenance = source,
        )
        return if (restored) {
            PtvMusicAutoRestoreResult.Restored(authoredQueue.size, 0)
        } else {
            PtvMusicAutoRestoreResult.Failed
        }
    }
}

sealed class PtvMusicAutoRestoreResult(val message: String) {
    data object AlreadyActive : PtvMusicAutoRestoreResult("Queue already active.")
    data object AlreadyAttempted : PtvMusicAutoRestoreResult("Queue restore already checked.")
    data object NoSavedState : PtvMusicAutoRestoreResult("No saved Android Auto queue.")
    data object NoPlayableItems : PtvMusicAutoRestoreResult("Saved Android Auto queue had no playable songs.")
    data object Failed : PtvMusicAutoRestoreResult("Android Auto queue restore failed.")

    data class Restored(val restoredCount: Int, val missingCount: Int) :
        PtvMusicAutoRestoreResult(
            when (missingCount) {
                0 -> "Restored $restoredCount Android Auto songs."
                else -> "Restored $restoredCount Android Auto songs; $missingCount saved songs were missing."
            },
        )
}
