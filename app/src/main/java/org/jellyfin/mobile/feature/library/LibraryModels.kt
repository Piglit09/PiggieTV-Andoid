@file:Suppress("ClassSignature", "FunctionExpressionBody", "FunctionSignature")

package org.jellyfin.mobile.feature.library

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

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
) {
    override fun toString(): String =
        "LibraryBook(content=[REDACTED], format=$format, fileSizeBytes=$fileSizeBytes, " +
            "progress=$progress, isFavorite=$isFavorite, source=$source, readingKind=$readingKind)"
}

data class LibraryLink(
    val title: String,
    val href: String,
    val type: String?,
    val rel: String?,
    val lengthBytes: Long? = null,
) {
    override fun toString(): String = "LibraryLink(content=[REDACTED], lengthBytes=$lengthBytes)"
}

data class LibraryFacet(
    val id: String,
    val title: String,
    val subtitle: String?,
    val href: String,
) {
    override fun toString(): String = "LibraryFacet(content=[REDACTED])"
}

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
) {
    override fun toString(): String =
        "LibraryHome(content=[REDACTED], isJellyfinBacked=$isJellyfinBacked, " +
            "allBooks=${allBooks.size}, recentBooks=${recentBooks.size})"
}

data class OpdsAuthConfig(
    val username: String? = null,
    val password: String? = null,
    val bearerToken: String? = null,
) {
    override fun toString(): String =
        "OpdsAuthConfig(username=[REDACTED], password=[REDACTED], bearerToken=[REDACTED])"
}

val LibraryBook.primaryReaderLink: LibraryLink?
    get() = acquisitionLinks.firstOrNull { link ->
        detectLibraryBookFormat(link.type, title, link.href).supportStatus() == LibraryFormatSupport.NATIVE
    } ?: acquisitionLinks.firstOrNull()
        ?: readLinks.firstOrNull()

val LibraryBook.readerKey: String
    get() = when (source) {
        LibraryBookSource.JELLYFIN -> jellyfinReaderIdentity(id)
        LibraryBookSource.OPDS -> opdsReaderIdentity(id)
    }

internal fun jellyfinReaderIdentity(itemId: String): String =
    itemId.takeIf(SAFE_JELLYFIN_ITEM_ID::matches)
        ?.let { safeItemId -> "jellyfin:$safeItemId" }
        ?: "jellyfin:sha256:${itemId.sha256Hex()}"

internal fun opdsReaderIdentity(entryId: String): String =
    "opds:sha256:${entryId.sha256Hex()}"

internal fun String.isSafePersistentReaderKey(): Boolean =
    SAFE_PERSISTENT_READER_KEY.matches(this)

internal fun String.sha256Hex(): String = MessageDigest.getInstance("SHA-256")
    .digest(toByteArray(StandardCharsets.UTF_8))
    .joinToString(separator = "") { byte ->
        (byte.toInt() and HEX_BYTE_MASK)
            .toString(radix = HEX_RADIX)
            .padStart(length = HEX_BYTE_WIDTH, padChar = '0')
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

private val SAFE_JELLYFIN_ITEM_ID = Regex("[A-Za-z0-9_-]{1,128}")
private val SAFE_PERSISTENT_READER_KEY = Regex(
    "(?:jellyfin:[A-Za-z0-9_-]{1,128}|jellyfin:sha256:[0-9a-f]{64}|opds:sha256:[0-9a-f]{64})",
)
private const val HEX_BYTE_MASK = 0xff
private const val HEX_RADIX = 16
private const val HEX_BYTE_WIDTH = 2
