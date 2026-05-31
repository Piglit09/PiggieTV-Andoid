package org.jellyfin.mobile.ui.screens.home

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.jellyfin.mobile.app.ApiClientController
import org.jellyfin.mobile.data.entity.ServerEntity
import org.jellyfin.mobile.player.interaction.PlayOptions
import org.jellyfin.mobile.reporting.MediaReportReason
import org.jellyfin.mobile.reporting.MediaReportSender
import org.jellyfin.mobile.reporting.MediaReportTarget
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.exception.ApiClientException
import org.jellyfin.sdk.api.client.extensions.authenticateUserByName
import org.jellyfin.sdk.api.client.extensions.genresApi
import org.jellyfin.sdk.api.client.extensions.imageApi
import org.jellyfin.sdk.api.client.extensions.itemsApi
import org.jellyfin.sdk.api.client.extensions.studiosApi
import org.jellyfin.sdk.api.client.extensions.userApi
import org.jellyfin.sdk.api.client.extensions.userViewsApi
import org.jellyfin.sdk.model.UUID
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.CollectionType
import org.jellyfin.sdk.model.api.ImageType
import org.jellyfin.sdk.model.api.ItemFields
import org.jellyfin.sdk.model.api.ItemSortBy
import org.jellyfin.sdk.model.api.MediaType
import org.jellyfin.sdk.model.api.SortOrder
import org.jellyfin.sdk.model.api.UserDto
import timber.log.Timber
import kotlin.random.Random
import kotlin.time.Duration.Companion.ZERO

class NativeHomeViewModel(
    app: Application,
    private val apiClientController: ApiClientController,
    private val apiClient: ApiClient,
    private val mediaReportSender: MediaReportSender,
) : AndroidViewModel(app) {
    private val _uiState = MutableStateFlow<NativeHomeUiState>(NativeHomeUiState.Loading)
    val uiState: StateFlow<NativeHomeUiState> get() = _uiState

    private var currentServer: ServerEntity? = null
    private var currentUserId: UUID? = null
    private var currentUserName: String = ""
    private var homeRowsJob: Job? = null
    private var homeLoadVersion = 0
    private val genreIdsByUser = mutableMapOf<UUID, Map<String, UUID>>()
    private val studioIdsByUser = mutableMapOf<UUID, Map<String, UUID>>()

    fun load(server: ServerEntity, force: Boolean = false) {
        if (!force && currentServer?.id == server.id && uiState.value is NativeHomeUiState.Content) return

        currentServer = server
        viewModelScope.launch {
            _uiState.value = NativeHomeUiState.Loading
            apiClientController.loadSavedServerUser()

            val user = runCatching {
                withContext(Dispatchers.IO) { apiClient.userApi.getCurrentUser().content }
            }.getOrNull()

            if (user == null) {
                showLogin(server)
            } else {
                currentUserId = user.id
                currentUserName = user.name.orEmpty()
                loadHome(user)
            }
        }
    }

    fun signIn(server: ServerEntity, username: String, password: String) {
        val previous = uiState.value as? NativeHomeUiState.Login
        _uiState.value = (previous ?: NativeHomeUiState.Login(server.hostname)).copy(
            username = username,
            isSigningIn = true,
            error = null,
        )

        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    apiClient.userApi.authenticateUserByName(username.trim(), password).content
                }
                val user = requireNotNull(result.user) { "PiggieTV did not return a user for this login." }
                val accessToken = requireNotNull(result.accessToken) { "PiggieTV did not return an access token." }
                apiClientController.setupUser(server.id, user.id, accessToken)
                currentUserId = user.id
                currentUserName = user.name.orEmpty()
                loadHome(user)
            } catch (e: Exception) {
                _uiState.value = (previous ?: NativeHomeUiState.Login(server.hostname)).copy(
                    username = username,
                    isSigningIn = false,
                    error = e.friendlyMessage("Could not sign in to PiggieTV."),
                )
            }
        }
    }

    fun openLibrary(library: NativeMediaItem) {
        viewModelScope.launch {
            val content = uiState.value as? NativeHomeUiState.Content ?: return@launch
            _uiState.value = content.copy(isLoadingLibrary = true)
            try {
                val items = withContext(Dispatchers.IO) {
                    apiClient.itemsApi.getItems(
                        parentId = library.id,
                        recursive = library.collectionType != CollectionType.TVSHOWS,
                        includeItemTypes = library.collectionType.contentTypes(),
                        sortBy = listOf(ItemSortBy.SORT_NAME),
                        sortOrder = listOf(SortOrder.ASCENDING),
                        fields = displayItemFields,
                        enableUserData = true,
                        imageTypeLimit = 1,
                        enableImageTypes = imageTypes,
                        limit = 120,
                    ).content.items
                }

                _uiState.value = content.copy(
                    selectedLibrary = NativeLibraryContent(
                        title = library.title,
                        subtitle = library.subtitle,
                        items = items.map(::toNativeMediaItem),
                    ),
                    isLoadingLibrary = false,
                )
            } catch (e: Exception) {
                _uiState.value = content.copy(
                    selectedLibrary = NativeLibraryContent(
                        title = library.title,
                        subtitle = library.subtitle,
                        items = emptyList(),
                        error = e.friendlyMessage("Could not load this library."),
                    ),
                    isLoadingLibrary = false,
                )
            }
        }
    }

    fun openFolder(item: NativeMediaItem) {
        viewModelScope.launch {
            val content = uiState.value as? NativeHomeUiState.Content ?: return@launch
            _uiState.value = content.copy(isLoadingLibrary = true)
            try {
                val items = loadFolderItems(item)

                _uiState.value = content.copy(
                    selectedLibrary = NativeLibraryContent(
                        title = item.title,
                        subtitle = item.subtitle,
                        items = items,
                    ),
                    isLoadingLibrary = false,
                )
            } catch (e: Exception) {
                _uiState.value = content.copy(
                    selectedLibrary = NativeLibraryContent(
                        title = item.title,
                        subtitle = item.subtitle,
                        items = emptyList(),
                        error = e.friendlyMessage("Could not load this folder."),
                    ),
                    isLoadingLibrary = false,
                )
            }
        }
    }

    suspend fun loadFolderItems(item: NativeMediaItem): List<NativeMediaItem> {
        val includeItemTypes = item.childContentTypes()
        if (includeItemTypes.isEmpty()) return emptyList()

        return withContext(Dispatchers.IO) {
            apiClient.itemsApi.getItems(
                parentId = item.id,
                recursive = item.type == BaseItemKind.MUSIC_ARTIST,
                includeItemTypes = includeItemTypes,
                sortBy = item.childSortBy(),
                sortOrder = listOf(SortOrder.ASCENDING),
                fields = displayItemFields,
                enableUserData = true,
                imageTypeLimit = 1,
                enableImageTypes = imageTypes,
                limit = 200,
            ).content.items.map(::toNativeMediaItem)
        }
    }

    fun closeLibrary() {
        _uiState.update { state ->
            when (state) {
                is NativeHomeUiState.Content -> state.copy(selectedLibrary = null, isLoadingLibrary = false)
                else -> state
            }
        }
    }

    fun signOut(server: ServerEntity) {
        viewModelScope.launch {
            homeRowsJob?.cancel()
            homeLoadVersion++
            _uiState.value = NativeHomeUiState.Loading
            apiClientController.logoutCurrentUser()
            currentUserId = null
            currentUserName = ""
            showLogin(server)
        }
    }

    fun playRandomTitle(onPlay: (PlayOptions) -> Unit) {
        viewModelScope.launch {
            val loadedItem = (uiState.value as? NativeHomeUiState.Content)
                ?.home
                ?.sections
                ?.flatMap(NativeMediaSection::items)
                ?.filter { item -> item.isPlayable && !item.isFolder && item.type in randomPlayableTypes }
                ?.distinctBy(NativeMediaItem::id)
                ?.takeIf(List<NativeMediaItem>::isNotEmpty)
                ?.random(Random.Default)

            if (loadedItem != null) {
                onPlay(loadedItem.id.toPlayOptions())
                return@launch
            }

            val userId = currentUserId ?: runCatching {
                withContext(Dispatchers.IO) { apiClient.userApi.getCurrentUser().content.id }
            }.onSuccess { id ->
                currentUserId = id
            }.getOrElse { error ->
                Timber.w(error, "Could not load current PiggieTV user for random playback")
                return@launch
            }

            val item = runCatching {
                fetchRandomPlayableItem(userId)
            }.onFailure { error ->
                Timber.w(error, "Could not load a random PiggieTV title")
            }.getOrNull() ?: return@launch

            onPlay(item.id.toPlayOptions())
        }
    }

    private suspend fun fetchRandomPlayableItem(userId: UUID): BaseItemDto? = withContext(Dispatchers.IO) {
        apiClient.itemsApi.getItems(
            userId = userId,
            recursive = true,
            includeItemTypes = randomPlayableTypes,
            sortBy = listOf(ItemSortBy.RANDOM),
            fields = displayItemFields,
            enableUserData = true,
            imageTypeLimit = 1,
            enableImageTypes = imageTypes,
            enableTotalRecordCount = false,
            enableImages = true,
            limit = RANDOM_PLAYABLE_CANDIDATE_LIMIT,
        ).content.items.firstOrNull { item -> item.isRandomPlayableVideo() }
    }

    fun submitMediaReport(item: NativeMediaItem, reason: MediaReportReason, details: String?) {
        val target = MediaReportTarget(
            itemId = item.id.toString(),
            title = item.title,
            subtitle = item.subtitle,
            type = item.type.serialName,
            source = "Native Home",
            userName = currentUserName.takeIf(String::isNotBlank),
        )

        viewModelScope.launch {
            runCatching {
                mediaReportSender.send(target, reason, details)
            }.onFailure { error ->
                Timber.w(error, "Failed to send PiggieTV media report")
            }
        }
    }

    private suspend fun showLogin(server: ServerEntity) {
        val users = runCatching {
            withContext(Dispatchers.IO) { apiClient.userApi.getPublicUsers().content }
        }.getOrElse { emptyList() }

        _uiState.value = NativeHomeUiState.Login(
            serverName = server.hostname,
            publicUsers = users.mapNotNull(UserDto::name),
        )
    }

    private suspend fun loadHome(user: UserDto) {
        currentUserId = user.id
        val loadVersion = ++homeLoadVersion
        homeRowsJob?.cancel()
        _uiState.value = NativeHomeUiState.Loading
        try {
            val userViews = withContext(Dispatchers.IO) {
                apiClient.userViewsApi.getUserViews(
                    includeExternalContent = false,
                    includeHidden = false,
                ).content.items
            }
            if (loadVersion != homeLoadVersion) return

            val rows = PtvHomeRows.homeRows
            val rowResults = mutableMapOf<String, List<BaseItemDto>>()
            rows.firstOrNull { row -> row.type == PtvRowType.LIBRARIES }?.let { row ->
                rowResults[row.id] = userViews.take(row.itemLimit)
            }
            val initialSections = rows.toVisibleSections(rowResults)
            val content = NativeHomeContent(
                userName = user.name.orEmpty().ifBlank { "PiggieTV" },
                isAdmin = user.policy?.isAdministrator == true,
                dashboardUrl = currentServer?.dashboardUrl(),
                libraries = userViews.map(::toNativeMediaItem),
                hero = initialSections.homeHero(),
                sections = initialSections,
            )

            _uiState.value = NativeHomeUiState.Content(content)
            homeRowsJob = viewModelScope.launch {
                loadRowsProgressively(loadVersion, user.id, userViews)
            }
        } catch (e: Exception) {
            if (e is ApiClientException) {
                showLogin(requireNotNull(currentServer))
                return
            }

            _uiState.value = NativeHomeUiState.Error(e.friendlyMessage("Could not load PiggieTV."))
        }
    }

    private suspend fun loadRowsProgressively(loadVersion: Int, userId: UUID, userViews: List<BaseItemDto>) {
        val rows = PtvHomeRows.homeRows
        val rowResults = mutableMapOf<String, List<BaseItemDto>>()
        rows.firstOrNull { row -> row.type == PtvRowType.LIBRARIES }?.let { row ->
            rowResults[row.id] = userViews.take(row.itemLimit)
        }

        rows
            .filterNot { row -> row.type == PtvRowType.LIBRARIES || row.type == PtvRowType.SEERR_REQUESTS }
            .groupBy(PtvHomeRowSpec::tier)
            .toSortedMap()
            .values
            .forEach { tierRows ->
                if (loadVersion != homeLoadVersion) return

                val genreIdsByName = if (tierRows.any { row -> row.type == PtvRowType.GENRE }) {
                    loadGenreIdsCached(userId)
                } else {
                    emptyMap()
                }
                val studioIdsByName = if (tierRows.any { row ->
                        row.type == PtvRowType.SEARCH && (row.studioNames.isNotEmpty() || row.studioKeywords.isNotEmpty())
                    }
                ) {
                    loadStudioIdsCached(userId)
                } else {
                    emptyMap()
                }

                fetchRows(tierRows, userId, userViews, genreIdsByName, studioIdsByName).forEach { (row, items) ->
                    rowResults[row.id] = items
                }
                publishHomeRows(loadVersion, rows, rowResults)
            }
    }

    private suspend fun fetchRows(
        rows: List<PtvHomeRowSpec>,
        userId: UUID,
        userViews: List<BaseItemDto>,
        genreIdsByName: Map<String, UUID>,
        studioIdsByName: Map<String, UUID>,
    ): List<Pair<PtvHomeRowSpec, List<BaseItemDto>>> = coroutineScope {
        val semaphore = Semaphore(PTV_ROW_FETCH_CONCURRENCY)

        rows.map { row ->
            async {
                row to semaphore.withPermit {
                    runCatching {
                        fetchPtvRow(row, userId, userViews, genreIdsByName, studioIdsByName)
                    }.getOrDefault(emptyList())
                }
            }
        }.awaitAll()
    }

    private fun publishHomeRows(
        loadVersion: Int,
        rows: List<PtvHomeRowSpec>,
        rowResults: Map<String, List<BaseItemDto>>,
    ) {
        if (loadVersion != homeLoadVersion) return
        val sections = rows.toVisibleSections(rowResults)
        val hero = sections.homeHero()

        _uiState.update { state ->
            if (loadVersion != homeLoadVersion) return@update state
            when (state) {
                is NativeHomeUiState.Content -> state.copy(home = state.home.copy(hero = hero, sections = sections))
                else -> state
            }
        }
    }

    private suspend fun fetchPtvRow(
        row: PtvHomeRowSpec,
        userId: UUID,
        userViews: List<BaseItemDto>,
        genreIdsByName: Map<String, UUID>,
        studioIdsByName: Map<String, UUID>,
    ): List<BaseItemDto> = when (row.type) {
        PtvRowType.LIBRARIES -> userViews.take(row.itemLimit)
        PtvRowType.SEERR_REQUESTS -> emptyList()
        PtvRowType.CONTINUE -> apiClient.itemsApi.getResumeItems(
            userId = userId,
            limit = row.candidateLimit,
            mediaTypes = listOf(MediaType.VIDEO),
            includeItemTypes = videoTypes,
            fields = displayItemFields,
            enableUserData = true,
            imageTypeLimit = 1,
            enableImageTypes = imageTypes,
            enableTotalRecordCount = false,
            enableImages = true,
        ).content.items.toPtvRowItems(row)
        PtvRowType.LATEST -> getPtvItems(
            userId = userId,
            includeItemTypes = row.itemTypes,
            sortBy = latestSortBy,
            sortOrder = listOf(SortOrder.DESCENDING),
            fields = row.queryItemFields(),
            limit = row.queryLimit,
        ).toPtvRowItems(row)
        PtvRowType.GENRE -> {
            val genreIds = row.genres.mapNotNull { genre -> genreIdsByName[genre.normalizePtvText()] }

            if (genreIds.isEmpty()) {
                emptyList()
            } else {
                getPtvItems(
                    userId = userId,
                    includeItemTypes = row.includeItemTypes,
                    genreIds = genreIds,
                    sortBy = listOf(ItemSortBy.RANDOM),
                    fields = row.queryItemFields(),
                    limit = row.queryLimit,
                ).toPtvRowItems(row)
            }
        }
        PtvRowType.LIBRARY -> {
            val parentId = findLibraryParentId(row, userViews)

            getPtvItems(
                userId = userId,
                parentId = parentId,
                searchTerm = if (parentId == null) row.title else null,
                includeItemTypes = row.itemTypes.ifEmpty { row.includeItemTypes },
                sortBy = row.sortBy,
                sortOrder = row.sortOrder,
                fields = row.queryItemFields(),
                limit = row.queryLimit,
            ).toPtvRowItems(row)
        }
        PtvRowType.SEARCH -> {
            val searchTerms = row.searchTerms.ifEmpty { listOf(row.title) }.take(PTV_SEARCH_TERM_LIMIT)
            val searchItems = fetchSearchTermItems(row, userId, searchTerms)
            val studioIds = findStudioIds(row, studioIdsByName)
            val studioItems = if (studioIds.isNotEmpty()) {
                runCatching {
                    getPtvItems(
                        userId = userId,
                        includeItemTypes = row.includeItemTypes,
                        studioIds = studioIds,
                        sortBy = listOf(ItemSortBy.RANDOM),
                        fields = row.queryItemFields(),
                        limit = row.queryLimit,
                    )
                }.getOrDefault(emptyList())
            } else {
                emptyList()
            }

            (searchItems + studioItems).take(row.candidateLimit).toPtvRowItems(row)
        }
    }

    private suspend fun fetchSearchTermItems(
        row: PtvHomeRowSpec,
        userId: UUID,
        searchTerms: List<String>,
    ): List<BaseItemDto> = coroutineScope {
        val semaphore = Semaphore(PTV_SEARCH_TERM_FETCH_CONCURRENCY)

        searchTerms.map { searchTerm ->
            async {
                semaphore.withPermit {
                    runCatching {
                        getPtvItems(
                            userId = userId,
                            searchTerm = searchTerm,
                            includeItemTypes = row.includeItemTypes,
                            sortBy = listOf(ItemSortBy.RANDOM),
                            fields = row.queryItemFields(),
                            limit = row.queryLimit,
                        )
                    }.getOrDefault(emptyList())
                }
            }
        }.awaitAll().flatten()
    }

    private suspend fun getPtvItems(
        userId: UUID,
        parentId: UUID? = null,
        searchTerm: String? = null,
        includeItemTypes: List<BaseItemKind> = movieSeriesTypes,
        genreIds: List<UUID> = emptyList(),
        studioIds: List<UUID> = emptyList(),
        sortBy: List<ItemSortBy> = listOf(ItemSortBy.RANDOM),
        sortOrder: List<SortOrder> = emptyList(),
        fields: List<ItemFields> = displayItemFields,
        limit: Int = PTV_ROW_ITEM_LIMIT,
    ) = apiClient.itemsApi.getItems(
        userId = userId,
        parentId = parentId,
        searchTerm = searchTerm,
        includeItemTypes = includeItemTypes,
        genreIds = genreIds,
        studioIds = studioIds,
        sortBy = sortBy,
        sortOrder = sortOrder,
        recursive = true,
        fields = fields,
        enableUserData = true,
        imageTypeLimit = 1,
        enableImageTypes = imageTypes,
        enableTotalRecordCount = false,
        enableImages = true,
        limit = limit,
    ).content.items

    private suspend fun loadGenreIds(userId: UUID): Map<String, UUID> = runCatching {
        apiClient.genresApi.getGenres(
            userId = userId,
            includeItemTypes = movieSeriesTypes,
            sortBy = listOf(ItemSortBy.SORT_NAME),
            sortOrder = listOf(SortOrder.ASCENDING),
            enableTotalRecordCount = false,
            limit = 300,
        ).content.items
            .filter { item -> item.name != null }
            .associate { item -> item.name.orEmpty().normalizePtvText() to item.id }
    }.getOrDefault(emptyMap())

    private suspend fun loadGenreIdsCached(userId: UUID): Map<String, UUID> =
        genreIdsByUser[userId] ?: loadGenreIds(userId).also { genreIdsByUser[userId] = it }

    private suspend fun loadStudioIds(userId: UUID): Map<String, UUID> = runCatching {
        apiClient.studiosApi.getStudios(
            userId = userId,
            includeItemTypes = movieSeriesTypes,
            enableTotalRecordCount = false,
            limit = 500,
        ).content.items
            .filter { item -> item.name != null }
            .associate { item -> item.name.orEmpty().normalizePtvText() to item.id }
    }.getOrDefault(emptyMap())

    private suspend fun loadStudioIdsCached(userId: UUID): Map<String, UUID> =
        studioIdsByUser[userId] ?: loadStudioIds(userId).also { studioIdsByUser[userId] = it }

    private fun findLibraryParentId(row: PtvHomeRowSpec, userViews: List<BaseItemDto>): UUID? {
        val libraryNames = row.libraryNames.map { it.normalizePtvText() }.toSet()

        return userViews.firstOrNull { view ->
            view.name?.normalizePtvText()?.let { name -> name in libraryNames } == true
        }?.id
    }

    private fun findStudioIds(row: PtvHomeRowSpec, studioIdsByName: Map<String, UUID>): List<UUID> {
        val explicitNames = row.studioNames.map { it.normalizePtvText() }.toSet()
        val keywords = row.studioKeywords.map { it.normalizePtvText() }.filter(String::isNotBlank)
        val matches = studioIdsByName.filter { (name) ->
            name in explicitNames || keywords.any { keyword -> name.contains(keyword) }
        }

        return matches.values.distinct()
    }

    private fun PtvHomeRowSpec.queryItemFields(): List<ItemFields> = when (matcher) {
        PtvItemMatcher.NONE -> displayItemFields
        else -> matcherItemFields
    }

    private fun List<BaseItemDto>.toPtvRowItems(row: PtvHomeRowSpec): List<BaseItemDto> {
        val uniqueItems = distinctBy(BaseItemDto::id)
        val matchedItems = uniqueItems.filter { item -> item.matchesPtvRow(row) }

        return matchedItems.take(row.itemLimit)
    }

    private fun PtvHomeRowSpec.toNativeSection(items: List<NativeMediaItem>) = NativeMediaSection(
        id = id,
        title = title,
        rowKicker = rowKicker.takeIf(String::isNotBlank),
        groupKicker = groupKicker.takeIf(String::isNotBlank),
        groupTitle = groupTitle.takeIf(String::isNotBlank),
        showGroupHeader = showGroupHeader,
        presentation = presentation,
        shape = shape,
        opensLibraries = type == PtvRowType.LIBRARIES,
        items = items,
    )

    private fun List<PtvHomeRowSpec>.toVisibleSections(
        rowResults: Map<String, List<BaseItemDto>>,
    ): List<NativeMediaSection> {
        var previousGroup = ""

        return mapNotNull { row ->
            val items = rowResults[row.id] ?: return@mapNotNull null
            if (items.isEmpty() && row.type != PtvRowType.LIBRARIES) return@mapNotNull null

            val showGroupHeader = row.groupKey.isNotBlank() && row.groupKey != previousGroup
            if (row.groupKey.isNotBlank()) previousGroup = row.groupKey

            row.copy(showGroupHeader = showGroupHeader).toNativeSection(items.map(::toNativeMediaItem))
        }
    }

    private fun List<NativeMediaSection>.homeHero(): NativeMediaItem? =
        firstOrNull { section -> section.presentation == PtvRowPresentation.FEATURED && section.items.isNotEmpty() }
            ?.items
            ?.firstOrNull()

    private fun toNativeMediaItem(item: BaseItemDto): NativeMediaItem {
        val primaryTag = item.imageTags?.get(ImageType.PRIMARY)
        val backdropItemId = item.parentBackdropItemId ?: item.id
        val backdropTag = item.backdropImageTags?.firstOrNull() ?: item.parentBackdropImageTags?.firstOrNull()
        val childCount = item.childCount ?: item.recursiveItemCount

        return NativeMediaItem(
            id = item.id,
            title = item.name.orEmpty().ifBlank { "Untitled" },
            subtitle = item.subtitle(childCount),
            overview = item.overview,
            type = item.type,
            collectionType = item.collectionType,
            posterUrl = primaryTag?.let {
                apiClient.imageApi.getItemImageUrl(
                    itemId = item.id,
                    imageType = ImageType.PRIMARY,
                    maxWidth = 420,
                    quality = 88,
                    tag = it,
                )
            },
            backdropUrl = backdropTag?.let {
                apiClient.imageApi.getItemImageUrl(
                    itemId = backdropItemId,
                    imageType = ImageType.BACKDROP,
                    maxWidth = 900,
                    quality = 86,
                    tag = it,
                )
            },
            progress = item.userData?.playedPercentage?.toFloat(),
            isFolder = item.isFolder == true || item.type in folderTypes,
            isPlayable = item.isPlayableVideo() || item.isPlayableAudio(),
        )
    }

    private fun BaseItemDto.subtitle(childCount: Int?): String? = when {
        seriesName != null && indexNumber != null -> "S${parentIndexNumber ?: 0}:E$indexNumber"
        type == BaseItemKind.AUDIO -> artists?.joinToString()?.takeIf(String::isNotBlank) ?: album
        type == BaseItemKind.MUSIC_ALBUM -> artists?.joinToString()?.takeIf(String::isNotBlank)
            ?: childCount?.let { "$it tracks" }
        productionYear != null -> productionYear.toString()
        collectionType != null -> collectionType?.displayName
        childCount != null -> "$childCount items"
        type == BaseItemKind.SERIES -> "Series"
        type == BaseItemKind.SEASON -> "Season ${indexNumber ?: ""}".trim()
        type == BaseItemKind.FOLDER -> "Folder"
        else -> type.serialName
    }

    private fun CollectionType?.contentTypes(): List<BaseItemKind> = when (this) {
        CollectionType.MOVIES -> listOf(BaseItemKind.MOVIE, BaseItemKind.VIDEO)
        CollectionType.TVSHOWS -> listOf(BaseItemKind.SERIES)
        CollectionType.MUSIC -> listOf(BaseItemKind.MUSIC_ALBUM)
        CollectionType.BOOKS -> listOf(BaseItemKind.AUDIO_BOOK)
        CollectionType.HOMEVIDEOS -> listOf(BaseItemKind.VIDEO, BaseItemKind.MOVIE)
        CollectionType.BOXSETS -> listOf(BaseItemKind.BOX_SET)
        CollectionType.PLAYLISTS -> listOf(BaseItemKind.PLAYLIST)
        else -> emptyList()
    }

    private fun NativeMediaItem.childContentTypes(): List<BaseItemKind> = when (type) {
        BaseItemKind.MUSIC_ALBUM,
        BaseItemKind.PLAYLIST,
        -> listOf(BaseItemKind.AUDIO)

        BaseItemKind.MUSIC_ARTIST -> listOf(BaseItemKind.MUSIC_ALBUM)
        BaseItemKind.SERIES -> listOf(BaseItemKind.SEASON)
        BaseItemKind.SEASON -> listOf(BaseItemKind.EPISODE)
        else -> emptyList()
    }

    private fun NativeMediaItem.childSortBy(): List<ItemSortBy> = when (type) {
        BaseItemKind.SERIES,
        BaseItemKind.SEASON,
        -> listOf(ItemSortBy.SORT_NAME)

        else -> listOf(ItemSortBy.SORT_NAME)
    }

    private fun BaseItemDto.isPlayableVideo() = mediaType == MediaType.VIDEO && type in playableVideoTypes

    private fun BaseItemDto.isPlayableAudio() = mediaType == MediaType.AUDIO || type in playableAudioTypes

    private fun BaseItemDto.isRandomPlayableVideo() = type in playableVideoTypes && isFolder != true

    private fun UUID.toPlayOptions() = PlayOptions(
        ids = listOf(this),
        mediaSourceId = null,
        startIndex = 0,
        startPosition = ZERO,
        audioStreamIndex = null,
        subtitleStreamIndex = null,
        playFromDownloads = false,
    )

    private val CollectionType.displayName: String
        get() = when (this) {
            CollectionType.MOVIES -> "Movies"
            CollectionType.TVSHOWS -> "TV Shows"
            CollectionType.MUSICVIDEOS -> "Music Videos"
            CollectionType.HOMEVIDEOS -> "Videos"
            CollectionType.BOXSETS -> "Collections"
            CollectionType.PLAYLISTS -> "Playlists"
            else -> serialName.replaceFirstChar { char -> char.uppercase() }
        }

    private fun Exception.friendlyMessage(fallback: String) = message?.takeIf(String::isNotBlank) ?: fallback

    private fun ServerEntity.dashboardUrl(): String = "${hostname.trimEnd('/')}/web/index.html#!/dashboard.html"

    private companion object {
        const val PTV_SEARCH_TERM_LIMIT = 8
        const val PTV_ROW_FETCH_CONCURRENCY = 4
        const val PTV_SEARCH_TERM_FETCH_CONCURRENCY = 3
        const val RANDOM_PLAYABLE_CANDIDATE_LIMIT = 50

        val imageTypes = listOf(ImageType.PRIMARY, ImageType.BACKDROP, ImageType.BANNER, ImageType.THUMB)
        val displayItemFields = listOf(
            ItemFields.OVERVIEW,
            ItemFields.PRIMARY_IMAGE_ASPECT_RATIO,
            ItemFields.PARENT_ID,
            ItemFields.ITEM_COUNTS,
            ItemFields.DATE_CREATED,
        )
        val matcherItemFields = displayItemFields + listOf(
            ItemFields.GENRES,
            ItemFields.TAGS,
            ItemFields.STUDIOS,
            ItemFields.PRODUCTION_LOCATIONS,
            ItemFields.PROVIDER_IDS,
            ItemFields.ORIGINAL_TITLE,
        )
        val latestSortBy = listOf(ItemSortBy.PREMIERE_DATE, ItemSortBy.DATE_CREATED, ItemSortBy.SORT_NAME)
        val videoTypes = listOf(BaseItemKind.MOVIE, BaseItemKind.EPISODE, BaseItemKind.VIDEO)
        val playableVideoTypes = setOf(BaseItemKind.MOVIE, BaseItemKind.EPISODE, BaseItemKind.VIDEO)
        val randomPlayableTypes = listOf(BaseItemKind.MOVIE, BaseItemKind.EPISODE, BaseItemKind.VIDEO)
        val playableAudioTypes = setOf(BaseItemKind.AUDIO, BaseItemKind.AUDIO_BOOK)
        val folderTypes = setOf(
            BaseItemKind.COLLECTION_FOLDER,
            BaseItemKind.FOLDER,
            BaseItemKind.MUSIC_ALBUM,
            BaseItemKind.MUSIC_ARTIST,
            BaseItemKind.PLAYLIST,
            BaseItemKind.SEASON,
            BaseItemKind.SERIES,
            BaseItemKind.USER_VIEW,
            BaseItemKind.BOX_SET,
        )
    }
}

sealed interface NativeHomeUiState {
    data object Loading : NativeHomeUiState
    data class Login(
        val serverName: String,
        val publicUsers: List<String> = emptyList(),
        val username: String = "",
        val isSigningIn: Boolean = false,
        val error: String? = null,
    ) : NativeHomeUiState
    data class Content(
        val home: NativeHomeContent,
        val selectedLibrary: NativeLibraryContent? = null,
        val isLoadingLibrary: Boolean = false,
    ) : NativeHomeUiState
    data class Error(val message: String) : NativeHomeUiState
}

data class NativeHomeContent(
    val userName: String,
    val isAdmin: Boolean,
    val dashboardUrl: String?,
    val libraries: List<NativeMediaItem>,
    val hero: NativeMediaItem?,
    val sections: List<NativeMediaSection>,
)

data class NativeLibraryContent(
    val title: String,
    val subtitle: String?,
    val items: List<NativeMediaItem>,
    val error: String? = null,
)

data class NativeMediaSection(
    val id: String,
    val title: String,
    val rowKicker: String?,
    val groupKicker: String?,
    val groupTitle: String?,
    val showGroupHeader: Boolean,
    val presentation: PtvRowPresentation,
    val shape: PtvRowShape,
    val opensLibraries: Boolean,
    val items: List<NativeMediaItem>,
)

data class NativeMediaItem(
    val id: UUID,
    val title: String,
    val subtitle: String?,
    val overview: String?,
    val type: BaseItemKind,
    val collectionType: CollectionType?,
    val posterUrl: String?,
    val backdropUrl: String?,
    val progress: Float?,
    val isFolder: Boolean,
    val isPlayable: Boolean,
)
