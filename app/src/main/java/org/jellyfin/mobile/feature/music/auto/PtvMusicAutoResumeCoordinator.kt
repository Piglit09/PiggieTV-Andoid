package org.jellyfin.mobile.feature.music.auto

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.jellyfin.mobile.feature.music.MusicItem
import org.jellyfin.mobile.feature.music.MusicPlaybackController
import org.jellyfin.mobile.feature.music.MusicRepository
import timber.log.Timber

class PtvMusicAutoResumeCoordinator(
    private val repository: MusicRepository,
    private val playbackController: MusicPlaybackController,
    private val resumeStore: PtvMusicAutoResumeStore,
) {
    private val restoreMutex = Mutex()
    private var restoreAttempted = false

    suspend fun restoreQueueIfNeeded(): PtvMusicAutoRestoreResult = restoreMutex.withLock {
        if (playbackController.state.value.hasCurrent) {
            return@withLock PtvMusicAutoRestoreResult.AlreadyActive
        }
        if (restoreAttempted) {
            return@withLock PtvMusicAutoRestoreResult.AlreadyAttempted
        }
        restoreAttempted = true

        val savedState = resumeStore.loadPlaybackState()
        if (savedState == null) {
            return@withLock PtvMusicAutoRestoreResult.NoSavedState.also {
                resumeStore.markRestoreStatus(it.message)
            }
        }

        val savedIds = savedState.queueUuids
        if (savedIds.isEmpty()) {
            resumeStore.clearPlaybackState()
            return@withLock PtvMusicAutoRestoreResult.NoPlayableItems.also {
                resumeStore.markRestoreStatus(it.message)
            }
        }

        runCatching {
            val restoredItems = repository.loadItems(savedIds)
                .filter(MusicItem::isPlayable)
            val missingCount = savedIds.size - restoredItems.size
            if (restoredItems.isEmpty()) {
                resumeStore.clearPlaybackState()
                PtvMusicAutoRestoreResult.NoPlayableItems
            } else {
                val restoredCurrentIndex = restoredItems.indexOfFirst { item ->
                    item.id.toString() == savedState.currentItemId
                }.takeIf { index -> index >= 0 }
                    ?: savedState.currentIndex.coerceIn(0, restoredItems.lastIndex)

                playbackController.restoreQueue(
                    queue = restoredItems,
                    currentIndex = restoredCurrentIndex,
                    positionMs = savedState.positionMs,
                    shuffleEnabled = savedState.shuffleEnabled,
                    repeatMode = savedState.parsedRepeatMode,
                )
                PtvMusicAutoRestoreResult.Restored(
                    restoredCount = restoredItems.size,
                    missingCount = missingCount.coerceAtLeast(0),
                )
            }
        }.fold(
            onSuccess = { result ->
                resumeStore.markRestoreStatus(result.message)
                result
            },
            onFailure = { error ->
                Timber.e(error, "PTV Music Auto queue restore failed")
                PtvMusicAutoRestoreResult.Failed.also {
                    resumeStore.markRestoreStatus(it.message)
                    resumeStore.markLastPlaybackError(error.message ?: it.message)
                }
            },
        )
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
