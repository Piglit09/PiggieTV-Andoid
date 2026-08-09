@file:Suppress("LargeClass", "LongMethod", "TooGenericExceptionCaught", "TooManyFunctions")

package org.jellyfin.mobile.feature.music

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.genresApi
import org.jellyfin.sdk.api.client.extensions.imageApi
import org.jellyfin.sdk.api.client.extensions.itemsApi
import org.jellyfin.sdk.api.client.extensions.playlistsApi
import org.jellyfin.sdk.api.client.extensions.userApi
import org.jellyfin.sdk.api.client.extensions.userLibraryApi
import org.jellyfin.sdk.api.client.extensions.userViewsApi
import org.jellyfin.sdk.model.UUID
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.CollectionType
import org.jellyfin.sdk.model.api.CreatePlaylistDto
import org.jellyfin.sdk.model.api.ImageType
import org.jellyfin.sdk.model.api.ItemFields
import org.jellyfin.sdk.model.api.ItemFilter
import org.jellyfin.sdk.model.api.ItemSortBy
import org.jellyfin.sdk.model.api.MediaStreamType
import org.jellyfin.sdk.model.api.MediaType
import org.jellyfin.sdk.model.api.SortOrder
import org.jellyfin.sdk.model.serializer.toUUIDOrNull
import timber.log.Timber

class MusicRepository(
    private val apiClient: ApiClient,
    context: Context? = null,
    private val savedUserIdProvider: (() -> UUID?)? = null,
) {
    val instanceId: String = runtimeInstanceId(this)
    private val notInterestedPreferences: SharedPreferences? = context
        ?.applicationContext
        ?.getSharedPreferences(NOT_INTERESTED_PREFS_NAME, Context.MODE_PRIVATE)

    @Volatile
    private var cachedHome: MusicHome? = null

    @Volatile
    private var cachedBrowsePages: Map<MusicBrowseKind, MusicPage> = emptyMap()

    @Volatile
    private var cachedPlaybackTracks: List<MusicItem> = emptyList()

    @Volatile
    private var activeCacheScope: MusicCacheScope? = null

    private val cacheScopeLock = Any()

    @Volatile
    private var fallbackNotInterestedIds: Set<UUID> = emptySet()

    init {
        Timber.i(
            "PTV music repository instance created repositoryId=$instanceId " +
                "apiClientId=${runtimeInstanceId(apiClient)} host=${apiClient.safeBaseHost()}",
        )
    }

    private suspend fun resolveCurrentSession(): MusicCacheScope {
        val userId = savedUserIdProvider?.invoke() ?: apiClient.userApi.getCurrentUser().content.id
        return selectCacheScope(userId)
    }

    private suspend fun currentUserId(): UUID = resolveCurrentSession().userId

    private fun selectCacheScope(userId: UUID): MusicCacheScope {
        val nextScope = MusicCacheScope(serverUrl = apiClient.cacheScopeBaseUrl(), userId = userId)
        synchronized(cacheScopeLock) {
            val previousScope = activeCacheScope
            if (previousScope != null && previousScope != nextScope) {
                cachedHome = null
                cachedBrowsePages = emptyMap()
                cachedPlaybackTracks = emptyList()
                Timber.i("PTV music cache cleared after server or user changed")
            }
            activeCacheScope = nextScope
        }
        return nextScope
    }

    private fun emptyMusicHome(library: MusicLibrary? = null) = MusicHome(
        library = library,
        recentlyAddedAlbums = emptyList(),
        albums = emptyList(),
        albumsTotalCount = 0,
        artists = emptyList(),
        artistsTotalCount = 0,
        songs = emptyList(),
        songsTotalCount = 0,
        genres = emptyList(),
        playlists = emptyList(),
        favorites = emptyList(),
        recentlyPlayed = emptyList(),
        recommendations = emptyList(),
    )

    private fun publishHomeSnapshot(
        observer: (MusicHome) -> Unit,
        home: MusicHome,
        stage: String,
        expectedScope: MusicCacheScope,
    ) {
        if (activeCacheScope != expectedScope) {
            Timber.i("PTV music skipped stale $stage snapshot after server or user changed")
            return
        }
        runCatching { observer(home) }
            .onSuccess {
                Timber.i(
                    "PTV music published $stage snapshot albums=${home.albums.size} " +
                        "artists=${home.artists.size} songs=${home.songs.size}",
                )
            }
            .onFailure { error -> Timber.w(error, "PTV music $stage home observer failed") }
    }

    suspend fun loadHome(onInitialHome: (MusicHome) -> Unit = {}): MusicHome = withContext(Dispatchers.IO) {
        logRepositoryCall("loadHome") {
            val session = logRepositoryCall("resolveCurrentUser") {
                resolveCurrentSession()
            }
            val userId = session.userId
            val previousHome = cachedHome
            val shellHome = previousHome ?: emptyMusicHome()
            publishHomeSnapshot(onInitialHome, shellHome, stage = "session", expectedScope = session)

            val userViews = logRepositoryCall("getUserViews") {
                apiClient.userViewsApi.getUserViews(
                    includeExternalContent = false,
                    includeHidden = false,
                ).content.items
            }
            val musicLibrary = userViews.firstOrNull { item -> item.collectionType == CollectionType.MUSIC }
                ?.let { item -> MusicLibrary(id = item.id, name = item.name.orEmpty().ifBlank { "Music" }) }
            val scopedHome = shellHome.copy(library = musicLibrary)
            if (scopedHome.library != shellHome.library) {
                publishHomeSnapshot(onInitialHome, scopedHome, stage = "library", expectedScope = session)
            }

            logMusicLibraryContext(
                context = "home",
                userId = userId,
                userViews = userViews,
                musicLibrary = musicLibrary,
            )

            Timber.i(
                "PTV music home cache read cachedHome=${previousHome != null} " +
                    "browseKinds=${cachedBrowsePages.keys} playbackTracks=${cachedPlaybackTracks.size}",
            )
            supervisorScope {
                val homeRequestGate = Semaphore(HOME_MAX_CONCURRENT_REQUESTS)
                val recentlyAddedAlbums = async {
                    loadHomeList(
                        name = "recentlyAddedAlbums",
                        source = MusicHomeSource.RECENTLY_ADDED_ALBUMS,
                        cachedItems = previousHome?.recentlyAddedAlbums?.takeIf { it.isNotEmpty() }
                            ?: cachedItemsFor(MusicBrowseKind.RECENTLY_ADDED),
                        requestGate = homeRequestGate,
                        cacheScope = session,
                    ) {
                        getMusicItemsPageWithLibraryFallback(
                            queryName = "home.recentlyAddedAlbums",
                            userId = userId,
                            musicLibrary = musicLibrary,
                            includeItemTypes = listOf(BaseItemKind.MUSIC_ALBUM),
                            sortBy = listOf(ItemSortBy.DATE_CREATED),
                            sortOrder = listOf(SortOrder.DESCENDING),
                            fields = musicBrowseItemFields,
                            limit = ROW_LIMIT,
                        ).items
                    }
                }
                val albums = async {
                    loadHomePage(
                        name = "albums",
                        source = MusicHomeSource.ALBUMS,
                        cachedPage = previousHome?.albums.toCachedPage(previousHome?.albumsTotalCount)
                            ?: cachedPageFor(MusicBrowseKind.ALBUMS),
                        requestGate = homeRequestGate,
                        cacheScope = session,
                    ) {
                        getMusicItemsPageWithLibraryFallback(
                            queryName = "home.albums",
                            userId = userId,
                            musicLibrary = musicLibrary,
                            includeItemTypes = listOf(BaseItemKind.MUSIC_ALBUM),
                            sortBy = listOf(ItemSortBy.SORT_NAME),
                            fields = musicBrowseItemFields,
                            limit = ROW_LIMIT,
                        )
                    }
                }
                val artists = async {
                    loadHomePage(
                        name = "artists",
                        source = MusicHomeSource.ARTISTS,
                        cachedPage = previousHome?.artists.toCachedPage(previousHome?.artistsTotalCount)
                            ?: cachedPageFor(MusicBrowseKind.ARTISTS),
                        requestGate = homeRequestGate,
                        cacheScope = session,
                    ) {
                        getMusicItemsPageWithLibraryFallback(
                            queryName = "home.artists",
                            userId = userId,
                            musicLibrary = musicLibrary,
                            includeItemTypes = listOf(BaseItemKind.MUSIC_ARTIST),
                            sortBy = listOf(ItemSortBy.SORT_NAME),
                            fields = musicBrowseItemFields,
                            limit = ROW_LIMIT,
                        )
                    }
                }
                val songs = async {
                    loadHomePage(
                        name = "songs",
                        source = MusicHomeSource.SONGS,
                        cachedPage = previousHome?.songs.toCachedPage(previousHome?.songsTotalCount)
                            ?: cachedPageFor(MusicBrowseKind.SONGS),
                        requestGate = homeRequestGate,
                        cacheScope = session,
                    ) {
                        getMusicItemsPageWithLibraryFallback(
                            queryName = "home.songs",
                            userId = userId,
                            musicLibrary = musicLibrary,
                            includeItemTypes = listOf(BaseItemKind.AUDIO),
                            sortBy = listOf(ItemSortBy.SORT_NAME),
                            fields = musicBrowseItemFields,
                            enableTotalRecordCount = false,
                            limit = SONG_PAGE_SIZE,
                        )
                    }
                }
                val genres = async {
                    loadHomeList(
                        name = "genres",
                        source = MusicHomeSource.GENRES,
                        cachedItems = previousHome?.genres?.takeIf { it.isNotEmpty() }
                            ?: cachedItemsFor(MusicBrowseKind.GENRES),
                        requestGate = homeRequestGate,
                        cacheScope = session,
                    ) {
                        loadGenres(userId, musicLibrary?.id)
                    }
                }
                val playlists = async {
                    loadHomeList(
                        name = "playlists",
                        source = MusicHomeSource.PLAYLISTS,
                        cachedItems = previousHome?.playlists?.takeIf { it.isNotEmpty() }
                            ?: cachedItemsFor(MusicBrowseKind.PLAYLISTS),
                        requestGate = homeRequestGate,
                        cacheScope = session,
                    ) {
                        loadMusicPlaylists(
                            userId = userId,
                            sortBy = listOf(ItemSortBy.SORT_NAME),
                            limit = ROW_LIMIT,
                        )
                    }
                }
                val favorites = async {
                    loadHomeList(
                        name = "favorites",
                        source = MusicHomeSource.FAVORITES,
                        cachedItems = previousHome?.favorites?.takeIf { it.isNotEmpty() }
                            ?: cachedItemsFor(MusicBrowseKind.FAVORITES),
                        requestGate = homeRequestGate,
                        cacheScope = session,
                    ) {
                        getMusicItemsPageWithLibraryFallback(
                            queryName = "home.favorites",
                            userId = userId,
                            musicLibrary = musicLibrary,
                            includeItemTypes = listOf(BaseItemKind.AUDIO),
                            isFavorite = true,
                            sortBy = listOf(ItemSortBy.SORT_NAME),
                            fields = musicBrowseItemFields,
                            enableTotalRecordCount = false,
                            limit = ROW_LIMIT,
                        ).items
                    }
                }
                val recentlyPlayed = async {
                    loadHomeList(
                        name = "recentlyPlayed",
                        source = MusicHomeSource.RECENTLY_PLAYED,
                        cachedItems = previousHome?.recentlyPlayed?.takeIf { it.isNotEmpty() }
                            ?: cachedItemsFor(MusicBrowseKind.RECENTLY_PLAYED),
                        requestGate = homeRequestGate,
                        cacheScope = session,
                    ) {
                        getMusicItemsPageWithLibraryFallback(
                            queryName = "home.recentlyPlayed",
                            userId = userId,
                            musicLibrary = musicLibrary,
                            includeItemTypes = listOf(BaseItemKind.AUDIO),
                            filters = listOf(ItemFilter.IS_PLAYED),
                            sortBy = listOf(ItemSortBy.DATE_PLAYED),
                            sortOrder = listOf(SortOrder.DESCENDING),
                            fields = musicBrowseItemFields,
                            enableTotalRecordCount = false,
                            limit = ROW_LIMIT,
                        ).items
                    }
                }

                val loadedSongs = songs.await()
                val cachedArtists = if (activeCacheScope == session) {
                    cachedPageFor(MusicBrowseKind.ARTISTS)
                        ?.takeIf { page -> page.items.isNotEmpty() }
                        ?.let { page ->
                            HomeSourceLoadResult(
                                source = MusicHomeSource.ARTISTS,
                                page = page,
                                fromCache = true,
                            )
                        }
                } else {
                    null
                }
                val preferredArtists = async {
                    withTimeoutOrNull(HOME_ARTIST_QUERY_GRACE_MS) { artists.await() }
                        ?: cachedArtists?.also {
                            Timber.i(
                                "PTV music home.artists using derived song metadata instead of waiting for the full query",
                            )
                            artists.cancel()
                        }
                        ?: artists.await()
                }
                val songsHome = scopedHome.mergeHomeSources(listOfNotNull(loadedSongs, cachedArtists))
                publishHomeSnapshot(onInitialHome, songsHome, stage = "songs", expectedScope = session)

                val loadedAlbums = albums.await()
                val loadedFavorites = favorites.await()
                val loadedRecent = recentlyPlayed.await()
                val loadedGenres = genres.await()
                val fastHome = scopedHome.mergeHomeSources(
                    listOfNotNull(
                        loadedSongs,
                        loadedAlbums,
                        cachedArtists,
                        loadedFavorites,
                        loadedRecent,
                        loadedGenres,
                    ),
                )
                publishHomeSnapshot(onInitialHome, fastHome, stage = "priority-rows", expectedScope = session)

                val recommendations = async {
                    loadHomeList(
                        name = "recommendations",
                        source = MusicHomeSource.RECOMMENDATIONS,
                        cachedItems = previousHome?.recommendations?.takeIf { it.isNotEmpty() }
                            ?: cachedItemsFor(MusicBrowseKind.RECOMMENDATIONS),
                        requestGate = homeRequestGate,
                        cacheScope = session,
                    ) {
                        loadRecommendations(
                            userId = userId,
                            musicLibraryId = musicLibrary?.id,
                            seedItems = loadedFavorites.items + loadedRecent.items,
                            genres = loadedGenres.items,
                            fallbackSongs = loadedSongs.page.items,
                        )
                    }
                }

                val loadedRecentlyAddedAlbums = recentlyAddedAlbums.await()
                val loadedArtists = preferredArtists.await()
                val loadedPlaylists = playlists.await()
                val loadedRecommendations = recommendations.await()
                val sourceResults = listOf(
                    loadedRecentlyAddedAlbums,
                    loadedAlbums,
                    loadedArtists,
                    loadedSongs,
                    loadedGenres,
                    loadedPlaylists,
                    loadedFavorites,
                    loadedRecent,
                    loadedRecommendations,
                ).map { result -> result.withLatestCacheFallback(expectedScope = session) }
                val sourceResultsByType = sourceResults.associateBy(HomeSourceLoadResult::source)

                val home = MusicHome(
                    library = musicLibrary,
                    recentlyAddedAlbums = sourceResultsByType.getValue(MusicHomeSource.RECENTLY_ADDED_ALBUMS).items,
                    albums = sourceResultsByType.getValue(MusicHomeSource.ALBUMS).page.items,
                    albumsTotalCount = sourceResultsByType.getValue(MusicHomeSource.ALBUMS).page.totalCount,
                    artists = sourceResultsByType.getValue(MusicHomeSource.ARTISTS).page.items,
                    artistsTotalCount = sourceResultsByType.getValue(MusicHomeSource.ARTISTS).page.totalCount,
                    songs = sourceResultsByType.getValue(MusicHomeSource.SONGS).page.items,
                    songsTotalCount = sourceResultsByType.getValue(MusicHomeSource.SONGS).page.totalCount,
                    genres = sourceResultsByType.getValue(MusicHomeSource.GENRES).items,
                    playlists = sourceResultsByType.getValue(MusicHomeSource.PLAYLISTS).items,
                    favorites = sourceResultsByType.getValue(MusicHomeSource.FAVORITES).items,
                    recentlyPlayed = sourceResultsByType.getValue(MusicHomeSource.RECENTLY_PLAYED).items,
                    recommendations = sourceResultsByType.getValue(MusicHomeSource.RECOMMENDATIONS).items,
                    sourceErrors = sourceResults.mapNotNull { result ->
                        result.errorMessage?.let { message -> result.source to message }
                    }.toMap(),
                    sourceCacheHits = sourceResults.filter(
                        HomeSourceLoadResult::fromCache,
                    ).mapTo(mutableSetOf()) { result ->
                        result.source
                    },
                )
                if (home.hasAnyLibraryContent) {
                    val cacheAccepted = synchronized(cacheScopeLock) {
                        if (activeCacheScope == session) {
                            cachedHome = home
                            true
                        } else {
                            false
                        }
                    }
                    if (cacheAccepted) {
                        cacheHome(home, expectedScope = session)
                        Timber.i(
                            "PTV music home cache write albums=${home.albumsTotalCount} " +
                                "artists=${home.artistsTotalCount} songs=${home.songsTotalCount} " +
                                "errors=${home.sourceErrors.keys} cacheHits=${home.sourceCacheHits}",
                        )
                    } else {
                        Timber.i("PTV music skipped stale home cache after server or user changed")
                    }
                } else {
                    Timber.w(
                        "PTV music home returned no records; not caching empty failed state. " +
                            "libraryId=${musicLibrary?.id} host=${apiClient.safeBaseHost()} " +
                            "userId=$userId errors=${home.sourceErrors.keys}",
                    )
                }
                home
            }
        }
    }

    suspend fun searchMusic(query: String): List<MusicItem> = withContext(Dispatchers.IO) {
        searchMusicPage(query = query, startIndex = 0, limit = SEARCH_LIMIT).items
    }

    suspend fun searchMusicPage(query: String, startIndex: Int, limit: Int): MusicPage = withContext(Dispatchers.IO) {
        val userId = currentUserId()
        getMusicItemsPage(
            queryName = "search",
            userId = userId,
            searchTerm = query.trim(),
            includeItemTypes = listOf(
                BaseItemKind.AUDIO,
                BaseItemKind.MUSIC_ALBUM,
                BaseItemKind.MUSIC_ARTIST,
                BaseItemKind.PLAYLIST,
            ),
            sortBy = listOf(ItemSortBy.SORT_NAME),
            fields = musicBrowseItemFields,
            enableTotalRecordCount = false,
            startIndex = startIndex,
            limit = limit.coerceIn(1, SEARCH_LIMIT),
        )
    }

    suspend fun loadSongsPage(startIndex: Int): MusicPage = withContext(Dispatchers.IO) {
        loadSongsPage(startIndex = startIndex, limit = SONG_PAGE_SIZE)
    }

    suspend fun loadSongsPage(startIndex: Int, limit: Int): MusicPage = withContext(Dispatchers.IO) {
        val userId = currentUserId()
        val musicLibrary = loadMusicLibrary(userId = userId, context = "songsPage")
        getMusicItemsPageWithLibraryFallback(
            queryName = "songsPage",
            userId = userId,
            musicLibrary = musicLibrary,
            includeItemTypes = listOf(BaseItemKind.AUDIO),
            sortBy = listOf(ItemSortBy.SORT_NAME),
            fields = musicBrowseItemFields,
            enableTotalRecordCount = false,
            startIndex = startIndex,
            limit = limit.coerceIn(1, AUTO_PAGE_LIMIT),
        ).also { page ->
            cacheBrowsePage(MusicBrowseKind.SONGS, page, "songsPage")
            rememberPlaybackTracks(page.items, "songsPage")
        }
    }

    suspend fun loadBrowsePage(kind: MusicBrowseKind, startIndex: Int, limit: Int): MusicPage =
        withContext(Dispatchers.IO) {
            val pageLimit = limit.coerceIn(1, AUTO_PAGE_LIMIT)
            cachedBrowsePage(kind = kind, startIndex = startIndex, limit = pageLimit)?.let { cachedPage ->
                if (cachedPage.hasRequestedWindow(startIndex = startIndex, limit = pageLimit)) {
                    Timber.i(
                        "PTV music browse $kind using cached phone data immediately " +
                            "count=${cachedPage.items.size}/${cachedPage.totalCount} start=$startIndex limit=$pageLimit " +
                            "repositoryId=$instanceId",
                    )
                    return@withContext cachedPage
                }

                Timber.i(
                    "PTV music browse $kind cache is partial; trying fresh page " +
                        "cached=${cachedPage.items.size}/${cachedPage.totalCount} start=$startIndex limit=$pageLimit " +
                        "repositoryId=$instanceId",
                )
            }

            val page = runCatching {
                loadBrowsePageFromApi(kind = kind, startIndex = startIndex, limit = pageLimit)
            }.getOrElse { error ->
                val cachedPage = cachedBrowsePage(kind = kind, startIndex = startIndex, limit = pageLimit)
                if (cachedPage != null) {
                    Timber.w(
                        error,
                        "PTV music browse $kind failed; using cached phone data count=${cachedPage.items.size}",
                    )
                    return@withContext cachedPage
                }

                Timber.w(
                    error,
                    "PTV music browse $kind repository not ready; returning loading-safe empty page " +
                        "start=$startIndex limit=$pageLimit repositoryId=$instanceId",
                )
                return@withContext MusicPage(emptyList(), totalCount = 0, startIndex = startIndex)
            }

            if (page.items.isEmpty() && page.totalCount == 0) {
                val cachedPage = cachedBrowsePage(kind = kind, startIndex = startIndex, limit = pageLimit)
                if (cachedPage != null) {
                    Timber.w(
                        "PTV music browse $kind returned empty; using cached phone data count=${cachedPage.items.size}",
                    )
                    return@withContext cachedPage
                }
            }

            cacheBrowsePage(kind, page, "browse.$kind")
            if (kind == MusicBrowseKind.SONGS) rememberPlaybackTracks(page.items, "browse.$kind")
            page
        }

    fun cachedBrowsePage(kind: MusicBrowseKind, startIndex: Int, limit: Int): MusicPage? {
        if (!isCacheVisibleForConfiguredSession()) return null
        return cachedBrowsePageInternal(kind = kind, startIndex = startIndex, limit = limit)
    }

    private suspend fun loadBrowsePageFromApi(kind: MusicBrowseKind, startIndex: Int, limit: Int): MusicPage {
        val userId = currentUserId()
        val musicLibrary = loadMusicLibrary(userId = userId, context = "browse.$kind")

        return when (kind) {
            MusicBrowseKind.RECENTLY_ADDED -> getMusicItemsPageWithLibraryFallback(
                queryName = "browse.recentlyAdded",
                userId = userId,
                musicLibrary = musicLibrary,
                includeItemTypes = listOf(BaseItemKind.MUSIC_ALBUM),
                sortBy = listOf(ItemSortBy.DATE_CREATED),
                sortOrder = listOf(SortOrder.DESCENDING),
                startIndex = startIndex,
                limit = limit,
            )

            MusicBrowseKind.ALBUMS -> getMusicItemsPageWithLibraryFallback(
                queryName = "browse.albums",
                userId = userId,
                musicLibrary = musicLibrary,
                includeItemTypes = listOf(BaseItemKind.MUSIC_ALBUM),
                sortBy = listOf(ItemSortBy.SORT_NAME),
                startIndex = startIndex,
                limit = limit,
            )

            MusicBrowseKind.ARTISTS -> getMusicItemsPageWithLibraryFallback(
                queryName = "browse.artists",
                userId = userId,
                musicLibrary = musicLibrary,
                includeItemTypes = listOf(BaseItemKind.MUSIC_ARTIST),
                sortBy = listOf(ItemSortBy.SORT_NAME),
                startIndex = startIndex,
                limit = limit,
            )

            MusicBrowseKind.SONGS -> getMusicItemsPageWithLibraryFallback(
                queryName = "browse.songs",
                userId = userId,
                musicLibrary = musicLibrary,
                includeItemTypes = listOf(BaseItemKind.AUDIO),
                sortBy = listOf(ItemSortBy.SORT_NAME),
                fields = musicBrowseItemFields,
                enableTotalRecordCount = false,
                startIndex = startIndex,
                limit = limit,
            )

            MusicBrowseKind.GENRES -> loadGenresPage(
                userId = userId,
                parentId = musicLibrary?.id,
                startIndex = startIndex,
                limit = limit,
            )

            MusicBrowseKind.PLAYLISTS -> getMusicItemsPage(
                userId = userId,
                includeItemTypes = listOf(BaseItemKind.PLAYLIST),
                sortBy = listOf(ItemSortBy.SORT_NAME),
                startIndex = startIndex,
                limit = limit,
            ).filterToRealMusicPlaylists(userId)

            MusicBrowseKind.FAVORITES -> getMusicItemsPageWithLibraryFallback(
                queryName = "browse.favorites",
                userId = userId,
                musicLibrary = musicLibrary,
                includeItemTypes = listOf(BaseItemKind.AUDIO),
                isFavorite = true,
                sortBy = listOf(ItemSortBy.SORT_NAME),
                fields = musicBrowseItemFields,
                enableTotalRecordCount = false,
                startIndex = startIndex,
                limit = limit,
            )

            MusicBrowseKind.RECENTLY_PLAYED -> getMusicItemsPageWithLibraryFallback(
                queryName = "browse.recentlyPlayed",
                userId = userId,
                musicLibrary = musicLibrary,
                includeItemTypes = listOf(BaseItemKind.AUDIO),
                filters = listOf(ItemFilter.IS_PLAYED),
                sortBy = listOf(ItemSortBy.DATE_PLAYED),
                sortOrder = listOf(SortOrder.DESCENDING),
                fields = musicBrowseItemFields,
                enableTotalRecordCount = false,
                startIndex = startIndex,
                limit = limit,
            )

            MusicBrowseKind.RECOMMENDATIONS -> {
                val home = loadHome()
                home.recommendations.toPage(startIndex = startIndex, limit = limit)
            }
        }
    }

    suspend fun loadPlaylists(): List<MusicItem> = withContext(Dispatchers.IO) {
        val userId = currentUserId()
        loadMusicPlaylists(
            userId = userId,
            sortBy = listOf(ItemSortBy.SORT_NAME),
            limit = PLAYLIST_LIMIT,
        )
    }

    suspend fun loadPlaybackQueueForTrack(item: MusicItem): List<MusicItem> = withContext(Dispatchers.IO) {
        if (!item.isPlayable) return@withContext emptyList()

        val userId = currentUserId()
        val albumQueue = item.albumId?.let { albumId ->
            getMusicItems(
                queryName = "queue.albumTracks",
                userId = userId,
                parentId = albumId,
                includeItemTypes = listOf(BaseItemKind.AUDIO),
                sortBy = listOf(ItemSortBy.PARENT_INDEX_NUMBER, ItemSortBy.INDEX_NUMBER, ItemSortBy.SORT_NAME),
                limit = PLAYLIST_ITEM_LIMIT,
            )
        }.orEmpty()

        albumQueue
            .filter(MusicItem::isPlayable)
            .ensureContains(item)
            .also { queue -> rememberPlaybackTracks(queue, "playbackQueue") }
    }

    suspend fun addToAutoPlaylist(
        item: MusicItem,
        playlistName: String = AUTO_PLAYLIST_NAME,
        source: String = "repository",
    ): String = withContext(Dispatchers.IO) {
        runCatching {
            if (!item.isPlayable) {
                throw UnsupportedOperationException("No playable PTV Music song is currently selected.")
            }

            val userId = currentUserId()
            Timber.i(
                "PTV music command addToAutoPlaylist source=$source currentTrackTitle=${item.title} " +
                    "currentItemId=${item.id} userId=$userId playlistName=$playlistName",
            )

            val playlist = findPlaylistByName(userId = userId, playlistName = playlistName)
                ?.toAutoPlaylistRef()
                ?: createAutoPlaylist(
                    userId = userId,
                    item = item,
                    playlistName = playlistName,
                    source = source,
                )

            addCurrentItemToPlaylist(
                userId = userId,
                item = item,
                playlist = playlist,
                source = source,
            )
        }.getOrElse { error ->
            Timber.e(
                error,
                "PTV music command addToAutoPlaylist source=$source result=failure " +
                    "currentTrackTitle=${item.title} currentItemId=${item.id} playlistName=$playlistName " +
                    "exception=${error::class.qualifiedName} reason=${error.message}",
            )
            throw error
        }
    }

    fun loadCachedGeneratedQueue(seed: MusicItem, limit: Int = AUTO_GENERATED_QUEUE_LIMIT): List<MusicItem> {
        val requestedLimit = limit.coerceIn(MIN_GENERATED_MIX_TRACKS, MAX_GENERATED_MIX_TRACKS)
        if (!isCacheVisibleForConfiguredSession()) {
            return listOf(seed).filter(MusicItem::isPlayable)
        }

        val recommendations = cachedItemsFor(MusicBrowseKind.RECOMMENDATIONS)
        val favorites = cachedItemsFor(MusicBrowseKind.FAVORITES)
        val recentlyPlayed = cachedItemsFor(MusicBrowseKind.RECENTLY_PLAYED)
        val songs = cachedItemsFor(MusicBrowseKind.SONGS)
        val candidates = (recommendations + favorites + recentlyPlayed + songs + cachedPlaybackTracks)
            .filter(MusicItem::isPlayable)
            .distinctBy(MusicItem::id)
        val seedArtistIds = seed.artistIds.toSet()
        val seedArtistName = seed.artist.orEmpty().normalizeMusicText()
        val seedAlbumName = seed.album.orEmpty().normalizeMusicText()
        val seedGenres = seed.genres.map { genre -> genre.normalizeMusicText() }.filter(String::isNotBlank).toSet()
        val albumAffinity = candidates.filter { item ->
            (seed.albumId != null && item.albumId == seed.albumId) ||
                (seedAlbumName.isNotBlank() && item.album.orEmpty().normalizeMusicText() == seedAlbumName)
        }
        val artistAffinity = candidates.filter { item ->
            item.artistIds.any(seedArtistIds::contains) ||
                (seedArtistName.isNotBlank() && item.artist.orEmpty().normalizeMusicText() == seedArtistName)
        }
        val genreAffinity = candidates.filter { item ->
            item.genres.any { genre -> genre.normalizeMusicText() in seedGenres }
        }

        return MusicGeneratedMixBuilder.buildRanked(
            seed = seed,
            groups = listOf(
                MusicRecommendationGroup(MusicRecommendationSource.ALBUM_AFFINITY, albumAffinity),
                MusicRecommendationGroup(MusicRecommendationSource.ARTIST_AFFINITY, artistAffinity),
                MusicRecommendationGroup(MusicRecommendationSource.GENRE_AFFINITY, genreAffinity),
                MusicRecommendationGroup(MusicRecommendationSource.RECOMMENDATION, recommendations),
                MusicRecommendationGroup(MusicRecommendationSource.FAVORITE, favorites),
                MusicRecommendationGroup(MusicRecommendationSource.LISTENING_HISTORY, recentlyPlayed),
                MusicRecommendationGroup(MusicRecommendationSource.DISCOVERY, songs + cachedPlaybackTracks),
            ),
            affinityItems = (listOf(seed) + favorites + recentlyPlayed).distinctBy(MusicItem::id),
            notInterestedIds = notInterestedItemIds(),
            maxTracks = requestedLimit,
            surface = MusicRecommendationSurface.RADIO,
        ).also { tracks ->
            rememberPlaybackTracks(tracks, "cachedGeneratedQueue")
            Timber.i(
                "PTV music recommendation cache fast path surface=radio resultCount=${tracks.size} " +
                    "candidateCount=${candidates.size} albumAffinity=${albumAffinity.size} " +
                    "artistAffinity=${artistAffinity.size} genreAffinity=${genreAffinity.size}",
            )
        }
    }

    suspend fun loadAutoGeneratedQueue(seed: MusicItem, limit: Int = AUTO_GENERATED_QUEUE_LIMIT): List<MusicItem> =
        withContext(Dispatchers.IO) {
            val requestedLimit = limit.coerceIn(MIN_GENERATED_MIX_TRACKS, MAX_GENERATED_MIX_TRACKS)
            val userId = currentUserId()
            val musicLibrary = loadMusicLibrary()
            val cachedRecommendations = cachedItemsFor(MusicBrowseKind.RECOMMENDATIONS)
            val cachedSongs = cachedItemsFor(MusicBrowseKind.SONGS)
            val cachedFavoriteSeeds = cachedItemsFor(MusicBrowseKind.FAVORITES).take(RECOMMENDATION_SEED_LIMIT)
            val cachedRecentSeeds = cachedItemsFor(MusicBrowseKind.RECENTLY_PLAYED).take(RECOMMENDATION_SEED_LIMIT)
            val cachedGenres = cachedItemsFor(MusicBrowseKind.GENRES)
            val (generatedSources, recommendationCandidates) = supervisorScope {
                val sourceRequests = listOf(
                    async { loadGenres(userId = userId, parentId = musicLibrary?.id) },
                    async {
                        getMusicItems(
                            queryName = "autoQueue.favoriteSeeds",
                            userId = userId,
                            parentId = musicLibrary?.id,
                            includeItemTypes = listOf(BaseItemKind.AUDIO),
                            isFavorite = true,
                            sortBy = listOf(ItemSortBy.SORT_NAME),
                            fields = musicRecommendationItemFields,
                            enableTotalRecordCount = false,
                            limit = RECOMMENDATION_SEED_LIMIT,
                        )
                    },
                    async {
                        getMusicItems(
                            queryName = "autoQueue.recentSeeds",
                            userId = userId,
                            parentId = musicLibrary?.id,
                            includeItemTypes = listOf(BaseItemKind.AUDIO),
                            filters = listOf(ItemFilter.IS_PLAYED),
                            sortBy = listOf(ItemSortBy.DATE_PLAYED),
                            sortOrder = listOf(SortOrder.DESCENDING),
                            fields = musicRecommendationItemFields,
                            enableTotalRecordCount = false,
                            limit = RECOMMENDATION_SEED_LIMIT,
                        )
                    },
                    async {
                        getMusicItems(
                            queryName = "autoQueue.recentlyAddedAudio",
                            userId = userId,
                            parentId = musicLibrary?.id,
                            includeItemTypes = listOf(BaseItemKind.AUDIO),
                            sortBy = listOf(ItemSortBy.DATE_CREATED),
                            sortOrder = listOf(SortOrder.DESCENDING),
                            fields = musicRecommendationItemFields,
                            enableTotalRecordCount = false,
                            limit = requestedLimit.coerceAtLeast(ROW_LIMIT),
                        )
                    },
                    async {
                        seed.albumId?.let { albumId ->
                            getMusicItems(
                                queryName = "autoQueue.seedAlbum",
                                userId = userId,
                                parentId = albumId,
                                includeItemTypes = listOf(BaseItemKind.AUDIO),
                                sortBy = listOf(
                                    ItemSortBy.PARENT_INDEX_NUMBER,
                                    ItemSortBy.INDEX_NUMBER,
                                    ItemSortBy.SORT_NAME,
                                ),
                                fields = musicRecommendationItemFields,
                                enableTotalRecordCount = false,
                                limit = requestedLimit,
                            )
                        }.orEmpty()
                    },
                    async {
                        if (seed.artistIds.isEmpty()) {
                            emptyList()
                        } else {
                            getMusicItems(
                                queryName = "autoQueue.seedArtists",
                                userId = userId,
                                parentId = musicLibrary?.id,
                                includeItemTypes = listOf(BaseItemKind.AUDIO),
                                artistIds = seed.artistIds.take(RECOMMENDATION_SEED_LIMIT),
                                sortBy = listOf(ItemSortBy.RANDOM),
                                fields = musicRecommendationItemFields,
                                enableTotalRecordCount = false,
                                limit = requestedLimit,
                            )
                        }
                    },
                )
                val recommendationsRequest = async {
                    loadRecommendations(
                        userId = userId,
                        musicLibraryId = musicLibrary?.id,
                        seedItems = (listOf(seed) + cachedFavoriteSeeds + cachedRecentSeeds)
                            .distinctBy(MusicItem::id),
                        genres = cachedGenres,
                        fallbackSongs = (cachedFavoriteSeeds + cachedRecentSeeds + cachedSongs)
                            .distinctBy(MusicItem::id),
                    )
                }
                sourceRequests.awaitAll() to recommendationsRequest.await()
            }
            val favoriteSeeds = generatedSources[1]
            val recentSeeds = generatedSources[2]
            val recentlyAdded = generatedSources[3]
            val albumFallback = generatedSources[4]
            val artistFallback = generatedSources[5]
            val fallbackUsed = recommendationCandidates.isEmpty()
            val candidateGroups = listOf(
                MusicRecommendationGroup(MusicRecommendationSource.RECOMMENDATION, recommendationCandidates),
                MusicRecommendationGroup(MusicRecommendationSource.ALBUM_AFFINITY, albumFallback),
                MusicRecommendationGroup(MusicRecommendationSource.ARTIST_AFFINITY, artistFallback),
                MusicRecommendationGroup(MusicRecommendationSource.RECOMMENDATION, cachedRecommendations),
                MusicRecommendationGroup(MusicRecommendationSource.FALLBACK, cachedSongs),
                MusicRecommendationGroup(MusicRecommendationSource.FAVORITE, favoriteSeeds),
                MusicRecommendationGroup(MusicRecommendationSource.LISTENING_HISTORY, recentSeeds),
                MusicRecommendationGroup(MusicRecommendationSource.DISCOVERY, recentlyAdded),
            )

            MusicGeneratedMixBuilder.buildRanked(
                seed = seed,
                groups = candidateGroups,
                affinityItems = (listOf(seed) + favoriteSeeds + recentSeeds).distinctBy(MusicItem::id),
                notInterestedIds = notInterestedItemIds(),
                maxTracks = requestedLimit,
                surface = MusicRecommendationSurface.RADIO,
            )
                .also { tracks ->
                    rememberPlaybackTracks(tracks, "autoGeneratedQueue")
                    Timber.i(
                        "PTV music recommendation generation surface=radio policy=familiar25-discovery75 " +
                            "source=recommendations+album+artist+cached resultCount=${tracks.size} " +
                            "candidateCount=${candidateGroups.flatMap(
                                MusicRecommendationGroup::items,
                            ).distinctBy(MusicItem::id).size} " +
                            "fallbackUsed=$fallbackUsed notInterestedFiltered=${notInterestedItemIds().size}",
                    )
                }
        }

    suspend fun loadAutoPicksQueue(seed: MusicItem? = null, limit: Int = AUTO_GENERATED_QUEUE_LIMIT): List<MusicItem> =
        withContext(Dispatchers.IO) {
            val requestedLimit = limit.coerceIn(MIN_GENERATED_MIX_TRACKS, MAX_GENERATED_MIX_TRACKS)
            val cachedCandidates = cachedAutoPicksCandidates(seed)
            suspend fun loadSource(
                name: String,
                fallback: List<MusicItem> = emptyList(),
                block: suspend () -> List<MusicItem>,
            ): List<MusicItem> {
                val result = withTimeoutOrNull(AUTO_PICK_SOURCE_TIMEOUT_MS) {
                    runCatching { block() }
                }
                if (result == null) {
                    Timber.w("PTV music auto picks source=$name timed out; using fallback count=${fallback.size}")
                    return fallback
                }
                return result.onFailure { error ->
                    Timber.w(error, "PTV music auto picks source=$name failed")
                }.getOrDefault(fallback)
            }

            supervisorScope {
                val generated = async {
                    val item = seed?.takeIf(MusicItem::isPlayable) ?: return@async emptyList()
                    loadSource(name = "seedMix", fallback = cachedCandidates) {
                        loadAutoGeneratedQueue(seed = item, limit = requestedLimit)
                    }
                }
                val recommendations = async {
                    loadSource(
                        name = "recommendations",
                        fallback = cachedItemsFor(MusicBrowseKind.RECOMMENDATIONS),
                    ) {
                        loadBrowsePage(
                            kind = MusicBrowseKind.RECOMMENDATIONS,
                            startIndex = 0,
                            limit = requestedLimit,
                        ).items
                    }
                }
                val favorites = async {
                    loadSource(name = "favorites", fallback = cachedItemsFor(MusicBrowseKind.FAVORITES)) {
                        loadBrowsePage(
                            kind = MusicBrowseKind.FAVORITES,
                            startIndex = 0,
                            limit = requestedLimit,
                        ).items
                    }
                }
                val recent = async {
                    loadSource(name = "recentlyPlayed", fallback = cachedItemsFor(MusicBrowseKind.RECENTLY_PLAYED)) {
                        loadBrowsePage(
                            kind = MusicBrowseKind.RECENTLY_PLAYED,
                            startIndex = 0,
                            limit = requestedLimit,
                        ).items
                    }
                }
                val savedPicks = async {
                    loadSource(name = "savedPlaylist") {
                        loadAutoPlaylistTracks(limit = requestedLimit)
                    }
                }

                val blockedIds = notInterestedItemIds()
                val generatedTracks = generated.await()
                val recommendationTracks = recommendations.await()
                val favoriteTracks = favorites.await()
                val recentTracks = recent.await()
                val savedTracks = savedPicks.await()
                val candidateGroups = listOf(
                    MusicRecommendationGroup(MusicRecommendationSource.RECOMMENDATION, generatedTracks),
                    MusicRecommendationGroup(MusicRecommendationSource.RECOMMENDATION, recommendationTracks),
                    MusicRecommendationGroup(MusicRecommendationSource.FAVORITE, favoriteTracks),
                    MusicRecommendationGroup(MusicRecommendationSource.LISTENING_HISTORY, recentTracks),
                    MusicRecommendationGroup(MusicRecommendationSource.FAVORITE, savedTracks),
                    MusicRecommendationGroup(MusicRecommendationSource.FALLBACK, cachedCandidates),
                )
                MusicRecommendationRanker.rank(
                    seed = seed,
                    groups = candidateGroups,
                    affinityItems = (listOfNotNull(seed) + favoriteTracks + recentTracks)
                        .distinctBy(MusicItem::id),
                    notInterestedIds = blockedIds,
                    maxItems = requestedLimit,
                    surface = MusicRecommendationSurface.MADE_FOR_YOU,
                )
                    .also { tracks ->
                        rememberPlaybackTracks(tracks, "autoPicksQueue")
                        Timber.i(
                            "PTV music recommendation generation surface=made_for_you " +
                                "policy=familiar70-discovery30 resultCount=${tracks.size} " +
                                "seedPresent=${seed != null} cached=${cachedCandidates.size} " +
                                "generated=${generatedTracks.size} recommendations=${recommendationTracks.size} " +
                                "favorites=${favoriteTracks.size} recent=${recentTracks.size} saved=${savedTracks.size}",
                        )
                    }
            }
        }

    suspend fun loadRecentlyAddedTracks(limit: Int = AUTO_GENERATED_QUEUE_LIMIT): List<MusicItem> =
        withContext(Dispatchers.IO) {
            val userId = currentUserId()
            val musicLibrary = loadMusicLibrary(userId = userId, context = "recentlyAddedTracks")
            getMusicItemsPageWithLibraryFallback(
                queryName = "recentlyAddedTracks",
                userId = userId,
                musicLibrary = musicLibrary,
                includeItemTypes = listOf(BaseItemKind.AUDIO),
                sortBy = listOf(ItemSortBy.DATE_CREATED),
                sortOrder = listOf(SortOrder.DESCENDING),
                fields = musicBrowseItemFields,
                enableTotalRecordCount = false,
                limit = limit.coerceIn(1, AUTO_PAGE_LIMIT),
            ).items.filter(MusicItem::isPlayable).distinctBy(MusicItem::id)
                .also { tracks -> rememberPlaybackTracks(tracks, "recentlyAddedTracks") }
        }

    suspend fun loadHeavyRotationTracks(limit: Int = AUTO_GENERATED_QUEUE_LIMIT): List<MusicItem> =
        withContext(Dispatchers.IO) {
            val cachedTracks = listOf(
                cachedItemsFor(MusicBrowseKind.FAVORITES),
                cachedItemsFor(MusicBrowseKind.RECENTLY_PLAYED),
                cachedItemsFor(MusicBrowseKind.RECOMMENDATIONS),
                cachedItemsFor(MusicBrowseKind.SONGS),
            ).flatten()
                .filter(MusicItem::isPlayable)
                .distinctBy(MusicItem::id)
                .take(limit)

            if (cachedTracks.isNotEmpty()) {
                Timber.i("PTV music heavyRotation using cached tracks count=${cachedTracks.size}")
                return@withContext cachedTracks
            }

            val userId = currentUserId()
            val musicLibrary = loadMusicLibrary(userId = userId, context = "heavyRotation")
            val favorites = getMusicItemsPageWithLibraryFallback(
                queryName = "heavyRotation.favorites",
                userId = userId,
                musicLibrary = musicLibrary,
                includeItemTypes = listOf(BaseItemKind.AUDIO),
                isFavorite = true,
                sortBy = listOf(ItemSortBy.SORT_NAME),
                fields = musicBrowseItemFields,
                enableTotalRecordCount = false,
                limit = (limit / 2).coerceAtLeast(1),
            ).items
            val recent = getMusicItemsPageWithLibraryFallback(
                queryName = "heavyRotation.recent",
                userId = userId,
                musicLibrary = musicLibrary,
                includeItemTypes = listOf(BaseItemKind.AUDIO),
                filters = listOf(ItemFilter.IS_PLAYED),
                sortBy = listOf(ItemSortBy.DATE_PLAYED),
                sortOrder = listOf(SortOrder.DESCENDING),
                fields = musicBrowseItemFields,
                enableTotalRecordCount = false,
                limit = limit,
            ).items

            (recent + favorites)
                .filter(MusicItem::isPlayable)
                .distinctBy(MusicItem::id)
                .take(limit)
                .also { tracks -> rememberPlaybackTracks(tracks, "heavyRotation") }
        }

    suspend fun loadArtistTracks(artistIds: List<UUID>, limit: Int = AUTO_GENERATED_QUEUE_LIMIT): List<MusicItem> =
        withContext(Dispatchers.IO) {
            val ids = artistIds.distinct()
            if (ids.isEmpty()) return@withContext emptyList()
            val userId = currentUserId()
            val musicLibrary = loadMusicLibrary(userId = userId, context = "artistMix")
            getMusicItemsPageWithLibraryFallback(
                queryName = "artistMix",
                userId = userId,
                musicLibrary = musicLibrary,
                includeItemTypes = listOf(BaseItemKind.AUDIO),
                artistIds = ids,
                sortBy = listOf(ItemSortBy.RANDOM),
                fields = musicBrowseItemFields,
                enableTotalRecordCount = false,
                limit = limit.coerceIn(1, AUTO_PAGE_LIMIT),
            ).items.filter(MusicItem::isPlayable).distinctBy(MusicItem::id)
                .also { tracks -> rememberPlaybackTracks(tracks, "artistMix") }
        }

    suspend fun loadArtistRadioQueue(seed: MusicItem, limit: Int = AUTO_GENERATED_QUEUE_LIMIT): List<MusicItem> =
        withContext(Dispatchers.IO) {
            val requestedLimit = limit.coerceIn(MIN_GENERATED_MIX_TRACKS, MAX_GENERATED_MIX_TRACKS)
            val artistIds = resolveArtistRadioIds(seed)
            val artistTracks = if (artistIds.isEmpty()) {
                emptyList()
            } else {
                loadArtistTracks(artistIds = artistIds, limit = requestedLimit)
            }
            val generatedTracks = if (seed.isPlayable) {
                runCatching {
                    loadAutoGeneratedQueue(seed = seed, limit = requestedLimit)
                }.onFailure { error ->
                    Timber.w(error, "PTV music artist radio generated fallback failed seedItemId=${seed.id}")
                }.getOrDefault(emptyList())
            } else {
                emptyList()
            }
            val cachedTracks = cachedArtistRadioCandidates(seed)

            MusicGeneratedMixBuilder.buildRanked(
                seed = seed,
                groups = listOf(
                    MusicRecommendationGroup(MusicRecommendationSource.ARTIST_AFFINITY, artistTracks),
                    MusicRecommendationGroup(MusicRecommendationSource.RECOMMENDATION, generatedTracks),
                    MusicRecommendationGroup(MusicRecommendationSource.FALLBACK, cachedTracks),
                ),
                affinityItems = listOf(seed),
                notInterestedIds = notInterestedItemIds(),
                maxTracks = requestedLimit,
                surface = MusicRecommendationSurface.RADIO,
            ).also { tracks ->
                rememberPlaybackTracks(tracks, "artistRadio")
                Timber.i(
                    "PTV music recommendation generation surface=radio policy=familiar25-discovery75 " +
                        "seedAffinity=strict seedArtistSignals=${artistIds.size} resultCount=${tracks.size} " +
                        "artistTracks=${artistTracks.size} generated=${generatedTracks.size} " +
                        "cached=${cachedTracks.size}",
                )
            }
        }

    suspend fun loadGenreTracks(genreName: String, limit: Int = AUTO_GENERATED_QUEUE_LIMIT): List<MusicItem> =
        withContext(Dispatchers.IO) {
            val normalizedGenre = genreName.normalizeMusicText()
            if (normalizedGenre.isBlank()) return@withContext emptyList()
            val userId = currentUserId()
            val musicLibrary = loadMusicLibrary(userId = userId, context = "genreMix")
            val genre = loadGenres(userId = userId, parentId = musicLibrary?.id)
                .firstOrNull { item -> item.title.normalizeMusicText() == normalizedGenre }
            if (genre == null) {
                Timber.w("PTV music genreMix missing genreName=$genreName")
                return@withContext emptyList()
            }

            getMusicItemsPageWithLibraryFallback(
                queryName = "genreMix",
                userId = userId,
                musicLibrary = musicLibrary,
                includeItemTypes = listOf(BaseItemKind.AUDIO),
                genreIds = listOf(genre.id),
                sortBy = listOf(ItemSortBy.RANDOM),
                fields = musicBrowseItemFields,
                enableTotalRecordCount = false,
                limit = limit.coerceIn(1, AUTO_PAGE_LIMIT),
            ).items.filter(MusicItem::isPlayable).distinctBy(MusicItem::id)
                .also { tracks -> rememberPlaybackTracks(tracks, "genreMix") }
        }

    suspend fun loadAutoPlaylist(playlistName: String = AUTO_PLAYLIST_NAME): MusicItem? = withContext(Dispatchers.IO) {
        val userId = currentUserId()
        findPlaylistByName(userId = userId, playlistName = playlistName)
    }

    suspend fun loadAutoPlaylistTracks(
        playlistName: String = AUTO_PLAYLIST_NAME,
        limit: Int = AUTO_GENERATED_QUEUE_LIMIT,
    ): List<MusicItem> = withContext(Dispatchers.IO) {
        val playlist = loadAutoPlaylist(playlistName = playlistName)
        if (playlist == null) {
            Timber.i("PTV music auto playlist tracks missing playlistName=$playlistName")
            emptyList()
        } else {
            loadChildrenPage(item = playlist, startIndex = 0, limit = limit.coerceIn(1, AUTO_PAGE_LIMIT))
                .items
                .filter(MusicItem::isPlayable)
                .distinctBy(MusicItem::id)
        }
    }

    suspend fun loadChildren(item: MusicItem): List<MusicItem> = withContext(Dispatchers.IO) {
        loadChildrenPage(item = item, startIndex = 0, limit = DETAIL_LIMIT).items
    }

    suspend fun loadChildrenPage(item: MusicItem, startIndex: Int, limit: Int): MusicPage =
        withContext(Dispatchers.IO) {
            val userId = currentUserId()
            val pageLimit = limit.coerceIn(1, DETAIL_LIMIT)

            when (item.type) {
                BaseItemKind.MUSIC_ALBUM,
                BaseItemKind.PLAYLIST,
                -> loadTracksForParentPage(
                    queryName = when (item.type) {
                        BaseItemKind.PLAYLIST -> "children.playlistTracks"
                        else -> "children.albumTracks"
                    },
                    userId = userId,
                    parentItem = item,
                    startIndex = startIndex,
                    limit = pageLimit,
                )

                BaseItemKind.MUSIC_ARTIST -> getMusicItemsPage(
                    userId = userId,
                    includeItemTypes = listOf(BaseItemKind.MUSIC_ALBUM),
                    artistIds = listOf(item.id),
                    sortBy = listOf(ItemSortBy.SORT_NAME),
                    startIndex = startIndex,
                    limit = pageLimit,
                )

                BaseItemKind.GENRE,
                BaseItemKind.MUSIC_GENRE,
                -> getMusicItemsPage(
                    userId = userId,
                    includeItemTypes = listOf(BaseItemKind.AUDIO),
                    genreIds = listOf(item.id),
                    sortBy = listOf(ItemSortBy.SORT_NAME),
                    startIndex = startIndex,
                    limit = pageLimit,
                )

                else -> MusicPage(emptyList(), totalCount = 0, startIndex = startIndex)
            }.also { page ->
                cacheItem(item, "children.parent")
                if (page.items.any(MusicItem::isPlayable)) rememberPlaybackTracks(page.items, "children.${item.type}")
            }
        }

    suspend fun loadItem(itemId: UUID): MusicItem? = withContext(Dispatchers.IO) {
        runCatching {
            apiClient.userLibraryApi.getItem(itemId = itemId).content.let(::toMusicItem)
        }.getOrNull()?.also { item -> cacheItem(item, "loadItem") }
    }

    suspend fun loadItems(itemIds: List<UUID>): List<MusicItem> = withContext(Dispatchers.IO) {
        val distinctIds = itemIds.distinct()
        if (distinctIds.isEmpty()) return@withContext emptyList()

        val userId = currentUserId()
        val items = apiClient.itemsApi.getItems(
            userId = userId,
            ids = distinctIds,
            recursive = true,
            fields = musicItemFields,
            enableUserData = true,
            imageTypeLimit = 1,
            enableImageTypes = imageTypes,
            enableTotalRecordCount = false,
            enableImages = true,
            limit = distinctIds.size,
        ).content.items.map(::toMusicItem)
        items.forEach { item -> cacheItem(item, "loadItems") }
        rememberPlaybackTracks(items, "loadItems")
        val itemsById = items.associateBy(MusicItem::id)

        distinctIds.mapNotNull(itemsById::get)
    }

    suspend fun setFavorite(item: MusicItem, favorite: Boolean): MusicItem = withContext(Dispatchers.IO) {
        Timber.i("PTV music command favorite source=repository itemId=${item.id} target=$favorite")
        val userData = when {
            favorite -> apiClient.userLibraryApi.markFavoriteItem(itemId = item.id)
            else -> apiClient.userLibraryApi.unmarkFavoriteItem(itemId = item.id)
        }.content

        item.copy(isFavorite = userData.isFavorite).also { updated ->
            cacheItem(updated, "favorite")
            Timber.i(
                "PTV music command favorite source=repository itemId=${item.id} " +
                    "result=success favorite=${updated.isFavorite}",
            )
        }
    }

    fun notInterestedItemIds(): Set<UUID> {
        val storedIds = notInterestedPreferences
            ?.getStringSet(NOT_INTERESTED_ITEM_IDS_KEY, emptySet())
            .orEmpty()
            .mapNotNull(String::toUUIDOrNull)
            .toSet()
        return storedIds + fallbackNotInterestedIds
    }

    fun isNotInterested(item: MusicItem): Boolean = item.id in notInterestedItemIds()

    fun toggleNotInterested(item: MusicItem): Boolean {
        val currentIds = notInterestedItemIds()
        val updatedIds = when (item.id in currentIds) {
            true -> currentIds - item.id
            false -> currentIds + item.id
        }
        saveNotInterestedItemIds(updatedIds)
        Timber.i(
            "PTV music command notInterested source=repository itemId=${item.id} " +
                "enabled=${item.id in updatedIds} storedCount=${updatedIds.size}",
        )
        return item.id in updatedIds
    }

    fun clearNotInterested(item: MusicItem) {
        saveNotInterestedItemIds(notInterestedItemIds() - item.id)
    }

    private suspend fun findPlaylistByName(userId: UUID, playlistName: String): MusicItem? {
        Timber.i(
            "PTV music auto playlist lookup start path=$ITEMS_ENDPOINT userId=$userId " +
                "playlistName=$playlistName",
        )
        val playlists = getMusicItemsPage(
            queryName = "autoPlaylist.lookup",
            userId = userId,
            includeItemTypes = listOf(BaseItemKind.PLAYLIST),
            sortBy = listOf(ItemSortBy.SORT_NAME),
            fields = musicBrowseItemFields,
            enableTotalRecordCount = false,
            limit = PLAYLIST_LIMIT,
        ).items
        val playlist = playlists.firstOrNull { playlist ->
            playlist.title.equals(playlistName, ignoreCase = true)
        }
        Timber.i(
            "PTV music auto playlist lookup name=$playlistName " +
                "candidateCount=${playlists.size} result=${playlist?.id ?: "<missing>"} " +
                "resultTitle=${playlist?.title ?: "<missing>"}",
        )
        return playlist?.also { item -> cacheItem(item, "autoPlaylist.lookup") }
    }

    private suspend fun createAutoPlaylist(
        userId: UUID,
        item: MusicItem,
        playlistName: String,
        source: String,
    ): AutoPlaylistRef {
        Timber.i(
            "PTV music command addToAutoPlaylist source=$source playlistCreate start " +
                "path=POST /Playlists userId=$userId playlistName=$playlistName " +
                "currentTrackTitle=${item.title} currentItemId=${item.id}",
        )
        val creationResult = apiClient.playlistsApi.createPlaylist(
            CreatePlaylistDto(
                name = playlistName,
                ids = listOf(item.id),
                userId = userId,
                mediaType = MediaType.AUDIO,
                users = emptyList(),
                isPublic = false,
            ),
        ).content
        val playlistId = creationResult.id.toUUIDOrNull()
            ?: throw IllegalStateException(
                "Jellyfin returned invalid playlist id '${creationResult.id}' for $playlistName.",
            )
        Timber.i(
            "PTV music command addToAutoPlaylist source=$source playlistCreate result=success " +
                "playlistName=$playlistName playlistId=$playlistId currentItemId=${item.id}",
        )
        return AutoPlaylistRef(id = playlistId, title = playlistName)
    }

    private suspend fun addCurrentItemToPlaylist(
        userId: UUID,
        item: MusicItem,
        playlist: AutoPlaylistRef,
        source: String,
    ): String {
        Timber.i(
            "PTV music command addToAutoPlaylist source=$source duplicateCheck start " +
                "path=GET /Playlists/{playlistId}/Items currentTrackTitle=${item.title} " +
                "currentItemId=${item.id} playlistId=${playlist.id} userId=$userId",
        )
        val existingIds = runCatching {
            loadPlaylistItemIds(userId = userId, playlistId = playlist.id)
        }.onSuccess { ids ->
            Timber.i(
                "PTV music command addToAutoPlaylist source=$source duplicateCheck result=success " +
                    "currentItemId=${item.id} playlistId=${playlist.id} " +
                    "existingCount=${ids.size} alreadyPresent=${item.id in ids}",
            )
        }.onFailure { error ->
            Timber.w(
                error,
                "PTV music command addToAutoPlaylist source=$source duplicateCheck failed " +
                    "currentTrackTitle=${item.title} currentItemId=${item.id} playlistId=${playlist.id} " +
                    "exception=${error::class.qualifiedName} reason=${error.message}; attempting add anyway",
            )
        }.getOrDefault(emptySet())

        if (item.id in existingIds) {
            Timber.i(
                "PTV music command addToAutoPlaylist source=$source duplicateHandling=already-present " +
                    "currentTrackTitle=${item.title} currentItemId=${item.id} playlistId=${playlist.id}",
            )
            return "Already in ${playlist.title}."
        }

        Timber.i(
            "PTV music command addToAutoPlaylist source=$source addItem start " +
                "path=POST /Playlists/{playlistId}/Items currentTrackTitle=${item.title} " +
                "currentItemId=${item.id} playlistId=${playlist.id} userId=$userId",
        )
        runCatching {
            apiClient.playlistsApi.addItemToPlaylist(
                playlistId = playlist.id,
                ids = listOf(item.id),
                userId = userId,
            )
        }.onFailure { error ->
            Timber.e(
                error,
                "PTV music command addToAutoPlaylist source=$source addItem result=failure " +
                    "currentTrackTitle=${item.title} currentItemId=${item.id} playlistId=${playlist.id} " +
                    "exception=${error::class.qualifiedName} reason=${error.message}",
            )
        }.getOrThrow()
        Timber.i(
            "PTV music command addToAutoPlaylist source=$source addItem result=success " +
                "currentTrackTitle=${item.title} currentItemId=${item.id} playlistId=${playlist.id}",
        )
        return "Added to ${playlist.title}."
    }

    suspend fun addToPlaylist(target: MusicPlaylistTarget, playlist: MusicItem): String = withContext(Dispatchers.IO) {
        Timber.i(
            "PTV music command addToPlaylist source=repository currentItemId=${target.item.id} " +
                "playlistId=${playlist.id}",
        )
        val userId = currentUserId()
        val tracks = loadPlaylistTargetTracks(userId, target)
        if (tracks.isEmpty()) return@withContext "No playable songs were found for ${target.item.title}."

        val existingIds = loadPlaylistItemIds(userId, playlist.id)
        val newIds = tracks
            .map(MusicItem::id)
            .filterNot(existingIds::contains)
            .distinct()

        if (newIds.isEmpty()) {
            "Already in ${playlist.title}."
        } else {
            apiClient.playlistsApi.addItemToPlaylist(
                playlistId = playlist.id,
                ids = newIds,
                userId = userId,
            )

            when (newIds.size) {
                1 -> "Added to ${playlist.title}."
                else -> "Added ${newIds.size} songs to ${playlist.title}."
            }
        }
    }

    suspend fun createPlaylist(name: String, target: MusicPlaylistTarget): String = withContext(Dispatchers.IO) {
        Timber.i(
            "PTV music command createPlaylist source=repository currentItemId=${target.item.id} " +
                "name=${name.trim().ifBlank { target.defaultPlaylistName() }}",
        )
        val userId = currentUserId()
        val tracks = loadPlaylistTargetTracks(userId, target)
        if (tracks.isEmpty()) return@withContext "No playable songs were found for ${target.item.title}."

        val playlistName = name.trim().ifBlank { target.defaultPlaylistName() }
        apiClient.playlistsApi.createPlaylist(
            CreatePlaylistDto(
                name = playlistName,
                ids = tracks.map(MusicItem::id).distinct(),
                userId = userId,
                mediaType = MediaType.AUDIO,
                users = emptyList(),
                isPublic = false,
            ),
        )

        when (tracks.size) {
            1 -> "Created $playlistName."
            else -> "Created $playlistName with ${tracks.size} songs."
        }
    }

    private data class HomeSourceLoadResult(
        val source: MusicHomeSource,
        val page: MusicPage = MusicPage(emptyList(), totalCount = 0, startIndex = 0),
        val items: List<MusicItem> = page.items,
        val errorMessage: String? = null,
        val fromCache: Boolean = false,
    )

    private data class MusicCacheScope(val serverUrl: String, val userId: UUID)

    private fun MusicHome.mergeHomeSources(results: List<HomeSourceLoadResult>): MusicHome {
        var merged = this
        results.forEach { result ->
            merged = when (result.source) {
                MusicHomeSource.RECENTLY_ADDED_ALBUMS -> merged.copy(recentlyAddedAlbums = result.items)

                MusicHomeSource.ALBUMS -> merged.copy(
                    albums = result.page.items,
                    albumsTotalCount = result.page.totalCount,
                )

                MusicHomeSource.ARTISTS -> merged.copy(
                    artists = result.page.items,
                    artistsTotalCount = result.page.totalCount,
                )

                MusicHomeSource.SONGS -> merged.copy(
                    songs = result.page.items,
                    songsTotalCount = result.page.totalCount,
                )

                MusicHomeSource.GENRES -> merged.copy(genres = result.items)

                MusicHomeSource.PLAYLISTS -> merged.copy(playlists = result.items)

                MusicHomeSource.FAVORITES -> merged.copy(favorites = result.items)

                MusicHomeSource.RECENTLY_PLAYED -> merged.copy(recentlyPlayed = result.items)

                MusicHomeSource.RECOMMENDATIONS -> merged.copy(recommendations = result.items)
            }
        }

        val completedSources = results.mapTo(mutableSetOf(), HomeSourceLoadResult::source)
        val errors = (sourceErrors - completedSources) + results.mapNotNull { result ->
            result.errorMessage?.let { message -> result.source to message }
        }
        val cacheHits = (sourceCacheHits - completedSources) + results
            .filter(HomeSourceLoadResult::fromCache)
            .map(HomeSourceLoadResult::source)
        return merged.copy(sourceErrors = errors, sourceCacheHits = cacheHits)
    }

    private fun HomeSourceLoadResult.withLatestCacheFallback(expectedScope: MusicCacheScope): HomeSourceLoadResult {
        if (activeCacheScope != expectedScope) return this
        if (items.isNotEmpty()) return this
        if (errorMessage == null && source !in CACHE_PRESERVED_ON_EMPTY_SOURCES) return this

        val browseKind = source.toBrowseKind() ?: return this
        val latestPage = cachedPageFor(browseKind)?.takeIf { page -> page.items.isNotEmpty() } ?: return this
        Timber.w(
            "PTV music home source=$source recovered from live cache count=" +
                "${latestPage.items.size}/${latestPage.totalCount}",
        )
        return copy(
            page = latestPage,
            items = latestPage.items,
            fromCache = true,
        )
    }

    private data class AutoPlaylistRef(val id: UUID, val title: String)

    private fun MusicItem.toAutoPlaylistRef(): AutoPlaylistRef = AutoPlaylistRef(id = id, title = title)

    private suspend fun loadHomePage(
        name: String,
        source: MusicHomeSource,
        cachedPage: MusicPage?,
        requestGate: Semaphore,
        cacheScope: MusicCacheScope,
        block: suspend () -> MusicPage,
    ): HomeSourceLoadResult {
        val result = requestGate.withPermit {
            withTimeoutOrNull(HOME_SOURCE_TIMEOUT_MS) {
                runCatching { logRepositoryCall("home.$name", block) }
                    .onFailure { error -> if (error is CancellationException) throw error }
            }
        }

        if (result == null) {
            val message = "PTV music home.$name timed out after ${HOME_SOURCE_TIMEOUT_MS}ms"
            Timber.w("$message; source=$source isolated=true cached=${cachedPage != null}")
            return cachedPage.toHomeSourceLoadResult(source = source, errorMessage = message)
        }

        return result.fold(
            onSuccess = { page ->
                if (page.isEmptyResult && cachedPage != null && source in CACHE_PRESERVED_ON_EMPTY_SOURCES) {
                    val message = "PTV music home.$name returned empty while cache has ${cachedPage.items.size}"
                    Timber.w("$message; source=$source using cached page")
                    return cachedPage.toHomeSourceLoadResult(source = source, errorMessage = message)
                }

                Timber.i("PTV music home.$name source=$source loaded count=${page.items.size}/${page.totalCount}")
                cacheHomeSourcePage(source, page, "home.$name", expectedScope = cacheScope)
                HomeSourceLoadResult(source = source, page = page)
            },
            onFailure = { error ->
                val message = "PTV music home.$name failed: ${error.message ?: error::class.java.simpleName}"
                Timber.e(error, "$message; source=$source isolated=true cached=${cachedPage != null}")
                cachedPage.toHomeSourceLoadResult(source = source, errorMessage = message)
            },
        )
    }

    private fun MusicPage?.toHomeSourceLoadResult(
        source: MusicHomeSource,
        errorMessage: String,
    ): HomeSourceLoadResult = when {
        this != null -> {
            Timber.w(
                "PTV music home source=$source using cached page count=${items.size}/$totalCount " +
                    "because ${errorMessage.substringBefore(':')}",
            )
            HomeSourceLoadResult(source = source, page = this, errorMessage = errorMessage, fromCache = true)
        }

        else -> HomeSourceLoadResult(source = source, errorMessage = errorMessage)
    }

    private suspend fun loadHomeList(
        name: String,
        source: MusicHomeSource,
        cachedItems: List<MusicItem>,
        requestGate: Semaphore,
        cacheScope: MusicCacheScope,
        block: suspend () -> List<MusicItem>,
    ): HomeSourceLoadResult {
        val result = requestGate.withPermit {
            withTimeoutOrNull(HOME_SOURCE_TIMEOUT_MS) {
                runCatching { logRepositoryCall("home.$name", block) }
                    .onFailure { error -> if (error is CancellationException) throw error }
            }
        }

        if (result == null) {
            val message = "PTV music home.$name timed out after ${HOME_SOURCE_TIMEOUT_MS}ms"
            Timber.w("$message; source=$source isolated=true cached=${cachedItems.isNotEmpty()}")
            return cachedItems.toHomeSourceLoadResult(source = source, errorMessage = message)
        }

        return result.fold(
            onSuccess = { items ->
                if (items.isEmpty() && cachedItems.isNotEmpty() && source in CACHE_PRESERVED_ON_EMPTY_SOURCES) {
                    val message = "PTV music home.$name returned empty while cache has ${cachedItems.size}"
                    Timber.w("$message; source=$source using cached list")
                    return cachedItems.toHomeSourceLoadResult(source = source, errorMessage = message)
                }

                Timber.i("PTV music home.$name source=$source loaded count=${items.size}")
                cacheHomeSourceItems(source, items, "home.$name", expectedScope = cacheScope)
                HomeSourceLoadResult(source = source, items = items)
            },
            onFailure = { error ->
                val message = "PTV music home.$name failed: ${error.message ?: error::class.java.simpleName}"
                Timber.e(error, "$message; source=$source isolated=true cached=${cachedItems.isNotEmpty()}")
                cachedItems.toHomeSourceLoadResult(source = source, errorMessage = message)
            },
        )
    }

    private fun List<MusicItem>.toHomeSourceLoadResult(
        source: MusicHomeSource,
        errorMessage: String,
    ): HomeSourceLoadResult = when {
        isNotEmpty() -> {
            Timber.w(
                "PTV music home source=$source using cached list count=$size " +
                    "because ${errorMessage.substringBefore(':')}",
            )
            HomeSourceLoadResult(source = source, items = this, errorMessage = errorMessage, fromCache = true)
        }

        else -> HomeSourceLoadResult(source = source, errorMessage = errorMessage)
    }

    private suspend fun <T> logRepositoryCall(name: String, block: suspend () -> T): T {
        val startedAt = System.currentTimeMillis()
        Timber.i(
            "PTV music repository $name started; " +
                "host=${apiClient.safeBaseHost()} authenticated=${apiClient.hasAccessToken()}",
        )
        return try {
            block().also {
                Timber.i("PTV music repository $name finished in ${System.currentTimeMillis() - startedAt}ms")
            }
        } catch (cancellation: CancellationException) {
            Timber.i("PTV music repository $name cancelled after ${System.currentTimeMillis() - startedAt}ms")
            throw cancellation
        } catch (error: Throwable) {
            Timber.e(error, "PTV music repository $name failed after ${System.currentTimeMillis() - startedAt}ms")
            throw error
        }
    }

    private fun logMusicLibraryContext(
        context: String,
        userId: UUID?,
        userViews: List<BaseItemDto>,
        musicLibrary: MusicLibrary?,
    ) {
        val musicViews = userViews
            .filter { item -> item.collectionType == CollectionType.MUSIC }
            .joinToString { item -> "${item.name.orEmpty().ifBlank { "Music" }}:${item.id}" }
            .ifBlank { "<none>" }
        Timber.i(
            "PTV music $context context host=${apiClient.safeBaseHost()} " +
                "authenticated=${apiClient.hasAccessToken()} userId=${userId ?: "<unknown>"} " +
                "selectedLibraryId=${musicLibrary?.id ?: "<all>"} " +
                "selectedLibraryName=${musicLibrary?.name ?: "<all music>"} " +
                "musicLibraryCandidates=$musicViews allViewCount=${userViews.size}",
        )
    }

    private suspend fun loadRecommendations(
        userId: UUID,
        musicLibraryId: UUID?,
        seedItems: List<MusicItem>,
        genres: List<MusicItem>,
        fallbackSongs: List<MusicItem>,
    ): List<MusicItem> = supervisorScope {
        val seedIds = seedItems.map(MusicItem::id).toSet()
        val blockedIds = notInterestedItemIds()
        val seedArtistIds = seedItems.flatMap(MusicItem::artistIds).distinct().take(RECOMMENDATION_SEED_LIMIT)
        val seedAlbumIds = seedItems.mapNotNull(MusicItem::albumId).distinct().take(RECOMMENDATION_SEED_LIMIT)
        val seedGenreNames = seedItems
            .flatMap(MusicItem::genres)
            .map { genre -> genre.normalizeMusicText() }
            .filter(String::isNotBlank)
            .toSet()
        val genreIds = genres
            .filter { genre -> genre.title.normalizeMusicText() in seedGenreNames }
            .map(MusicItem::id)
            .take(RECOMMENDATION_GENRE_LIMIT)

        val sameArtists = async {
            if (seedArtistIds.isEmpty()) {
                emptyList()
            } else {
                getMusicItems(
                    queryName = "recommendations.sameArtists",
                    userId = userId,
                    parentId = musicLibraryId,
                    includeItemTypes = listOf(BaseItemKind.AUDIO),
                    artistIds = seedArtistIds,
                    sortBy = listOf(ItemSortBy.RANDOM),
                    fields = musicRecommendationItemFields,
                    enableTotalRecordCount = false,
                    limit = ROW_LIMIT,
                )
            }
        }
        val sameAlbums = async {
            if (seedAlbumIds.isEmpty()) {
                emptyList()
            } else {
                getMusicItems(
                    queryName = "recommendations.sameAlbums",
                    userId = userId,
                    parentId = musicLibraryId,
                    includeItemTypes = listOf(BaseItemKind.AUDIO),
                    albumIds = seedAlbumIds,
                    sortBy = listOf(ItemSortBy.RANDOM),
                    fields = musicRecommendationItemFields,
                    enableTotalRecordCount = false,
                    limit = ROW_LIMIT,
                )
            }
        }
        val sameGenres = async {
            if (genreIds.isEmpty()) {
                emptyList()
            } else {
                getMusicItems(
                    queryName = "recommendations.sameGenres",
                    userId = userId,
                    parentId = musicLibraryId,
                    includeItemTypes = listOf(BaseItemKind.AUDIO),
                    genreIds = genreIds,
                    sortBy = listOf(ItemSortBy.RANDOM),
                    fields = musicRecommendationItemFields,
                    enableTotalRecordCount = false,
                    limit = ROW_LIMIT,
                )
            }
        }
        val highPlayCount = async {
            getMusicItems(
                queryName = "recommendations.highPlayCount",
                userId = userId,
                parentId = musicLibraryId,
                includeItemTypes = listOf(BaseItemKind.AUDIO),
                sortBy = listOf(ItemSortBy.PLAY_COUNT),
                sortOrder = listOf(SortOrder.DESCENDING),
                fields = musicRecommendationItemFields,
                enableTotalRecordCount = false,
                limit = ROW_LIMIT,
            )
        }
        val recentlyAdded = async {
            getMusicItems(
                queryName = "recommendations.recentlyAddedAudio",
                userId = userId,
                parentId = musicLibraryId,
                includeItemTypes = listOf(BaseItemKind.AUDIO),
                sortBy = listOf(ItemSortBy.DATE_CREATED),
                sortOrder = listOf(SortOrder.DESCENDING),
                fields = musicRecommendationItemFields,
                enableTotalRecordCount = false,
                limit = ROW_LIMIT,
            )
        }

        MusicRecommendationRanker.rank(
            groups = listOf(
                MusicRecommendationGroup(MusicRecommendationSource.ARTIST_AFFINITY, sameArtists.await()),
                MusicRecommendationGroup(MusicRecommendationSource.ALBUM_AFFINITY, sameAlbums.await()),
                MusicRecommendationGroup(MusicRecommendationSource.GENRE_AFFINITY, sameGenres.await()),
                MusicRecommendationGroup(MusicRecommendationSource.LISTENING_HISTORY, highPlayCount.await()),
                MusicRecommendationGroup(MusicRecommendationSource.DISCOVERY, recentlyAdded.await()),
                MusicRecommendationGroup(MusicRecommendationSource.FALLBACK, fallbackSongs),
            ),
            affinityItems = seedItems,
            notInterestedIds = blockedIds,
            excludedIds = seedIds,
            maxItems = ROW_LIMIT,
            surface = MusicRecommendationSurface.HOME,
        )
    }

    private suspend fun loadGenres(userId: UUID, parentId: UUID?): List<MusicItem> {
        val startedAt = System.currentTimeMillis()
        Timber.i(
            "PTV music query start name=home.genres endpoint=$GENRES_ENDPOINT " +
                "host=${apiClient.safeBaseHost()} authenticated=${apiClient.hasAccessToken()} " +
                "userId=$userId parentId=$parentId includeItemTypes=${listOf(BaseItemKind.AUDIO)} " +
                "recursive=<not-supported> start=0 limit=$ROW_LIMIT",
        )
        val result = apiClient.genresApi.getGenres(
            userId = userId,
            parentId = parentId,
            includeItemTypes = listOf(BaseItemKind.AUDIO),
            sortBy = listOf(ItemSortBy.SORT_NAME),
            sortOrder = listOf(SortOrder.ASCENDING),
            enableTotalRecordCount = false,
            imageTypeLimit = 1,
            enableImageTypes = imageTypes,
            limit = ROW_LIMIT,
        ).content

        Timber.i(
            "PTV music query returned name=home.genres endpoint=$GENRES_ENDPOINT " +
                "parentId=$parentId includeItemTypes=${listOf(BaseItemKind.AUDIO)} " +
                "recursive=<not-supported> count=${result.items.size}/${result.totalRecordCount} " +
                "in ${System.currentTimeMillis() - startedAt}ms",
        )
        return result.items.map(::toMusicItem)
    }

    private suspend fun loadGenresPage(userId: UUID, parentId: UUID?, startIndex: Int, limit: Int): MusicPage {
        val startedAt = System.currentTimeMillis()
        Timber.i(
            "PTV music query start name=genresPage endpoint=$GENRES_ENDPOINT " +
                "host=${apiClient.safeBaseHost()} authenticated=${apiClient.hasAccessToken()} " +
                "userId=$userId parentId=$parentId includeItemTypes=${listOf(BaseItemKind.AUDIO)} " +
                "recursive=<not-supported> start=$startIndex limit=$limit",
        )
        val result = apiClient.genresApi.getGenres(
            userId = userId,
            parentId = parentId,
            includeItemTypes = listOf(BaseItemKind.AUDIO),
            sortBy = listOf(ItemSortBy.SORT_NAME),
            sortOrder = listOf(SortOrder.ASCENDING),
            enableTotalRecordCount = true,
            imageTypeLimit = 1,
            enableImageTypes = imageTypes,
            startIndex = startIndex,
            limit = limit,
        ).content

        Timber.i(
            "PTV music query returned name=genresPage endpoint=$GENRES_ENDPOINT " +
                "parentId=$parentId includeItemTypes=${listOf(BaseItemKind.AUDIO)} " +
                "recursive=<not-supported> count=${result.items.size}/${result.totalRecordCount} " +
                "in ${System.currentTimeMillis() - startedAt}ms",
        )
        return MusicPage(
            items = result.items.map(::toMusicItem),
            totalCount = result.totalRecordCount,
            startIndex = result.startIndex,
        )
    }

    private suspend fun loadTracksForParentPage(
        queryName: String,
        userId: UUID,
        parentItem: MusicItem,
        startIndex: Int,
        limit: Int,
    ): MusicPage {
        val sortBy = listOf(ItemSortBy.PARENT_INDEX_NUMBER, ItemSortBy.INDEX_NUMBER, ItemSortBy.SORT_NAME)
        val directChildren = getMusicItemsPage(
            queryName = "$queryName.directChildren",
            userId = userId,
            parentId = parentItem.id,
            includeItemTypes = emptyList(),
            recursive = null,
            sortBy = sortBy,
            fields = musicBrowseItemFields,
            enableTotalRecordCount = false,
            startIndex = startIndex,
            limit = limit,
        ).filterPlayableTracks()

        if (!directChildren.isEmptyResult) return directChildren

        Timber.w(
            "PTV music $queryName direct parent query returned zero tracks; " +
                "retrying recursive Audio parentId=${parentItem.id} type=${parentItem.type}",
        )
        return getMusicItemsPage(
            queryName = "$queryName.recursiveAudioFallback",
            userId = userId,
            parentId = parentItem.id,
            includeItemTypes = listOf(BaseItemKind.AUDIO),
            recursive = true,
            sortBy = sortBy,
            fields = musicBrowseItemFields,
            enableTotalRecordCount = false,
            startIndex = startIndex,
            limit = limit,
        ).filterPlayableTracks()
    }

    private suspend fun getMusicItems(
        queryName: String = "items.list",
        userId: UUID,
        parentId: UUID? = null,
        searchTerm: String? = null,
        includeItemTypes: List<BaseItemKind>,
        artistIds: List<UUID> = emptyList(),
        albumIds: List<UUID> = emptyList(),
        genreIds: List<UUID> = emptyList(),
        filters: List<ItemFilter> = emptyList(),
        isFavorite: Boolean? = null,
        sortBy: List<ItemSortBy>,
        sortOrder: List<SortOrder> = emptyList(),
        fields: List<ItemFields> = musicItemFields,
        recursive: Boolean? = true,
        enableTotalRecordCount: Boolean = true,
        limit: Int,
    ): List<MusicItem> = getMusicItemsPage(
        queryName = queryName,
        userId = userId,
        parentId = parentId,
        searchTerm = searchTerm,
        includeItemTypes = includeItemTypes,
        artistIds = artistIds,
        albumIds = albumIds,
        genreIds = genreIds,
        filters = filters,
        isFavorite = isFavorite,
        sortBy = sortBy,
        sortOrder = sortOrder,
        fields = fields,
        recursive = recursive,
        enableTotalRecordCount = enableTotalRecordCount,
        limit = limit,
    ).items

    private suspend fun getMusicItemsPageWithLibraryFallback(
        queryName: String,
        userId: UUID,
        musicLibrary: MusicLibrary?,
        searchTerm: String? = null,
        includeItemTypes: List<BaseItemKind>,
        artistIds: List<UUID> = emptyList(),
        albumIds: List<UUID> = emptyList(),
        genreIds: List<UUID> = emptyList(),
        filters: List<ItemFilter> = emptyList(),
        isFavorite: Boolean? = null,
        sortBy: List<ItemSortBy>,
        sortOrder: List<SortOrder> = emptyList(),
        fields: List<ItemFields> = musicItemFields,
        recursive: Boolean? = true,
        enableTotalRecordCount: Boolean = true,
        startIndex: Int = 0,
        limit: Int,
    ): MusicPage {
        val scopedPage = getMusicItemsPage(
            queryName = "$queryName.scoped",
            userId = userId,
            parentId = musicLibrary?.id,
            searchTerm = searchTerm,
            includeItemTypes = includeItemTypes,
            artistIds = artistIds,
            albumIds = albumIds,
            genreIds = genreIds,
            filters = filters,
            isFavorite = isFavorite,
            sortBy = sortBy,
            sortOrder = sortOrder,
            fields = fields,
            recursive = recursive,
            enableTotalRecordCount = enableTotalRecordCount,
            startIndex = startIndex,
            limit = limit,
        )

        if (musicLibrary == null || !scopedPage.isEmptyResult) return scopedPage

        Timber.w(
            "PTV music $queryName parent-scoped query returned zero records; " +
                "retrying without parentId library=${musicLibrary.name} libraryId=${musicLibrary.id}",
        )
        val fallbackPage = getMusicItemsPage(
            queryName = "$queryName.allLibraryFallback",
            userId = userId,
            searchTerm = searchTerm,
            includeItemTypes = includeItemTypes,
            artistIds = artistIds,
            albumIds = albumIds,
            genreIds = genreIds,
            filters = filters,
            isFavorite = isFavorite,
            sortBy = sortBy,
            sortOrder = sortOrder,
            fields = fields,
            recursive = recursive,
            enableTotalRecordCount = enableTotalRecordCount,
            startIndex = startIndex,
            limit = limit,
        )
        Timber.i(
            "PTV music $queryName all-library fallback returned " +
                "${fallbackPage.items.size}/${fallbackPage.totalCount}",
        )
        return fallbackPage
    }

    private suspend fun loadMusicPlaylists(userId: UUID, sortBy: List<ItemSortBy>, limit: Int): List<MusicItem> =
        getMusicItemsPage(
            queryName = "playlists",
            userId = userId,
            includeItemTypes = listOf(BaseItemKind.PLAYLIST),
            sortBy = sortBy,
            fields = musicBrowseItemFields,
            limit = limit,
        ).filterToRealMusicPlaylists(userId).items

    private suspend fun MusicPage.filterToRealMusicPlaylists(userId: UUID): MusicPage = supervisorScope {
        val probeGate = Semaphore(PLAYLIST_PROBE_MAX_CONCURRENT_REQUESTS)
        val filteredItems = items.map { playlist ->
            async {
                probeGate.withPermit { playlist to playlist.hasAudioPlaylistItems(userId) }
            }
        }.awaitAll()
            .filter { (_, hasAudio) -> hasAudio }
            .map { (playlist, _) -> playlist }

        copy(items = filteredItems, totalCount = filteredItems.size)
    }

    private suspend fun MusicItem.hasAudioPlaylistItems(userId: UUID): Boolean = runCatching {
        apiClient.playlistsApi.getPlaylistItems(
            playlistId = id,
            userId = userId,
            limit = PLAYLIST_AUDIO_PROBE_LIMIT,
            fields = emptyList(),
            enableImages = false,
            enableUserData = false,
        ).content.items.any { item ->
            item.mediaType == MediaType.AUDIO || item.type == BaseItemKind.AUDIO
        }
    }.getOrDefault(false)

    private suspend fun getMusicItemsPage(
        queryName: String = "items.page",
        userId: UUID,
        parentId: UUID? = null,
        searchTerm: String? = null,
        includeItemTypes: List<BaseItemKind>,
        artistIds: List<UUID> = emptyList(),
        albumIds: List<UUID> = emptyList(),
        genreIds: List<UUID> = emptyList(),
        filters: List<ItemFilter> = emptyList(),
        isFavorite: Boolean? = null,
        sortBy: List<ItemSortBy>,
        sortOrder: List<SortOrder> = emptyList(),
        fields: List<ItemFields> = musicItemFields,
        recursive: Boolean? = true,
        enableTotalRecordCount: Boolean = true,
        startIndex: Int = 0,
        limit: Int,
    ): MusicPage {
        val startedAt = System.currentTimeMillis()
        Timber.d(
            "PTV music query start name=$queryName endpoint=$ITEMS_ENDPOINT " +
                "host=${apiClient.safeBaseHost()} authenticated=${apiClient.hasAccessToken()} " +
                "userId=$userId parentId=$parentId types=$includeItemTypes start=$startIndex limit=$limit " +
                "recursive=$recursive totalCount=$enableTotalRecordCount " +
                "search=${!searchTerm.isNullOrBlank()} filters=$filters favorite=$isFavorite " +
                "sortBy=$sortBy sortOrder=$sortOrder fields=$fields",
        )
        val result = try {
            apiClient.itemsApi.getItems(
                userId = userId,
                parentId = parentId,
                searchTerm = searchTerm?.takeIf(String::isNotBlank),
                includeItemTypes = includeItemTypes,
                artistIds = artistIds,
                albumIds = albumIds,
                genreIds = genreIds,
                filters = filters,
                isFavorite = isFavorite,
                recursive = recursive,
                sortBy = sortBy,
                sortOrder = sortOrder,
                startIndex = startIndex,
                fields = fields,
                enableUserData = true,
                imageTypeLimit = 1,
                enableImageTypes = imageTypes,
                enableTotalRecordCount = enableTotalRecordCount,
                enableImages = true,
                limit = limit,
            ).content
        } catch (error: Throwable) {
            Timber.e(
                error,
                "PTV music query failed name=$queryName endpoint=$ITEMS_ENDPOINT " +
                    "host=${apiClient.safeBaseHost()} authenticated=${apiClient.hasAccessToken()} " +
                    "userId=$userId parentId=$parentId types=$includeItemTypes start=$startIndex limit=$limit " +
                    "recursive=$recursive totalCount=$enableTotalRecordCount " +
                    "after ${System.currentTimeMillis() - startedAt}ms",
            )
            throw error
        }

        Timber.i(
            "PTV music query returned name=$queryName count=${result.items.size}/${result.totalRecordCount} " +
                "endpoint=$ITEMS_ENDPOINT types=$includeItemTypes parentId=$parentId " +
                "recursive=$recursive totalCount=$enableTotalRecordCount start=$startIndex " +
                "in ${System.currentTimeMillis() - startedAt}ms",
        )
        if (result.items.isEmpty() && result.totalRecordCount == 0) {
            Timber.w(
                "PTV music query empty name=$queryName reason=server-returned-zero-records " +
                    "userId=$userId parentId=$parentId types=$includeItemTypes recursive=$recursive " +
                    "search=${!searchTerm.isNullOrBlank()}",
            )
        }

        val totalCount = when {
            enableTotalRecordCount -> result.totalRecordCount
            result.items.size >= limit -> startIndex + result.items.size + 1
            else -> startIndex + result.items.size
        }
        return MusicPage(
            items = result.items.map(::toMusicItem),
            totalCount = totalCount,
            startIndex = result.startIndex,
        )
    }

    private suspend fun loadMusicLibrary(userId: UUID? = null, context: String = "musicLibrary"): MusicLibrary? {
        val userViews = apiClient.userViewsApi.getUserViews(
            includeExternalContent = false,
            includeHidden = false,
        ).content.items
        val musicLibrary = userViews.firstOrNull { item -> item.collectionType == CollectionType.MUSIC }
            ?.let { item -> MusicLibrary(id = item.id, name = item.name.orEmpty().ifBlank { "Music" }) }
        logMusicLibraryContext(context = context, userId = userId, userViews = userViews, musicLibrary = musicLibrary)
        return musicLibrary
    }

    private suspend fun resolveArtistRadioIds(seed: MusicItem): List<UUID> {
        val directIds = seed.directArtistRadioIds()
        if (directIds.isNotEmpty()) return directIds

        val artistName = seed.artistRadioName().takeIf(String::isNotBlank) ?: return emptyList()
        return runCatching {
            searchMusic(artistName)
                .firstOrNull { result ->
                    result.type == BaseItemKind.MUSIC_ARTIST &&
                        result.title.equals(artistName, ignoreCase = true)
                }
                ?.let { artist -> listOf(artist.id) }
                .orEmpty()
        }.onFailure { error ->
            Timber.w(error, "PTV music artist radio artist lookup failed artist=$artistName")
        }.getOrDefault(emptyList())
    }

    private fun cachedAutoPicksCandidates(seed: MusicItem?): List<MusicItem> = (
        listOfNotNull(seed?.takeIf(MusicItem::isPlayable)) +
            listOf(
                cachedItemsFor(MusicBrowseKind.RECOMMENDATIONS),
                cachedItemsFor(MusicBrowseKind.FAVORITES),
                cachedItemsFor(MusicBrowseKind.RECENTLY_PLAYED),
                cachedItemsFor(MusicBrowseKind.SONGS),
                cachedPlaybackTracks,
            ).flatten()
        )
        .filter(MusicItem::isPlayable)
        .distinctBy(MusicItem::id)

    private fun cachedArtistRadioCandidates(seed: MusicItem): List<MusicItem> {
        val artistIds = seed.directArtistRadioIds().toSet()
        val artistName = seed.artistRadioName().normalizeMusicText()
        if (artistIds.isEmpty() && artistName.isBlank()) return emptyList()

        return listOf(
            cachedItemsFor(MusicBrowseKind.FAVORITES),
            cachedItemsFor(MusicBrowseKind.RECENTLY_PLAYED),
            cachedItemsFor(MusicBrowseKind.RECOMMENDATIONS),
            cachedItemsFor(MusicBrowseKind.SONGS),
            cachedPlaybackTracks,
        ).flatten()
            .filter(MusicItem::isPlayable)
            .filter { item ->
                item.artistIds.any(artistIds::contains) ||
                    (artistName.isNotBlank() && item.artist.orEmpty().normalizeMusicText() == artistName)
            }
            .distinctBy(MusicItem::id)
    }

    private fun MusicItem.directArtistRadioIds(): List<UUID> = when (type) {
        BaseItemKind.MUSIC_ARTIST -> listOf(id)
        else -> artistIds
    }.distinct()

    private fun MusicItem.artistRadioName(): String = when (type) {
        BaseItemKind.MUSIC_ARTIST -> title
        else -> artist.orEmpty()
    }

    private suspend fun loadPlaylistTargetTracks(userId: UUID, target: MusicPlaylistTarget): List<MusicItem> =
        when (target.type) {
            MusicPlaylistTargetType.CURRENT_TRACK -> listOf(target.item).filter(MusicItem::isPlayable)

            MusicPlaylistTargetType.ALBUM -> getMusicItems(
                queryName = "playlistTarget.albumTracks",
                userId = userId,
                parentId = target.item.id,
                includeItemTypes = listOf(BaseItemKind.AUDIO),
                sortBy = listOf(ItemSortBy.SORT_NAME),
                limit = PLAYLIST_ITEM_LIMIT,
            )

            MusicPlaylistTargetType.ARTIST -> getMusicItems(
                queryName = "playlistTarget.artistTracks",
                userId = userId,
                includeItemTypes = listOf(BaseItemKind.AUDIO),
                artistIds = target.item.artistIds.ifEmpty { listOf(target.item.id) },
                sortBy = listOf(ItemSortBy.SORT_NAME),
                limit = PLAYLIST_ITEM_LIMIT,
            )

            MusicPlaylistTargetType.GENRE -> getMusicItems(
                queryName = "playlistTarget.genreTracks",
                userId = userId,
                includeItemTypes = listOf(BaseItemKind.AUDIO),
                genreIds = listOf(target.item.id),
                sortBy = listOf(ItemSortBy.SORT_NAME),
                limit = PLAYLIST_ITEM_LIMIT,
            )
        }

    private suspend fun loadPlaylistItemIds(userId: UUID, playlistId: UUID): Set<UUID> =
        apiClient.playlistsApi.getPlaylistItems(
            playlistId = playlistId,
            userId = userId,
            limit = PLAYLIST_DUPLICATE_CHECK_LIMIT,
            enableImages = false,
            enableUserData = false,
        ).content.items.map(BaseItemDto::id).toSet()

    private fun MusicPlaylistTarget.defaultPlaylistName(): String = when (type) {
        MusicPlaylistTargetType.CURRENT_TRACK -> "PTV Music"
        MusicPlaylistTargetType.ALBUM -> item.title
        MusicPlaylistTargetType.ARTIST -> "${item.title} Essentials"
        MusicPlaylistTargetType.GENRE -> "${item.title} Mix"
    }

    private fun toMusicItem(item: BaseItemDto): MusicItem {
        val backdropItemId = item.parentBackdropItemId ?: item.id
        val backdropTag = item.backdropImageTags?.firstOrNull() ?: item.parentBackdropImageTags?.firstOrNull()
        val artist = item.artists?.joinToString()?.takeIf(String::isNotBlank)
            ?: item.albumArtist
            ?: item.artistItems?.joinToString { artistItem -> artistItem.name.orEmpty() }?.takeIf(String::isNotBlank)
        val childCount = when (item.type) {
            BaseItemKind.MUSIC_ARTIST -> item.albumCount ?: item.childCount ?: item.recursiveItemCount
            else -> item.childCount ?: item.recursiveItemCount
        }
        val audioStream = item.mediaStreams.orEmpty().firstOrNull { stream -> stream.type == MediaStreamType.AUDIO }
        val posterUrl = item.musicPosterUrl()
        val backdropUrl = when (item.type) {
            BaseItemKind.AUDIO -> posterUrl

            else -> backdropTag?.let { tag ->
                apiClient.imageApi.getItemImageUrl(
                    itemId = backdropItemId,
                    imageType = ImageType.BACKDROP,
                    maxWidth = 900,
                    quality = 84,
                    tag = tag,
                )
            }
        }

        return MusicItem(
            id = item.id,
            title = item.name.orEmpty().ifBlank { "Untitled" },
            subtitle = item.musicSubtitle(artist, childCount),
            album = item.album,
            albumId = item.albumId,
            artist = artist,
            artistIds = item.artistItems.orEmpty().map { artistItem -> artistItem.id },
            genres = item.genres.orEmpty(),
            type = item.type,
            collectionType = item.collectionType,
            posterUrl = posterUrl,
            backdropUrl = backdropUrl,
            container = item.container,
            codec = audioStream?.codec,
            playCount = item.userData?.playCount ?: 0,
            progress = item.userData?.playedPercentage?.toFloat(),
            isFavorite = item.userData?.isFavorite == true,
            isFolder = item.isFolder == true || item.type in folderTypes,
            isPlayable = item.mediaType == MediaType.AUDIO || item.type == BaseItemKind.AUDIO,
        )
    }

    private fun BaseItemDto.musicPosterUrl(): String? {
        val primaryTag = imageTags?.get(ImageType.PRIMARY)
        val albumImageUrl = albumId?.let { id ->
            albumPrimaryImageTag?.let { tag -> primaryImageUrl(itemId = id, tag = tag) }
        }
        val parentImageUrl = parentId?.let { id ->
            parentPrimaryImageTag?.let { tag -> primaryImageUrl(itemId = id, tag = tag) }
        }
        val itemImageUrl = primaryTag?.let { tag -> primaryImageUrl(itemId = id, tag = tag) }

        return when (type) {
            BaseItemKind.AUDIO -> albumImageUrl ?: itemImageUrl ?: parentImageUrl
            BaseItemKind.MUSIC_ALBUM -> itemImageUrl
            else -> itemImageUrl ?: albumImageUrl ?: parentImageUrl
        }
    }

    private fun primaryImageUrl(itemId: UUID, tag: String): String = apiClient.imageApi.getItemImageUrl(
        itemId = itemId,
        imageType = ImageType.PRIMARY,
        maxWidth = 420,
        quality = 88,
        tag = tag,
    )

    private fun BaseItemDto.musicSubtitle(artist: String?, childCount: Int?): String? = when (type) {
        BaseItemKind.AUDIO -> artist ?: album

        BaseItemKind.MUSIC_ALBUM -> listOfNotNull(artist, childCount?.let { count -> "$count tracks" })
            .joinToString(" - ")
            .ifBlank { null }

        BaseItemKind.MUSIC_ARTIST -> childCount?.let { count -> "$count albums" } ?: "Artist"

        BaseItemKind.PLAYLIST -> childCount?.let { count -> "$count tracks" } ?: "Playlist"

        else -> collectionType?.serialName ?: type.serialName
    }

    private fun String.normalizeMusicText(): String = lowercase()
        .replace("&", " and ")
        .replace("'", "")
        .replace(Regex("[^a-z0-9]+"), " ")
        .trim()
        .replace(Regex("\\s+"), " ")

    private fun List<MusicItem>.toPage(startIndex: Int, limit: Int): MusicPage {
        val safeStart = startIndex.coerceAtLeast(0)
        val items = drop(safeStart).take(limit)
        return MusicPage(items = items, totalCount = size, startIndex = safeStart)
    }

    private fun cacheHome(home: MusicHome, expectedScope: MusicCacheScope) {
        cacheBrowseItems(
            MusicBrowseKind.RECENTLY_ADDED,
            home.recentlyAddedAlbums,
            "home.recentlyAdded",
            expectedScope = expectedScope,
        )
        cacheBrowseItems(
            MusicBrowseKind.ALBUMS,
            home.albums,
            "home.albums",
            home.albumsTotalCount,
            expectedScope = expectedScope,
        )
        cacheBrowseItems(
            MusicBrowseKind.ARTISTS,
            home.artists,
            "home.artists",
            home.artistsTotalCount,
            expectedScope = expectedScope,
        )
        cacheBrowseItems(
            MusicBrowseKind.SONGS,
            home.songs,
            "home.songs",
            home.songsTotalCount,
            expectedScope = expectedScope,
        )
        cacheBrowseItems(MusicBrowseKind.GENRES, home.genres, "home.genres", expectedScope = expectedScope)
        cacheBrowseItems(MusicBrowseKind.PLAYLISTS, home.playlists, "home.playlists", expectedScope = expectedScope)
        cacheBrowseItems(MusicBrowseKind.FAVORITES, home.favorites, "home.favorites", expectedScope = expectedScope)
        cacheBrowseItems(
            MusicBrowseKind.RECENTLY_PLAYED,
            home.recentlyPlayed,
            "home.recentlyPlayed",
            expectedScope = expectedScope,
        )
        cacheBrowseItems(
            MusicBrowseKind.RECOMMENDATIONS,
            home.recommendations,
            "home.recommendations",
            expectedScope = expectedScope,
        )
        rememberPlaybackTracks(
            home.songs + home.favorites + home.recentlyPlayed + home.recommendations,
            "home",
            expectedScope = expectedScope,
        )
    }

    private fun cacheHomeSourcePage(
        source: MusicHomeSource,
        page: MusicPage,
        label: String,
        expectedScope: MusicCacheScope,
    ) {
        val browseKind = source.toBrowseKind() ?: return
        cacheBrowsePage(browseKind, page, label, expectedScope = expectedScope)
        if (browseKind == MusicBrowseKind.SONGS) {
            rememberPlaybackTracks(page.items, label, expectedScope = expectedScope)
        }
    }

    private fun cacheHomeSourceItems(
        source: MusicHomeSource,
        items: List<MusicItem>,
        label: String,
        expectedScope: MusicCacheScope,
    ) {
        val browseKind = source.toBrowseKind() ?: return
        cacheBrowseItems(browseKind, items, label, expectedScope = expectedScope)
        if (items.any(MusicItem::isPlayable)) {
            rememberPlaybackTracks(items, label, expectedScope = expectedScope)
        }
    }

    private fun MusicHomeSource.toBrowseKind(): MusicBrowseKind? = when (this) {
        MusicHomeSource.RECENTLY_ADDED_ALBUMS -> MusicBrowseKind.RECENTLY_ADDED
        MusicHomeSource.ALBUMS -> MusicBrowseKind.ALBUMS
        MusicHomeSource.ARTISTS -> MusicBrowseKind.ARTISTS
        MusicHomeSource.SONGS -> MusicBrowseKind.SONGS
        MusicHomeSource.GENRES -> MusicBrowseKind.GENRES
        MusicHomeSource.PLAYLISTS -> MusicBrowseKind.PLAYLISTS
        MusicHomeSource.FAVORITES -> MusicBrowseKind.FAVORITES
        MusicHomeSource.RECENTLY_PLAYED -> MusicBrowseKind.RECENTLY_PLAYED
        MusicHomeSource.RECOMMENDATIONS -> MusicBrowseKind.RECOMMENDATIONS
    }

    private fun cacheItem(item: MusicItem, label: String) {
        when {
            item.isPlayable -> rememberPlaybackTracks(listOf(item), label)
            item.type == BaseItemKind.MUSIC_ALBUM -> cacheBrowseItems(MusicBrowseKind.ALBUMS, listOf(item), label)
            item.type == BaseItemKind.MUSIC_ARTIST -> cacheBrowseItems(MusicBrowseKind.ARTISTS, listOf(item), label)
            item.type in genreTypes -> cacheBrowseItems(MusicBrowseKind.GENRES, listOf(item), label)
            item.type == BaseItemKind.PLAYLIST -> cacheBrowseItems(MusicBrowseKind.PLAYLISTS, listOf(item), label)
        }
    }

    private fun rememberPlaybackTracks(items: List<MusicItem>, label: String, expectedScope: MusicCacheScope? = null) =
        synchronized(cacheScopeLock) {
            val playableItems = items.filter(MusicItem::isPlayable)
            if (playableItems.isEmpty()) return@synchronized
            if (expectedScope != null && activeCacheScope != expectedScope) return@synchronized

            val mergedTracks = (playableItems + cachedPlaybackTracks)
                .distinctBy(MusicItem::id)
                .take(MAX_TRACK_CACHE_SIZE)
            cachedPlaybackTracks = mergedTracks
            cacheBrowseItems(MusicBrowseKind.SONGS, mergedTracks, "$label.playbackTracks")

            val derivedAlbums = mergedTracks.toAlbumItems()
            if (derivedAlbums.isNotEmpty()) {
                cacheBrowseItems(MusicBrowseKind.ALBUMS, derivedAlbums, "$label.derivedAlbums")
                cacheBrowseItems(MusicBrowseKind.RECENTLY_ADDED, derivedAlbums, "$label.derivedRecentlyAdded")
            }

            val derivedArtists = mergedTracks.toArtistItems()
            if (derivedArtists.isNotEmpty()) {
                cacheBrowseItems(MusicBrowseKind.ARTISTS, derivedArtists, "$label.derivedArtists")
            }

            Timber.i(
                "PTV music cache write playbackTracks source=$label tracks=${mergedTracks.size} " +
                    "derivedAlbums=${derivedAlbums.size} derivedArtists=${derivedArtists.size}",
            )
        }

    private fun List<MusicItem>.toAlbumItems(): List<MusicItem> = asSequence()
        .filter(MusicItem::isPlayable)
        .mapNotNull { track ->
            val albumId = track.albumId ?: return@mapNotNull null
            val albumTitle = track.album?.takeIf(String::isNotBlank) ?: return@mapNotNull null
            MusicItem(
                id = albumId,
                title = albumTitle,
                subtitle = track.artist,
                album = albumTitle,
                albumId = albumId,
                artist = track.artist,
                artistIds = track.artistIds,
                genres = track.genres,
                type = BaseItemKind.MUSIC_ALBUM,
                collectionType = null,
                posterUrl = track.posterUrl,
                backdropUrl = track.backdropUrl,
                container = null,
                codec = null,
                playCount = 0,
                progress = null,
                isFavorite = false,
                isFolder = true,
                isPlayable = false,
            )
        }
        .distinctBy(MusicItem::id)
        .toList()

    private fun List<MusicItem>.toArtistItems(): List<MusicItem> = asSequence()
        .filter(MusicItem::isPlayable)
        .flatMap { track ->
            track.artistIds.mapNotNull { artistId ->
                val artistName = track.artist?.takeIf(String::isNotBlank) ?: return@mapNotNull null
                MusicItem(
                    id = artistId,
                    title = artistName,
                    subtitle = "Artist",
                    album = null,
                    albumId = null,
                    artist = artistName,
                    artistIds = listOf(artistId),
                    genres = track.genres,
                    type = BaseItemKind.MUSIC_ARTIST,
                    collectionType = null,
                    posterUrl = null,
                    backdropUrl = track.backdropUrl,
                    container = null,
                    codec = null,
                    playCount = 0,
                    progress = null,
                    isFavorite = false,
                    isFolder = true,
                    isPlayable = false,
                )
            }
        }
        .distinctBy(MusicItem::id)
        .toList()

    private fun cacheBrowsePage(
        kind: MusicBrowseKind,
        page: MusicPage,
        label: String,
        expectedScope: MusicCacheScope? = null,
    ) {
        if (page.items.isEmpty()) return
        cacheBrowseItems(kind, page.items, label, page.totalCount, page.startIndex, expectedScope)
    }

    private fun cacheBrowseItems(
        kind: MusicBrowseKind,
        items: List<MusicItem>,
        label: String,
        totalCount: Int = items.size,
        startIndex: Int = 0,
        expectedScope: MusicCacheScope? = null,
    ) {
        if (items.isEmpty()) return

        val page = synchronized(cacheScopeLock) {
            if (expectedScope != null && activeCacheScope != expectedScope) return
            val existing = cachedBrowsePages[kind]
            val shouldMergeWithExisting = existing != null &&
                (
                    startIndex > 0 || label.contains("derived") || label.contains("playbackTracks") ||
                        label == "loadItem" || label == "loadItems" || label.startsWith("children")
                    )
            val mergedItems = when {
                existing == null -> items
                shouldMergeWithExisting -> existing.items.mergeDistinct(items)
                startIndex <= 0 -> items
                else -> existing.items.mergeDistinct(items)
            }
            MusicPage(
                items = mergedItems.distinctBy(MusicItem::id),
                totalCount = totalCount.coerceAtLeast(mergedItems.size),
                startIndex = 0,
            ).also { mergedPage ->
                cachedBrowsePages = cachedBrowsePages + (kind to mergedPage)
            }
        }
        Timber.i(
            "PTV music cache write kind=$kind source=$label count=${page.items.size}/${page.totalCount} " +
                "repositoryId=$instanceId",
        )
    }

    private fun cachedPageFor(kind: MusicBrowseKind): MusicPage? =
        cachedBrowsePageInternal(kind = kind, startIndex = 0, limit = AUTO_PAGE_LIMIT)

    private fun cachedItemsFor(kind: MusicBrowseKind): List<MusicItem> = cachedPageFor(kind)?.items.orEmpty()

    private fun cachedBrowsePageInternal(kind: MusicBrowseKind, startIndex: Int, limit: Int): MusicPage? {
        cachedBrowsePages[kind]?.takeIf { page -> page.items.isNotEmpty() }?.let { page ->
            Timber.i(
                "PTV music cache read kind=$kind source=browseCache count=${page.items.size}/${page.totalCount} " +
                    "start=$startIndex limit=$limit repositoryId=$instanceId",
            )
            return page.items.toPage(startIndex = startIndex, limit = limit).copy(totalCount = page.totalCount)
        }

        val home = cachedHome
        val items = home?.let { cached ->
            when (kind) {
                MusicBrowseKind.RECENTLY_ADDED -> cached.recentlyAddedAlbums
                MusicBrowseKind.ALBUMS -> cached.albums
                MusicBrowseKind.ARTISTS -> cached.artists
                MusicBrowseKind.SONGS -> cached.songs
                MusicBrowseKind.GENRES -> cached.genres
                MusicBrowseKind.PLAYLISTS -> cached.playlists
                MusicBrowseKind.FAVORITES -> cached.favorites
                MusicBrowseKind.RECENTLY_PLAYED -> cached.recentlyPlayed
                MusicBrowseKind.RECOMMENDATIONS -> cached.recommendations
            }
        }.orEmpty()

        if (items.isNotEmpty()) {
            Timber.i(
                "PTV music cache read kind=$kind source=homeCache count=${items.size} " +
                    "start=$startIndex limit=$limit repositoryId=$instanceId",
            )
            return items.toPage(startIndex = startIndex, limit = limit)
        }

        val derivedItems = when (kind) {
            MusicBrowseKind.RECENTLY_ADDED,
            MusicBrowseKind.ALBUMS,
            -> cachedPlaybackTracks.toAlbumItems()

            MusicBrowseKind.ARTISTS -> cachedPlaybackTracks.toArtistItems()

            MusicBrowseKind.SONGS -> cachedPlaybackTracks

            else -> emptyList()
        }

        if (derivedItems.isNotEmpty()) {
            Timber.i(
                "PTV music cache read kind=$kind source=playbackTrackCache count=${derivedItems.size} " +
                    "start=$startIndex limit=$limit repositoryId=$instanceId",
            )
            return derivedItems.toPage(startIndex = startIndex, limit = limit)
        }

        Timber.i(
            "PTV music cache read kind=$kind miss start=$startIndex limit=$limit " +
                "repositoryId=$instanceId",
        )
        return null
    }

    private fun MusicPage.filterPlayableTracks(): MusicPage {
        val playableItems = items.filter(MusicItem::isPlayable)
        if (items.isNotEmpty() && playableItems.isEmpty()) {
            Timber.w(
                "PTV music track query returned ${items.size} non-playable children; " +
                    "types=${items.map(MusicItem::type).distinct()}",
            )
        }

        return copy(
            items = playableItems,
            totalCount = when {
                playableItems.size == items.size -> totalCount
                playableItems.size >= totalCount -> playableItems.size
                else -> totalCount.coerceAtLeast(playableItems.size)
            },
        )
    }

    private fun List<MusicItem>?.toCachedPage(totalCount: Int?): MusicPage? = when {
        isNullOrEmpty() -> null
        else -> MusicPage(items = this, totalCount = totalCount ?: size, startIndex = 0)
    }

    private val MusicHome.hasAnyLibraryContent: Boolean
        get() = recentlyAddedAlbums.isNotEmpty() ||
            albumsTotalCount > 0 ||
            artistsTotalCount > 0 ||
            songsTotalCount > 0 ||
            albums.isNotEmpty() ||
            artists.isNotEmpty() ||
            songs.isNotEmpty() ||
            genres.isNotEmpty() ||
            playlists.isNotEmpty() ||
            favorites.isNotEmpty() ||
            recentlyPlayed.isNotEmpty() ||
            recommendations.isNotEmpty()

    private val MusicPage.isEmptyResult: Boolean
        get() = items.isEmpty() && totalCount == 0

    private fun MusicPage.hasRequestedWindow(startIndex: Int, limit: Int): Boolean {
        val requestedEndExclusive = startIndex.coerceAtLeast(0) + limit.coerceAtLeast(1)
        val cachedEndExclusive = this.startIndex + items.size
        return cachedEndExclusive >= requestedEndExclusive || cachedEndExclusive >= totalCount
    }

    private fun List<MusicItem>.ensureContains(item: MusicItem): List<MusicItem> = when {
        any { queuedItem -> queuedItem.id == item.id } -> this
        else -> listOf(item) + this
    }

    private fun List<MusicItem>.mergeDistinct(items: List<MusicItem>): List<MusicItem> =
        (this + items).distinctBy(MusicItem::id)

    private fun saveNotInterestedItemIds(ids: Set<UUID>) {
        fallbackNotInterestedIds = ids
        notInterestedPreferences?.edit {
            putStringSet(NOT_INTERESTED_ITEM_IDS_KEY, ids.map(UUID::toString).toSet())
        }
    }

    private fun ApiClient.safeBaseHost(): String {
        val value = baseUrl?.substringBefore("?") ?: return "<none>"
        return value.substringAfter("://", value).substringBefore("/")
    }

    private fun ApiClient.cacheScopeBaseUrl(): String = baseUrl?.trimEnd('/') ?: "<none>"

    private fun isCacheVisibleForConfiguredSession(): Boolean {
        val userIdProvider = savedUserIdProvider ?: return true
        val configuredUserId = userIdProvider() ?: return false
        return activeCacheScope == MusicCacheScope(
            serverUrl = apiClient.cacheScopeBaseUrl(),
            userId = configuredUserId,
        )
    }

    private fun ApiClient.hasAccessToken(): Boolean = !accessToken.isNullOrBlank()

    private fun runtimeInstanceId(value: Any): String = System.identityHashCode(value).toString(16)

    private companion object {
        const val AUTO_PAGE_LIMIT = MusicPagingDefaults.AUTO_PAGE_LIMIT
        const val AUTO_GENERATED_QUEUE_LIMIT = 35
        const val AUTO_PLAYLIST_NAME = "PTV Auto Picks"
        const val DETAIL_LIMIT = 160
        const val HOME_MAX_CONCURRENT_REQUESTS = 4
        const val HOME_ARTIST_QUERY_GRACE_MS = 2_500L
        const val HOME_SOURCE_TIMEOUT_MS = 35_000L
        const val PLAYLIST_PROBE_MAX_CONCURRENT_REQUESTS = 2
        const val AUTO_PICK_SOURCE_TIMEOUT_MS = 3_000L
        const val RECOMMENDATION_GENRE_LIMIT = 4
        const val RECOMMENDATION_SEED_LIMIT = 6
        const val ROW_LIMIT = 18
        const val PLAYLIST_DUPLICATE_CHECK_LIMIT = 1_000
        const val PLAYLIST_AUDIO_PROBE_LIMIT = 12
        const val PLAYLIST_ITEM_LIMIT = 500
        const val PLAYLIST_LIMIT = 100
        const val SEARCH_LIMIT = 60
        const val SONG_PAGE_SIZE = MusicPagingDefaults.HOME_SONG_LIMIT
        const val MIN_GENERATED_MIX_TRACKS = 25
        const val MAX_GENERATED_MIX_TRACKS = 50
        const val MAX_TRACK_CACHE_SIZE = 300
        const val ITEMS_ENDPOINT = "GET /Users/{userId}/Items"
        const val GENRES_ENDPOINT = "GET /Genres"
        const val NOT_INTERESTED_PREFS_NAME = "ptv_music_song_actions"
        const val NOT_INTERESTED_ITEM_IDS_KEY = "not_interested_item_ids"
        val CACHE_PRESERVED_ON_EMPTY_SOURCES = setOf(
            MusicHomeSource.RECENTLY_ADDED_ALBUMS,
            MusicHomeSource.ALBUMS,
            MusicHomeSource.ARTISTS,
            MusicHomeSource.SONGS,
        )

        val imageTypes = listOf(ImageType.PRIMARY, ImageType.BACKDROP, ImageType.THUMB)
        val musicItemFields = listOf(
            ItemFields.OVERVIEW,
            ItemFields.PRIMARY_IMAGE_ASPECT_RATIO,
            ItemFields.PARENT_ID,
            ItemFields.ITEM_COUNTS,
            ItemFields.DATE_CREATED,
            ItemFields.GENRES,
            ItemFields.MEDIA_STREAMS,
            ItemFields.MEDIA_SOURCES,
        )
        val musicBrowseItemFields = listOf(
            ItemFields.OVERVIEW,
            ItemFields.PRIMARY_IMAGE_ASPECT_RATIO,
            ItemFields.PARENT_ID,
            ItemFields.ITEM_COUNTS,
            ItemFields.DATE_CREATED,
            ItemFields.GENRES,
        )
        val musicRecommendationItemFields = listOf(
            ItemFields.PRIMARY_IMAGE_ASPECT_RATIO,
            ItemFields.PARENT_ID,
            ItemFields.DATE_CREATED,
            ItemFields.GENRES,
        )
        val folderTypes = setOf(
            BaseItemKind.MUSIC_ALBUM,
            BaseItemKind.MUSIC_ARTIST,
            BaseItemKind.GENRE,
            BaseItemKind.MUSIC_GENRE,
            BaseItemKind.PLAYLIST,
        )
        val genreTypes = setOf(BaseItemKind.GENRE, BaseItemKind.MUSIC_GENRE)
    }
}
