package org.jellyfin.mobile.feature.library

import kotlinx.coroutines.async
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeoutOrNull
import org.jellyfin.mobile.app.AppPreferences

class LibraryRepository(
    private val preferences: AppPreferences,
    private val opdsClient: OpdsClient,
) {
    suspend fun loadHome(): LibraryHome {
        val baseUrl = preferences.libraryServerBaseUrl
        val authConfig = preferences.libraryAuthConfig

        // Autocaliweb/Calibre-Web exposes the OPDS catalog under /opds by default.
        // The child endpoint paths below mirror common Calibre-Web feeds and can be
        // changed here if your server uses custom routing or a reverse-proxy prefix.
        val allBooks = fetchBooks(baseUrl, OPDS_INITIAL_BOOK_PATHS, authConfig)
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

        return LibraryHome(
            serverBaseUrl = baseUrl,
            allBooks = displayBooks,
            recentBooks = displayBooks.take(RECENT_FALLBACK_LIMIT),
            authors = catalogFacets.matching("author"),
            series = catalogFacets.matching("series"),
            categories = catalogFacets.matching("categor", "tag", "genre"),
        )
    }

    suspend fun loadHomeExtras(current: LibraryHome): LibraryHome = supervisorScope {
        val baseUrl = preferences.libraryServerBaseUrl
        val authConfig = preferences.libraryAuthConfig
        val recent = async {
            withTimeoutOrNull(OPDS_OPTIONAL_TIMEOUT_MS) {
                fetchBooks(baseUrl, OPDS_RECENT_BOOKS_PATHS, authConfig)
            }.orEmpty()
        }
        val authors = async { fetchOptionalFacets(baseUrl, OPDS_AUTHORS_PATHS, authConfig) }
        val series = async { fetchOptionalFacets(baseUrl, OPDS_SERIES_PATHS, authConfig) }
        val categories = async { fetchOptionalFacets(baseUrl, OPDS_CATEGORIES_PATHS, authConfig) }

        current.copy(
            recentBooks = recent.await().ifEmpty { current.recentBooks },
            authors = authors.await().ifEmpty { current.authors },
            series = series.await().ifEmpty { current.series },
            categories = categories.await().ifEmpty { current.categories },
        )
    }

    suspend fun loadBookDetail(book: LibraryBook): LibraryBook {
        val detailUrl = book.detailUrl ?: return book
        val feed = opdsClient.fetchFeed(preferences.libraryServerBaseUrl, detailUrl, preferences.libraryAuthConfig)
        return feed.entries.firstOrNull()?.toBook(preferences.libraryServerBaseUrl) ?: book
    }

    private suspend fun fetchBooks(baseUrl: String, candidatePaths: List<String>, authConfig: OpdsAuthConfig): List<LibraryBook> {
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
            acquisitionLinks = acquisitionLinks.map { it.toLibraryLink() },
            readLinks = readLinks.map { it.toLibraryLink() },
        )
    }

    private fun OpdsLink.toLibraryLink() = LibraryLink(
        title = title ?: type?.substringAfterLast('/')?.uppercase() ?: "Open",
        href = href,
        type = type,
        rel = rel,
    )

    private companion object {
        const val OPDS_CATALOG_PATH = "/opds"
        val OPDS_INITIAL_BOOK_PATHS = listOf("/opds/new", "/opds/recent", "/opds/discover", "/opds/books", "/opds")
        val OPDS_RECENT_BOOKS_PATHS = listOf("/opds/new", "/opds/recent", "/opds/discover")
        val OPDS_AUTHORS_PATHS = listOf("/opds/authors", "/opds/author")
        val OPDS_SERIES_PATHS = listOf("/opds/series")
        val OPDS_CATEGORIES_PATHS = listOf("/opds/categories", "/opds/category", "/opds/tags")
        const val OPDS_OPTIONAL_TIMEOUT_MS = 5_000L
        const val RECENT_FALLBACK_LIMIT = 12
        val COVER_THUMBNAIL_RELS = setOf("http://opds-spec.org/image/thumbnail", "x-stanza-cover-image-thumbnail")
        val COVER_RELS = setOf("http://opds-spec.org/image", "http://opds-spec.org/image/thumbnail", "x-stanza-cover-image", "x-stanza-cover-image-thumbnail")
        val READ_RELS = setOf("alternate", "http://opds-spec.org/stream")
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
