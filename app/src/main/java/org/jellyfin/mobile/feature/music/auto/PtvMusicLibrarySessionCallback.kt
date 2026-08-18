@file:Suppress("TooManyFunctions")

package org.jellyfin.mobile.feature.music.auto

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.CommandButton
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaConstants
import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSession.MediaItemsWithStartPosition
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.guava.future
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.jellyfin.mobile.R
import org.jellyfin.mobile.feature.music.MusicBrowseKind
import org.jellyfin.mobile.feature.music.MusicItem
import org.jellyfin.mobile.feature.music.MusicPage
import org.jellyfin.mobile.feature.music.MusicPlaybackController
import org.jellyfin.mobile.feature.music.MusicPlaybackState
import org.jellyfin.mobile.feature.music.MusicRepeatMode
import org.jellyfin.mobile.feature.music.MusicRepository
import org.jellyfin.mobile.feature.music.MusicSongAction
import org.jellyfin.mobile.feature.music.MusicSongActionHandler
import org.jellyfin.mobile.feature.music.MusicSongActionRequest
import org.jellyfin.mobile.feature.music.MusicSongActionResult
import org.jellyfin.sdk.model.UUID
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap

@UnstableApi
internal class PtvMusicLibrarySessionCallback(
    private val context: Context,
    private val repository: MusicRepository,
    private val playbackController: MusicPlaybackController,
    private val songActionHandler: MusicSongActionHandler,
    private val resumeStore: PtvMusicAutoResumeStore,
    private val resumeCoordinator: PtvMusicAutoResumeCoordinator,
    private val savedSessionReady: Deferred<Unit>,
) : MediaLibrarySession.Callback {
    private val callbackId = runtimeInstanceId(this)
    private val ioScope = CoroutineScope(Dispatchers.IO)
    private val itemCache = ConcurrentHashMap<UUID, MusicItem>()
    private val queueCache = ConcurrentHashMap<UUID, List<MusicItem>>()
    private val browseCache = ConcurrentHashMap<String, List<MediaItem>>()
    private val generatedQueueCache = ConcurrentHashMap<String, List<MusicItem>>()

    override fun onConnect(
        session: MediaSession,
        controller: MediaSession.ControllerInfo,
    ): MediaSession.ConnectionResult {
        if (
            !isPtvMusicControllerAllowed(
                controllerIsTrusted = controller.isTrusted,
                controllerUid = controller.uid,
                applicationUid = context.applicationInfo.uid,
            )
        ) {
            Timber.w("PTV Music Auto rejected untrusted controller package=${controller.packageName}")
            return MediaSession.ConnectionResult.reject()
        }
        val sessionCommands = MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS
            .buildUpon()
            .addSessionCommands(PtvMusicAutoCommand.commands)
            .build()

        Timber.i(
            "PTV Music Auto controller connected package=${controller.packageName} " +
                "callbackId=$callbackId repositoryId=${repository.instanceId} " +
                "controllerId=${playbackController.instanceId} playerId=${playbackController.playerInstanceId}",
        )
        resumeStore.markSessionCommand("connect ${controller.packageName}")
        return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
            .setAvailableSessionCommands(sessionCommands)
            .setAvailablePlayerCommands(ptvMusicAutoPlayerCommands())
            .setCustomLayout(PtvMusicAutoCommand.buttonsFor(playbackController.state.value))
            .setMediaButtonPreferences(PtvMusicAutoCommand.buttonsFor(playbackController.state.value))
            .build()
    }

    override fun onGetLibraryRoot(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        params: LibraryParams?,
    ): ListenableFuture<LibraryResult<MediaItem>> = ioScope.future {
        val item = when {
            params?.isRecent == true -> PtvMusicAutoCategory.RECENTLY_PLAYED.toAutoMediaItem()
            params?.isSuggested == true -> PtvMusicAutoCategory.RECOMMENDATIONS.toAutoMediaItem()
            else -> rootMediaItem()
        }

        Timber.i(
            "PTV Music Auto root requested package=${browser.packageName} " +
                "recent=${params?.isRecent == true} suggested=${params?.isSuggested == true} " +
                "callbackId=$callbackId controllerId=${playbackController.instanceId}",
        )
        LibraryResult.ofItem(item, params)
    }

    override fun onGetChildren(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        parentId: String,
        page: Int,
        pageSize: Int,
        params: LibraryParams?,
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = ioScope.future {
        runCatching {
            val startIndex = page.toStartIndex(pageSize)
            val limit = pageSize.toSafePageSize()
            when {
                parentId == PtvMusicAutoIds.ROOT -> {
                    val rootItems = PTV_MUSIC_AUTO_ROOT_ITEMS.map { item -> item.toAutoMediaItem() }
                        .drop(startIndex)
                        .take(limit)
                    Timber.i(
                        "PTV Music Auto root children requested count=${rootItems.size} page=$page pageSize=$pageSize",
                    )
                    rootItems
                }

                parentId == PtvMusicAutoIds.RECOMMENDED_MIXES -> {
                    loadMediaItemsWithCache(
                        cacheKey = "recommendedMixes:$startIndex:$limit:${playbackController.state.value.currentItem?.id}",
                        label = "recommended mixes",
                    ) {
                        loadRecommendedMixItems().drop(startIndex).take(limit)
                    }
                }

                parentId == PtvMusicAutoIds.AUTO_PICKS -> {
                    loadGeneratedQueueForMediaId(
                        mediaId = parentId,
                        startIndex = startIndex,
                        limit = limit,
                        callbackName = "onGetChildren",
                    ).tracks.toMediaItems()
                }

                parentId == PtvMusicAutoIds.MORE -> {
                    loadMediaItemsWithCache(
                        cacheKey = "more:$startIndex:$limit:${playbackController.state.value.currentItem?.id}",
                        label = "more",
                    ) {
                        loadMoreMediaItems().drop(startIndex).take(limit)
                    }
                }

                PtvMusicAutoCategory.fromMediaId(parentId) != null -> {
                    val category = requireNotNull(PtvMusicAutoCategory.fromMediaId(parentId))
                    val mediaItems = loadMediaItemsWithCache(
                        cacheKey = "category:$parentId:$startIndex:$limit",
                        label = "category ${category.name}",
                    ) {
                        repository.loadBrowsePage(
                            kind = category.browseKind,
                            startIndex = startIndex,
                            limit = limit,
                        ).toMediaItems()
                    }
                    if (mediaItems.isEmpty()) {
                        category.activePlaybackMediaItems(startIndex = startIndex, limit = limit)?.let { activeItems ->
                            Timber.i(
                                "PTV Music Auto category ${category.name} using fallback active shared queue " +
                                    "count=${activeItems.size} current=${playbackController.state.value.currentItem?.id} " +
                                    "callbackId=$callbackId",
                            )
                            resumeStore.markBrowseStatus("fallback-activeQueue:${category.name}", activeItems.size)
                            return@future LibraryResult.ofItemList(activeItems, params)
                        }
                    }
                    mediaItems
                }

                PtvMusicAutoMix.fromMediaId(parentId) != null -> {
                    loadGeneratedQueueForMediaId(
                        mediaId = parentId,
                        startIndex = startIndex,
                        limit = limit,
                        callbackName = "onGetChildren",
                    ).tracks.toMediaItems()
                }

                else -> {
                    loadMediaItemsWithCache(
                        cacheKey = "children:$parentId:$startIndex:$limit",
                        label = "children $parentId",
                    ) {
                        val item = parentId.toCachedMusicItem()
                        val musicPage = item?.let {
                            repository.loadChildrenPage(
                                item = it,
                                startIndex = startIndex,
                                limit = limit,
                            )
                        } ?: MusicPage(emptyList(), totalCount = 0, startIndex = 0)
                        musicPage.toMediaItems()
                    }
                }
            }
        }.fold(
            onSuccess = { items -> LibraryResult.ofItemList(items, params) },
            onFailure = { error ->
                val message = "PTV Music Auto browse failed for $parentId page=$page pageSize=$pageSize"
                Timber.e(error, message)
                resumeStore.markLastPlaybackError(error.message ?: message)
                LibraryResult.ofError(
                    SessionError(
                        SessionError.ERROR_NOT_SUPPORTED,
                        "PTV could not load this music row.",
                    ),
                )
            },
        )
    }

    override fun onGetItem(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        mediaId: String,
    ): ListenableFuture<LibraryResult<MediaItem>> = ioScope.future {
        when {
            mediaId == PtvMusicAutoIds.ROOT -> LibraryResult.ofItem(rootMediaItem(), null)

            PTV_MUSIC_AUTO_ROOT_ITEMS.any { item -> item.mediaId == mediaId } -> {
                LibraryResult.ofItem(
                    requireNotNull(PTV_MUSIC_AUTO_ROOT_ITEMS.firstOrNull { item -> item.mediaId == mediaId })
                        .toAutoMediaItem(),
                    null,
                )
            }

            PtvMusicAutoCategory.fromMediaId(mediaId) != null -> {
                LibraryResult.ofItem(
                    requireNotNull(PtvMusicAutoCategory.fromMediaId(mediaId)).toAutoMediaItem(),
                    null,
                )
            }

            PtvMusicAutoMix.fromMediaId(mediaId) != null -> {
                LibraryResult.ofItem(requireNotNull(PtvMusicAutoMix.fromMediaId(mediaId)).toAutoMediaItem(), null)
            }

            PtvMusicAutoMoreItem.entries.any { item -> item.mediaId == mediaId } -> {
                LibraryResult.ofItem(
                    requireNotNull(PtvMusicAutoMoreItem.entries.firstOrNull { item -> item.mediaId == mediaId })
                        .toAutoMediaItem(),
                    null,
                )
            }

            else -> {
                savedSessionReady.await()
                val item = mediaId.toCachedMusicItem()
                if (item == null) {
                    LibraryResult.ofError(
                        SessionError(
                            SessionError.ERROR_BAD_VALUE,
                            "PTV could not find that music item.",
                        ),
                    )
                } else {
                    LibraryResult.ofItem(item.toAutoMediaItem(), null)
                }
            }
        }
    }

    override fun onSearch(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        query: String,
        params: LibraryParams?,
    ): ListenableFuture<LibraryResult<Void>> = ioScope.future {
        val count = if (query.isBlank()) 0 else DEFAULT_SEARCH_RESULT_COUNT
        session.notifySearchResultChanged(browser, query, count, params)
        LibraryResult.ofVoid(params)
    }

    override fun onGetSearchResult(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        query: String,
        page: Int,
        pageSize: Int,
        params: LibraryParams?,
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = ioScope.future {
        if (query.isBlank()) {
            LibraryResult.ofItemList(emptyList(), params)
        } else {
            resumeStore.markSessionCommand("search '${query.trim()}'")
            runCatching {
                val startIndex = page.toStartIndex(pageSize)
                val limit = pageSize.toSafePageSize()
                loadMediaItemsWithCache(
                    cacheKey = "search:${query.trim().lowercase()}:$startIndex:$limit",
                    label = "search '${query.trim()}'",
                ) {
                    repository.searchMusicPage(
                        query = query,
                        startIndex = startIndex,
                        limit = limit,
                    ).toMediaItems()
                }
            }.fold(
                onSuccess = { items -> LibraryResult.ofItemList(items, params) },
                onFailure = { error ->
                    val message = "PTV Music Auto search failed for page=$page pageSize=$pageSize"
                    Timber.e(error, message)
                    resumeStore.markLastPlaybackError(error.message ?: message)
                    LibraryResult.ofError(
                        SessionError(
                            SessionError.ERROR_NOT_SUPPORTED,
                            "PTV could not search music right now.",
                        ),
                    )
                },
            )
        }
    }

    override fun onSetMediaItems(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo,
        mediaItems: List<MediaItem>,
        startIndex: Int,
        startPositionMs: Long,
    ): ListenableFuture<MediaItemsWithStartPosition> = ioScope.future {
        val callbackStartedAt = System.currentTimeMillis()
        val selectedMediaItem = mediaItems.getOrNull(startIndex.takeIf { it != C.INDEX_UNSET } ?: 0)
        Timber.i(
            "PTV Music Auto onSetMediaItems selectedMediaId=${selectedMediaItem?.mediaId ?: "<none>"} " +
                "startIndex=$startIndex itemCount=${mediaItems.size}",
        )

        runCatching {
            withTimeoutOrNull(AUTO_SELECTION_TIMEOUT_MS) {
                awaitSavedSession("setMediaItems", AUTO_SELECTION_SESSION_READY_TIMEOUT_MS)
                startSelectedMediaItem(
                    selectedMediaItem = selectedMediaItem
                        ?: throw UnsupportedOperationException("PTV Music Auto did not receive a playable item."),
                    startPositionMs = startPositionMs,
                    callbackStartedAt = callbackStartedAt,
                )
            } ?: run {
                Timber.w(
                    "PTV Music Auto onSetMediaItems completed=false timeout=true " +
                        "selectedMediaId=${selectedMediaItem?.mediaId ?: "<none>"} " +
                        "durationMs=${System.currentTimeMillis() - callbackStartedAt}",
                )
                throw IllegalStateException(
                    "PTV Music Auto timed out getting selection for ${selectedMediaItem?.mediaId ?: "<none>"}.",
                )
            }
        }.fold(
            onSuccess = { itemsWithStartPosition ->
                Timber.i(
                    "PTV Music Auto onSetMediaItems completed=true " +
                        "selectedMediaId=${selectedMediaItem?.mediaId ?: "<none>"} " +
                        "returnedCount=${itemsWithStartPosition.mediaItems.size} " +
                        "durationMs=${System.currentTimeMillis() - callbackStartedAt}",
                )
                itemsWithStartPosition
            },
            onFailure = { error ->
                val message = error.message ?: "PTV Music Auto could not start playback."
                Timber.e(
                    error,
                    "PTV Music Auto onSetMediaItems completed=true failureReason=$message " +
                        "selectedMediaId=${selectedMediaItem?.mediaId ?: "<none>"} " +
                        "durationMs=${System.currentTimeMillis() - callbackStartedAt}",
                )
                resumeStore.markLastPlaybackError(message)
                currentMediaItemsWithStartPosition()
            },
        )
    }

    override fun onAddMediaItems(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo,
        mediaItems: List<MediaItem>,
    ): ListenableFuture<List<MediaItem>> = ioScope.future {
        val callbackStartedAt = System.currentTimeMillis()
        val selectedMediaItem = mediaItems.firstOrNull()
        val mediaId = selectedMediaItem?.mediaId
        Timber.i(
            "PTV Music Auto onAddMediaItems selectedMediaId=${mediaId ?: "<none>"} " +
                "itemCount=${mediaItems.size}",
        )

        if (mediaId?.isGeneratedQueueMediaId() != true) {
            Timber.i(
                "PTV Music Auto onAddMediaItems completed=true passthrough=true " +
                    "selectedMediaId=${mediaId ?: "<none>"} returnedCount=${mediaItems.size} " +
                    "durationMs=${System.currentTimeMillis() - callbackStartedAt}",
            )
            return@future mediaItems
        }

        runCatching {
            withTimeoutOrNull(AUTO_SELECTION_TIMEOUT_MS) {
                awaitSavedSession("addMediaItems", AUTO_SELECTION_SESSION_READY_TIMEOUT_MS)
                loadGeneratedQueueForMediaId(
                    mediaId = mediaId,
                    startIndex = 0,
                    limit = MIX_TRACK_LIMIT,
                    callbackName = "onAddMediaItems",
                ).tracks.toMediaItems()
            } ?: emptyList()
        }.fold(
            onSuccess = { items ->
                Timber.i(
                    "PTV Music Auto onAddMediaItems completed=true selectedMediaId=$mediaId " +
                        "returnedCount=${items.size} durationMs=${System.currentTimeMillis() - callbackStartedAt}",
                )
                items
            },
            onFailure = { error ->
                Timber.e(
                    error,
                    "PTV Music Auto onAddMediaItems completed=true failureReason=${error.message} " +
                        "selectedMediaId=$mediaId durationMs=${System.currentTimeMillis() - callbackStartedAt}",
                )
                emptyList()
            },
        )
    }

    private suspend fun startSelectedMediaItem(
        selectedMediaItem: MediaItem,
        startPositionMs: Long,
        callbackStartedAt: Long,
    ): MediaItemsWithStartPosition {
        Timber.i(
            "PTV Music Auto resolving selection selectedMediaId=${selectedMediaItem.mediaId} " +
                "resolvedMix=${PtvMusicAutoMix.fromMediaId(selectedMediaItem.mediaId)?.name ?: "<none>"}",
        )
        return when (selectedMediaItem.mediaId) {
            PtvMusicAutoIds.SHUFFLE_ALL -> startShuffleAll(startPositionMs)

            PtvMusicAutoIds.MORE_LIKE_THIS -> startMoreLikeThis(startPositionMs)

            PtvMusicAutoIds.AUTO_PICKS -> startGeneratedQueueMediaId(
                mediaId = selectedMediaItem.mediaId,
                startPositionMs = startPositionMs,
                callbackStartedAt = callbackStartedAt,
            )

            else -> PtvMusicAutoMix.fromMediaId(selectedMediaItem.mediaId)
                ?.let {
                    startGeneratedQueueMediaId(
                        mediaId = selectedMediaItem.mediaId,
                        startPositionMs = startPositionMs,
                        callbackStartedAt = callbackStartedAt,
                    )
                }
                ?: startResolvedMediaItem(selectedMediaItem, startPositionMs)
        }
    }

    private suspend fun startGeneratedQueueMediaId(
        mediaId: String,
        startPositionMs: Long,
        callbackStartedAt: Long,
    ): MediaItemsWithStartPosition {
        val startPosition = startPositionMs.takeIf { it != C.TIME_UNSET }?.coerceAtLeast(0) ?: 0
        val queueResult = loadGeneratedQueueForMediaId(
            mediaId = mediaId,
            startIndex = 0,
            limit = MIX_TRACK_LIMIT,
            callbackName = "onSetMediaItems",
        )
        val queue = queueResult.tracks
        val firstItem = queue.firstOrNull()
            ?: throw UnsupportedOperationException(
                "${mediaId.generatedQueueDisplayName()} has no playable songs right now.",
            )

        playbackController.play(item = firstItem, queue = queue, startPositionMs = startPosition)
        rememberItems(queue)
        resumeStore.markLastPlaybackError(null)
        resumeStore.markSessionCommand("generatedQueue $mediaId count=${queue.size}")
        Timber.i(
            "PTV Music Auto generated selection started selectedMediaId=$mediaId " +
                "resolvedMix=${PtvMusicAutoMix.fromMediaId(mediaId)?.name ?: "<none>"} " +
                "source=${queueResult.source} candidateCount=${queueResult.candidateCount} " +
                "returnedCount=${queue.size} durationMs=${System.currentTimeMillis() - callbackStartedAt} " +
                "firstItem=${firstItem.id}",
        )

        return MediaItemsWithStartPosition(
            queue.map { item -> item.toAutoMediaItem() },
            0,
            startPosition,
        )
    }

    override fun onCustomCommand(
        session: MediaSession,
        controller: MediaSession.ControllerInfo,
        customCommand: SessionCommand,
        args: Bundle,
    ): ListenableFuture<SessionResult> = ioScope.future {
        runCatching {
            Timber.i(
                "PTV Music Auto custom command received action=${customCommand.customAction} " +
                    "package=${controller.packageName} currentItemId=${playbackController.state.value.currentItem?.id}",
            )
            when (customCommand.customAction) {
                PtvMusicAutoCommand.ACTION_TOGGLE_SHUFFLE -> handleToggleShuffle()
                PtvMusicAutoCommand.ACTION_CYCLE_REPEAT -> handleCycleRepeat()
                PtvMusicAutoCommand.ACTION_TOGGLE_FAVORITE -> handleToggleFavorite()
                PtvMusicAutoCommand.ACTION_ADD_TO_PLAYLIST -> handleAddToPlaylist()
                PtvMusicAutoCommand.ACTION_GENERATE_QUEUE -> handleGenerateQueue()
                else -> throw UnsupportedOperationException("PTV Music Auto command is not supported.")
            }
            resumeStore.markLastPlaybackError(null)
            SessionResult(SessionResult.RESULT_SUCCESS)
        }.getOrElse { error ->
            val message = error.message ?: "PTV Music Auto could not run that action."
            Timber.e(error, "PTV Music Auto custom command failed action=${customCommand.customAction}")
            resumeStore.markLastPlaybackError(message)
            SessionResult(SessionError(SessionError.ERROR_NOT_SUPPORTED, message))
        }
    }

    override fun onPlaybackResumption(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo,
    ): ListenableFuture<MediaItemsWithStartPosition> = ioScope.future {
        savedSessionReady.await()
        resumeCoordinator.restoreQueueIfNeeded()
        val state = playbackController.state.value
        val currentItem = state.currentItem
            ?: throw UnsupportedOperationException("PTV Music has no track to resume.")
        val queue = state.queue.ifEmpty { listOf(currentItem) }
        val index = state.currentIndex.takeIf { it in queue.indices }
            ?: queue.indexOfFirst { item -> item.id == currentItem.id }.coerceAtLeast(0)

        MediaItemsWithStartPosition(
            queue.map { item -> item.toAutoMediaItem() },
            index,
            state.positionMs,
        )
    }

    private fun currentMediaItemsWithStartPosition(): MediaItemsWithStartPosition {
        val state = playbackController.state.value
        val queue = state.queue.filter(MusicItem::isPlayable)
        if (queue.isEmpty()) {
            Timber.w("PTV Music Auto returning empty current selection fallback")
            return MediaItemsWithStartPosition(emptyList(), C.INDEX_UNSET, C.TIME_UNSET)
        }

        val index = state.currentIndex.takeIf { value -> value in queue.indices }
            ?: state.currentItem?.let { currentItem ->
                queue.indexOfFirst { item -> item.id == currentItem.id }
            }?.takeIf { value -> value >= 0 }
            ?: 0
        Timber.i(
            "PTV Music Auto returning current selection fallback count=${queue.size} " +
                "index=$index currentItemId=${state.currentItem?.id}",
        )
        return MediaItemsWithStartPosition(
            queue.map { item -> item.toAutoMediaItem() },
            index,
            state.positionMs.coerceAtLeast(0),
        )
    }

    private suspend fun startShuffleAll(startPositionMs: Long): MediaItemsWithStartPosition {
        val startPosition = startPositionMs.takeIf { it != C.TIME_UNSET }?.coerceAtLeast(0) ?: 0
        val queue = repository.loadBrowsePage(
            kind = MusicBrowseKind.SONGS,
            startIndex = 0,
            limit = MIX_TRACK_LIMIT,
        ).items.filter(MusicItem::isPlayable)
        val itemToPlay = queue.firstOrNull()
            ?: throw UnsupportedOperationException("PTV Music Auto could not find songs to shuffle.")

        playbackController.play(
            item = itemToPlay,
            queue = queue,
            startPositionMs = startPosition,
            shuffle = true,
        )
        rememberItems(queue)
        resumeStore.markLastPlaybackError(null)
        resumeStore.markSessionCommand("shuffleAll count=${queue.size}")
        Timber.i("PTV Music Auto Shuffle All started count=${queue.size} firstItem=${itemToPlay.id}")

        return MediaItemsWithStartPosition(
            queue.map { item -> item.toAutoMediaItem() },
            queue.indexOfFirst { item -> item.id == itemToPlay.id }.coerceAtLeast(0),
            startPosition,
        )
    }

    private suspend fun startAutoPicks(startPositionMs: Long): MediaItemsWithStartPosition {
        val startPosition = startPositionMs.takeIf { it != C.TIME_UNSET }?.coerceAtLeast(0) ?: 0
        val queue = loadGeneratedQueueForMediaId(
            mediaId = PtvMusicAutoIds.AUTO_PICKS,
            startIndex = 0,
            limit = MIX_TRACK_LIMIT,
            callbackName = "startAutoPicks",
        ).tracks
        val firstItem = queue.firstOrNull()
            ?: throw UnsupportedOperationException("PTV Auto Picks has no playable songs yet.")

        playbackController.play(item = firstItem, queue = queue, startPositionMs = startPosition)
        rememberItems(queue)
        resumeStore.markLastPlaybackError(null)
        resumeStore.markSessionCommand("autoPicks count=${queue.size}")
        Timber.i("PTV Music Auto PTV Auto Picks started count=${queue.size} firstItem=${firstItem.id}")

        return MediaItemsWithStartPosition(
            queue.map { item -> item.toAutoMediaItem() },
            0,
            startPosition,
        )
    }

    private suspend fun startMix(mix: PtvMusicAutoMix, startPositionMs: Long): MediaItemsWithStartPosition {
        val startPosition = startPositionMs.takeIf { it != C.TIME_UNSET }?.coerceAtLeast(0) ?: 0
        val queue = loadGeneratedQueueForMediaId(
            mediaId = mix.mediaId,
            startIndex = 0,
            limit = MIX_TRACK_LIMIT,
            callbackName = "startMix",
        ).tracks
        val firstItem = queue.firstOrNull()
            ?: throw UnsupportedOperationException("${mix.title} has no playable songs right now.")

        playbackController.play(item = firstItem, queue = queue, startPositionMs = startPosition)
        rememberItems(queue)
        resumeStore.markLastPlaybackError(null)
        resumeStore.markSessionCommand("mix ${mix.name} count=${queue.size}")
        Timber.i("PTV Music Auto mix started mix=${mix.name} count=${queue.size} firstItem=${firstItem.id}")

        return MediaItemsWithStartPosition(
            queue.map { item -> item.toAutoMediaItem() },
            0,
            startPosition,
        )
    }

    private suspend fun startResolvedMediaItem(
        selectedMediaItem: MediaItem,
        startPositionMs: Long,
    ): MediaItemsWithStartPosition {
        val selectedItem = selectedMediaItem.mediaId.toCachedMusicItem()
            ?: throw UnsupportedOperationException("PTV Music Auto could not resolve the selected item.")
        val activePlaybackState = playbackController.state.value
        val activeQueue = activePlaybackState.queue.filterPlayableDistinct()
        val activeQueueContainsSelection = activeQueue.any { item -> item.id == selectedItem.id }
        val selectedItemIsCurrent = activePlaybackState.currentItem?.id == selectedItem.id
        val queue = when {
            selectedItem.isPlayable && activeQueueContainsSelection -> activeQueue
            else -> selectedItem.resolvePlaybackQueue()
        }
        val itemToPlay = when {
            selectedItem.isPlayable -> selectedItem
            else -> queue.firstOrNull()
        } ?: throw UnsupportedOperationException("PTV Music Auto found no playable songs.")
        val startPosition = startPositionMs.takeIf { it != C.TIME_UNSET }?.coerceAtLeast(0)
            ?: activePlaybackState.positionMs.takeIf { selectedItemIsCurrent }?.coerceAtLeast(0)
            ?: 0
        val playbackQueue = queue.ensureContains(itemToPlay)
        val playbackIndex = playbackQueue.indexOfFirst { item -> item.id == itemToPlay.id }.coerceAtLeast(0)

        if (selectedItemIsCurrent && activeQueueContainsSelection && activePlaybackState.hasCurrent) {
            rememberItems(playbackQueue)
            Timber.i(
                "PTV Music Auto reused active queue for current item selectedMediaId=${selectedMediaItem.mediaId} " +
                    "queueSize=${playbackQueue.size} currentIndex=$playbackIndex position=${startPosition}ms",
            )
        } else {
            playbackController.play(
                item = itemToPlay,
                queue = playbackQueue,
                startPositionMs = startPosition,
            )
        }
        resumeStore.markLastPlaybackError(null)
        resumeStore.markSessionCommand(
            "play ${itemToPlay.id} activeQueue=$activeQueueContainsSelection current=$selectedItemIsCurrent",
        )

        return MediaItemsWithStartPosition(
            playbackQueue.map { item -> item.toAutoMediaItem() },
            playbackIndex,
            startPosition,
        )
    }

    private suspend fun startMoreLikeThis(startPositionMs: Long): MediaItemsWithStartPosition {
        val currentItem = playbackController.state.value.currentItem
            ?: throw UnsupportedOperationException("No PTV Music song is currently playing.")
        val startPosition = startPositionMs.takeIf { it != C.TIME_UNSET }?.coerceAtLeast(0) ?: 0
        val generatedQueue = repository.loadAutoGeneratedQueue(currentItem)
            .filter(MusicItem::isPlayable)
        val firstItem = generatedQueue.firstOrNull()
            ?: throw UnsupportedOperationException("PTV could not find similar songs right now.")

        playbackController.play(item = firstItem, queue = generatedQueue, startPositionMs = startPosition)
        rememberItems(generatedQueue)
        resumeStore.markLastPlaybackError(null)
        resumeStore.markSessionCommand("moreLikeThis ${currentItem.id} count=${generatedQueue.size}")
        Timber.i("PTV Music Auto More Like This started count=${generatedQueue.size} seed=${currentItem.id}")

        return MediaItemsWithStartPosition(
            generatedQueue.map { item -> item.toAutoMediaItem() },
            0,
            startPosition,
        )
    }

    private suspend fun loadRecommendedMixItems(): List<MediaItem> {
        val currentItem = playbackController.state.value.currentItem
        val mixes = buildList {
            add(PtvMusicAutoMix.FOR_YOU)
            add(PtvMusicAutoMix.RECENTLY_PLAYED)
            add(PtvMusicAutoMix.LIKED)
            if (currentItem?.artistIds?.isNotEmpty() == true) add(PtvMusicAutoMix.CURRENT_ARTIST)
            if (currentItem?.genres?.isNotEmpty() == true) add(PtvMusicAutoMix.CURRENT_GENRE)
            add(PtvMusicAutoMix.HEAVY_ROTATION)
            add(PtvMusicAutoMix.LATEST)
        }
        Timber.i(
            "PTV Music Auto recommended mixes loaded count=${mixes.size} " +
                "currentItemId=${currentItem?.id}",
        )
        return mixes.map { mix -> mix.toAutoMediaItem() }
    }

    private suspend fun loadGeneratedQueueForMediaId(
        mediaId: String,
        startIndex: Int,
        limit: Int,
        callbackName: String,
    ): AutoGeneratedQueueResult {
        val startedAt = System.currentTimeMillis()
        val pageLimit = limit.coerceIn(1, MIX_TRACK_LIMIT)
        val mix = PtvMusicAutoMix.fromMediaId(mediaId)
        val label = mix?.name ?: mediaId.generatedQueueDisplayName()
        Timber.i(
            "PTV Music Auto generated queue resolving callback=$callbackName selectedMediaId=$mediaId " +
                "resolvedMix=${mix?.name ?: "<none>"} start=$startIndex limit=$pageLimit",
        )

        generatedQueueCache[mediaId]
            ?.filterPlayableDistinct()
            ?.takeIf(List<MusicItem>::isNotEmpty)
            ?.let { cachedTracks ->
                return generatedQueueResult(
                    mediaId = mediaId,
                    mix = mix,
                    source = "generated-cache",
                    candidates = cachedTracks,
                    startIndex = startIndex,
                    limit = pageLimit,
                    callbackName = callbackName,
                    startedAt = startedAt,
                )
            }

        val cachedCandidates = when {
            mediaId == PtvMusicAutoIds.AUTO_PICKS -> cachedAutoPicksCandidates()
            mix != null -> cachedMixCandidates(mix)
            else -> emptyList()
        }
        if (cachedCandidates.isNotEmpty()) {
            return generatedQueueResult(
                mediaId = mediaId,
                mix = mix,
                source = "cache",
                candidates = cachedCandidates,
                startIndex = startIndex,
                limit = pageLimit,
                callbackName = callbackName,
                startedAt = startedAt,
            )
        }

        val freshCandidates = withTimeoutOrNull(AUTO_GENERATED_FRESH_TIMEOUT_MS) {
            when {
                mediaId == PtvMusicAutoIds.AUTO_PICKS -> loadFreshAutoPicksCandidates()
                mix != null -> loadFreshMixCandidates(mix)
                else -> emptyList()
            }
        }

        if (freshCandidates == null) {
            Timber.w(
                "PTV Music Auto generated queue completed=false timeout=true callback=$callbackName selectedMediaId=$mediaId " +
                    "resolvedMix=${mix?.name ?: "<none>"} timeoutMs=$AUTO_GENERATED_FRESH_TIMEOUT_MS " +
                    "durationMs=${System.currentTimeMillis() - startedAt}",
            )
            return generatedQueueResult(
                mediaId = mediaId,
                mix = mix,
                source = "timeout-empty",
                candidates = emptyList(),
                startIndex = startIndex,
                limit = pageLimit,
                callbackName = callbackName,
                startedAt = startedAt,
            )
        }

        val freshTracks = freshCandidates.filterPlayableDistinct()
        val fallbackTracks = when {
            freshTracks.isNotEmpty() -> freshTracks
            mix == PtvMusicAutoMix.FOR_YOU || mix == PtvMusicAutoMix.HEAVY_ROTATION -> activeQueueCandidates()
            else -> emptyList()
        }
        val source = when {
            freshTracks.isNotEmpty() -> "fresh"
            fallbackTracks.isNotEmpty() -> "fallback-active-queue"
            else -> "fresh-empty"
        }

        return generatedQueueResult(
            mediaId = mediaId,
            mix = mix,
            source = source,
            candidates = fallbackTracks,
            startIndex = startIndex,
            limit = pageLimit,
            callbackName = callbackName,
            startedAt = startedAt,
        )
    }

    private fun generatedQueueResult(
        mediaId: String,
        mix: PtvMusicAutoMix?,
        source: String,
        candidates: List<MusicItem>,
        startIndex: Int,
        limit: Int,
        callbackName: String,
        startedAt: Long,
    ): AutoGeneratedQueueResult {
        val playableCandidates = candidates.filterPlayableDistinct().take(MIX_TRACK_LIMIT)
        if (playableCandidates.isNotEmpty()) {
            generatedQueueCache[mediaId] = playableCandidates
            rememberItems(playableCandidates)
        }
        val returnedTracks = playableCandidates.drop(startIndex.coerceAtLeast(0)).take(limit)
        Timber.i(
            "PTV Music Auto generated queue completed=true callback=$callbackName selectedMediaId=$mediaId " +
                "resolvedMix=${mix?.name ?: "<none>"} source=$source " +
                "candidateCount=${playableCandidates.size} returnedCount=${returnedTracks.size} " +
                "durationMs=${System.currentTimeMillis() - startedAt}",
        )
        resumeStore.markBrowseStatus(
            "generated:$source:${mix?.name ?: mediaId.generatedQueueDisplayName()}",
            returnedTracks.size,
        )
        return AutoGeneratedQueueResult(
            tracks = returnedTracks,
            source = source,
            candidateCount = playableCandidates.size,
        )
    }

    private suspend fun loadFreshMixCandidates(mix: PtvMusicAutoMix): List<MusicItem> = when (mix) {
        PtvMusicAutoMix.FOR_YOU -> repository.loadBrowsePage(
            kind = MusicBrowseKind.RECOMMENDATIONS,
            startIndex = 0,
            limit = MIX_TRACK_LIMIT,
        ).items

        PtvMusicAutoMix.RECENTLY_PLAYED -> repository.loadBrowsePage(
            kind = MusicBrowseKind.RECENTLY_PLAYED,
            startIndex = 0,
            limit = MIX_TRACK_LIMIT,
        ).items

        PtvMusicAutoMix.LIKED -> repository.loadBrowsePage(
            kind = MusicBrowseKind.FAVORITES,
            startIndex = 0,
            limit = MIX_TRACK_LIMIT,
        ).items

        PtvMusicAutoMix.CURRENT_ARTIST -> {
            val currentItem = playbackController.state.value.currentItem
            repository.loadArtistTracks(
                artistIds = currentItem?.artistIds.orEmpty(),
                limit = MIX_TRACK_LIMIT,
            )
        }

        PtvMusicAutoMix.CURRENT_GENRE -> {
            val genreName = playbackController.state.value.currentItem?.genres.orEmpty().firstOrNull().orEmpty()
            repository.loadGenreTracks(genreName = genreName, limit = MIX_TRACK_LIMIT)
        }

        PtvMusicAutoMix.HEAVY_ROTATION -> repository.loadHeavyRotationTracks(limit = MIX_TRACK_LIMIT)

        PtvMusicAutoMix.LATEST -> repository.loadRecentlyAddedTracks(limit = MIX_TRACK_LIMIT)
    }

    private suspend fun loadFreshAutoPicksCandidates(): List<MusicItem> = repository.loadAutoPicksQueue(
        seed = playbackController.state.value.currentItem,
        limit = MIX_TRACK_LIMIT,
    )

    private fun cachedAutoPicksCandidates(): List<MusicItem> {
        val activeQueue = activeQueueCandidates()
        val favorites = cachedBrowseCandidates(MusicBrowseKind.FAVORITES)
        val recent = cachedBrowseCandidates(MusicBrowseKind.RECENTLY_PLAYED)
        val recommendations = cachedBrowseCandidates(MusicBrowseKind.RECOMMENDATIONS)
        val songs = cachedBrowseCandidates(MusicBrowseKind.SONGS)
        val currentItem = playbackController.state.value.currentItem

        return (
            listOfNotNull(currentItem?.takeIf(MusicItem::isPlayable)) +
                recommendations +
                favorites +
                recent +
                activeQueue +
                songs
            )
            .filterPlayableDistinct()
    }

    private fun cachedMixCandidates(mix: PtvMusicAutoMix): List<MusicItem> {
        val activeQueue = activeQueueCandidates()
        val favorites = cachedBrowseCandidates(MusicBrowseKind.FAVORITES)
        val recent = cachedBrowseCandidates(MusicBrowseKind.RECENTLY_PLAYED)
        val recommendations = cachedBrowseCandidates(MusicBrowseKind.RECOMMENDATIONS)
        val songs = cachedBrowseCandidates(MusicBrowseKind.SONGS)
        val currentItem = playbackController.state.value.currentItem

        return when (mix) {
            PtvMusicAutoMix.FOR_YOU -> recommendations + activeQueue + favorites + recent + songs

            PtvMusicAutoMix.RECENTLY_PLAYED -> recent + activeQueue

            PtvMusicAutoMix.LIKED -> favorites

            PtvMusicAutoMix.CURRENT_ARTIST -> {
                val artistIds = currentItem?.artistIds.orEmpty().toSet()
                if (artistIds.isEmpty()) {
                    emptyList()
                } else {
                    (activeQueue + songs + recent + favorites).filter { item ->
                        item.artistIds.any(artistIds::contains)
                    }
                }
            }

            PtvMusicAutoMix.CURRENT_GENRE -> {
                val genreName = currentItem?.genres.orEmpty().firstOrNull()?.normalizeAutoText().orEmpty()
                if (genreName.isBlank()) {
                    emptyList()
                } else {
                    (activeQueue + songs + recent + favorites).filter { item ->
                        item.genres.any { genre -> genre.normalizeAutoText() == genreName }
                    }
                }
            }

            PtvMusicAutoMix.HEAVY_ROTATION -> favorites + recent + recommendations + activeQueue + songs

            PtvMusicAutoMix.LATEST -> songs + activeQueue
        }.filterPlayableDistinct()
    }

    private fun cachedBrowseCandidates(kind: MusicBrowseKind): List<MusicItem> =
        repository.cachedBrowsePage(kind = kind, startIndex = 0, limit = MIX_TRACK_LIMIT)
            ?.items
            .orEmpty()

    private fun activeQueueCandidates(): List<MusicItem> = playbackController.state.value.queue.filterPlayableDistinct()

    private suspend fun loadMoreMediaItems(): List<MediaItem> {
        val items = mutableListOf<MediaItem>()
        items += PtvMusicAutoMoreItem.SHUFFLE_ALL.toAutoMediaItem()
        if (playbackController.state.value.currentItem != null) {
            items += PtvMusicAutoMoreItem.MORE_LIKE_THIS.toAutoMediaItem()
        }
        items += PtvMusicAutoMoreItem.ALL_SONGS.toAutoMediaItem()

        runCatching {
            repository.loadAutoPlaylist()
        }.onSuccess { playlist ->
            if (playlist != null) {
                rememberItems(listOf(playlist))
                items += playlist.toAutoMediaItem()
            } else {
                Timber.i("PTV Music Auto More did not find $AUTO_PLAYLIST_NAME playlist yet")
            }
        }.onFailure { error ->
            Timber.w(error, "PTV Music Auto More could not load playlists for $AUTO_PLAYLIST_NAME")
        }

        Timber.i("PTV Music Auto More loaded count=${items.size}")
        return items
    }

    private suspend fun String.toCachedMusicItem(): MusicItem? {
        val itemId = PtvMusicAutoIds.itemIdFromMediaId(this) ?: return null
        return itemCache[itemId] ?: repository.loadItem(itemId)?.also { item ->
            itemCache[item.id] = item
        }
    }

    private suspend fun MusicItem.resolvePlaybackQueue(): List<MusicItem> {
        if (isPlayable) {
            queueCache[id]?.let { return it }
            val restoredQueue = repository.loadPlaybackQueueForTrack(this)
                .filter(MusicItem::isPlayable)
                .ifEmpty { listOf(this) }
            rememberItems(restoredQueue)
            return restoredQueue
        }

        val children = repository.loadChildrenPage(
            item = this,
            startIndex = 0,
            limit = MAX_PAGE_SIZE,
        ).items
        return children
            .filter(MusicItem::isPlayable)
            .ifEmpty { listOf(this).filter(MusicItem::isPlayable) }
    }

    private suspend fun loadMediaItemsWithCache(
        cacheKey: String,
        label: String,
        block: suspend () -> List<MediaItem>,
    ): List<MediaItem> {
        awaitSavedSession(label)
        val cachedItems = browseCache[cacheKey]
        var lastError: Throwable? = null

        repeat(AUTO_BROWSE_ATTEMPTS) { attempt ->
            val result = runCatching {
                withTimeout(AUTO_BROWSE_TIMEOUT_MS) {
                    block()
                }
            }

            result.onSuccess { items ->
                if (items.isNotEmpty()) {
                    browseCache[cacheKey] = items
                    Timber.i("PTV Music Auto loaded $label source=fresh count=${items.size} attempt=${attempt + 1}")
                    resumeStore.markBrowseStatus("fresh:$label", items.size)
                    return items
                }

                if (cachedItems != null) {
                    Timber.w("PTV Music Auto $label returned empty; source=cache count=${cachedItems.size}")
                    resumeStore.markBrowseStatus("cache-empty:$label", cachedItems.size)
                    return cachedItems
                }

                Timber.i("PTV Music Auto loaded empty $label source=fresh-empty attempt=${attempt + 1}")
                resumeStore.markBrowseStatus("fresh-empty:$label", 0)
                return items
            }.onFailure { error ->
                lastError = error
                Timber.w(error, "PTV Music Auto $label failed attempt=${attempt + 1}/$AUTO_BROWSE_ATTEMPTS")
            }
        }

        if (cachedItems != null) {
            Timber.w("PTV Music Auto $label failed; source=cache count=${cachedItems.size}")
            resumeStore.markBrowseStatus("cache-failure:$label", cachedItems.size)
            return cachedItems
        }

        throw lastError ?: IllegalStateException("PTV Music Auto could not load $label")
    }

    private suspend fun awaitSavedSession(label: String, timeoutMs: Long = AUTO_SESSION_READY_TIMEOUT_MS) {
        val ready = withTimeoutOrNull(timeoutMs) {
            savedSessionReady.await()
        }

        if (ready == null) {
            Timber.w("PTV Music Auto timed out waiting for saved Jellyfin session before $label timeoutMs=$timeoutMs")
        }
    }

    private suspend fun handleToggleShuffle() {
        val enabled = !playbackController.state.value.shuffleEnabled
        Timber.i(
            "PTV Music Auto command shuffle source=androidAuto currentItemId=${playbackController.state.value.currentItem?.id} " +
                "target=$enabled",
        )
        playbackController.setShuffleEnabled(enabled, source = "androidAuto")
        Timber.i(
            "PTV Music Auto command shuffle source=androidAuto result=success " +
                "updated=${playbackController.state.value.shuffleEnabled}",
        )
    }

    private suspend fun handleCycleRepeat() {
        val repeatMode = when (playbackController.state.value.repeatMode) {
            MusicRepeatMode.NONE -> MusicRepeatMode.ALL
            MusicRepeatMode.ALL -> MusicRepeatMode.ONE
            MusicRepeatMode.ONE -> MusicRepeatMode.NONE
        }
        Timber.i(
            "PTV Music Auto command repeat source=androidAuto currentItemId=${playbackController.state.value.currentItem?.id} " +
                "target=$repeatMode",
        )
        playbackController.setRepeatMode(repeatMode, source = "androidAuto")
        Timber.i(
            "PTV Music Auto command repeat source=androidAuto result=success " +
                "updated=${playbackController.state.value.repeatMode}",
        )
    }

    private suspend fun handleToggleFavorite() {
        awaitSavedSession("custom.favorite")
        val currentItem = playbackController.state.value.currentItem
            ?: throw UnsupportedOperationException("No PTV Music song is currently playing.")
        Timber.i(
            "PTV Music Auto command favorite source=androidAuto currentItemId=${currentItem.id} " +
                "target=${!currentItem.isFavorite}",
        )
        val result = songActionHandler.execute(
            request = MusicSongActionRequest.forItem(
                action = MusicSongAction.TOGGLE_FAVORITE,
                item = currentItem,
                source = "androidAuto",
            ),
            item = currentItem,
            queue = playbackController.state.value.queue.ifEmpty { listOf(currentItem) },
        ) as MusicSongActionResult.FavoriteUpdated
        val updatedItem = result.item
        itemCache[updatedItem.id] = updatedItem
        Timber.i(
            "PTV Music Auto command favorite source=androidAuto currentItemId=${updatedItem.id} " +
                "result=success favorite=${updatedItem.isFavorite}",
        )
    }

    private suspend fun handleAddToPlaylist() {
        awaitSavedSession("custom.addToPlaylist")
        val currentItem = playbackController.state.value.currentItem
            ?: throw UnsupportedOperationException("No PTV Music song is currently playing.")
        Timber.i(
            "PTV Music Auto command addToPlaylist source=androidAuto " +
                "currentTrackTitle=${currentItem.title} currentItemId=${currentItem.id}",
        )
        runCatching {
            songActionHandler.execute(
                request = MusicSongActionRequest.forItem(
                    action = MusicSongAction.ADD_TO_PLAYLIST,
                    item = currentItem,
                    source = "androidAuto",
                ),
                item = currentItem,
                queue = playbackController.state.value.queue.ifEmpty { listOf(currentItem) },
            ).message ?: "Added to $AUTO_PLAYLIST_NAME."
        }.onSuccess { message ->
            resumeStore.markSessionCommand("addToPlaylist ${currentItem.id}: $message")
            Timber.i(
                "PTV Music Auto command addToPlaylist source=androidAuto " +
                    "currentTrackTitle=${currentItem.title} currentItemId=${currentItem.id} " +
                    "result=success message=$message",
            )
        }.onFailure { error ->
            Timber.e(
                error,
                "PTV Music Auto command addToPlaylist source=androidAuto result=failure " +
                    "currentTrackTitle=${currentItem.title} currentItemId=${currentItem.id} " +
                    "exception=${error::class.qualifiedName} reason=${error.message}",
            )
            throw error
        }
    }

    private suspend fun handleGenerateQueue() {
        awaitSavedSession("custom.generateQueue")
        val currentItem = playbackController.state.value.currentItem
            ?: throw UnsupportedOperationException("No PTV Music song is currently playing.")
        val result = songActionHandler.execute(
            request = MusicSongActionRequest.forItem(
                action = MusicSongAction.START_MIX,
                item = currentItem,
                source = "androidAuto",
            ),
            item = currentItem,
            queue = playbackController.state.value.queue.ifEmpty { listOf(currentItem) },
        ) as MusicSongActionResult.MixStarted
        val generatedQueue = result.queue
        if (generatedQueue.isEmpty()) {
            throw UnsupportedOperationException("PTV could not find similar songs right now.")
        }
        rememberItems(generatedQueue)
        resumeStore.markSessionCommand("generateQueue ${currentItem.id} count=${generatedQueue.size}")
        Timber.i("PTV Music Auto generated queue started count=${generatedQueue.size} seed=${currentItem.id}")
    }

    private fun MusicPage.toMediaItems(): List<MediaItem> {
        rememberItems(items)
        return items.map { item -> item.toAutoMediaItem() }
    }

    private fun List<MusicItem>.toMediaItems(): List<MediaItem> {
        rememberItems(this)
        return map { item -> item.toAutoMediaItem() }
    }

    private fun PtvMusicAutoCategory.activePlaybackMediaItems(startIndex: Int, limit: Int): List<MediaItem>? {
        if (this != PtvMusicAutoCategory.RECOMMENDATIONS) return null
        val playbackState = playbackController.state.value
        val queue = playbackState.queue.filter(MusicItem::isPlayable)
        if (queue.isEmpty()) return null

        val currentIndex = playbackState.currentIndex.takeIf { index -> index in queue.indices }
            ?: playbackState.currentItem?.let { currentItem ->
                queue.indexOfFirst { item -> item.id == currentItem.id }
            }?.takeIf { index -> index >= 0 }
            ?: 0
        val rotatedQueue = queue.drop(currentIndex) + queue.take(currentIndex)
        val pageItems = rotatedQueue
            .distinctBy(MusicItem::id)
            .drop(startIndex)
            .take(limit)
        if (pageItems.isEmpty()) return null

        rememberItems(pageItems)
        return pageItems.map { item -> item.toAutoMediaItem() }
    }

    private fun rememberItems(items: List<MusicItem>) {
        items.forEach { item -> itemCache[item.id] = item }
        val playableQueue = items.filter(MusicItem::isPlayable)
        playableQueue.forEach { item -> queueCache[item.id] = playableQueue }
    }

    private fun rootMediaItem(): MediaItem = MediaItem.Builder()
        .setMediaId(PtvMusicAutoIds.ROOT)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(context.getString(R.string.app_name))
                .setSubtitle("PTV Music")
                .setIsBrowsable(true)
                .setIsPlayable(false)
                .setArtworkUri(R.drawable.ic_music_note_white_24dp.asResourceUri())
                .setExtras(contentStyleExtras(grid = false, playable = false))
                .build(),
        )
        .build()

    private fun moreMediaItem(): MediaItem = MediaItem.Builder()
        .setMediaId(PtvMusicAutoIds.MORE)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle("More")
                .setSubtitle("Genres, shuffle, radio, and PTV Auto Picks")
                .setIsBrowsable(true)
                .setIsPlayable(false)
                .setArtworkUri(R.drawable.ic_music_note_white_24dp.asResourceUri())
                .setExtras(contentStyleExtras(grid = false, playable = false))
                .build(),
        )
        .build()

    private fun PtvMusicAutoCategory.toAutoMediaItem(): MediaItem = MediaItem.Builder()
        .setMediaId(toPtvMusicAutoDescriptor().mediaId)
        .setMediaMetadata(
            toPtvMusicAutoDescriptor().toMediaMetadata(
                fallbackArtworkUri = R.drawable.ic_music_note_white_24dp.asResourceUri(),
            ),
        )
        .build()

    private fun PtvMusicAutoRootItem.toAutoMediaItem(): MediaItem = MediaItem.Builder()
        .setMediaId(mediaId)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setSubtitle(subtitle)
                .setIsBrowsable(isBrowsable)
                .setIsPlayable(isPlayable)
                .setArtworkUri(R.drawable.ic_music_note_white_24dp.asResourceUri())
                .setExtras(contentStyleExtras(grid = false, playable = isPlayable))
                .build(),
        )
        .build()

    private fun PtvMusicAutoMix.toAutoMediaItem(): MediaItem = MediaItem.Builder()
        .setMediaId(mediaId)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setSubtitle(subtitle)
                .setIsBrowsable(false)
                .setIsPlayable(true)
                .setArtworkUri(R.drawable.ic_music_note_white_24dp.asResourceUri())
                .setExtras(contentStyleExtras(grid = false, playable = true))
                .build(),
        )
        .build()

    private fun PtvMusicAutoMoreItem.toAutoMediaItem(): MediaItem = MediaItem.Builder()
        .setMediaId(mediaId)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setSubtitle(subtitle)
                .setIsBrowsable(isBrowsable)
                .setIsPlayable(isPlayable)
                .setArtworkUri(R.drawable.ic_music_note_white_24dp.asResourceUri())
                .setExtras(contentStyleExtras(grid = false, playable = isPlayable))
                .build(),
        )
        .build()

    private fun MusicItem.toAutoMediaItem(): MediaItem = MediaItem.Builder()
        .setMediaId(toPtvMusicAutoDescriptor().mediaId)
        .setMediaMetadata(toPtvMusicAutoDescriptor().toMediaMetadata())
        .build()

    private fun PtvMusicAutoItemDescriptor.toMediaMetadata(fallbackArtworkUri: Uri? = null): MediaMetadata =
        MediaMetadata.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            .setArtist(artist)
            .setAlbumTitle(album)
            .setIsBrowsable(isBrowsable)
            .setIsPlayable(isPlayable)
            .setArtworkUri(artworkUri?.let(Uri::parse) ?: fallbackArtworkUri)
            .setExtras(contentStyleExtras(grid = grid, playable = isPlayable))
            .build()

    private fun Int.asResourceUri(): Uri = Uri.Builder()
        .scheme(ContentResolver.SCHEME_ANDROID_RESOURCE)
        .authority(context.resources.getResourcePackageName(this))
        .appendPath(context.resources.getResourceTypeName(this))
        .appendPath(context.resources.getResourceEntryName(this))
        .build()

    private fun contentStyleExtras(grid: Boolean, playable: Boolean): Bundle = Bundle().apply {
        val style = when (grid) {
            true -> MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM
            false -> MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM
        }
        if (playable) {
            putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_PLAYABLE, style)
        } else {
            putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_BROWSABLE, style)
        }
    }

    private fun Int.toSafePageSize(): Int = when {
        this <= 0 -> DEFAULT_PAGE_SIZE
        else -> coerceAtMost(MAX_PAGE_SIZE)
    }

    private fun Int.toStartIndex(pageSize: Int): Int = coerceAtLeast(0) * pageSize.toSafePageSize()

    private fun List<MusicItem>.ensureContains(item: MusicItem): List<MusicItem> = when {
        any { queuedItem -> queuedItem.id == item.id } -> this
        else -> listOf(item) + this
    }

    private fun List<MusicItem>.filterPlayableDistinct(): List<MusicItem> =
        filter(MusicItem::isPlayable).distinctBy(MusicItem::id)

    private fun String.isGeneratedQueueMediaId(): Boolean =
        this == PtvMusicAutoIds.AUTO_PICKS || PtvMusicAutoMix.fromMediaId(this) != null

    private fun String.generatedQueueDisplayName(): String = when {
        this == PtvMusicAutoIds.AUTO_PICKS -> AUTO_PLAYLIST_NAME
        else -> PtvMusicAutoMix.fromMediaId(this)?.title ?: this
    }

    private fun String.normalizeAutoText(): String = lowercase()
        .replace("&", " and ")
        .replace("'", "")
        .replace(Regex("[^a-z0-9]+"), " ")
        .trim()
        .replace(Regex("\\s+"), " ")

    private companion object {
        const val AUTO_BROWSE_ATTEMPTS = 2
        const val AUTO_BROWSE_TIMEOUT_MS = 12_000L
        const val AUTO_GENERATED_FRESH_TIMEOUT_MS = 3_500L
        const val AUTO_SELECTION_TIMEOUT_MS = 4_500L
        const val AUTO_SELECTION_SESSION_READY_TIMEOUT_MS = 750L
        const val AUTO_SESSION_READY_TIMEOUT_MS = 4_000L
        const val DEFAULT_PAGE_SIZE = 24
        const val DEFAULT_SEARCH_RESULT_COUNT = 50
        const val MIX_TRACK_LIMIT = 50
        const val MAX_PAGE_SIZE = 100
        const val AUTO_PLAYLIST_NAME = "PTV Auto Picks"
    }
}

internal fun isPtvMusicControllerAllowed(
    controllerIsTrusted: Boolean,
    controllerUid: Int,
    applicationUid: Int,
): Boolean = controllerIsTrusted || (controllerUid >= 0 && controllerUid == applicationUid)

private data class AutoGeneratedQueueResult(val tracks: List<MusicItem>, val source: String, val candidateCount: Int)

@UnstableApi
internal object PtvMusicAutoCommand {
    const val ACTION_TOGGLE_SHUFFLE = "org.piggietv.music.AUTO_TOGGLE_SHUFFLE"
    const val ACTION_CYCLE_REPEAT = "org.piggietv.music.AUTO_CYCLE_REPEAT"
    const val ACTION_TOGGLE_FAVORITE = "org.piggietv.music.AUTO_TOGGLE_FAVORITE"
    const val ACTION_ADD_TO_PLAYLIST = "org.piggietv.music.AUTO_ADD_TO_PLAYLIST"
    const val ACTION_GENERATE_QUEUE = "org.piggietv.music.AUTO_GENERATE_QUEUE"

    val actions: List<String> = listOf(
        ACTION_TOGGLE_SHUFFLE,
        ACTION_CYCLE_REPEAT,
        ACTION_TOGGLE_FAVORITE,
        ACTION_ADD_TO_PLAYLIST,
        ACTION_GENERATE_QUEUE,
    )

    val commands: List<SessionCommand> by lazy {
        actions.map { action -> SessionCommand(action, Bundle.EMPTY) }
    }

    fun songActionFor(customAction: String): MusicSongAction? = when (customAction) {
        ACTION_TOGGLE_FAVORITE -> MusicSongAction.TOGGLE_FAVORITE
        ACTION_ADD_TO_PLAYLIST -> MusicSongAction.ADD_TO_PLAYLIST
        ACTION_GENERATE_QUEUE -> MusicSongAction.START_MIX
        else -> null
    }

    val buttons: List<CommandButton> by lazy {
        buttonsFor()
    }

    fun buttonsFor(state: MusicPlaybackState? = null): List<CommandButton> {
        val repeatIcon = when (state?.repeatMode) {
            MusicRepeatMode.ALL -> CommandButton.ICON_REPEAT_ALL
            MusicRepeatMode.ONE -> CommandButton.ICON_REPEAT_ONE
            else -> CommandButton.ICON_REPEAT_OFF
        }
        val shuffleIcon = when (state?.shuffleEnabled) {
            true -> CommandButton.ICON_SHUFFLE_ON
            else -> CommandButton.ICON_SHUFFLE_OFF
        }
        val favoriteIcon = when (state?.currentItem?.isFavorite) {
            true -> CommandButton.ICON_HEART_FILLED
            else -> CommandButton.ICON_HEART_UNFILLED
        }

        return listOf(
            commandButton(shuffleIcon, if (state?.shuffleEnabled == true) "Shuffle on" else "Shuffle", commands[0]),
            commandButton(repeatIcon, state?.repeatMode?.repeatDisplayName() ?: "Repeat", commands[1]),
            commandButton(favoriteIcon, if (state?.currentItem?.isFavorite == true) "Liked" else "Like", commands[2]),
            commandButton(CommandButton.ICON_PLAYLIST_ADD, "Add to playlist", commands[3]),
            commandButton(CommandButton.ICON_RADIO, "PTV Radio", commands[4]),
        )
    }

    private fun MusicRepeatMode.repeatDisplayName(): String = when (this) {
        MusicRepeatMode.NONE -> "Repeat"
        MusicRepeatMode.ALL -> "Repeat all"
        MusicRepeatMode.ONE -> "Repeat one"
    }

    private fun commandButton(icon: Int, displayName: String, command: SessionCommand): CommandButton =
        CommandButton.Builder(icon)
            .setDisplayName(displayName)
            .setSessionCommand(command)
            .setSlots(CommandButton.SLOT_OVERFLOW)
            .build()
}

private fun runtimeInstanceId(value: Any): String = System.identityHashCode(value).toString(16)
