@file:Suppress("ClassSignature", "FunctionExpressionBody", "FunctionSignature")

package org.jellyfin.mobile.feature.library

enum class LibraryBookSource {
    JELLYFIN,
    OPDS,
}

enum class LibraryReadingKind {
    BOOK,
    COMIC,
    MANGA,
}

data class LibraryBook(
    val id: String,
    val title: String,
    val subtitle: String?,
    val authors: List<String>,
    val summary: String?,
    val coverUrl: String?,
    val categories: List<String>,
    val series: String?,
    val updated: String?,
    val detailUrl: String?,
    val acquisitionLinks: List<LibraryLink>,
    val readLinks: List<LibraryLink>,
    val format: LibraryBookFormat = LibraryBookFormat.UNKNOWN,
    val fileSizeBytes: Long? = null,
    val progress: LibraryReadingProgress? = null,
    val isFavorite: Boolean = false,
    val source: LibraryBookSource = LibraryBookSource.OPDS,
    val jellyfinItemId: String? = null,
    val readingKind: LibraryReadingKind = LibraryReadingKind.BOOK,
)

data class LibraryLink(
    val title: String,
    val href: String,
    val type: String?,
    val rel: String?,
    val lengthBytes: Long? = null,
)

data class LibraryFacet(
    val id: String,
    val title: String,
    val subtitle: String?,
    val href: String,
)

data class LibraryHome(
    val serverBaseUrl: String,
    val sourceLabel: String = serverBaseUrl,
    val isJellyfinBacked: Boolean = false,
    val allBooks: List<LibraryBook>,
    val recentBooks: List<LibraryBook>,
    val authors: List<LibraryFacet>,
    val series: List<LibraryFacet>,
    val categories: List<LibraryFacet>,
    val continueReading: List<LibraryBook> = emptyList(),
    val collections: List<LibraryFacet> = emptyList(),
    val genres: List<LibraryFacet> = emptyList(),
    val favorites: List<LibraryBook> = emptyList(),
    val comicsManga: List<LibraryBook> = emptyList(),
    val comics: List<LibraryBook> = emptyList(),
    val manga: List<LibraryBook> = emptyList(),
)

data class OpdsAuthConfig(
    val username: String? = null,
    val password: String? = null,
    val bearerToken: String? = null,
)

val LibraryBook.primaryReaderLink: LibraryLink?
    get() = acquisitionLinks.firstOrNull { link ->
        detectLibraryBookFormat(link.type, title, link.href).supportStatus() == LibraryFormatSupport.NATIVE
    } ?: acquisitionLinks.firstOrNull()
        ?: readLinks.firstOrNull()

val LibraryBook.readerKey: String
    get() = when (source) {
        LibraryBookSource.JELLYFIN -> "jellyfin:$id"
        LibraryBookSource.OPDS -> primaryReaderLink?.href ?: detailUrl ?: id
    }

val LibraryBook.supportStatus: LibraryFormatSupport
    get() = format.supportStatus()

fun LibraryBook.withReadingState(
    resumeState: LibraryReaderResumeState?,
    favorite: Boolean,
): LibraryBook = copy(
    progress = resumeState?.let { state ->
        LibraryReadingProgress(
            pageIndex = state.pageIndex,
            pageCount = state.pageCount,
            progress = state.progress,
            updatedAtMs = state.updatedAtMs,
        )
    },
    isFavorite = favorite,
)

fun LibraryLink.inferredFormat(filename: String): LibraryBookFormat =
    detectLibraryBookFormat(
        mimeType = type,
        filename = filename,
        href = href,
    )
