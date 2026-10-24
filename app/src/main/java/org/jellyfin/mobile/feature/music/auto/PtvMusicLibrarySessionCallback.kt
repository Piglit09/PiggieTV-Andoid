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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.guava.future
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.jellyfin.mobile.R
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
import org.jellyfin.mobile.feature.music.MusicRepeatMode
import org.jellyfin.mobile.feature.music.MusicRepository
import org.jellyfin.mobile.feature.music.MusicSongAction
import org.jellyfin.mobile.feature.music.MusicSongActionHandler
import org.jellyfin.mobile.feature.music.MusicSongActionRequest
import org.jellyfin.mobile.feature.music.MusicSongActionResult
import org.jellyfin.sdk.model.UUID
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.serializer.toUUIDOrNull
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
    private val savedSessionReady: Deferred<UUID?>,
    private val authState: StateFlow<MobileAuthState>,
    private val playerDispatcher: CoroutineDispatcher = Dispatchers.Main,
    private val serverUrlForLocalId: (Long) -> String? = { null },
) : MediaLibrarySession.Callback {
    private val callbackId = runtimeInstanceId(this)
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val itemCache = ConcurrentHashMap<Pair<PtvMusicAutoBrowseScope, UUID>, MusicItem>()
    private val queueCache = ConcurrentHashMap<Pair<PtvMusicAutoBrowseScope, UUID>, List<MusicItem>>()
    private val browseCache = ConcurrentHashMap<Pair<PtvMusicAutoBrowseScope, String>, List<MediaItem>>()
    private val generatedQueueCache = ConcurrentHashMap<Pair<PtvMusicAutoBrowseScope, String>, List<MusicItem>>()
    private val browseScopeLock = Any()
    private var observedBrowseScope: PtvMusicAutoBrowseScope? = null
    private var observedLocalOwner: PtvMusicAutoQueueOwner? = null
    private var localOwnerChangedWithinScope = false
    private var lastAuthenticatedScope: PtvMusicAutoBrowseScope? = null
    private var lastAuthenticatedOwner: PtvMusicAutoQueueOwner? = null
    private var pendingPriorOwner: PtvMusicAutoQueueOwner? = null
    private var selectedListeningMode: Pair<PtvMusicAutoBrowseScope, MusicListeningMode>? = null
    private var generatedCacheSeed: Pair<PtvMusicAutoBrowseScope, UUID?>? = null
    private var recommendationGeneration = 0L
    private var recommendationSession: ActiveRecommendationSession? = null
    private var recommendationStateJob: Job? = null
    private var recommendationTransitionJob: Job? = null
    private var recommendationRefillJob: Job? = null

    private data class AutoSelectionIdentity(
        val scope: PtvMusicAutoBrowseScope,
        val localOwner: PtvMusicAutoQueueOwner,
    )

    private data class PlaylistTrackSelection(val playlistId: UUID, val trackIndex: Int, val trackId: UUID)

    private class ActiveRecommendationSession(
        val identity: AutoSelectionIdentity,
        val generation: Long,
        val sourceMediaId: String,
        var context: MusicRecommendationSessionContext,
        val expectedItemIds: MutableSet<UUID>,
        var activated: Boolean = false,
        var lastRefillItemId: UUID? = null,
        var refillFailureItemId: UUID? = null,
        var refillFailureCount: Int = 0,
        var refillInFlight: Boolean = false,
        var refillSerial: Long = 0,
    )

    private data class RecommendationRefillRequest(
        val session: ActiveRecommendationSession,
        val context: MusicRecommendationSessionContext,
        val currentItem: MusicItem,
        val expectedItemIds: Set<UUID>,
        val serial: Long,
    )

    fun onAuthenticationChanged() {
        syncBrowseScope()
    }

    fun onServiceDestroyed() {
        synchronized(browseScopeLock) { clearRecommendationSessionLocked() }
        ioScope.cancel()
    }

    /** Rebuilds only the recommendation profile proven to belong to the restored authenticated queue. */
    @Suppress("CyclomaticComplexMethod", "LongMethod", "ReturnCount")
    suspend fun restoreSavedRecommendationSession(): Boolean {
        val scope = syncBrowseScope() ?: return false
        val identity = runCatching { captureSelectionIdentity(scope) }.getOrNull() ?: return false
        val state = playbackController.state.value
        val saved = resumeStore.loadPlaybackState() ?: return false
        val source = saved.queueProvenance ?: return false
        val snapshot = source.recommendation ?: return false
        val sourceMatchesQueue = source.kind == PtvMusicAutoQueueProvenance.RECOMMENDATION &&
            source.queueSha256 == saved.fullQueueSha256 && source.matchesQueue(state.queue)
        val queueOwnerMatches = saved.queueOwnerOrNull() == identity.localOwner &&
            state.queueOwner == identity.localOwner && playbackController.isCurrentQueueOwnedByAuthenticatedUser()
        val sourceIsSupported = snapshot.sourceMediaId in
            setOf(PtvMusicAutoIds.AUTO_PICKS, PtvMusicAutoIds.MORE_LIKE_THIS)
        if (!sourceMatchesQueue || !queueOwnerMatches || !sourceIsSupported) {
            return false
        }
        val mode = MusicListeningMode.entries.firstOrNull { it.name == snapshot.selectedMode } ?: return false
        val seedId = snapshot.seedId.toUUIDOrNull() ?: return false
        val recentIds = snapshot.recentPlayedIds.map(String::toUUIDOrNull)
        val skippedIds = snapshot.skippedIds.map(String::toUUIDOrNull)
        val recommendedIds = snapshot.recommendedIds.map(String::toUUIDOrNull)
        if (recentIds.any { it == null } || skippedIds.any { it == null } || recommendedIds.any { it == null }) {
            return false
        }
        val hydratedIds = (listOf(seedId) + recentIds.filterNotNull()).distinct()
        val hydrated = runCatching { repository.loadItems(hydratedIds) }
            .getOrNull()?.filter(MusicItem::isPlayable)?.associateBy(MusicItem::id) ?: return false
        val seed = hydrated[seedId] ?: return false
        val recent = recentIds.map { id -> hydrated[id] ?: return false }
        val serverUrl = serverUrlForLocalId(identity.localOwner.serverId)?.trimEnd('/') ?: return false
        val userId = identity.scope.userId.toUUIDOrNull() ?: return false
        val restored = runCatching {
            MusicRecommendationSessionContext.restore(
                owner = MusicRecommendationSessionOwner(serverUrl, userId),
                seed = seed,
                mode = mode,
                recentPlayedItems = recent,
                skippedIds = skippedIds.filterNotNull(),
                recommendedIds = recommendedIds.filterNotNull(),
                catalogOffset = snapshot.catalogOffset,
                catalogExhausted = snapshot.catalogExhausted,
                revision = snapshot.revision,
            )
        }.getOrNull() ?: return false
        if (!recommendationOwnerMatchesIdentity(restored, identity) ||
            !restored.recommendedIds.containsAll(state.queue.map(MusicItem::id))
        ) {
            return false
        }

        return synchronized(browseScopeLock) {
            val current = playbackController.state.value
            val selectionIsCurrent = isSelectionIdentityCurrent(identity) &&
                playbackController.isCurrentQueueOwnedByAuthenticatedUser() && current.queueOwner == identity.localOwner
            val savedQueueIsCurrent = source.matchesQueue(current.queue) &&
                resumeStore.loadPlaybackState()?.queueProvenance == source
            if (!selectionIsCurrent || !savedQueueIsCurrent) {
                return@synchronized false
            }
            if (recommendationSession != null) return@synchronized true
            clearRecommendationSessionLocked()
            selectedListeningMode = scope to mode
            recommendationSession = ActiveRecommendationSession(
                identity = identity,
                generation = recommendationGeneration,
                sourceMediaId = snapshot.sourceMediaId,
                context = restored,
                expectedItemIds = current.queue.map(MusicItem::id).toMutableSet(),
                activated = true,
            )
            recommendationStateJob = ioScope.launch {
                playbackController.state.collect(::observeRecommendationPlaybackState)
            }
            recommendationTransitionJob = ioScope.launch {
                playbackController.transitionEvents.collect(::recordVerifiedRecommendationTransition)
            }
            true
        }
    }

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
            syncBrowseScope() == null -> rootMediaItem()
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

    // Each explicit parent kind has its own browse contract; keep the dispatch visible here.
    @Suppress("CyclomaticComplexMethod", "LongMethod")
    override fun onGetChildren(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        parentId: String,
        page: Int,
        pageSize: Int,
        params: LibraryParams?,
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = ioScope.future {
        val browseScope = syncBrowseScope()
        if (parentId != PtvMusicAutoIds.ROOT && browseScope == null) {
            return@future unavailableBrowseResult()
        }
        runCatching {
            val startIndex = page.toStartIndex(pageSize)
            val limit = pageSize.toSafePageSize()
            when {
                parentId == PtvMusicAutoIds.ROOT -> {
                    val rootItems = ptvMusicAutoRootItemsFor(authState.value).map { item -> item.toAutoMediaItem() }
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
                    ) { _ ->
                        loadRecommendedMixItems().drop(startIndex).take(limit)
                    }
                }

                parentId == PtvMusicAutoIds.LISTENING_MODES -> {
                    val scope = requireNotNull(browseScope)
                    val selected = listeningModeFor(scope)
                    PtvMusicAutoListeningMode.entries
                        .map { mode -> mode.toAutoMediaItem(selected = mode.listeningMode == selected) }
                        .drop(startIndex)
                        .take(limit)
                }

                PtvMusicAutoListeningMode.fromMediaId(parentId) != null -> {
                    val mode = requireNotNull(PtvMusicAutoListeningMode.fromMediaId(parentId))
                    selectListeningMode(requireNotNull(browseScope), mode.listeningMode)
                    loadGeneratedQueueForMediaId(
                        mediaId = PtvMusicAutoIds.AUTO_PICKS,
                        startIndex = startIndex,
                        limit = limit,
                        callbackName = "onGetChildren:mode:${mode.name}",
                    ).let { result -> result.tracks.map { item -> item.toAutoMediaItem() } }
                }

                parentId == PtvMusicAutoIds.AUTO_PICKS -> {
                    loadGeneratedQueueForMediaId(
                        mediaId = parentId,
                        startIndex = startIndex,
                        limit = limit,
                        callbackName = "onGetChildren",
                    ).let { result -> result.tracks.map { item -> item.toAutoMediaItem() } }
                }

                parentId == PtvMusicAutoIds.MORE -> {
                    loadMediaItemsWithCache(
                        cacheKey = "more:$startIndex:$limit:${playbackController.state.value.currentItem?.id}",
                        label = "more",
                    ) { scope ->
                        loadMoreMediaItems(scope).drop(startIndex).take(limit)
                    }
                }

                PtvMusicAutoCategory.fromMediaId(parentId) != null -> {
                    val category = requireNotNull(PtvMusicAutoCategory.fromMediaId(parentId))
                    val mediaItems = loadMediaItemsWithCache(
                        cacheKey = "category:$parentId:$startIndex:$limit",
                        label = "category ${category.name}",
                    ) { scope ->
                        repository.loadBrowsePage(
                            kind = category.browseKind,
                            startIndex = startIndex,
                            limit = limit,
                        ).toMediaItems(scope)
                    }
                    if (mediaItems.isEmpty()) {
                        category.activePlaybackMediaItems(
                            startIndex = startIndex,
                            limit = limit,
                            scope = requireNotNull(browseScope),
                        )?.let { activeItems ->
                            Timber.i(
                                "PTV Music Auto category ${category.name} using fallback active shared queue " +
                                    "count=${activeItems.size} current=${playbackController.state.value.currentItem?.id} " +
                                    "callbackId=$callbackId",
                            )
                            resumeStore.markBrowseStatus("fallback-activeQueue:${category.name}", activeItems.size)
                            activeItems
                        } ?: mediaItems
                    } else {
                        mediaItems
                    }
                }

                PtvMusicAutoMix.fromMediaId(parentId) != null -> {
                    loadGeneratedQueueForMediaId(
                        mediaId = parentId,
                        startIndex = startIndex,
                        limit = limit,
                        callbackName = "onGetChildren",
                    ).let { result -> result.tracks.map { item -> item.toAutoMediaItem() } }
                }

                else -> {
                    loadMediaItemsWithCache(
                        cacheKey = "children:$parentId:$startIndex:$limit",
                        label = "children $parentId",
                    ) { scope ->
                        val item = parentId.toCachedMusicItem(scope)
                        val musicPage = item?.let {
                            repository.loadChildrenPage(
                                item = it,
                                startIndex = startIndex,
                                limit = limit,
                            )
                        } ?: MusicPage(emptyList(), totalCount = 0, startIndex = 0)
                        if (item?.type == BaseItemKind.PLAYLIST) {
                            musicPage.toPlaylistMediaItems(scope, item.id)
                        } else {
                            musicPage.toMediaItems(scope)
                        }
                    }
                }
            }
        }.fold(
            onSuccess = { items ->
                if (parentId != PtvMusicAutoIds.ROOT && syncBrowseScope() != browseScope) {
                    unavailableBrowseResult()
                } else {
                    LibraryResult.ofItemList(items, params)
                }
            },
            onFailure = { error ->
                val message = "PTV Music Auto browse failed for $parentId page=$page pageSize=$pageSize"
                Timber.e(error, message)
                resumeStore.markLastPlaybackError(error.message ?: message)
                if (syncBrowseScope() != browseScope) {
                    unavailableBrowseResult()
                } else {
                    val playlistParentId = PtvMusicAutoIds.itemIdFromMediaId(parentId)
                    val isPlaylistBrowse = parentId == PtvMusicAutoCategory.PLAYLISTS.mediaId ||
                        playlistParentId?.let { itemCache[browseScope to it]?.type == BaseItemKind.PLAYLIST } == true
                    LibraryResult.ofError(
                        if (isPlaylistBrowse || error is java.io.IOException) {
                            SessionError(SessionError.ERROR_IO, "PTV could not load this music row. Try again.")
                        } else {
                            SessionError(SessionError.ERROR_NOT_SUPPORTED, "PTV could not load this music row.")
                        },
                    )
                }
            },
        )
    }

    override fun onGetItem(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        mediaId: String,
    ): ListenableFuture<LibraryResult<MediaItem>> = ioScope.future {
        syncBrowseScope()
        val statusItem = ptvMusicAutoRootItemsFor(authState.value)
            .firstOrNull { item -> item.mediaId == mediaId && mediaId.startsWith(PTV_AUTO_STATUS_PREFIX) }
        when {
            mediaId == PtvMusicAutoIds.ROOT -> LibraryResult.ofItem(rootMediaItem(), null)

            statusItem != null -> LibraryResult.ofItem(statusItem.toAutoMediaItem(), null)

            ptvMusicAutoBrowseScope(authState.value) == null -> LibraryResult.ofError(
                SessionError(SessionError.ERROR_NOT_SUPPORTED, "Open PiggieTV on your phone to reconnect music."),
            )

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

            PtvMusicAutoListeningMode.fromMediaId(mediaId) != null -> {
                val scope = requireNotNull(syncBrowseScope())
                val mode = requireNotNull(PtvMusicAutoListeningMode.fromMediaId(mediaId))
                LibraryResult.ofItem(
                    mode.toAutoMediaItem(selected = mode.listeningMode == listeningModeFor(scope)),
                    null,
                )
            }

            PtvMusicAutoMoreItem.entries.any { item -> item.mediaId == mediaId } -> {
                LibraryResult.ofItem(
                    requireNotNull(PtvMusicAutoMoreItem.entries.firstOrNull { item -> item.mediaId == mediaId })
                        .toAutoMediaItem(),
                    null,
                )
            }

            else -> {
                val item = runCatching {
                    val scope = awaitSavedSession("getItem")
                    mediaId.toCachedMusicItem(scope).takeIf { syncBrowseScope() == scope }
                }.getOrNull()
                if (item == null) {
                    LibraryResult.ofError(
                        SessionError(
                            SessionError.ERROR_BAD_VALUE,
                            "PTV could not load that music item. Reconnect on your phone and try again.",
                        ),
                    )
                } else {
                    LibraryResult.ofItem(item.toAutoMediaItem(mediaId), null)
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
        val count = if (query.isBlank() || syncBrowseScope() == null) 0 else DEFAULT_SEARCH_RESULT_COUNT
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
        val searchScope = syncBrowseScope() ?: return@future unavailableBrowseResult()
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
                ) { scope ->
                    repository.searchMusicPage(
                        query = query,
                        startIndex = startIndex,
                        limit = limit,
                    ).toMediaItems(scope)
                }
            }.fold(
                onSuccess = { items ->
                    if (syncBrowseScope() == searchScope) {
                        LibraryResult.ofItemList(items, params)
                    } else {
                        unavailableBrowseResult()
                    }
                },
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

    // The timeout, result, and account-switch branches are one Media3 callback transaction.
    @Suppress("LongMethod")
    override fun onSetMediaItems(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo,
        mediaItems: List<MediaItem>,
        startIndex: Int,
        startPositionMs: Long,
    ): ListenableFuture<MediaItemsWithStartPosition> = ioScope.future {
        syncBrowseScope()
        var selectionIdentity: AutoSelectionIdentity? = null
        val callbackStartedAt = System.currentTimeMillis()
        val selectedMediaItem = mediaItems.getOrNull(startIndex.takeIf { it != C.INDEX_UNSET } ?: 0)
        Timber.i(
            "PTV Music Auto onSetMediaItems selectedMediaId=${selectedMediaItem?.mediaId ?: "<none>"} " +
                "startIndex=$startIndex itemCount=${mediaItems.size}",
        )

        runCatching {
            withTimeoutOrNull(AUTO_SELECTION_TIMEOUT_MS) {
                selectionIdentity = captureSelectionIdentity(
                    awaitSavedSession("setMediaItems", AUTO_SELECTION_SESSION_READY_TIMEOUT_MS),
                )
                startSelectedMediaItem(
                    selectedMediaItem = selectedMediaItem
                        ?: throw UnsupportedOperationException("PTV Music Auto did not receive a playable item."),
                    startPositionMs = startPositionMs,
                    callbackStartedAt = callbackStartedAt,
                    identity = requireNotNull(selectionIdentity),
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
                if (selectionIdentity?.let(::isSelectionIdentityCurrent) != true) {
                    return@future emptyMediaItemsWithStartPosition()
                }
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
                if (selectionIdentity?.let(::isSelectionIdentityCurrent) != true) {
                    emptyMediaItemsWithStartPosition()
                } else {
                    currentMediaItemsWithStartPosition()
                }
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
                ).let { result -> result.tracks.toMediaItems(result.scope) }
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
        identity: AutoSelectionIdentity,
    ): MediaItemsWithStartPosition {
        val selectionGeneration = synchronized(browseScopeLock) {
            ++recommendationGeneration
        }
        Timber.i(
            "PTV Music Auto resolving selection selectedMediaId=${selectedMediaItem.mediaId} " +
                "resolvedMix=${PtvMusicAutoMix.fromMediaId(selectedMediaItem.mediaId)?.name ?: "<none>"}",
        )
        return when (selectedMediaItem.mediaId) {
            PtvMusicAutoIds.SHUFFLE_ALL -> startShuffleAll(startPositionMs, identity, selectionGeneration)

            PtvMusicAutoIds.MORE_LIKE_THIS -> startMoreLikeThis(startPositionMs, identity, selectionGeneration)

            PtvMusicAutoIds.AUTO_PICKS -> startGeneratedQueueMediaId(
                mediaId = selectedMediaItem.mediaId,
                startPositionMs = startPositionMs,
                callbackStartedAt = callbackStartedAt,
                identity = identity,
                selectionGeneration = selectionGeneration,
            )

            else -> PtvMusicAutoMix.fromMediaId(selectedMediaItem.mediaId)
                ?.let {
                    startGeneratedQueueMediaId(
                        mediaId = selectedMediaItem.mediaId,
                        startPositionMs = startPositionMs,
                        callbackStartedAt = callbackStartedAt,
                        identity = identity,
                        selectionGeneration = selectionGeneration,
                    )
                }
                ?: startResolvedMediaItem(selectedMediaItem, startPositionMs, identity, selectionGeneration)
        }
    }

    private suspend fun startGeneratedQueueMediaId(
        mediaId: String,
        startPositionMs: Long,
        callbackStartedAt: Long,
        identity: AutoSelectionIdentity,
        selectionGeneration: Long,
    ): MediaItemsWithStartPosition {
        val listeningMode = listeningModeFor(identity.scope)
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

        check(queueResult.scope == identity.scope) { "PTV Music Auto account changed while selecting music." }
        check(listeningModeFor(identity.scope) == listeningMode) {
            "PTV Music Auto listening mode changed while selecting music."
        }
        requireCurrentSelectionIdentity(identity)
        rememberItems(queue, identity.scope)
        requireCurrentSelectionIdentity(identity)
        if (mediaId == PtvMusicAutoIds.AUTO_PICKS) {
            val context = repository.beginAutoRecommendationSession(firstItem, queue, listeningMode)
            startGeneratedRecommendationPlayback(
                firstItem,
                queue,
                startPosition,
                identity,
                selectionGeneration,
                context,
            )
        } else {
            commitReplacementSelection(identity, selectionGeneration) {
                playbackController.play(
                    item = firstItem,
                    queue = queue,
                    startPositionMs = startPosition,
                    expectedOwner = identity.localOwner,
                )
            }
        }
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

    private fun startGeneratedRecommendationPlayback(
        firstItem: MusicItem,
        queue: List<MusicItem>,
        startPosition: Long,
        identity: AutoSelectionIdentity,
        selectionGeneration: Long,
        context: MusicRecommendationSessionContext,
    ) {
        activateRecommendationSession(
            identity,
            selectionGeneration,
            PtvMusicAutoIds.AUTO_PICKS,
            context,
            queue,
        ) { source ->
            playbackController.play(
                item = firstItem,
                queue = queue,
                startPositionMs = startPosition,
                expectedOwner = identity.localOwner,
                queueProvenance = source,
            )
        }
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
        val scope = runCatching { awaitSavedSession("playbackResumption") }.getOrNull()
            ?: return@future emptyMediaItemsWithStartPosition()
        runCatching {
            withContext(playerDispatcher) {
                resumeCoordinator.restoreQueueIfNeeded()
            }
            if (!isTrustedPlaybackScope(scope)) {
                throw IllegalStateException("PTV Music Auto has no queue owned by this account")
            }
            val state = playbackController.state.value
            val currentItem = state.currentItem
                ?: throw UnsupportedOperationException("PTV Music has no track to resume.")
            val queue = state.queue.ifEmpty { listOf(currentItem) }
            val index = state.currentIndex.takeIf { it in queue.indices }
                ?: queue.indexOfFirst { item -> item.id == currentItem.id }.coerceAtLeast(0)
            val savedSource = resumeStore.loadPlaybackState()?.queueProvenance
            val playlistId = savedSource
                ?.takeIf { source -> source.kind == PtvMusicAutoQueueProvenance.PLAYLIST && source.matchesQueue(queue) }
                ?.playlistId?.toUUIDOrNull()
            val response = MediaItemsWithStartPosition(
                queue.mapIndexed { itemIndex, item ->
                    if (playlistId == null) {
                        item.toAutoMediaItem()
                    } else {
                        item.toAutoMediaItem(playlistTrackMediaId(playlistId, itemIndex, item.id))
                    }
                },
                index,
                state.positionMs,
            ).takeIf { isTrustedPlaybackScope(scope) } ?: emptyMediaItemsWithStartPosition()
            if (savedSource?.kind == PtvMusicAutoQueueProvenance.RECOMMENDATION &&
                response.mediaItems.isNotEmpty()
            ) {
                // Media3 needs the queue promptly. Hydrate the persisted recommendation profile
                // separately; that path rechecks the account and queue before activation.
                ioScope.launch {
                    runCatching { restoreSavedRecommendationSession() }
                        .onFailure { Timber.w(it, "PTV Music Auto recommendation profile restore unavailable") }
                }
            }
            response
        }.getOrElse { error ->
            Timber.w(error, "PTV Music Auto playback resumption unavailable")
            emptyMediaItemsWithStartPosition()
        }
    }

    private fun emptyMediaItemsWithStartPosition() =
        MediaItemsWithStartPosition(emptyList(), C.INDEX_UNSET, C.TIME_UNSET)

    private fun currentMediaItemsWithStartPosition(): MediaItemsWithStartPosition {
        val scope = syncBrowseScope() ?: return emptyMediaItemsWithStartPosition()
        if (!isTrustedPlaybackScope(scope)) return emptyMediaItemsWithStartPosition()
        val state = playbackController.state.value
        val queue = state.queue.filter(MusicItem::isPlayable)
        if (queue.isEmpty()) {
            Timber.w("PTV Music Auto returning empty current selection fallback")
            return emptyMediaItemsWithStartPosition()
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
        ).takeIf { isTrustedPlaybackScope(scope) } ?: emptyMediaItemsWithStartPosition()
    }

    private suspend fun startShuffleAll(
        startPositionMs: Long,
        identity: AutoSelectionIdentity,
        selectionGeneration: Long,
    ): MediaItemsWithStartPosition {
        val startPosition = startPositionMs.takeIf { it != C.TIME_UNSET }?.coerceAtLeast(0) ?: 0
        val queue = repository.loadBrowsePage(
            kind = MusicBrowseKind.SONGS,
            startIndex = 0,
            limit = MIX_TRACK_LIMIT,
        ).items.filter(MusicItem::isPlayable)
        val itemToPlay = queue.firstOrNull()
            ?: throw UnsupportedOperationException("PTV Music Auto could not find songs to shuffle.")

        requireCurrentSelectionIdentity(identity)
        rememberItems(queue, identity.scope)
        commitReplacementSelection(identity, selectionGeneration) {
            playbackController.play(
                item = itemToPlay,
                queue = queue,
                startPositionMs = startPosition,
                shuffle = true,
                expectedOwner = identity.localOwner,
            )
        }
        resumeStore.markLastPlaybackError(null)
        resumeStore.markSessionCommand("shuffleAll count=${queue.size}")
        Timber.i("PTV Music Auto Shuffle All started count=${queue.size} firstItem=${itemToPlay.id}")

        return MediaItemsWithStartPosition(
            queue.map { item -> item.toAutoMediaItem() },
            queue.indexOfFirst { item -> item.id == itemToPlay.id }.coerceAtLeast(0),
            startPosition,
        )
    }

    private suspend fun startAutoPicks(
        startPositionMs: Long,
        identity: AutoSelectionIdentity,
    ): MediaItemsWithStartPosition {
        val startPosition = startPositionMs.takeIf { it != C.TIME_UNSET }?.coerceAtLeast(0) ?: 0
        val queueResult = loadGeneratedQueueForMediaId(
            mediaId = PtvMusicAutoIds.AUTO_PICKS,
            startIndex = 0,
            limit = MIX_TRACK_LIMIT,
            callbackName = "startAutoPicks",
        )
        val queue = queueResult.tracks
        val firstItem = queue.firstOrNull()
            ?: throw UnsupportedOperationException("PTV Auto Picks has no playable songs yet.")

        check(queueResult.scope == identity.scope) { "PTV Music Auto account changed while selecting music." }
        requireCurrentSelectionIdentity(identity)
        rememberItems(queue, identity.scope)
        requireCurrentSelectionIdentity(identity)
        playbackController.play(
            item = firstItem,
            queue = queue,
            startPositionMs = startPosition,
            expectedOwner = identity.localOwner,
        )
        resumeStore.markLastPlaybackError(null)
        resumeStore.markSessionCommand("autoPicks count=${queue.size}")
        Timber.i("PTV Music Auto PTV Auto Picks started count=${queue.size} firstItem=${firstItem.id}")

        return MediaItemsWithStartPosition(
            queue.map { item -> item.toAutoMediaItem() },
            0,
            startPosition,
        )
    }

    private suspend fun startMix(
        mix: PtvMusicAutoMix,
        startPositionMs: Long,
        identity: AutoSelectionIdentity,
    ): MediaItemsWithStartPosition {
        val startPosition = startPositionMs.takeIf { it != C.TIME_UNSET }?.coerceAtLeast(0) ?: 0
        val queueResult = loadGeneratedQueueForMediaId(
            mediaId = mix.mediaId,
            startIndex = 0,
            limit = MIX_TRACK_LIMIT,
            callbackName = "startMix",
        )
        val queue = queueResult.tracks
        val firstItem = queue.firstOrNull()
            ?: throw UnsupportedOperationException("${mix.title} has no playable songs right now.")

        check(queueResult.scope == identity.scope) { "PTV Music Auto account changed while selecting music." }
        requireCurrentSelectionIdentity(identity)
        rememberItems(queue, identity.scope)
        requireCurrentSelectionIdentity(identity)
        playbackController.play(
            item = firstItem,
            queue = queue,
            startPositionMs = startPosition,
            expectedOwner = identity.localOwner,
        )
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
        identity: AutoSelectionIdentity,
        selectionGeneration: Long,
    ): MediaItemsWithStartPosition {
        val scope = identity.scope
        requireCurrentSelectionIdentity(identity)
        val playlistSelection = selectedMediaItem.mediaId.toPlaylistTrackSelection()
        val selectedItem = selectedMediaItem.mediaId.toCachedMusicItem(scope)
            ?: throw UnsupportedOperationException("PTV Music Auto could not resolve the selected item.")
        val mediaContext = resolvedMediaContext(playlistSelection, selectedItem, scope)
        val queue = selectedPlaybackQueue(
            mediaContext.playlist,
            selectedItem,
            mediaContext.activeQueue,
            mediaContext.activeQueueContainsSelection,
            scope,
        )
        val itemToPlay = selectedItemToPlay(playlistSelection, selectedItem, queue)
        val startPosition = startPositionMs.takeIf { it != C.TIME_UNSET }?.coerceAtLeast(0)
            ?: mediaContext.activePlaybackState.positionMs
                .takeIf { mediaContext.selectedItemIsCurrent }
                ?.coerceAtLeast(0)
            ?: 0
        val playbackQueue = if (mediaContext.playlist != null) queue else queue.ensureContains(itemToPlay)
        val playbackIndex = playlistSelection?.trackIndex
            ?: playbackQueue.indexOfFirst { item -> item.id == itemToPlay.id }.coerceAtLeast(0)
        requireCurrentSelectionIdentity(identity)
        rememberItems(playbackQueue, scope, cachePlaybackQueue = mediaContext.playlist == null)

        if (
            canReuseActiveQueue(
                mediaContext.playlist,
                mediaContext.selectedItemIsCurrent,
                mediaContext.activeQueueContainsSelection,
                mediaContext.activePlaybackState,
            )
        ) {
            requireCurrentSelectionGeneration(identity, selectionGeneration)
            attachActivePlaylistProvenance(mediaContext.playlist, identity)
            Timber.i(
                "PTV Music Auto reused active queue for current item selectedMediaId=${selectedMediaItem.mediaId} " +
                    "queueSize=${playbackQueue.size} currentIndex=$playbackIndex position=${startPosition}ms",
            )
        } else {
            commitReplacementSelection(identity, selectionGeneration) {
                playbackController.play(
                    item = itemToPlay,
                    queue = playbackQueue,
                    startPositionMs = startPosition,
                    expectedOwner = identity.localOwner,
                    authoredQueueIndex = playbackIndex.takeIf { mediaContext.playlist != null },
                    queueProvenance = playlistProvenance(mediaContext.playlist, playbackQueue, playbackIndex),
                )
            }
        }
        recordResolvedSelection(itemToPlay, mediaContext)

        return MediaItemsWithStartPosition(
            resolvedMediaItems(playbackQueue, mediaContext.playlist),
            playbackIndex,
            startPosition,
        )
    }

    private fun attachActivePlaylistProvenance(playlist: MusicItem?, identity: AutoSelectionIdentity) {
        val playlistId = playlist?.id ?: return
        val active = playbackController.state.value
        val source = resumeStore.loadPlaybackState()?.queueProvenance?.takeIf { saved ->
            saved.kind == PtvMusicAutoQueueProvenance.PLAYLIST &&
                saved.playlistId == playlistId.toString() && saved.matchesQueue(active.queue)
        } ?: return
        resumeStore.attachQueueProvenance(
            source.forQueue(active.queue, active.currentIndex),
            active,
            identity.localOwner,
        )
    }

    private fun playlistProvenance(
        playlist: MusicItem?,
        playbackQueue: List<MusicItem>,
        playbackIndex: Int,
    ): PtvMusicAutoQueueProvenance? = playlist?.let { source ->
        PtvMusicAutoQueueProvenance.playlist(source.id, playbackQueue, playbackIndex)
    }

    private fun recordResolvedSelection(itemToPlay: MusicItem, mediaContext: ResolvedMediaContext) {
        resumeStore.markLastPlaybackError(null)
        resumeStore.markSessionCommand(
            "play ${itemToPlay.id} playlist=${mediaContext.playlist?.id} " +
                "activeQueue=${mediaContext.activeQueueContainsSelection} current=${mediaContext.selectedItemIsCurrent}",
        )
    }

    private data class ResolvedMediaContext(
        val playlist: MusicItem?,
        val activePlaybackState: MusicPlaybackState,
        val activeQueue: List<MusicItem>,
        val activeQueueContainsSelection: Boolean,
        val selectedItemIsCurrent: Boolean,
    )

    private suspend fun resolvedMediaContext(
        selection: PlaylistTrackSelection?,
        selectedItem: MusicItem,
        scope: PtvMusicAutoBrowseScope,
    ): ResolvedMediaContext {
        val playlist = selectedPlaylistFor(selection, selectedItem, scope)
        val activePlaybackState = playbackController.state.value
        val trusted = isTrustedPlaybackScope(scope)
        val activeQueue = if (trusted) activePlaybackState.queue.filterPlayableDistinct() else emptyList()
        return ResolvedMediaContext(
            playlist = playlist,
            activePlaybackState = activePlaybackState,
            activeQueue = activeQueue,
            activeQueueContainsSelection = activeQueue.any { item -> item.id == selectedItem.id },
            selectedItemIsCurrent = trusted && activePlaybackState.currentItem?.id == selectedItem.id,
        )
    }

    private suspend fun selectedPlaylistFor(
        selection: PlaylistTrackSelection?,
        selectedItem: MusicItem,
        scope: PtvMusicAutoBrowseScope,
    ): MusicItem? = when {
        selection != null -> loadPlaylistParent(selection.playlistId, scope)
        selectedItem.type == BaseItemKind.PLAYLIST -> selectedItem
        else -> null
    }

    private suspend fun selectedPlaybackQueue(
        playlist: MusicItem?,
        selectedItem: MusicItem,
        activeQueue: List<MusicItem>,
        activeQueueContainsSelection: Boolean,
        scope: PtvMusicAutoBrowseScope,
    ): List<MusicItem> = when {
        playlist != null -> loadFullPlaylistQueue(playlist, scope)
        selectedItem.isPlayable && activeQueueContainsSelection -> activeQueue
        else -> selectedItem.resolvePlaybackQueue(scope)
    }

    private fun selectedItemToPlay(
        selection: PlaylistTrackSelection?,
        selectedItem: MusicItem,
        queue: List<MusicItem>,
    ): MusicItem = when {
        selection != null -> queue.getOrNull(selection.trackIndex)
            ?.takeIf { item -> item.id == selectedItem.id }
            ?: throw UnsupportedOperationException("PTV Music Auto playlist changed. Browse it again.")

        selectedItem.isPlayable -> selectedItem

        else -> queue.firstOrNull()
            ?: throw UnsupportedOperationException("PTV Music Auto found no playable songs.")
    }

    private fun canReuseActiveQueue(
        playlist: MusicItem?,
        selectedItemIsCurrent: Boolean,
        activeQueueContainsSelection: Boolean,
        activePlaybackState: MusicPlaybackState,
    ): Boolean {
        val activeSelection = selectedItemIsCurrent && activeQueueContainsSelection && activePlaybackState.hasCurrent
        return playlist == null && activeSelection
    }

    private fun resolvedMediaItems(queue: List<MusicItem>, playlist: MusicItem?): List<MediaItem> =
        if (playlist == null) {
            queue.map { item -> item.toAutoMediaItem() }
        } else {
            queue.mapIndexed { index, item ->
                item.toAutoMediaItem(playlistTrackMediaId(playlist.id, index, item.id))
            }
        }

    private suspend fun startMoreLikeThis(
        startPositionMs: Long,
        identity: AutoSelectionIdentity,
        selectionGeneration: Long,
    ): MediaItemsWithStartPosition {
        val currentItem = trustedCurrentPlaybackItem()
            ?: throw UnsupportedOperationException("No PTV Music song is currently playing.")
        val startPosition = startPositionMs.takeIf { it != C.TIME_UNSET }?.coerceAtLeast(0) ?: 0
        val listeningMode = listeningModeFor(identity.scope)
        val generatedQueue = repository.loadAutoGeneratedQueue(
            currentItem,
            listeningMode = listeningMode,
        )
            .filter(MusicItem::isPlayable)
        val firstItem = generatedQueue.firstOrNull()
            ?: throw UnsupportedOperationException("PTV could not find similar songs right now.")

        requireCurrentSelectionIdentity(identity)
        rememberItems(generatedQueue, identity.scope)
        requireCurrentSelectionIdentity(identity)
        check(listeningModeFor(identity.scope) == listeningMode) {
            "PTV Music Auto listening mode changed while selecting music."
        }
        val context = repository.beginAutoRecommendationSession(currentItem, generatedQueue, listeningMode)
        activateRecommendationSession(
            identity,
            selectionGeneration,
            PtvMusicAutoIds.MORE_LIKE_THIS,
            context,
            generatedQueue,
        ) { source ->
            playbackController.play(
                item = firstItem,
                queue = generatedQueue,
                startPositionMs = startPosition,
                expectedOwner = identity.localOwner,
                queueProvenance = source,
            )
        }
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
        val currentItem = trustedCurrentPlaybackItem()
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
        val scope = awaitSavedSession("generated queue $callbackName")
        val startedAt = System.currentTimeMillis()
        val pageLimit = limit.coerceIn(1, MIX_TRACK_LIMIT)
        val mix = PtvMusicAutoMix.fromMediaId(mediaId)
        val listeningMode = listeningModeFor(scope)
        val cacheKey = generatedQueueCacheKey(scope, mediaId, mix)
        val label = mix?.name ?: mediaId.generatedQueueDisplayName()
        Timber.i(
            "PTV Music Auto generated queue resolving callback=$callbackName selectedMediaId=$mediaId " +
                "resolvedMix=${mix?.name ?: "<none>"} start=$startIndex limit=$pageLimit",
        )

        generatedQueueCache[scope to cacheKey]
            ?.filterPlayableDistinct()
            ?.takeIf(List<MusicItem>::isNotEmpty)
            ?.let { cachedTracks ->
                return generatedQueueResult(
                    scope = scope,
                    mediaId = mediaId,
                    cacheKey = cacheKey,
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
            mediaId == PtvMusicAutoIds.AUTO_PICKS -> emptyList()
            mix != null -> cachedMixCandidates(mix)
            else -> emptyList()
        }
        if (cachedCandidates.isNotEmpty()) {
            return generatedQueueResult(
                scope = scope,
                mediaId = mediaId,
                cacheKey = cacheKey,
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
                mediaId == PtvMusicAutoIds.AUTO_PICKS -> loadFreshAutoPicksCandidates(listeningMode)
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
                scope = scope,
                mediaId = mediaId,
                cacheKey = cacheKey,
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
            scope = scope,
            mediaId = mediaId,
            cacheKey = cacheKey,
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
        scope: PtvMusicAutoBrowseScope,
        mediaId: String,
        cacheKey: String,
        mix: PtvMusicAutoMix?,
        source: String,
        candidates: List<MusicItem>,
        startIndex: Int,
        limit: Int,
        callbackName: String,
        startedAt: Long,
    ): AutoGeneratedQueueResult {
        if (syncBrowseScope() != scope) {
            throw IllegalStateException("PTV Music Auto account changed while loading generated music")
        }
        check(generatedQueueCacheKey(scope, mediaId, mix) == cacheKey) {
            "PTV Music Auto listening mode or playing song changed while loading generated music"
        }
        val playableCandidates = candidates.filterPlayableDistinct().take(MIX_TRACK_LIMIT)
        if (playableCandidates.isNotEmpty()) {
            rememberItems(playableCandidates, scope)
            synchronized(browseScopeLock) {
                check(syncBrowseScope() == scope && generatedQueueCacheKey(scope, mediaId, mix) == cacheKey) {
                    "PTV Music Auto account, mode, or seed changed before caching generated music."
                }
                val scopedKey = scope to cacheKey
                if (!generatedQueueCache.containsKey(scopedKey) &&
                    generatedQueueCache.size >= MAX_GENERATED_QUEUE_CACHE_ENTRIES
                ) {
                    generatedQueueCache.keys.firstOrNull()?.let(generatedQueueCache::remove)
                }
                generatedQueueCache[scopedKey] = playableCandidates
            }
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
            scope = scope,
        )
    }

    private fun generatedQueueCacheKey(
        scope: PtvMusicAutoBrowseScope,
        mediaId: String,
        mix: PtvMusicAutoMix?,
    ): String {
        val seedSensitive = mediaId == PtvMusicAutoIds.AUTO_PICKS ||
            mix == PtvMusicAutoMix.CURRENT_ARTIST ||
            mix == PtvMusicAutoMix.CURRENT_GENRE
        val seed = if (seedSensitive) trustedCurrentPlaybackItem()?.id else null
        if (seedSensitive) {
            synchronized(browseScopeLock) {
                val currentSeed = scope to seed
                if (generatedCacheSeed != currentSeed) {
                    generatedQueueCache.clear()
                    generatedCacheSeed = currentSeed
                }
            }
        }
        return "$mediaId:mode=${listeningModeFor(scope).name}:seed=${seed ?: "none"}"
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
            val currentItem = trustedCurrentPlaybackItem()
            repository.loadArtistTracks(
                artistIds = currentItem?.artistIds.orEmpty(),
                limit = MIX_TRACK_LIMIT,
            )
        }

        PtvMusicAutoMix.CURRENT_GENRE -> {
            val genreName = trustedCurrentPlaybackItem()?.genres.orEmpty().firstOrNull().orEmpty()
            repository.loadGenreTracks(genreName = genreName, limit = MIX_TRACK_LIMIT)
        }

        PtvMusicAutoMix.HEAVY_ROTATION -> repository.loadHeavyRotationTracks(limit = MIX_TRACK_LIMIT)

        PtvMusicAutoMix.LATEST -> repository.loadRecentlyAddedTracks(limit = MIX_TRACK_LIMIT)
    }

    private suspend fun loadFreshAutoPicksCandidates(mode: MusicListeningMode): List<MusicItem> =
        repository.loadAutoPicksQueue(
            seed = trustedCurrentPlaybackItem(),
            limit = MIX_TRACK_LIMIT,
            listeningMode = mode,
        )

    private fun cachedMixCandidates(mix: PtvMusicAutoMix): List<MusicItem> {
        val activeQueue = activeQueueCandidates()
        val favorites = cachedBrowseCandidates(MusicBrowseKind.FAVORITES)
        val recent = cachedBrowseCandidates(MusicBrowseKind.RECENTLY_PLAYED)
        val recommendations = cachedBrowseCandidates(MusicBrowseKind.RECOMMENDATIONS)
        val songs = cachedBrowseCandidates(MusicBrowseKind.SONGS)
        val currentItem = trustedCurrentPlaybackItem()

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

    private fun activeQueueCandidates(): List<MusicItem> {
        val scope = syncBrowseScope() ?: return emptyList()
        if (!isTrustedPlaybackScope(scope)) return emptyList()
        return playbackController.state.value.queue.filterPlayableDistinct()
    }

    private fun trustedCurrentPlaybackItem(): MusicItem? {
        val scope = syncBrowseScope() ?: return null
        if (!isTrustedPlaybackScope(scope)) return null
        return playbackController.state.value.currentItem
    }

    private suspend fun loadMoreMediaItems(scope: PtvMusicAutoBrowseScope): List<MediaItem> {
        val items = mutableListOf<MediaItem>()
        items += PtvMusicAutoMoreItem.SHUFFLE_ALL.toAutoMediaItem()
        if (trustedCurrentPlaybackItem() != null) {
            items += PtvMusicAutoMoreItem.MORE_LIKE_THIS.toAutoMediaItem()
        }
        items += PtvMusicAutoMoreItem.ALL_SONGS.toAutoMediaItem()

        runCatching {
            repository.loadAutoPlaylist()
        }.onSuccess { playlist ->
            if (playlist != null) {
                rememberItems(listOf(playlist), scope)
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

    // Early returns prevent loading or caching an item after its authenticated browse scope changes.
    @Suppress("ReturnCount")
    private suspend fun String.toCachedMusicItem(
        expectedScope: PtvMusicAutoBrowseScope? = syncBrowseScope(),
    ): MusicItem? {
        val scope = expectedScope ?: return null
        if (syncBrowseScope() != scope) return null
        val itemId = toPlaylistTrackSelection()?.trackId ?: PtvMusicAutoIds.itemIdFromMediaId(this) ?: return null
        itemCache[scope to itemId]?.let { cached -> return cached.takeIf { syncBrowseScope() == scope } }
        val loaded = repository.loadItem(itemId) ?: return null
        synchronized(browseScopeLock) {
            if (syncBrowseScope() != scope) return null
            itemCache[scope to loaded.id] = loaded
        }
        return loaded
    }

    private suspend fun MusicItem.resolvePlaybackQueue(scope: PtvMusicAutoBrowseScope): List<MusicItem> {
        if (type == BaseItemKind.PLAYLIST) return loadFullPlaylistQueue(this, scope)
        if (isPlayable) {
            if (syncBrowseScope() != scope) {
                throw IllegalStateException("PTV Music Auto account changed while resolving playback")
            }
            queueCache[scope to id]?.let { return it }
            val restoredQueue = repository.loadPlaybackQueueForTrack(this)
                .filter(MusicItem::isPlayable)
                .ifEmpty { listOf(this) }
            rememberItems(restoredQueue, scope)
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

    private suspend fun loadPlaylistParent(playlistId: UUID, scope: PtvMusicAutoBrowseScope): MusicItem {
        if (syncBrowseScope() != scope) {
            throw IllegalStateException("PTV Music Auto account changed while loading playlist")
        }
        val parent = itemCache[scope to playlistId] ?: repository.loadItem(playlistId)
        if (parent?.type != BaseItemKind.PLAYLIST) {
            throw UnsupportedOperationException("PTV Music Auto could not find the selected playlist.")
        }
        rememberItems(listOf(parent), scope, cachePlaybackQueue = false)
        return parent
    }

    private suspend fun loadFullPlaylistQueue(playlist: MusicItem, scope: PtvMusicAutoBrowseScope): List<MusicItem> {
        if (syncBrowseScope() != scope) {
            throw IllegalStateException("PTV Music Auto account changed while loading playlist")
        }
        val tracks = repository.loadPlaylistQueue(playlist)
        if (syncBrowseScope() != scope) {
            throw IllegalStateException("PTV Music Auto account changed while loading playlist")
        }
        return tracks
    }

    private suspend fun loadMediaItemsWithCache(
        cacheKey: String,
        label: String,
        block: suspend (PtvMusicAutoBrowseScope) -> List<MediaItem>,
    ): List<MediaItem> {
        val scope = awaitSavedSession(label)
        val scopedCacheKey = scope to cacheKey
        val cachedItems = browseCache[scopedCacheKey]
        var lastError: Throwable? = null

        repeat(AUTO_BROWSE_ATTEMPTS) { attempt ->
            val result = runCatching {
                withTimeout(AUTO_BROWSE_TIMEOUT_MS) {
                    block(scope)
                }
            }

            result.onSuccess { items ->
                if (syncBrowseScope() != scope) {
                    throw IllegalStateException("PTV Music Auto session changed during $label")
                }
                if (items.isNotEmpty()) {
                    browseCache[scopedCacheKey] = items
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

        if (cachedItems != null && syncBrowseScope() == scope) {
            Timber.w("PTV Music Auto $label failed; source=cache count=${cachedItems.size}")
            resumeStore.markBrowseStatus("cache-failure:$label", cachedItems.size)
            return cachedItems
        }

        throw lastError ?: IllegalStateException("PTV Music Auto could not load $label")
    }

    private suspend fun awaitSavedSession(
        label: String,
        timeoutMs: Long = AUTO_SESSION_READY_TIMEOUT_MS,
    ): PtvMusicAutoBrowseScope {
        syncBrowseScope()?.let { return it }
        val restoredUser = withTimeoutOrNull(timeoutMs) {
            savedSessionReady.await()
        }
        val scope = syncBrowseScope()
        if (scope != null) return scope
        Timber.w(
            "PTV Music Auto has no authenticated Jellyfin session before $label " +
                "restoreCompleted=${savedSessionReady.isCompleted} restoredUser=${restoredUser != null}",
        )
        throw IllegalStateException("Open PiggieTV on your phone to reconnect music.")
    }

    private fun captureSelectionIdentity(scope: PtvMusicAutoBrowseScope): AutoSelectionIdentity {
        val localOwner = resumeStore.currentLocalQueueOwner()
            ?: throw IllegalStateException("PTV Music Auto has no selected local account.")
        return AutoSelectionIdentity(scope, localOwner).also(::requireCurrentSelectionIdentity)
    }

    private fun isSelectionIdentityCurrent(identity: AutoSelectionIdentity): Boolean =
        syncBrowseScope() == identity.scope &&
            resumeStore.currentLocalQueueOwner() == identity.localOwner &&
            syncBrowseScope() == identity.scope

    private fun requireCurrentSelectionIdentity(identity: AutoSelectionIdentity) {
        check(isSelectionIdentityCurrent(identity)) { "PTV Music Auto account changed while selecting music." }
    }

    private fun requireCurrentSelectionGeneration(identity: AutoSelectionIdentity, selectionGeneration: Long) {
        synchronized(browseScopeLock) {
            requireCurrentSelectionIdentity(identity)
            check(recommendationGeneration == selectionGeneration) {
                "PTV Music Auto selected another queue while music loaded."
            }
        }
    }

    private fun commitReplacementSelection(
        identity: AutoSelectionIdentity,
        selectionGeneration: Long,
        startPlayback: () -> Unit,
    ) {
        synchronized(browseScopeLock) {
            requireCurrentSelectionGeneration(identity, selectionGeneration)
            startPlayback()
            clearRecommendationSessionLocked()
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
        val scope = awaitSavedSession("custom.favorite")
        val identity = captureSelectionIdentity(scope)
        val currentItem = trustedCurrentPlaybackItem()
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
            expectedOwner = identity.localOwner,
            ownerStillCurrent = { isSelectionIdentityCurrent(identity) },
        ) as MusicSongActionResult.FavoriteUpdated
        requireCurrentSelectionIdentity(identity)
        val updatedItem = result.item
        rememberItems(listOf(updatedItem), scope)
        Timber.i(
            "PTV Music Auto command favorite source=androidAuto currentItemId=${updatedItem.id} " +
                "result=success favorite=${updatedItem.isFavorite}",
        )
    }

    private suspend fun handleAddToPlaylist() {
        val scope = awaitSavedSession("custom.addToPlaylist")
        val identity = captureSelectionIdentity(scope)
        val currentItem = trustedCurrentPlaybackItem()
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
                expectedOwner = identity.localOwner,
                ownerStillCurrent = { isSelectionIdentityCurrent(identity) },
            ).message ?: "Added to $AUTO_PLAYLIST_NAME."
        }.onSuccess { message ->
            requireCurrentSelectionIdentity(identity)
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
        val scope = awaitSavedSession("custom.generateQueue")
        val identity = captureSelectionIdentity(scope)
        val currentItem = trustedCurrentPlaybackItem()
            ?: throw UnsupportedOperationException("No PTV Music song is currently playing.")
        val result = songActionHandler.execute(
            request = MusicSongActionRequest.forItem(
                action = MusicSongAction.START_MIX,
                item = currentItem,
                source = "androidAuto",
            ),
            item = currentItem,
            queue = playbackController.state.value.queue.ifEmpty { listOf(currentItem) },
            expectedOwner = identity.localOwner,
            ownerStillCurrent = { isSelectionIdentityCurrent(identity) },
        ) as MusicSongActionResult.MixStarted
        requireCurrentSelectionIdentity(identity)
        val generatedQueue = result.queue
        if (generatedQueue.isEmpty()) {
            throw UnsupportedOperationException("PTV could not find similar songs right now.")
        }
        rememberItems(generatedQueue, scope)
        resumeStore.markSessionCommand("generateQueue ${currentItem.id} count=${generatedQueue.size}")
        Timber.i("PTV Music Auto generated queue started count=${generatedQueue.size} seed=${currentItem.id}")
    }

    private fun MusicPage.toMediaItems(scope: PtvMusicAutoBrowseScope): List<MediaItem> {
        rememberItems(items, scope)
        return items.map { item -> item.toAutoMediaItem() }
    }

    private fun MusicPage.toPlaylistMediaItems(scope: PtvMusicAutoBrowseScope, playlistId: UUID): List<MediaItem> {
        rememberItems(items, scope, cachePlaybackQueue = false)
        return items.mapIndexed { index, item ->
            item.toAutoMediaItem(playlistTrackMediaId(playlistId, startIndex + index, item.id))
        }
    }

    private fun List<MusicItem>.toMediaItems(scope: PtvMusicAutoBrowseScope): List<MediaItem> {
        rememberItems(this, scope)
        return map { item -> item.toAutoMediaItem() }
    }

    private fun PtvMusicAutoCategory.activePlaybackMediaItems(
        startIndex: Int,
        limit: Int,
        scope: PtvMusicAutoBrowseScope,
    ): List<MediaItem>? {
        if (this != PtvMusicAutoCategory.RECOMMENDATIONS) return null
        if (!isTrustedPlaybackScope(scope)) return null
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

        rememberItems(pageItems, scope)
        return pageItems.map { item -> item.toAutoMediaItem() }
    }

    private fun rememberItems(
        items: List<MusicItem>,
        expectedScope: PtvMusicAutoBrowseScope,
        cachePlaybackQueue: Boolean = true,
    ) {
        synchronized(browseScopeLock) {
            if (syncBrowseScope() != expectedScope) {
                throw IllegalStateException("PTV Music Auto account changed while loading music")
            }
            items.forEach { item -> itemCache[expectedScope to item.id] = item }
            if (cachePlaybackQueue) {
                val playableQueue = items.filter(MusicItem::isPlayable)
                playableQueue.forEach { item -> queueCache[expectedScope to item.id] = playableQueue }
            }
        }
    }

    private fun listeningModeFor(scope: PtvMusicAutoBrowseScope): MusicListeningMode = synchronized(browseScopeLock) {
        selectedListeningMode?.takeIf { it.first == scope }?.second
            ?: MusicListeningMode.BALANCED_DISCOVERY
    }

    private fun selectListeningMode(scope: PtvMusicAutoBrowseScope, mode: MusicListeningMode) {
        synchronized(browseScopeLock) {
            check(syncBrowseScope() == scope) { "PTV Music Auto account changed while choosing a listening mode" }
            if (listeningModeFor(scope) != mode) {
                generatedQueueCache.clear()
                selectedListeningMode = scope to mode
            }
        }
    }

    private fun activateRecommendationSession(
        identity: AutoSelectionIdentity,
        selectionGeneration: Long,
        sourceMediaId: String,
        context: MusicRecommendationSessionContext,
        queue: List<MusicItem>,
        startPlayback: (PtvMusicAutoQueueProvenance) -> Unit,
    ) {
        synchronized(browseScopeLock) {
            requireCurrentSelectionIdentity(identity)
            check(recommendationOwnerMatchesIdentity(context, identity)) {
                "PTV Music Auto recommendation session belongs to another account."
            }
            check(recommendationGeneration == selectionGeneration) {
                "PTV Music Auto selected another queue while recommendations loaded."
            }
            check(context.selectedMode == listeningModeFor(identity.scope)) {
                "PTV Music Auto listening mode changed while recommendations loaded."
            }
            val itemIds = queue.map(MusicItem::id).toMutableSet()
            check(itemIds.isNotEmpty() && context.recommendedIds.containsAll(itemIds)) {
                "PTV Music Auto recommendation session did not authorize the selected queue."
            }
            startPlayback(recommendationProvenance(sourceMediaId, context, queue))
            clearRecommendationSessionLocked()
            recommendationSession = ActiveRecommendationSession(
                identity = identity,
                generation = recommendationGeneration,
                sourceMediaId = sourceMediaId,
                context = context,
                expectedItemIds = itemIds,
            )
            recommendationStateJob = ioScope.launch {
                playbackController.state.collect(::observeRecommendationPlaybackState)
            }
            recommendationTransitionJob = ioScope.launch {
                playbackController.transitionEvents.collect(::recordVerifiedRecommendationTransition)
            }
        }
    }

    private fun recommendationProvenance(
        sourceMediaId: String,
        context: MusicRecommendationSessionContext,
        queue: List<MusicItem>,
    ): PtvMusicAutoQueueProvenance = PtvMusicAutoQueueProvenance.recommendation(
        queue,
        PtvMusicAutoRecommendationSnapshot(
            sourceMediaId = sourceMediaId,
            selectedMode = context.selectedMode.name,
            seedId = context.seed.id.toString(),
            recentPlayedIds = context.recentPlayedIds.map(UUID::toString),
            skippedIds = context.skippedIds.map(UUID::toString),
            recommendedIds = context.recommendedIds.map(UUID::toString),
            catalogOffset = context.catalogOffset,
            catalogExhausted = context.catalogExhausted,
            revision = context.revision,
        ),
    )

    private fun recommendationOwnerMatchesIdentity(
        context: MusicRecommendationSessionContext,
        identity: AutoSelectionIdentity,
    ): Boolean {
        val serverId = identity.scope.serverId.toLongOrNull() ?: return false
        val userId = identity.scope.userId.toUUIDOrNull() ?: return false
        if (identity.localOwner.serverId != serverId) return false
        val serverUrl = serverUrlForLocalId(serverId)?.trimEnd('/')?.takeIf(String::isNotBlank) ?: return false
        return context.owner.serverUrl.trimEnd('/') == serverUrl && context.owner.userId == userId
    }

    private fun observeRecommendationPlaybackState(state: MusicPlaybackState) {
        val request = synchronized(browseScopeLock) {
            val session = recommendationSession ?: return@synchronized null
            if (!isSelectionIdentityCurrent(session.identity)) {
                clearRecommendationSessionLocked()
                return@synchronized null
            }
            val ownsExpectedQueue = recommendationQueueMatches(state, session)
            if (!session.activated) {
                if (!ownsExpectedQueue) return@synchronized null
                session.activated = true
            } else if (!ownsExpectedQueue) {
                clearRecommendationSessionLocked()
                return@synchronized null
            }
            val current = state.currentItem ?: return@synchronized null
            if (!canRequestRecommendationRefill(state, session, current)) return@synchronized null
            if (session.refillFailureItemId != current.id) {
                session.refillFailureItemId = current.id
                session.refillFailureCount = 0
            }
            session.lastRefillItemId = current.id
            session.refillInFlight = true
            session.refillSerial++
            RecommendationRefillRequest(
                session = session,
                context = session.context,
                currentItem = current,
                expectedItemIds = session.expectedItemIds.toSet(),
                serial = session.refillSerial,
            )
        } ?: return

        launchRecommendationRefill(request)
    }

    private fun recommendationQueueMatches(state: MusicPlaybackState, session: ActiveRecommendationSession): Boolean =
        state.queueOwner == session.identity.localOwner &&
            state.queue.size == session.expectedItemIds.size &&
            state.queue.map(MusicItem::id).toSet() == session.expectedItemIds

    private fun canRequestRecommendationRefill(
        state: MusicPlaybackState,
        session: ActiveRecommendationSession,
        current: MusicItem,
    ): Boolean {
        val authenticatedQueue = playbackController.isCurrentQueueOwnedByAuthenticatedUser()
        val playbackIsActive = authenticatedQueue && (state.isPlaying || state.isBuffering)
        val playbackIsCurrent = playbackIsActive && state.currentIndex in state.queue.indices
        if (!playbackIsCurrent) return false
        val currentIsRecommended = state.queue[state.currentIndex].id == current.id &&
            current.id in session.context.recommendedIds
        if (!currentIsRecommended) return false
        val refillIsDue = state.queue.size - state.currentIndex - 1 <= AUTO_RECOMMENDATION_PREFETCH_REMAINING &&
            !session.context.catalogExhausted &&
            !session.refillInFlight
        return refillIsDue && session.lastRefillItemId != current.id
    }

    private fun launchRecommendationRefill(request: RecommendationRefillRequest) {
        val job = ioScope.launch {
            try {
                val failure = runCatching {
                    val batch = repository.replenishAutoRecommendations(request.context, request.currentItem)
                    applyRecommendationRefill(request, batch)
                }.exceptionOrNull()
                if (failure != null) {
                    if (failure is CancellationException || failure !is Exception) throw failure
                    val retryDelayMs = retryDelayAfterRefillFailure(request)
                    Timber.w(failure, "PTV Music Auto recommendation refill unavailable retryDelayMs=$retryDelayMs")
                    if (retryDelayMs != null) delay(retryDelayMs)
                }
            } finally {
                val recheckCurrentState = synchronized(browseScopeLock) {
                    if (recommendationSession === request.session && request.session.refillSerial == request.serial) {
                        request.session.refillInFlight = false
                        true
                    } else {
                        false
                    }
                }
                if (recheckCurrentState) observeRecommendationPlaybackState(playbackController.state.value)
            }
        }
        synchronized(browseScopeLock) {
            if (recommendationSession === request.session && request.session.refillSerial == request.serial) {
                recommendationRefillJob = job
            } else {
                job.cancel()
            }
        }
    }

    private fun retryDelayAfterRefillFailure(request: RecommendationRefillRequest): Long? =
        synchronized(browseScopeLock) {
            val session = recommendationSession ?: return@synchronized null
            val sameRequest = session === request.session && session.refillSerial == request.serial
            val sameContext = session.context === request.context
            if (!sameRequest || !sameContext || !isSelectionIdentityCurrent(session.identity)) {
                return@synchronized null
            }
            if (!isCurrentRecommendationRefillPlayback(playbackController.state.value, session, request)) {
                return@synchronized null
            }
            if (session.refillFailureCount >= MAX_AUTO_RECOMMENDATION_SAME_SONG_RETRIES) return@synchronized null
            session.refillFailureCount++
            session.lastRefillItemId = null
            AUTO_RECOMMENDATION_RETRY_DELAY_MS * session.refillFailureCount
        }

    private suspend fun applyRecommendationRefill(
        request: RecommendationRefillRequest,
        batch: MusicRecommendationBatch,
    ) = withContext(playerDispatcher) {
        val freshItems = batch.items.filter(MusicItem::isPlayable)
            .distinctBy(MusicItem::id)
            .filterNot { it.id in request.expectedItemIds }
        synchronized(browseScopeLock) {
            val session = recommendationSession ?: return@synchronized
            if (!isCurrentRecommendationRefill(session, request, batch, freshItems)) return@synchronized
            val state = playbackController.state.value
            if (!isCurrentRecommendationRefillPlayback(state, session, request)) return@synchronized
            if (freshItems.isEmpty()) {
                session.context = batch.context
                return@synchronized
            }
            val retainedQueue = (state.queue + freshItems)
                .drop((state.currentIndex - AUTO_RECOMMENDATION_PREVIOUS_WINDOW).coerceAtLeast(0))
                .take(MAX_AUTO_RECOMMENDATION_RESIDENT_QUEUE)
            val retainedIds = retainedQueue.map(MusicItem::id)
            if (request.currentItem.id !in retainedIds ||
                !retainedIds.containsAll(freshItems.map(MusicItem::id))
            ) {
                return@synchronized
            }
            applyRetainedRecommendationQueue(session, batch, retainedQueue, retainedIds)
        }
    }

    private fun isCurrentRecommendationRefill(
        session: ActiveRecommendationSession,
        request: RecommendationRefillRequest,
        batch: MusicRecommendationBatch,
        freshItems: List<MusicItem>,
    ): Boolean {
        if (session !== request.session || session.refillSerial != request.serial) return false
        if (session.context !== request.context || session.context.revision != request.context.revision) return false
        if (
            session.expectedItemIds != request.expectedItemIds ||
            !isSelectionIdentityCurrent(session.identity)
        ) {
            return false
        }
        return isValidRecommendationBatch(batch, request, freshItems)
    }

    private fun isValidRecommendationBatch(
        batch: MusicRecommendationBatch,
        request: RecommendationRefillRequest,
        freshItems: List<MusicItem>,
    ): Boolean {
        val context = batch.context
        if (context.owner != request.context.owner || context.selectedMode != request.context.selectedMode ||
            context.seed.id != request.context.seed.id
        ) {
            return false
        }
        if (context.revision <= request.context.revision || context.catalogOffset < request.context.catalogOffset) {
            return false
        }
        return context.recommendedIds.containsAll(freshItems.map(MusicItem::id))
    }

    private fun isCurrentRecommendationRefillPlayback(
        state: MusicPlaybackState,
        session: ActiveRecommendationSession,
        request: RecommendationRefillRequest,
    ): Boolean {
        if (state.queueOwner != session.identity.localOwner ||
            !playbackController.isCurrentQueueOwnedByAuthenticatedUser() ||
            state.currentItem?.id != request.currentItem.id
        ) {
            return false
        }
        if (
            state.currentIndex !in state.queue.indices ||
            state.queue[state.currentIndex].id != request.currentItem.id
        ) {
            return false
        }
        return state.queue.size == request.expectedItemIds.size &&
            state.queue.map(MusicItem::id).toSet() == request.expectedItemIds
    }

    private fun applyRetainedRecommendationQueue(
        session: ActiveRecommendationSession,
        batch: MusicRecommendationBatch,
        retainedQueue: List<MusicItem>,
        retainedIds: List<UUID>,
    ) {
        if (!playbackController.replaceQueueKeepingCurrent(
                retainedQueue,
                "autoRecommendationRefill",
                preservePlaybackOrder = true,
            )
        ) {
            return
        }
        val residentQueue = playbackController.state.value.queue
        if (residentQueue.map(MusicItem::id) != retainedIds) {
            clearRecommendationSessionLocked()
            return
        }
        val residentIds = retainedIds.toSet()
        val droppedIds = session.expectedItemIds - residentIds
        droppedIds.forEach { id ->
            queueCache.remove(session.identity.scope to id)
            itemCache.remove(session.identity.scope to id)
        }
        session.expectedItemIds.clear()
        session.expectedItemIds.addAll(residentIds)
        session.context = batch.context
        persistRecommendationSession(session, playbackController.state.value)
        rememberItems(residentQueue, session.identity.scope)
    }

    private fun recordVerifiedRecommendationTransition(event: MusicPlaybackTransitionEvent) {
        val state = playbackController.state.value
        synchronized(browseScopeLock) {
            val session = recommendationSession ?: return
            if (!isVerifiedRecommendationTransition(event, state, session)) return
            session.context = when (event.reason) {
                MusicPlaybackTransitionReason.NATURAL ->
                    session.context.recordPlayed(state.queue[event.previousIndex])

                MusicPlaybackTransitionReason.EXPLICIT_SKIP ->
                    session.context.recordSkipped(event.previousItemId)
            }
            persistRecommendationSession(session, state)
            session.lastRefillItemId = null
            session.refillFailureItemId = null
            session.refillFailureCount = 0
            session.refillSerial++
            session.refillInFlight = false
            recommendationRefillJob?.cancel()
            recommendationRefillJob = null
        }
        observeRecommendationPlaybackState(state)
    }

    private fun persistRecommendationSession(session: ActiveRecommendationSession, state: MusicPlaybackState) {
        if (state.queueOwner != session.identity.localOwner ||
            !recommendationQueueMatches(state, session) ||
            !isSelectionIdentityCurrent(session.identity)
        ) {
            return
        }
        resumeStore.attachQueueProvenance(
            recommendationProvenance(session.sourceMediaId, session.context, state.queue),
            state,
            session.identity.localOwner,
        )
    }

    private fun isVerifiedRecommendationTransition(
        event: MusicPlaybackTransitionEvent,
        state: MusicPlaybackState,
        session: ActiveRecommendationSession,
    ): Boolean {
        if (!session.activated || !isSelectionIdentityCurrent(session.identity)) return false
        if (event.owner != session.identity.localOwner || state.queueOwner != event.owner ||
            !playbackController.isCurrentQueueOwnedByAuthenticatedUser()
        ) {
            return false
        }
        return transitionEntriesMatchQueue(event, state) &&
            recommendationQueueMatches(state, session) &&
            transitionMatchesCurrentItem(event, state, session)
    }

    private fun transitionEntriesMatchQueue(event: MusicPlaybackTransitionEvent, state: MusicPlaybackState): Boolean {
        if (event.previousIndex !in state.queue.indices || event.nextIndex !in state.queue.indices) return false
        return state.queue[event.previousIndex].id == event.previousItemId &&
            state.queue[event.nextIndex].id == event.nextItemId
    }

    private fun transitionMatchesCurrentItem(
        event: MusicPlaybackTransitionEvent,
        state: MusicPlaybackState,
        session: ActiveRecommendationSession,
    ): Boolean {
        val currentMatches = state.currentIndex == event.nextIndex && state.currentItem?.id == event.nextItemId
        return currentMatches && event.previousItemId in session.expectedItemIds &&
            event.nextItemId in session.expectedItemIds
    }

    private fun clearRecommendationSessionLocked() {
        recommendationGeneration++
        recommendationSession = null
        recommendationStateJob?.cancel()
        recommendationTransitionJob?.cancel()
        recommendationRefillJob?.cancel()
        recommendationStateJob = null
        recommendationTransitionJob = null
        recommendationRefillJob = null
    }

    private fun syncBrowseScope(): PtvMusicAutoBrowseScope? = synchronized(browseScopeLock) {
        val currentScope = ptvMusicAutoBrowseScope(authState.value)
        val currentOwner = resumeStore.currentLocalQueueOwner()
        val priorOwnerChanged = observeAuthenticatedOwnerTransition(currentScope, currentOwner)
        if (currentScope != observedBrowseScope) {
            clearRecommendationSessionLocked()
            itemCache.clear()
            queueCache.clear()
            browseCache.clear()
            generatedQueueCache.clear()
            generatedCacheSeed = null
            selectedListeningMode = null
            observedBrowseScope = currentScope
            observedLocalOwner = currentOwner
            localOwnerChangedWithinScope = false
        } else if (currentOwner != observedLocalOwner) {
            clearRecommendationSessionLocked()
            itemCache.clear()
            queueCache.clear()
            browseCache.clear()
            generatedQueueCache.clear()
            generatedCacheSeed = null
            selectedListeningMode = null
            if (priorOwnerChanged) {
                observedLocalOwner = currentOwner
                localOwnerChangedWithinScope = false
            } else {
                localOwnerChangedWithinScope = true
            }
        }
        currentScope.takeUnless { localOwnerChangedWithinScope || pendingPriorOwner != null }
    }

    private fun observeAuthenticatedOwnerTransition(
        scope: PtvMusicAutoBrowseScope?,
        owner: PtvMusicAutoQueueOwner?,
    ): Boolean {
        val previous = lastAuthenticatedScope
        if (scope != null) {
            val accountChanged = previous != null &&
                (scope.serverId != previous.serverId || scope.userId != previous.userId)
            if (accountChanged && owner != null && owner == lastAuthenticatedOwner) {
                // Auth moved first; the local owner still proves only the previous account's queue.
                pendingPriorOwner = owner
            } else if ((previous == null || accountChanged) && pendingPriorOwner == null) {
                lastAuthenticatedOwner = owner
            }
            lastAuthenticatedScope = scope
        }
        val priorOwner = pendingPriorOwner
        val priorOwnerChanged = priorOwner != null && owner != null && owner != priorOwner
        if (priorOwnerChanged) {
            pendingPriorOwner = null
            lastAuthenticatedOwner = owner
        }
        return priorOwnerChanged
    }

    private fun isTrustedPlaybackScope(scope: PtvMusicAutoBrowseScope): Boolean = syncBrowseScope() == scope &&
        playbackController.isCurrentQueueOwnedByAuthenticatedUser() &&
        syncBrowseScope() == scope

    private fun unavailableBrowseResult(): LibraryResult<ImmutableList<MediaItem>> = LibraryResult.ofError(
        SessionError(SessionError.ERROR_NOT_SUPPORTED, "Open PiggieTV on your phone to reconnect music."),
    )

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

    private fun PtvMusicAutoListeningMode.toAutoMediaItem(selected: Boolean): MediaItem = MediaItem.Builder()
        .setMediaId(mediaId)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setSubtitle(if (selected) "Selected · $subtitle" else subtitle)
                .setIsBrowsable(true)
                .setIsPlayable(false)
                .setArtworkUri(R.drawable.ic_music_note_white_24dp.asResourceUri())
                .setExtras(contentStyleExtras(grid = false, playable = false))
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

    private fun MusicItem.toAutoMediaItem(mediaId: String = toPtvMusicAutoDescriptor().mediaId): MediaItem =
        MediaItem.Builder()
            .setMediaId(mediaId)
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

    private fun playlistTrackMediaId(playlistId: UUID, trackIndex: Int, trackId: UUID): String =
        "$PLAYLIST_TRACK_PREFIX$playlistId:$trackIndex:$trackId"

    private fun String.toPlaylistTrackSelection(): PlaylistTrackSelection? =
        takeIf { it.startsWith(PLAYLIST_TRACK_PREFIX) }
            ?.removePrefix(PLAYLIST_TRACK_PREFIX)
            ?.split(':')
            ?.takeIf { parts -> parts.size == PLAYLIST_TRACK_PART_COUNT }
            ?.let { parts ->
                val playlistId = parts[0].toUUIDOrNull()
                val trackIndex = parts[1].toIntOrNull()?.takeIf { index -> index >= 0 }
                val trackId = parts[2].toUUIDOrNull()
                if (playlistId != null && trackIndex != null && trackId != null) {
                    PlaylistTrackSelection(playlistId, trackIndex, trackId)
                } else {
                    null
                }
            }

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
        const val PLAYLIST_TRACK_PREFIX = "ptv:auto:playlist-track:"
        const val PLAYLIST_TRACK_PART_COUNT = 3
        const val AUTO_BROWSE_ATTEMPTS = 2
        const val AUTO_BROWSE_TIMEOUT_MS = 12_000L
        const val AUTO_GENERATED_FRESH_TIMEOUT_MS = 3_500L
        const val AUTO_SELECTION_TIMEOUT_MS = 4_500L
        const val AUTO_SELECTION_SESSION_READY_TIMEOUT_MS = 750L
        const val AUTO_SESSION_READY_TIMEOUT_MS = 4_000L
        const val DEFAULT_PAGE_SIZE = 24
        const val DEFAULT_SEARCH_RESULT_COUNT = 50
        const val MIX_TRACK_LIMIT = 50
        const val MAX_GENERATED_QUEUE_CACHE_ENTRIES = 12
        const val AUTO_RECOMMENDATION_PREFETCH_REMAINING = 2
        const val MAX_AUTO_RECOMMENDATION_SAME_SONG_RETRIES = 2
        const val AUTO_RECOMMENDATION_RETRY_DELAY_MS = 500L
        const val AUTO_RECOMMENDATION_PREVIOUS_WINDOW = 12
        const val MAX_AUTO_RECOMMENDATION_RESIDENT_QUEUE = 100
        const val MAX_PAGE_SIZE = 100
        const val AUTO_PLAYLIST_NAME = "PTV Auto Picks"
    }
}

internal data class PtvMusicAutoBrowseScope(val serverId: String, val userId: String, val credentialInstanceId: UUID)

internal fun ptvMusicAutoBrowseScope(state: MobileAuthState): PtvMusicAutoBrowseScope? =
    (state as? MobileAuthState.Authenticated)?.let { authenticated ->
        PtvMusicAutoBrowseScope(
            serverId = authenticated.serverId,
            userId = authenticated.userId,
            credentialInstanceId = authenticated.credentialInstanceId,
        )
    }

private const val PTV_AUTO_STATUS_PREFIX = "ptv:auto:status:"

internal fun ptvMusicAutoRootItemsFor(state: MobileAuthState): List<PtvMusicAutoRootItem> = when (state) {
    is MobileAuthState.Authenticated -> PTV_MUSIC_AUTO_ROOT_ITEMS

    is MobileAuthState.ConnectivityError -> listOf(
        PtvMusicAutoRootItem(
            mediaId = "${PTV_AUTO_STATUS_PREFIX}retry",
            title = "Music is temporarily unavailable",
            subtitle = "Reconnect your phone and try again",
            isBrowsable = false,
        ),
    )

    MobileAuthState.Restoring,
    MobileAuthState.Validating,
    is MobileAuthState.Authenticating,
    MobileAuthState.QuickConnectRequesting,
    is MobileAuthState.QuickConnectWaiting,
    -> listOf(
        PtvMusicAutoRootItem(
            mediaId = "${PTV_AUTO_STATUS_PREFIX}connecting",
            title = "Connecting to music",
            subtitle = "Your library will appear when the connection is ready",
            isBrowsable = false,
        ),
    )

    else -> listOf(
        PtvMusicAutoRootItem(
            mediaId = "${PTV_AUTO_STATUS_PREFIX}sign-in",
            title = "Sign in on your phone",
            subtitle = "Open PiggieTV to reconnect your music library",
            isBrowsable = false,
        ),
    )
}

internal fun isPtvMusicControllerAllowed(
    controllerIsTrusted: Boolean,
    controllerUid: Int,
    applicationUid: Int,
): Boolean = controllerIsTrusted || (controllerUid >= 0 && controllerUid == applicationUid)

private data class AutoGeneratedQueueResult(
    val tracks: List<MusicItem>,
    val source: String,
    val candidateCount: Int,
    val scope: PtvMusicAutoBrowseScope,
)

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
