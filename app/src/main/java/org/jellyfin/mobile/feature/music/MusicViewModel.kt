@file:Suppress("TooManyFunctions")

package org.jellyfin.mobile.feature.music

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.jellyfin.mobile.app.ApiClientController
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.UUID
import timber.log.Timber

class MusicViewModel(
    private val repository: MusicRepository,
    private val playbackController: MusicPlaybackController,
    private val apiClientController: ApiClientController,
    private val songActionHandler: MusicSongActionHandler,
) : ViewModel() {
    private val _uiState = MutableStateFlow<MusicUiState>(MusicUiState.Loading)
    val uiState: StateFlow<MusicUiState> get() = _uiState
    val playbackState: StateFlow<MusicPlaybackState> get() = playbackController.state

    private var searchJob: Job? = null
    private var searchVersion = 0

    fun load(force: Boolean = false) {
        if (!force && _uiState.value is MusicUiState.Content) return

        viewModelScope.launch {
            _uiState.value = MusicUiState.Loading
            runCatching {
                apiClientController.loadSavedServerUser()
                repository.loadHome()
            }.onSuccess { home ->
                Timber.i(
                    "PTV music UI rendering home albums=${home.albumsTotalCount} artists=${home.artistsTotalCount} " +
                        "songs=${home.songsTotalCount} partial=${home.sourceErrors.isNotEmpty()} " +
                        "cached=${home.sourceCacheHits} errors=${home.sourceErrors.keys}",
                )
                _uiState.value = MusicUiState.Content(
                    home = home,
                    songsError = home.songsError,
                    notInterestedItemIds = repository.notInterestedItemIds(),
                )
            }.onFailure { error ->
                _uiState.value = MusicUiState.Error(error.message ?: "Could not load PiggieTV Music.")
            }
        }
    }

    fun search(query: String) {
        val content = _uiState.value as? MusicUiState.Content ?: return
        val trimmedQuery = query.trim()
        val version = ++searchVersion

        searchJob?.cancel()
        _uiState.value = content.copy(
            searchQuery = query,
            searchResults = if (trimmedQuery.isBlank()) emptyList() else content.searchResults,
            isSearching = trimmedQuery.isNotBlank(),
        )

        if (trimmedQuery.isBlank()) return

        searchJob = viewModelScope.launch {
            delay(SEARCH_DEBOUNCE_MS)
            runCatching {
                apiClientController.loadSavedServerUser()
                repository.searchMusic(trimmedQuery)
            }.onSuccess { results ->
                if (version != searchVersion) return@onSuccess
                _uiState.update { state ->
                    when (state) {
                        is MusicUiState.Content -> state.copy(searchResults = results, isSearching = false)
                        else -> state
                    }
                }
            }.onFailure { error ->
                if (version != searchVersion) return@onFailure
                _uiState.update { state ->
                    when (state) {
                        is MusicUiState.Content -> state.copy(
                            searchResults = emptyList(),
                            isSearching = false,
                            searchError = error.message ?: "Music search failed.",
                        )

                        else -> state
                    }
                }
            }
        }
    }

    fun loadMoreSongs() {
        val content = _uiState.value as? MusicUiState.Content ?: return
        if (content.isLoadingMoreSongs || (!content.home.hasMoreSongs && content.songsError == null)) return

        viewModelScope.launch {
            _uiState.value = content.copy(isLoadingMoreSongs = true, songsError = null)
            runCatching {
                apiClientController.loadSavedServerUser()
                repository.loadSongsPage(content.home.songs.size)
            }.onSuccess { page ->
                _uiState.update { state ->
                    when (state) {
                        is MusicUiState.Content -> state.copy(
                            home = state.home.copy(
                                songs = (state.home.songs + page.items).distinctBy(MusicItem::id),
                                songsTotalCount = page.totalCount,
                            ),
                            isLoadingMoreSongs = false,
                        )

                        else -> state
                    }
                }
            }.onFailure { error ->
                _uiState.update { state ->
                    when (state) {
                        is MusicUiState.Content -> state.copy(
                            isLoadingMoreSongs = false,
                            songsError = error.message ?: "Could not load more songs.",
                        )

                        else -> state
                    }
                }
            }
        }
    }

    fun openItem(item: MusicItem) {
        viewModelScope.launch {
            val content = _uiState.value as? MusicUiState.Content ?: return@launch
            val selectedStack = content.selectedItem?.let { selected ->
                content.selectedStack + MusicDetailSnapshot(
                    item = selected,
                    children = content.selectedChildren,
                    isLoading = content.isLoadingDetail,
                    error = content.detailError,
                )
            } ?: content.selectedStack
            _uiState.value = content.copy(
                selectedItem = item,
                selectedChildren = emptyList(),
                isLoadingDetail = item.isFolder,
                detailError = null,
                selectedStack = selectedStack,
            )
            if (!item.isFolder) return@launch

            runCatching {
                apiClientController.loadSavedServerUser()
                repository.loadChildren(item)
            }.onSuccess { children ->
                _uiState.update { state ->
                    when (state) {
                        is MusicUiState.Content -> state.copy(
                            selectedChildren = children,
                            isLoadingDetail = false,
                        )

                        else -> state
                    }
                }
            }.onFailure { error ->
                _uiState.update { state ->
                    when (state) {
                        is MusicUiState.Content -> state.copy(
                            selectedChildren = emptyList(),
                            isLoadingDetail = false,
                            detailError = error.message ?: "Could not load this music item.",
                        )

                        else -> state
                    }
                }
            }
        }
    }

    fun closeItem() {
        _uiState.update { state ->
            when (state) {
                is MusicUiState.Content -> {
                    val previous = state.selectedStack.lastOrNull()
                    if (previous == null) {
                        state.copy(
                            selectedItem = null,
                            selectedChildren = emptyList(),
                            isLoadingDetail = false,
                            detailError = null,
                            selectedStack = emptyList(),
                        )
                    } else {
                        state.copy(
                            selectedItem = previous.item,
                            selectedChildren = previous.children,
                            isLoadingDetail = previous.isLoading,
                            detailError = previous.error,
                            selectedStack = state.selectedStack.dropLast(1),
                        )
                    }
                }

                else -> state
            }
        }
    }

    fun play(item: MusicItem, queue: List<MusicItem>) {
        if (!item.isPlayable) {
            openItem(item)
            return
        }

        val initialQueue = MusicSmartQueueBuilder.initialQueue(selected = item, candidates = queue)
            .ifEmpty { listOf(item) }
        playbackController.play(item, initialQueue)
        loadSmartContinuationQueue(item)
    }

    fun playQueueItem(item: MusicItem, queue: List<MusicItem>) {
        val index = playbackState.value.queue.indexOfFirst { queuedItem -> queuedItem.id == item.id }
        if (index >= 0) {
            playbackController.seekToQueueItem(index = index, positionMs = 0)
        } else {
            playbackController.play(item, MusicSmartQueueBuilder.initialQueue(selected = item, candidates = queue))
        }
    }

    fun performSongAction(action: MusicSongAction, item: MusicItem, queue: List<MusicItem>) {
        viewModelScope.launch {
            val request = MusicSongActionRequest.forItem(action = action, item = item, source = "phone")
            Timber.i("PTV music command songAction source=phone action=$action selectedItemId=${request.itemId}")
            runCatching {
                if (action.requiresSavedSession()) {
                    apiClientController.loadSavedServerUser()
                }
                songActionHandler.execute(request = request, item = item, queue = queue)
            }.onSuccess { result ->
                handleSongActionResult(result)
            }.onFailure { error ->
                Timber.e(
                    error,
                    "PTV music command songAction source=phone result=failure " +
                        "action=$action selectedItemId=${item.id} exception=${error::class.qualifiedName}",
                )
                setActionMessage(error.message ?: "Could not run that song action.")
            }
        }
    }

    fun shufflePlay(queue: List<MusicItem>) {
        val playableQueue = queue.filter(MusicItem::isPlayable)
        val firstItem = playableQueue.randomOrNull() ?: return
        playbackController.play(firstItem, playableQueue, shuffle = true)
    }

    fun togglePlayPause() {
        playbackController.togglePlayPause()
    }

    fun previous() {
        playbackController.previous()
    }

    fun next() {
        playbackController.next()
    }

    fun retryPlayback() {
        playbackController.retryFailed()
    }

    fun skipPlaybackError() {
        playbackController.skipFailed()
    }

    fun seekTo(positionMs: Long) {
        playbackController.seekTo(positionMs)
    }

    fun toggleShuffle() {
        Timber.i("PTV music command shuffle source=phone")
        playbackController.toggleShuffle(source = "phone")
    }

    fun cycleRepeatMode() {
        Timber.i("PTV music command repeat source=phone")
        playbackController.cycleRepeatMode(source = "phone")
    }

    fun clearPlaybackError() {
        playbackController.clearError()
    }

    fun clearActionMessage() {
        _uiState.update { state ->
            when (state) {
                is MusicUiState.Content -> state.copy(actionMessage = null)
                else -> state
            }
        }
    }

    fun reportNotificationPermissionResult(granted: Boolean) {
        Timber.i("PTV music notification permission result source=phone granted=$granted")
        if (granted) {
            playbackController.refreshPlaybackNotification(source = "phone.notificationPermissionGranted")
        } else {
            setActionMessage("Enable notifications to use PTV Music controls outside the app.")
        }
    }

    fun toggleFavorite(item: MusicItem) {
        performSongAction(MusicSongAction.TOGGLE_FAVORITE, item, listOf(item))
    }

    fun addCurrentToDefaultPlaylist() {
        val item = playbackState.value.currentItem
        if (item == null) {
            Timber.w("PTV music command addToPlaylist source=phone result=failure reason=no-current-item")
            setActionMessage("No PTV Music song is currently playing.")
            return
        }
        performSongAction(MusicSongAction.ADD_TO_PLAYLIST, item, playbackState.value.queue.ifEmpty { listOf(item) })
    }

    fun openCurrentPlaylistDialog() {
        val item = playbackState.value.currentItem ?: return
        openPlaylistDialog(MusicPlaylistTarget(item, MusicPlaylistTargetType.CURRENT_TRACK))
    }

    fun openSourcePlaylistDialog(item: MusicItem) {
        val type = when (item.type) {
            BaseItemKind.MUSIC_ALBUM -> MusicPlaylistTargetType.ALBUM

            BaseItemKind.MUSIC_ARTIST -> MusicPlaylistTargetType.ARTIST

            BaseItemKind.GENRE,
            BaseItemKind.MUSIC_GENRE,
            -> MusicPlaylistTargetType.GENRE

            else -> MusicPlaylistTargetType.CURRENT_TRACK
        }
        openPlaylistDialog(MusicPlaylistTarget(item, type))
    }

    fun closePlaylistDialog() {
        _uiState.update { state ->
            when (state) {
                is MusicUiState.Content -> state.copy(playlistAction = MusicPlaylistActionState())
                else -> state
            }
        }
    }

    fun addPlaylistTargetToPlaylist(playlist: MusicItem) {
        viewModelScope.launch {
            val content = _uiState.value as? MusicUiState.Content ?: return@launch
            val target = content.playlistAction.target ?: return@launch

            runCatching {
                repository.addToPlaylist(target, playlist)
            }.onSuccess { message ->
                closePlaylistDialog()
                setActionMessage(message)
            }.onFailure { error ->
                updatePlaylistActionError(error.message ?: "Could not add songs to this playlist.")
            }
        }
    }

    fun createPlaylist(name: String) {
        viewModelScope.launch {
            val content = _uiState.value as? MusicUiState.Content ?: return@launch
            val target = content.playlistAction.target ?: return@launch

            runCatching {
                repository.createPlaylist(name, target)
            }.onSuccess { message ->
                closePlaylistDialog()
                setActionMessage(message)
                refreshPlaylists()
            }.onFailure { error ->
                updatePlaylistActionError(error.message ?: "Could not create this playlist.")
            }
        }
    }

    private fun openPlaylistDialog(target: MusicPlaylistTarget) {
        viewModelScope.launch {
            _uiState.update { state ->
                when (state) {
                    is MusicUiState.Content -> state.copy(
                        playlistAction = MusicPlaylistActionState(
                            isVisible = true,
                            target = target,
                            playlists = state.home.playlists,
                            isLoading = true,
                            defaultName = target.defaultPlaylistName(),
                        ),
                    )

                    else -> state
                }
            }

            runCatching {
                apiClientController.loadSavedServerUser()
                repository.loadPlaylists()
            }.onSuccess { playlists ->
                _uiState.update { state ->
                    when (state) {
                        is MusicUiState.Content -> state.copy(
                            home = state.home.copy(playlists = playlists),
                            playlistAction = state.playlistAction.copy(
                                playlists = playlists,
                                isLoading = false,
                                errorMessage = null,
                            ),
                        )

                        else -> state
                    }
                }
            }.onFailure { error ->
                _uiState.update { state ->
                    when (state) {
                        is MusicUiState.Content -> state.copy(
                            playlistAction = state.playlistAction.copy(
                                isLoading = false,
                                errorMessage = error.message ?: "Could not load playlists.",
                            ),
                        )

                        else -> state
                    }
                }
            }
        }
    }

    private fun refreshPlaylists() {
        viewModelScope.launch {
            runCatching {
                apiClientController.loadSavedServerUser()
                repository.loadPlaylists()
            }.onSuccess { playlists ->
                _uiState.update { state ->
                    when (state) {
                        is MusicUiState.Content -> state.copy(home = state.home.copy(playlists = playlists))
                        else -> state
                    }
                }
            }
        }
    }

    private fun updatePlaylistActionError(message: String) {
        _uiState.update { state ->
            when (state) {
                is MusicUiState.Content -> state.copy(
                    playlistAction = state.playlistAction.copy(
                        isLoading = false,
                        errorMessage = message,
                    ),
                )

                else -> state
            }
        }
    }

    private suspend fun handleSongActionResult(result: MusicSongActionResult) {
        when (result) {
            is MusicSongActionResult.PlaybackStarted -> Unit

            is MusicSongActionResult.MixStarted -> setActionMessage(result.message)

            is MusicSongActionResult.QueueUpdated -> setActionMessage(result.message)

            is MusicSongActionResult.PlaylistUpdated -> setActionMessage(result.message)

            is MusicSongActionResult.FavoriteUpdated -> {
                replaceItem(result.item) { result.message }
                Timber.i(
                    "PTV music command favorite source=phone selectedItemId=${result.item.id} " +
                        "result=success favorite=${result.item.isFavorite}",
                )
            }

            is MusicSongActionResult.NotInterestedUpdated -> {
                _uiState.update { state ->
                    when (state) {
                        is MusicUiState.Content -> state.copy(
                            notInterestedItemIds = repository.notInterestedItemIds(),
                            actionMessage = result.message,
                        )

                        else -> state
                    }
                }
            }

            is MusicSongActionResult.ArtistRequested -> openArtistFor(result.item)

            is MusicSongActionResult.Unavailable -> setActionMessage(result.message)
        }
    }

    private fun loadSmartContinuationQueue(item: MusicItem) {
        viewModelScope.launch {
            runCatching {
                apiClientController.loadSavedServerUser()
                repository.loadAutoGeneratedQueue(seed = item, limit = SMART_QUEUE_LIMIT)
            }.onSuccess { generatedQueue ->
                if (playbackState.value.currentItem?.id != item.id) {
                    Timber.i(
                        "PTV music smart queue discarded selectedItemId=${item.id} " +
                            "currentItemId=${playbackState.value.currentItem?.id}",
                    )
                    return@onSuccess
                }

                if (generatedQueue.size <= 1) {
                    Timber.w(
                        "PTV music smart queue found no related tracks selectedItemId=${item.id}; " +
                            "keeping initial queue size=${playbackState.value.queue.size}",
                    )
                    return@onSuccess
                }

                playbackController.replaceQueueKeepingCurrent(
                    queue = generatedQueue,
                    source = "phone.smartContinuation",
                )
                Timber.i(
                    "PTV music smart queue installed selectedItemId=${item.id} " +
                        "count=${generatedQueue.size} first=${generatedQueue.firstOrNull()?.id}",
                )
            }.onFailure { error ->
                Timber.w(
                    error,
                    "PTV music smart queue failed selectedItemId=${item.id}; " +
                        "keeping initial queue size=${playbackState.value.queue.size}",
                )
            }
        }
    }

    private suspend fun openArtistFor(item: MusicItem) {
        val artist = item.artistIds.firstOrNull()
            ?.let { artistId -> repository.loadItem(artistId) }
            ?: item.artist
                ?.takeIf(String::isNotBlank)
                ?.let { artistName ->
                    repository.searchMusic(artistName).firstOrNull { result ->
                        result.type == BaseItemKind.MUSIC_ARTIST &&
                            result.title.equals(artistName, ignoreCase = true)
                    }
                }

        if (artist == null) {
            setActionMessage("Artist is not available for ${item.title}.")
        } else {
            openItem(artist)
        }
    }

    private fun MusicSongAction.requiresSavedSession(): Boolean = when (this) {
        MusicSongAction.PLAY,
        MusicSongAction.PLAY_NEXT,
        MusicSongAction.ADD_TO_QUEUE,
        MusicSongAction.DOWNLOAD,
        MusicSongAction.NOT_INTERESTED,
        -> false

        MusicSongAction.START_MIX,
        MusicSongAction.ADD_TO_PLAYLIST,
        MusicSongAction.GO_TO_ARTIST,
        MusicSongAction.TOGGLE_FAVORITE,
        -> true
    }

    private fun replaceItem(updatedItem: MusicItem, message: () -> String) {
        _uiState.update { state ->
            when (state) {
                is MusicUiState.Content -> state.copy(
                    home = state.home.replaceItem(updatedItem),
                    searchResults = state.searchResults.replaceItem(updatedItem),
                    selectedItem = state.selectedItem?.replaceWith(updatedItem),
                    selectedChildren = state.selectedChildren.replaceItem(updatedItem),
                    selectedStack = state.selectedStack.replaceStackItem(updatedItem),
                    actionMessage = message(),
                )

                else -> state
            }
        }
    }

    private fun setActionMessage(message: String) {
        _uiState.update { state ->
            when (state) {
                is MusicUiState.Content -> state.copy(actionMessage = message)
                else -> state
            }
        }
    }

    private fun MusicHome.replaceItem(updatedItem: MusicItem) = copy(
        recentlyAddedAlbums = recentlyAddedAlbums.replaceItem(updatedItem),
        albums = albums.replaceItem(updatedItem),
        artists = artists.replaceItem(updatedItem),
        songs = songs.replaceItem(updatedItem),
        genres = genres.replaceItem(updatedItem),
        playlists = playlists.replaceItem(updatedItem),
        favorites = when (updatedItem.isFavorite) {
            true -> favorites.replaceOrAppend(updatedItem)
            false -> favorites.filterNot { item -> item.id == updatedItem.id }
        },
        recentlyPlayed = recentlyPlayed.replaceItem(updatedItem),
        recommendations = recommendations.replaceItem(updatedItem),
    )

    private fun List<MusicItem>.replaceItem(updatedItem: MusicItem): List<MusicItem> = map { item ->
        item.replaceWith(updatedItem)
    }

    private fun List<MusicDetailSnapshot>.replaceStackItem(updatedItem: MusicItem): List<MusicDetailSnapshot> =
        map { snapshot ->
            snapshot.copy(
                item = snapshot.item.replaceWith(updatedItem),
                children = snapshot.children.replaceItem(updatedItem),
            )
        }

    private fun List<MusicItem>.replaceOrAppend(updatedItem: MusicItem): List<MusicItem> = when {
        any { item -> item.id == updatedItem.id } -> replaceItem(updatedItem)
        else -> listOf(updatedItem) + this
    }

    private fun MusicItem.replaceWith(updatedItem: MusicItem): MusicItem = when (id) {
        updatedItem.id -> updatedItem
        else -> this
    }

    private fun MusicPlaylistTarget.defaultPlaylistName(): String = when (type) {
        MusicPlaylistTargetType.CURRENT_TRACK -> "PTV Music"
        MusicPlaylistTargetType.ALBUM -> item.title
        MusicPlaylistTargetType.ARTIST -> "${item.title} Essentials"
        MusicPlaylistTargetType.GENRE -> "${item.title} Mix"
    }

    private companion object {
        const val SEARCH_DEBOUNCE_MS = 300L
        const val SMART_QUEUE_LIMIT = 50
    }
}

sealed interface MusicUiState {
    data object Loading : MusicUiState
    data class Content(
        val home: MusicHome,
        val searchQuery: String = "",
        val searchResults: List<MusicItem> = emptyList(),
        val isSearching: Boolean = false,
        val searchError: String? = null,
        val selectedItem: MusicItem? = null,
        val selectedChildren: List<MusicItem> = emptyList(),
        val selectedStack: List<MusicDetailSnapshot> = emptyList(),
        val isLoadingDetail: Boolean = false,
        val detailError: String? = null,
        val actionMessage: String? = null,
        val isLoadingMoreSongs: Boolean = false,
        val songsError: String? = null,
        val playlistAction: MusicPlaylistActionState = MusicPlaylistActionState(),
        val notInterestedItemIds: Set<UUID> = emptySet(),
    ) : MusicUiState
    data class Error(val message: String) : MusicUiState
}

data class MusicDetailSnapshot(
    val item: MusicItem,
    val children: List<MusicItem>,
    val isLoading: Boolean,
    val error: String?,
)
