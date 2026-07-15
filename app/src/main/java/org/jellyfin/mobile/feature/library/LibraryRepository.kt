@file:Suppress(
    "ArgumentListWrapping",
    "BinaryExpressionWrapping",
    "BlankLineBetweenWhenConditions",
    "ClassSignature",
    "FunctionExpressionBody",
    "FunctionLiteral",
    "FunctionSignature",
    "MaximumLineLength",
    "ParameterListWrapping",
    "PropertyWrapping",
    "TooManyFunctions",
)

package org.jellyfin.mobile.feature.library

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.jellyfin.mobile.app.AppPreferences
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.imageApi
import org.jellyfin.sdk.api.client.extensions.itemsApi
import org.jellyfin.sdk.api.client.extensions.libraryApi
import org.jellyfin.sdk.api.client.extensions.userApi
import org.jellyfin.sdk.api.client.extensions.userLibraryApi
import org.jellyfin.sdk.api.client.extensions.userViewsApi
import org.jellyfin.sdk.model.UUID
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.CollectionType
import org.jellyfin.sdk.model.api.ImageType
import org.jellyfin.sdk.model.api.ItemFields
import org.jellyfin.sdk.model.api.ItemSortBy
import org.jellyfin.sdk.model.api.PersonKind
import org.jellyfin.sdk.model.api.SortOrder
import timber.log.Timber
import java.util.Locale

class LibraryRepository(
    private val preferences: AppPreferences,
    private val opdsClient: OpdsClient,
    private val apiClient: ApiClient,
) {
    suspend fun loadHome(): LibraryHome = withContext(Dispatchers.IO) {
        val jellyfinHome = runCatching {
            loadJellyfinHome()
        }.onFailure { error ->
            Timber.w(error, "PTV Books could not load Jellyfin-backed books; trying optional OPDS fallback")
        }.getOrNull()

        if (jellyfinHome != null && jellyfinHome.allBooks.isNotEmpty()) {
            return@withContext jellyfinHome
        }

        loadOpdsHome()
    }

    suspend fun loadHomeExtras(current: LibraryHome): LibraryHome =
        if (current.isJellyfinBacked) {
            current
        } else {
            loadOpdsHomeExtras(current)
        }

    suspend fun loadBookDetail(book: LibraryBook): LibraryBook =
        when (book.source) {
            LibraryBookSource.JELLYFIN -> loadJellyfinBookDetail(book)
            LibraryBookSource.OPDS -> loadOpdsBookDetail(book)
        }

    private suspend fun loadJellyfinHome(): LibraryHome = coroutineScope {
        val userId = apiClient.userApi.getCurrentUser().content.id
        val readingLibraries = loadReadingLibraries(userId)

        val loadedBooks = getJellyfinBooksAcrossLibraries(
            userId = userId,
            libraries = readingLibraries,
            sortBy = listOf(ItemSortBy.SORT_NAME),
            limit = JELLYFIN_BOOK_LIMIT,
        )
        val loadedRecent = loadedBooks
            .sortedForReading(listOf(ItemSortBy.DATE_CREATED), listOf(SortOrder.DESCENDING))
            .take(RECENT_FALLBACK_LIMIT)
        val loadedFavorites = loadedBooks.filter(LibraryBook::isFavorite).take(JELLYFIN_ROW_LIMIT)
        val sourceName = when (readingLibraries.size) {
            0 -> "Jellyfin Reading"
            1 -> readingLibraries.first().name.takeIf(String::isNotBlank) ?: "Jellyfin Reading"
            else -> "PTV Reading"
        }
        val comics = loadedBooks.filter { book -> book.readingKind == LibraryReadingKind.COMIC }
        val manga = loadedBooks.filter { book -> book.readingKind == LibraryReadingKind.MANGA }

        LibraryHome(
            serverBaseUrl = apiClient.baseUrl.orEmpty(),
            sourceLabel = "$sourceName - Jellyfin native",
            isJellyfinBacked = true,
            allBooks = loadedBooks,
            recentBooks = loadedRecent,
            authors = loadedBooks.toAuthorFacets(),
            series = loadedBooks.toSeriesFacets(),
            categories = loadedBooks.toGenreFacets(),
            collections = emptyList(),
            genres = loadedBooks.toGenreFacets(),
            favorites = loadedFavorites,
            comics = comics,
            manga = manga,
            comicsManga = comics + manga,
        )
    }

    private suspend fun loadJellyfinBookDetail(book: LibraryBook): LibraryBook {
        val itemId = book.jellyfinItemId ?: return book
        val item = runCatching {
            apiClient.userLibraryApi.getItem(itemId = java.util.UUID.fromString(itemId)).content
        }.getOrNull() ?: return book
        return item.toJellyfinBook(book.readingKind).copy(progress = book.progress)
    }

    private suspend fun loadReadingLibraries(userId: UUID): List<JellyfinBooksLibrary> = supervisorScope {
        val views = apiClient.userViewsApi.getUserViews(
            includeExternalContent = false,
            includeHidden = false,
        ).content.items
        val explicitReadingViews = views.filter { view -> view.isReadingLibrary() }
        val detectedReadingViews = views
            .filterNot { view -> explicitReadingViews.any { explicit -> explicit.id == view.id } }
            .map { view ->
                async {
                    val containsBooks = withTimeoutOrNull(JELLYFIN_LIBRARY_PROBE_TIMEOUT_MS) {
                        containsJellyfinBooks(userId, view.id)
                    } == true
                    view.takeIf { containsBooks }
                }
            }
            .awaitAll()
            .filterNotNull()

        (explicitReadingViews + detectedReadingViews)
            .distinctBy(BaseItemDto::id)
            .map { item ->
                JellyfinBooksLibrary(
                    id = item.id,
                    name = item.name.orEmpty().ifBlank { item.readingKind().label },
                    kind = item.readingKind(),
                )
            }
    }

    private suspend fun containsJellyfinBooks(userId: UUID, parentId: UUID): Boolean {
        val directOrRecursive = runCatching {
            apiClient.itemsApi.getItems(
                userId = userId,
                parentId = parentId,
                includeItemTypes = listOf(BaseItemKind.BOOK),
                recursive = true,
                enableTotalRecordCount = false,
                enableImages = false,
                limit = 1,
            ).content.items.isNotEmpty()
        }.getOrDefault(false)
        if (directOrRecursive) return true

        return loadJellyfinBookContainers(userId, parentId, limit = JELLYFIN_PROBE_CONTAINER_LIMIT)
            .any { container ->
                runCatching {
                    apiClient.itemsApi.getItems(
                        userId = userId,
                        parentId = container.id,
                        includeItemTypes = listOf(BaseItemKind.BOOK),
                        recursive = true,
                        enableTotalRecordCount = false,
                        enableImages = false,
                        limit = 1,
                    ).content.items.isNotEmpty()
                }.getOrDefault(false)
            }
    }

    private suspend fun getJellyfinBooksAcrossLibraries(
        userId: UUID,
        libraries: List<JellyfinBooksLibrary>,
        sortBy: List<ItemSortBy>,
        sortOrder: List<SortOrder> = emptyList(),
        isFavorite: Boolean? = null,
        limit: Int,
    ): List<LibraryBook> = coroutineScope {
        val targets = libraries.ifEmpty { listOf(null) }
        val perLibraryLimit = if (targets.size > 1) {
            ((limit + targets.size - 1) / targets.size).coerceAtLeast(JELLYFIN_MIN_PER_LIBRARY_LIMIT)
        } else {
            limit
        }

        targets.map { library ->
            async {
                getJellyfinBooks(
                    userId = userId,
                    library = library,
                    sortBy = sortBy,
                    sortOrder = sortOrder,
                    isFavorite = isFavorite,
                    limit = perLibraryLimit,
                )
            }
        }.awaitAll()
            .flatten()
            .distinctBy(LibraryBook::id)
            .sortedForReading(sortBy, sortOrder)
            .take(limit)
    }

    private suspend fun getJellyfinBooks(
        userId: UUID,
        library: JellyfinBooksLibrary?,
        sortBy: List<ItemSortBy>,
        sortOrder: List<SortOrder> = emptyList(),
        isFavorite: Boolean? = null,
        limit: Int,
    ): List<LibraryBook> {
        val primaryItems = runCatching {
            getJellyfinBookItems(
                userId = userId,
                parentId = library?.id,
                sortBy = sortBy,
                sortOrder = sortOrder,
                isFavorite = isFavorite,
                limit = limit,
            )
        }.onFailure { error ->
            Timber.w(error, "PTV Reading primary book query failed for library=${library?.name}")
        }.getOrDefault(emptyList())
        if (primaryItems.isNotEmpty() || library == null) {
            return primaryItems.map { item -> item.toJellyfinBook(library?.kind, library?.name) }
        }

        val nestedItems = supervisorScope {
            loadJellyfinBookContainers(userId, library.id, JELLYFIN_CONTAINER_SCAN_LIMIT)
                .map { container ->
                    async {
                        runCatching {
                            getJellyfinBookItems(
                                userId = userId,
                                parentId = container.id,
                                sortBy = sortBy,
                                sortOrder = sortOrder,
                                isFavorite = isFavorite,
                                limit = limit,
                            )
                        }.getOrDefault(emptyList())
                    }
                }
                .awaitAll()
                .flatten()
        }
        return nestedItems
            .distinctBy(BaseItemDto::id)
            .take(limit)
            .map { item -> item.toJellyfinBook(library.kind, library.name) }
    }

    private suspend fun getJellyfinBookItems(
        userId: UUID,
        parentId: UUID?,
        sortBy: List<ItemSortBy>,
        sortOrder: List<SortOrder>,
        isFavorite: Boolean?,
        limit: Int,
    ): List<BaseItemDto> = apiClient.itemsApi.getItems(
        userId = userId,
        parentId = parentId,
        includeItemTypes = listOf(BaseItemKind.BOOK),
        recursive = true,
        sortBy = sortBy,
        sortOrder = sortOrder,
        isFavorite = isFavorite,
        fields = jellyfinBookFields,
        enableUserData = true,
        imageTypeLimit = 1,
        enableImageTypes = imageTypes,
        enableTotalRecordCount = false,
        enableImages = true,
        limit = limit,
    ).content.items

    private suspend fun loadJellyfinBookContainers(userId: UUID, parentId: UUID, limit: Int): List<BaseItemDto> =
        runCatching {
            apiClient.itemsApi.getItems(
                userId = userId,
                parentId = parentId,
                includeItemTypes = listOf(BaseItemKind.FOLDER, BaseItemKind.BOX_SET),
                recursive = false,
                sortBy = listOf(ItemSortBy.SORT_NAME),
                enableTotalRecordCount = false,
                enableImages = false,
                limit = limit,
            ).content.items
        }.getOrDefault(emptyList())

    private fun BaseItemDto.toJellyfinBook(libraryKind: LibraryReadingKind? = null, libraryName: String? = null): LibraryBook {
        val primaryTag = imageTags?.get(ImageType.PRIMARY)
        val mediaSource = mediaSources.orEmpty().firstOrNull()
        val extension = inferBookExtension(container ?: mediaSource?.container ?: path)
        val filename = "${name.orEmpty().ifBlank { id.toString() }.safeFilename()}.$extension"
        val mimeType = extension.toBookMimeType()
        val downloadUrl = apiClient.libraryApi.getDownloadUrl(itemId = id)
        val link = LibraryLink(
            title = "Open native reader",
            href = downloadUrl,
            type = mimeType,
            rel = JELLYFIN_DOWNLOAD_REL,
            lengthBytes = mediaSource?.size,
        )
        val authors = people.orEmpty()
            .filter { person -> person.type in authorPersonKinds }
            .mapNotNull { person -> person.name?.takeIf(String::isNotBlank) }
            .distinct()
        val bookGenres = (genres.orEmpty() + tags.orEmpty()).distinct()
        val format = detectLibraryBookFormat(mimeType, filename, downloadUrl)
        val readingKind = readingKind(libraryKind, libraryName, format)

        return LibraryBook(
            id = id.toString(),
            title = name.orEmpty().ifBlank { "Untitled Book" },
            subtitle = authors.joinToString().takeIf(String::isNotBlank),
            authors = authors,
            summary = overview,
            coverUrl = primaryTag?.let { tag ->
                apiClient.imageApi.getItemImageUrl(
                    itemId = id,
                    imageType = ImageType.PRIMARY,
                    maxWidth = 640,
                    quality = 88,
                    tag = tag,
                )
            },
            categories = bookGenres,
            series = seriesName,
            updated = dateCreated?.toString(),
            detailUrl = null,
            acquisitionLinks = listOf(link),
            readLinks = listOf(link),
            format = format,
            fileSizeBytes = mediaSource?.size,
            isFavorite = userData?.isFavorite == true,
            source = LibraryBookSource.JELLYFIN,
            jellyfinItemId = id.toString(),
            readingKind = readingKind,
        )
    }

    private suspend fun loadOpdsHome(): LibraryHome {
        val baseUrl = preferences.libraryServerBaseUrl
        if (!baseUrl.isConfiguredOpdsFallback()) {
            return LibraryHome(
                serverBaseUrl = apiClient.baseUrl.orEmpty(),
                sourceLabel = "Jellyfin Reading native",
                isJellyfinBacked = true,
                allBooks = emptyList(),
                recentBooks = emptyList(),
                authors = emptyList(),
                series = emptyList(),
                categories = emptyList(),
            )
        }
        val authConfig = preferences.libraryAuthConfig
        val allBooks = fetchOpdsBooks(baseUrl, OPDS_INITIAL_BOOK_PATHS, authConfig)
        val catalogFeed = runCatching {
            withTimeoutOrNull(OPDS_OPTIONAL_TIMEOUT_MS) {
                opdsClient.fetchFeed(baseUrl, OPDS_CATALOG_PATH, authConfig)
            }
        }.getOrElse { error ->
            if (error is LibraryLoginRequiredException) throw error
            null
        }
        val catalogEntries = catalogFeed?.entries.orEmpty()
        val catalogBooks = catalogEntries.filter { entry -> entry.isBookEntry() }.map { entry -> entry.toBook(baseUrl) }
        val catalogFacets = catalogEntries.filterNot { entry -> entry.isBookEntry() }.mapNotNull { entry -> entry.toFacet() }
        val displayBooks = allBooks.ifEmpty { catalogBooks }
        val categoryFacets = catalogFacets.matching("categor", "tag", "genre")

        return LibraryHome(
            serverBaseUrl = baseUrl,
            sourceLabel = "Optional OPDS fallback",
            isJellyfinBacked = false,
            allBooks = displayBooks,
            recentBooks = displayBooks.take(RECENT_FALLBACK_LIMIT),
            authors = catalogFacets.matching("author"),
            series = catalogFacets.matching("series"),
            categories = categoryFacets,
            collections = catalogFacets.matching("collection", "shelf"),
            genres = categoryFacets.matching("genre", "tag", "categor"),
            favorites = displayBooks.filter { book -> book.isLikelyFavorite() },
            comics = displayBooks.filter { book -> book.readingKind == LibraryReadingKind.COMIC },
            manga = displayBooks.filter { book -> book.readingKind == LibraryReadingKind.MANGA },
            comicsManga = displayBooks.filter { book -> book.readingKind in setOf(LibraryReadingKind.COMIC, LibraryReadingKind.MANGA) },
        )
    }

    private suspend fun loadOpdsHomeExtras(current: LibraryHome): LibraryHome = supervisorScope {
        val baseUrl = preferences.libraryServerBaseUrl
        val authConfig = preferences.libraryAuthConfig
        val recent = async {
            withTimeoutOrNull(OPDS_OPTIONAL_TIMEOUT_MS) {
                fetchOpdsBooks(baseUrl, OPDS_RECENT_BOOKS_PATHS, authConfig)
            }.orEmpty()
        }
        val authors = async { fetchOptionalFacets(baseUrl, OPDS_AUTHORS_PATHS, authConfig) }
        val series = async { fetchOptionalFacets(baseUrl, OPDS_SERIES_PATHS, authConfig) }
        val categories = async { fetchOptionalFacets(baseUrl, OPDS_CATEGORIES_PATHS, authConfig) }
        val collections = async { fetchOptionalFacets(baseUrl, OPDS_COLLECTION_PATHS, authConfig) }

        current.copy(
            recentBooks = recent.await().ifEmpty { current.recentBooks },
            authors = authors.await().ifEmpty { current.authors },
            series = series.await().ifEmpty { current.series },
            categories = categories.await().ifEmpty { current.categories },
            collections = collections.await().ifEmpty { current.collections },
            genres = categories.await().ifEmpty { current.genres },
        )
    }

    private suspend fun loadOpdsBookDetail(book: LibraryBook): LibraryBook {
        val detailUrl = book.detailUrl ?: return book
        val feed = opdsClient.fetchFeed(preferences.libraryServerBaseUrl, detailUrl, preferences.libraryAuthConfig)
        return feed.entries.firstOrNull()?.toBook(preferences.libraryServerBaseUrl) ?: book
    }

    private suspend fun fetchOpdsBooks(baseUrl: String, candidatePaths: List<String>, authConfig: OpdsAuthConfig): List<LibraryBook> {
        var fallbackBooks = emptyList<LibraryBook>()

        candidatePaths.forEach { path ->
            val feed = runCatching {
                opdsClient.fetchFeed(baseUrl, path, authConfig)
            }.getOrElse { error ->
                if (error is LibraryLoginRequiredException) throw error
                return@forEach
            }
            val books = feed.entries.filter { entry -> entry.isBookEntry() }.map { entry -> entry.toBook(baseUrl) }
            if (books.isNotEmpty()) return books
            fallbackBooks = feed.entries.map { entry -> entry.toBook(baseUrl) }
        }

        return fallbackBooks
    }

    private suspend fun fetchFacets(baseUrl: String, candidatePaths: List<String>, authConfig: OpdsAuthConfig): List<LibraryFacet> {
        val feed = fetchFirstAvailableFeed(baseUrl, candidatePaths, authConfig)
        return feed.entries.map { entry ->
            LibraryFacet(
                id = entry.id.ifBlank { entry.title },
                title = entry.title,
                subtitle = entry.subtitle,
                href = entry.links.firstOrNull { it.rel?.contains("subsection") == true }?.href
                    ?: entry.links.firstOrNull()?.href.orEmpty(),
            )
        }
    }

    private suspend fun fetchFacetsOrEmpty(
        baseUrl: String,
        candidatePaths: List<String>,
        authConfig: OpdsAuthConfig,
    ): List<LibraryFacet> = runCatching {
        fetchFacets(baseUrl, candidatePaths, authConfig)
    }.getOrElse { error ->
        if (error is LibraryLoginRequiredException) throw error
        emptyList()
    }

    private suspend fun fetchFirstAvailableFeed(baseUrl: String, paths: List<String>, authConfig: OpdsAuthConfig): OpdsFeed {
        var lastError: Exception? = null

        paths.forEach { path ->
            runCatching {
                opdsClient.fetchFeed(baseUrl, path, authConfig)
            }.onSuccess { feed ->
                return feed
            }.onFailure { error ->
                if (error is LibraryLoginRequiredException) throw error
                lastError = error as? Exception ?: RuntimeException(error)
            }
        }

        throw lastError ?: IllegalStateException("No OPDS endpoint was available.")
    }

    private suspend fun fetchOptionalFacets(
        baseUrl: String,
        candidatePaths: List<String>,
        authConfig: OpdsAuthConfig,
    ): List<LibraryFacet> =
        withTimeoutOrNull(OPDS_OPTIONAL_TIMEOUT_MS) {
            fetchFacetsOrEmpty(baseUrl, candidatePaths, authConfig)
        }.orEmpty()

    private fun OpdsEntry.isBookEntry(): Boolean =
        links.any { link ->
            link.rel?.contains("acquisition") == true ||
                link.rel in COVER_RELS ||
                link.type in BOOK_MEDIA_TYPES ||
                link.type?.startsWith("image/") == true
        }

    private fun OpdsEntry.toFacet(): LibraryFacet? {
        val href = links.firstOrNull { it.rel?.contains("subsection") == true }?.href
            ?: links.firstOrNull()?.href
            ?: return null

        return LibraryFacet(
            id = id.ifBlank { title },
            title = title,
            subtitle = subtitle,
            href = href,
        )
    }

    private fun List<LibraryBook>.toAuthorFacets(): List<LibraryFacet> =
        flatMap(LibraryBook::authors)
            .distinctBy { value -> value.lowercase(Locale.US) }
            .sortedWith(String.CASE_INSENSITIVE_ORDER)
            .map { author -> LibraryFacet(id = "author:$author", title = author, subtitle = "Author", href = author) }

    private fun List<LibraryBook>.toSeriesFacets(): List<LibraryFacet> =
        mapNotNull(LibraryBook::series)
            .distinctBy { value -> value.lowercase(Locale.US) }
            .sortedWith(String.CASE_INSENSITIVE_ORDER)
            .map { series -> LibraryFacet(id = "series:$series", title = series, subtitle = "Series", href = series) }

    private fun List<LibraryBook>.toGenreFacets(): List<LibraryFacet> =
        flatMap(LibraryBook::categories)
            .distinctBy { value -> value.lowercase(Locale.US) }
            .sortedWith(String.CASE_INSENSITIVE_ORDER)
            .map { genre -> LibraryFacet(id = "genre:$genre", title = genre, subtitle = "Genre", href = genre) }

    private fun List<LibraryFacet>.matching(vararg keywords: String): List<LibraryFacet> =
        filter { facet ->
            val text = "${facet.title} ${facet.subtitle.orEmpty()} ${facet.href}".lowercase()
            keywords.any { keyword -> keyword in text }
        }

    private fun OpdsEntry.toBook(baseUrl: String): LibraryBook {
        val coverLink = links.firstOrNull { link -> link.rel in COVER_THUMBNAIL_RELS }
            ?: links.firstOrNull { link -> link.rel in COVER_RELS || link.type?.startsWith("image/") == true }
        val acquisitionLinks = links.filter { link ->
            link.rel?.contains("acquisition") == true || link.type in BOOK_MEDIA_TYPES
        }
        val readLinks = links.filter { link ->
            link.type?.startsWith("text/html") == true || link.rel in READ_RELS
        }

        val mappedAcquisitionLinks = acquisitionLinks.map { it.toLibraryLink() }
        val mappedReadLinks = readLinks.map { it.toLibraryLink() }
        val primaryLink = mappedAcquisitionLinks.firstOrNull() ?: mappedReadLinks.firstOrNull()
        val format = primaryLink?.inferredFormat(title) ?: LibraryBookFormat.UNKNOWN

        return LibraryBook(
            id = id.ifBlank { title },
            title = title,
            subtitle = subtitle ?: authors.joinToString().takeIf(String::isNotBlank),
            authors = authors,
            summary = summary,
            coverUrl = coverLink?.href,
            categories = categories,
            series = categories.firstOrNull { it.startsWith("series:", ignoreCase = true) }?.substringAfter(':')?.trim(),
            updated = updated,
            detailUrl = links.firstOrNull { it.rel == "alternate" || it.rel == "subsection" }?.href ?: baseUrl,
            acquisitionLinks = mappedAcquisitionLinks,
            readLinks = mappedReadLinks,
            format = format,
            fileSizeBytes = primaryLink?.lengthBytes,
            source = LibraryBookSource.OPDS,
            readingKind = inferReadingKind(format, categories + listOf(title, subtitle.orEmpty())),
        )
    }

    private fun OpdsLink.toLibraryLink() = LibraryLink(
        title = title ?: type?.substringAfterLast('/')?.uppercase() ?: "Open",
        href = href,
        type = type,
        rel = rel,
        lengthBytes = lengthBytes,
    )

    private fun LibraryBook.isLikelyFavorite(): Boolean =
        categories.any { category ->
            val text = category.lowercase()
            "favorite" in text || "liked" in text
        }

    private fun BaseItemDto.isReadingLibrary(): Boolean {
        val text = readingText()
        return collectionType == CollectionType.BOOKS ||
            BOOKS_KEYWORDS.containsMatchIn(text) ||
            COMIC_KEYWORDS.containsMatchIn(text) ||
            MANGA_KEYWORDS.containsMatchIn(text)
    }

    private fun BaseItemDto.readingKind(): LibraryReadingKind = inferReadingKind(
        format = LibraryBookFormat.UNKNOWN,
        hints = listOf(readingText()),
    )

    private fun BaseItemDto.readingKind(
        libraryKind: LibraryReadingKind?,
        libraryName: String?,
        format: LibraryBookFormat,
    ): LibraryReadingKind {
        val itemKind = inferReadingKind(
            format = format,
            hints = listOf(readingText(libraryName)),
        )
        return when {
            itemKind != LibraryReadingKind.BOOK -> itemKind
            libraryKind != null -> libraryKind
            else -> LibraryReadingKind.BOOK
        }
    }

    private fun BaseItemDto.readingText(extra: String? = null): String =
        listOfNotNull(
            name,
            collectionType?.serialName,
            type.serialName,
            overview,
            seriesName,
            extra,
        )
            .plus(genres.orEmpty())
            .plus(tags.orEmpty())
            .joinToString(" ")

    private fun inferReadingKind(format: LibraryBookFormat, hints: List<String>): LibraryReadingKind {
        val text = hints.joinToString(" ")
        return when {
            MANGA_KEYWORDS.containsMatchIn(text) -> LibraryReadingKind.MANGA
            COMIC_KEYWORDS.containsMatchIn(text) || format in setOf(LibraryBookFormat.CBZ, LibraryBookFormat.CBR) -> LibraryReadingKind.COMIC
            else -> LibraryReadingKind.BOOK
        }
    }

    private val LibraryReadingKind.label: String
        get() = when (this) {
            LibraryReadingKind.BOOK -> "Books"
            LibraryReadingKind.COMIC -> "Comics"
            LibraryReadingKind.MANGA -> "Manga"
        }

    private fun List<LibraryBook>.sortedForReading(sortBy: List<ItemSortBy>, sortOrder: List<SortOrder>): List<LibraryBook> =
        when {
            ItemSortBy.DATE_CREATED in sortBy && SortOrder.DESCENDING in sortOrder ->
                sortedWith(compareByDescending<LibraryBook> { book -> book.updated.orEmpty() }.thenBy(LibraryBook::title))

            else -> sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER, LibraryBook::title))
        }

    private fun inferBookExtension(value: String?): String {
        val normalized = value
            ?.substringAfterLast('/')
            ?.substringAfterLast('.')
            ?.substringBefore(',')
            ?.trim()
            ?.lowercase(Locale.US)
            .orEmpty()

        return when (normalized) {
            "epub" -> "epub"
            "pdf" -> "pdf"
            "cbz", "zip" -> "cbz"
            "txt", "text" -> "txt"
            "cbr", "rar" -> "cbr"
            "mobi" -> "mobi"
            "azw" -> "azw"
            "azw3" -> "azw3"
            else -> "epub"
        }
    }

    private fun String.toBookMimeType(): String = when (this) {
        "epub" -> "application/epub+zip"
        "pdf" -> "application/pdf"
        "cbz" -> "application/vnd.comicbook+zip"
        "txt" -> "text/plain"
        "cbr" -> "application/vnd.comicbook-rar"
        "mobi" -> "application/x-mobipocket-ebook"
        "azw",
        "azw3",
        -> "application/vnd.amazon.ebook"
        else -> "application/octet-stream"
    }

    private fun String.safeFilename(): String =
        replace(Regex("""[\\/:*?"<>|]+"""), "_")
            .replace(Regex("\\s+"), " ")
            .trim()
            .ifBlank { "book" }
            .take(MAX_FILENAME_LENGTH)

    private fun String.isConfiguredOpdsFallback(): Boolean =
        isNotBlank() && !contains(LEGACY_PIGGIETV_BOOKS_HOST, ignoreCase = true)

    private data class JellyfinBooksLibrary(
        val id: UUID,
        val name: String,
        val kind: LibraryReadingKind,
    )

    private companion object {
        const val JELLYFIN_BOOK_LIMIT = 96
        const val JELLYFIN_ROW_LIMIT = 36
        const val JELLYFIN_MIN_PER_LIBRARY_LIMIT = 24
        const val JELLYFIN_CONTAINER_SCAN_LIMIT = 24
        const val JELLYFIN_PROBE_CONTAINER_LIMIT = 8
        const val JELLYFIN_LIBRARY_PROBE_TIMEOUT_MS = 4_000L
        const val MAX_FILENAME_LENGTH = 96
        const val JELLYFIN_DOWNLOAD_REL = "jellyfin-download"
        const val LEGACY_PIGGIETV_BOOKS_HOST = "books.piggietv.com"
        const val OPDS_CATALOG_PATH = "/opds"
        val OPDS_INITIAL_BOOK_PATHS = listOf("/opds/new", "/opds/recent", "/opds/discover", "/opds/books", "/opds")
        val OPDS_RECENT_BOOKS_PATHS = listOf("/opds/new", "/opds/recent", "/opds/discover")
        val OPDS_AUTHORS_PATHS = listOf("/opds/authors", "/opds/author")
        val OPDS_SERIES_PATHS = listOf("/opds/series")
        val OPDS_CATEGORIES_PATHS = listOf("/opds/categories", "/opds/category", "/opds/tags")
        val OPDS_COLLECTION_PATHS = listOf("/opds/collections", "/opds/shelves", "/opds/shelf")
        const val OPDS_OPTIONAL_TIMEOUT_MS = 5_000L
        const val RECENT_FALLBACK_LIMIT = 12
        val imageTypes = listOf(ImageType.PRIMARY, ImageType.BACKDROP, ImageType.THUMB)
        val jellyfinBookFields = listOf(
            ItemFields.CAN_DOWNLOAD,
            ItemFields.DATE_CREATED,
            ItemFields.GENRES,
            ItemFields.MEDIA_SOURCES,
            ItemFields.OVERVIEW,
            ItemFields.PATH,
            ItemFields.PEOPLE,
            ItemFields.PRIMARY_IMAGE_ASPECT_RATIO,
            ItemFields.SERIES_PRIMARY_IMAGE,
            ItemFields.TAGS,
        )
        val authorPersonKinds = setOf(
            PersonKind.AUTHOR,
            PersonKind.WRITER,
            PersonKind.CREATOR,
            PersonKind.ILLUSTRATOR,
            PersonKind.ARTIST,
            PersonKind.PENCILLER,
            PersonKind.INKER,
            PersonKind.COLORIST,
            PersonKind.LETTERER,
            PersonKind.COVER_ARTIST,
            PersonKind.TRANSLATOR,
        )
        val COVER_THUMBNAIL_RELS = setOf("http://opds-spec.org/image/thumbnail", "x-stanza-cover-image-thumbnail")
        val COVER_RELS = setOf("http://opds-spec.org/image", "http://opds-spec.org/image/thumbnail", "x-stanza-cover-image", "x-stanza-cover-image-thumbnail")
        val READ_RELS = setOf("alternate", "http://opds-spec.org/stream")
        val MANGA_KEYWORDS = Regex("(?:manga|manhwa|manhua)", RegexOption.IGNORE_CASE)
        val COMIC_KEYWORDS = Regex("(?:comic|comics|graphic novel|graphic novels|issue|issues)", RegexOption.IGNORE_CASE)
        val BOOKS_KEYWORDS = Regex("\\b(?:book|books|ebook|ebooks|novel|novels|light novel|reader|reading)\\b", RegexOption.IGNORE_CASE)
        val BOOK_MEDIA_TYPES = setOf(
            "application/epub+zip",
            "application/pdf",
            "application/x-mobipocket-ebook",
            "application/vnd.amazon.ebook",
            "application/x-cbz",
            "application/vnd.comicbook+zip",
            "application/x-cbr",
            "application/vnd.comicbook-rar",
            "text/plain",
            "text/html",
        )
    }
}
