@file:Suppress(
    "ArgumentListWrapping",
    "BlankLineBetweenWhenConditions",
    "ClassSignature",
    "CyclomaticComplexMethod",
    "FunctionExpressionBody",
    "FunctionSignature",
    "StringTemplate",
)

package org.jellyfin.mobile.feature.library

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import timber.log.Timber

enum class LibraryBookFormat(
    val label: String,
    val preferredExtension: String,
) {
    EPUB("EPUB", "epub"),
    PDF("PDF", "pdf"),
    CBZ("CBZ", "cbz"),
    TXT("Text", "txt"),
    HTML("HTML", "html"),
    CBR("CBR", "cbr"),
    MOBI("MOBI", "mobi"),
    AZW("AZW", "azw"),
    AZW3("AZW3", "azw3"),
    MARKDOWN("Markdown", "md"),
    UNKNOWN("Unknown", "book"),
}

enum class LibraryFormatSupport {
    NATIVE,
    LIMITED,
    UNSUPPORTED,
    UNKNOWN,
}

enum class LibraryReaderTheme {
    DARK,
    LIGHT,
    SEPIA,
}

enum class LibraryReaderFitMode {
    WIDTH,
    HEIGHT,
}

@Serializable
data class LibraryReaderSettings(
    val theme: LibraryReaderTheme = LibraryReaderTheme.DARK,
    val fontScale: Float = DEFAULT_FONT_SCALE,
    val marginScale: Float = DEFAULT_MARGIN_SCALE,
    val lineSpacingScale: Float = DEFAULT_LINE_SPACING_SCALE,
    val imageFitMode: LibraryReaderFitMode = LibraryReaderFitMode.WIDTH,
    val singlePageMode: Boolean = false,
    val rightToLeftManga: Boolean = false,
    val zoomScale: Float = DEFAULT_ZOOM_SCALE,
) {
    fun normalized(): LibraryReaderSettings = copy(
        fontScale = fontScale.coerceIn(MIN_FONT_SCALE, MAX_FONT_SCALE),
        marginScale = marginScale.coerceIn(MIN_MARGIN_SCALE, MAX_MARGIN_SCALE),
        lineSpacingScale = lineSpacingScale.coerceIn(MIN_LINE_SPACING_SCALE, MAX_LINE_SPACING_SCALE),
        zoomScale = zoomScale.coerceIn(MIN_ZOOM_SCALE, MAX_ZOOM_SCALE),
    )

    companion object {
        const val DEFAULT_FONT_SCALE = 1.0f
        const val DEFAULT_MARGIN_SCALE = 1.0f
        const val DEFAULT_LINE_SPACING_SCALE = 1.0f
        const val DEFAULT_ZOOM_SCALE = 1.0f
        const val MIN_FONT_SCALE = 0.78f
        const val MAX_FONT_SCALE = 1.55f
        const val MIN_MARGIN_SCALE = 0.65f
        const val MAX_MARGIN_SCALE = 1.55f
        const val MIN_LINE_SPACING_SCALE = 0.85f
        const val MAX_LINE_SPACING_SCALE = 1.6f
        const val MIN_ZOOM_SCALE = 0.75f
        const val MAX_ZOOM_SCALE = 2.25f
    }
}

@Serializable
data class LibraryReaderResumeState(
    val readerKey: String,
    val title: String,
    val pageIndex: Int,
    val pageCount: Int,
    val progress: Float,
    val updatedAtMs: Long,
    val chapterIndex: Int = 0,
    val chapterTitle: String? = null,
    val chapterPageIndex: Int = 0,
) {
    fun normalized(): LibraryReaderResumeState {
        val safePageCount = pageCount.coerceAtLeast(0)
        val safePageIndex = when {
            safePageCount <= 0 -> 0
            else -> pageIndex.coerceIn(0, safePageCount - 1)
        }
        val safeChapterIndex = chapterIndex.coerceAtLeast(0)
        val safeProgress = when {
            safePageCount <= 0 -> progress.coerceIn(0f, 1f)
            else -> ((safePageIndex + 1).toFloat() / safePageCount.toFloat()).coerceIn(0f, 1f)
        }

        return copy(
            pageIndex = safePageIndex,
            pageCount = safePageCount,
            progress = safeProgress,
            updatedAtMs = updatedAtMs.coerceAtLeast(0),
            chapterIndex = safeChapterIndex,
            chapterPageIndex = chapterPageIndex.coerceAtLeast(0),
        )
    }
}

data class LibraryReadingProgress(
    val pageIndex: Int,
    val pageCount: Int,
    val progress: Float,
    val updatedAtMs: Long,
) {
    val percent: Int
        get() = (progress.coerceIn(0f, 1f) * 100f).toInt()
}

data class LibraryFormatMessage(
    val title: String,
    val message: String,
)

data class LibraryReaderChapter(
    val index: Int,
    val title: String,
    val startPageIndex: Int,
    val pageCount: Int,
    val href: String? = null,
) {
    fun containsPage(pageIndex: Int): Boolean =
        pageIndex in startPageIndex until startPageIndex + pageCount.coerceAtLeast(1)
}

fun detectLibraryBookFormat(
    mimeType: String?,
    filename: String?,
    href: String? = null,
): LibraryBookFormat {
    val normalizedMime = mimeType?.substringBefore(';')?.trim()?.lowercase()
    val lowerName = listOfNotNull(filename, href)
        .firstOrNull { value -> "." in value }
        ?.substringBefore('?')
        ?.substringBefore('#')
        ?.lowercase()

    return when {
        normalizedMime in EPUB_MIME_TYPES || lowerName?.endsWith(".epub") == true -> LibraryBookFormat.EPUB
        normalizedMime == "application/pdf" || lowerName?.endsWith(".pdf") == true -> LibraryBookFormat.PDF
        normalizedMime in CBZ_MIME_TYPES || lowerName?.endsWith(".cbz") == true -> LibraryBookFormat.CBZ
        normalizedMime in CBR_MIME_TYPES || lowerName?.endsWith(".cbr") == true -> LibraryBookFormat.CBR
        normalizedMime in MOBI_MIME_TYPES || lowerName?.endsWith(".mobi") == true -> LibraryBookFormat.MOBI
        normalizedMime in AZW3_MIME_TYPES || lowerName?.endsWith(".azw3") == true -> LibraryBookFormat.AZW3
        lowerName?.endsWith(".azw") == true -> LibraryBookFormat.AZW
        normalizedMime == "text/markdown" ||
            lowerName?.endsWith(".md") == true ||
            lowerName?.endsWith(".markdown") == true -> LibraryBookFormat.MARKDOWN
        normalizedMime == "text/plain" || lowerName?.endsWith(".txt") == true -> LibraryBookFormat.TXT
        normalizedMime?.startsWith("text/html") == true ||
            normalizedMime == "application/xhtml+xml" ||
            lowerName?.endsWith(".html") == true ||
            lowerName?.endsWith(".htm") == true ||
            lowerName?.endsWith(".xhtml") == true -> LibraryBookFormat.HTML
        else -> LibraryBookFormat.UNKNOWN
    }
}

fun LibraryBookFormat.supportStatus(): LibraryFormatSupport = when (this) {
    LibraryBookFormat.EPUB,
    LibraryBookFormat.PDF,
    LibraryBookFormat.CBZ,
    LibraryBookFormat.TXT,
    -> LibraryFormatSupport.NATIVE
    LibraryBookFormat.HTML -> LibraryFormatSupport.LIMITED
    LibraryBookFormat.CBR,
    LibraryBookFormat.MOBI,
    LibraryBookFormat.AZW,
    LibraryBookFormat.AZW3,
    LibraryBookFormat.MARKDOWN,
    -> LibraryFormatSupport.UNSUPPORTED
    LibraryBookFormat.UNKNOWN -> LibraryFormatSupport.UNKNOWN
}

fun LibraryBookFormat.unsupportedMessage(): LibraryFormatMessage = when (this) {
    LibraryBookFormat.CBR -> LibraryFormatMessage(
        title = "Detected CBR",
        message = "CBR/RAR comics are not supported by the native PTV reader because Android has no built-in safe RAR page decoder. Convert comics or manga to CBZ.",
    )
    LibraryBookFormat.MOBI -> LibraryFormatMessage(
        title = "Detected MOBI",
        message = "MOBI is an older ebook format and is not supported by the native PTV reader. Convert books to EPUB, or PDF for fixed-layout pages.",
    )
    LibraryBookFormat.AZW,
    LibraryBookFormat.AZW3,
    -> LibraryFormatMessage(
        title = "Detected $label",
        message = "Amazon Kindle formats are not available in the native PTV reader. Convert books to EPUB, or PDF for fixed-layout pages.",
    )
    LibraryBookFormat.MARKDOWN -> LibraryFormatMessage(
        title = "Detected Markdown",
        message = "Markdown rendering is not supported in PTV Books yet. Convert prose to EPUB, fixed-layout documents to PDF, or plain notes to TXT.",
    )
    LibraryBookFormat.UNKNOWN -> LibraryFormatMessage(
        title = "Unknown book format",
        message = "PTV Books could not identify this file. Use EPUB for books, PDF for fixed-layout documents, or CBZ for comics and manga.",
    )
    LibraryBookFormat.HTML -> LibraryFormatMessage(
        title = "Detected HTML",
        message = "HTML is limited because PTV Books does not use WebView. Use EPUB for books, PDF for fixed-layout documents, or CBZ for comics and manga.",
    )
    else -> LibraryFormatMessage(
        title = "Detected $label",
        message = "This book can open in the native PTV reader.",
    )
}

fun calculateLibraryProgress(pageIndex: Int, pageCount: Int): Float =
    when {
        pageCount <= 0 -> 0f
        else -> ((pageIndex.coerceIn(0, pageCount - 1) + 1).toFloat() / pageCount.toFloat())
            .coerceIn(0f, 1f)
    }

internal object LibraryReaderJson {
    val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
}

internal object LibraryReaderResumeStateSerializer {
    fun encode(state: LibraryReaderResumeState): String =
        LibraryReaderJson.json.encodeToString(state.normalized())

    fun decode(value: String): LibraryReaderResumeState? = runCatching {
        LibraryReaderJson.json.decodeFromString<LibraryReaderResumeState>(value).normalized()
    }.onFailure { error ->
        if (error is SerializationException || error is IllegalArgumentException) {
            Timber.w(error, "PTV Books could not decode saved reader progress")
        } else {
            Timber.e(error, "PTV Books saved reader progress failed unexpectedly")
        }
    }.getOrNull()
}

internal object LibraryReaderSettingsSerializer {
    fun encode(settings: LibraryReaderSettings): String =
        LibraryReaderJson.json.encodeToString(settings.normalized())

    fun decode(value: String): LibraryReaderSettings? = runCatching {
        LibraryReaderJson.json.decodeFromString<LibraryReaderSettings>(value).normalized()
    }.onFailure { error ->
        if (error is SerializationException || error is IllegalArgumentException) {
            Timber.w(error, "PTV Books could not decode reader settings")
        } else {
            Timber.e(error, "PTV Books reader settings failed unexpectedly")
        }
    }.getOrNull()
}

private val EPUB_MIME_TYPES = setOf("application/epub+zip")
private val CBZ_MIME_TYPES = setOf("application/x-cbz", "application/vnd.comicbook+zip")
private val CBR_MIME_TYPES = setOf("application/x-cbr", "application/vnd.comicbook-rar")
private val MOBI_MIME_TYPES = setOf("application/x-mobipocket-ebook")
private val AZW3_MIME_TYPES = setOf("application/vnd.amazon.ebook", "application/x-kindle-application")
