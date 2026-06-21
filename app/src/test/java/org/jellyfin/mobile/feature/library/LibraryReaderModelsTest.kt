package org.jellyfin.mobile.feature.library

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class LibraryReaderModelsTest {
    @Test
    fun `format detection uses mime type and file extension`() {
        detectLibraryBookFormat("application/epub+zip", "book.bin") shouldBe LibraryBookFormat.EPUB
        detectLibraryBookFormat(null, "book.pdf") shouldBe LibraryBookFormat.PDF
        detectLibraryBookFormat("application/vnd.comicbook+zip", "comic.zip") shouldBe LibraryBookFormat.CBZ
        detectLibraryBookFormat(null, "comic.cbr") shouldBe LibraryBookFormat.CBR
        detectLibraryBookFormat(null, "kindle.azw") shouldBe LibraryBookFormat.AZW
        detectLibraryBookFormat(null, "kindle.azw3") shouldBe LibraryBookFormat.AZW3
        detectLibraryBookFormat(null, "notes.markdown") shouldBe LibraryBookFormat.MARKDOWN
        detectLibraryBookFormat("text/plain", "notes.dat") shouldBe LibraryBookFormat.TXT
        detectLibraryBookFormat("text/html; charset=utf-8", "reader") shouldBe LibraryBookFormat.HTML
        detectLibraryBookFormat("application/xhtml+xml", "chapter.bin") shouldBe LibraryBookFormat.HTML
        detectLibraryBookFormat(null, "index.htm") shouldBe LibraryBookFormat.HTML
        detectLibraryBookFormat(null, "mystery.bin") shouldBe LibraryBookFormat.UNKNOWN
    }

    @Test
    fun `unsupported formats return PTV conversion guidance`() {
        LibraryBookFormat.CBR.supportStatus() shouldBe LibraryFormatSupport.UNSUPPORTED
        LibraryBookFormat.CBR.unsupportedMessage().message.contains("CBZ") shouldBe true
        LibraryBookFormat.MOBI.unsupportedMessage().message.contains("EPUB") shouldBe true
        LibraryBookFormat.AZW3.unsupportedMessage().title.contains("AZW3") shouldBe true
        LibraryBookFormat.MARKDOWN.unsupportedMessage().message.contains("TXT") shouldBe true
        LibraryBookFormat.HTML.unsupportedMessage().message.contains("WebView") shouldBe true
    }

    @Test
    fun `progress calculation clamps to valid range`() {
        calculateLibraryProgress(pageIndex = 0, pageCount = 10) shouldBe 0.1f
        calculateLibraryProgress(pageIndex = 99, pageCount = 10) shouldBe 1.0f
        calculateLibraryProgress(pageIndex = 3, pageCount = 0) shouldBe 0.0f
    }

    @Test
    fun `resume state serializes and normalizes`() {
        val decoded = LibraryReaderResumeStateSerializer.decode(
            LibraryReaderResumeStateSerializer.encode(
                LibraryReaderResumeState(
                    readerKey = "book",
                    title = "Book",
                    pageIndex = 20,
                    pageCount = 10,
                    progress = 0f,
                    updatedAtMs = 42,
                    chapterIndex = 3,
                    chapterTitle = "Chapter",
                    chapterPageIndex = 99,
                ),
            ),
        )

        decoded.shouldNotBeNull()
        decoded.pageIndex shouldBe 9
        decoded.progress shouldBe 1.0f
        decoded.updatedAtMs shouldBe 42
        decoded.chapterIndex shouldBe 3
        decoded.chapterTitle shouldBe "Chapter"
        decoded.chapterPageIndex shouldBe 99
    }

    @Test
    fun `invalid resume and settings payloads are ignored`() {
        LibraryReaderResumeStateSerializer.decode("not-json").shouldBeNull()
        LibraryReaderSettingsSerializer.decode("not-json").shouldBeNull()
    }

    @Test
    fun `reader settings serialize and clamp values`() {
        val decoded = LibraryReaderSettingsSerializer.decode(
            LibraryReaderSettingsSerializer.encode(
                LibraryReaderSettings(
                    theme = LibraryReaderTheme.SEPIA,
                    fontScale = 99f,
                    marginScale = -99f,
                    lineSpacingScale = 99f,
                    zoomScale = 99f,
                    singlePageMode = true,
                    rightToLeftManga = true,
                ),
            ),
        )

        decoded.shouldNotBeNull()
        decoded.theme shouldBe LibraryReaderTheme.SEPIA
        decoded.fontScale shouldBe LibraryReaderSettings.MAX_FONT_SCALE
        decoded.marginScale shouldBe LibraryReaderSettings.MIN_MARGIN_SCALE
        decoded.lineSpacingScale shouldBe LibraryReaderSettings.MAX_LINE_SPACING_SCALE
        decoded.zoomScale shouldBe LibraryReaderSettings.MAX_ZOOM_SCALE
        decoded.singlePageMode shouldBe true
        decoded.rightToLeftManga shouldBe true
    }

    @Test
    fun `book detail mapping exposes reader key and progress`() {
        val book = LibraryBook(
            id = "id",
            title = "Book",
            subtitle = null,
            authors = listOf("Author"),
            summary = null,
            coverUrl = null,
            categories = listOf("Series: Saga", "Comics"),
            series = "Saga",
            updated = null,
            detailUrl = null,
            acquisitionLinks = listOf(
                LibraryLink(
                    title = "CBZ",
                    href = "https://example.invalid/book.cbz",
                    type = "application/x-cbz",
                    rel = "http://opds-spec.org/acquisition",
                    lengthBytes = 100,
                ),
            ),
            readLinks = emptyList(),
            format = LibraryBookFormat.CBZ,
            fileSizeBytes = 100,
        ).withReadingState(
            resumeState = LibraryReaderResumeState(
                readerKey = "https://example.invalid/book.cbz",
                title = "Book",
                pageIndex = 4,
                pageCount = 10,
                progress = 0.5f,
                updatedAtMs = 55,
            ),
            favorite = true,
        )

        book.readerKey shouldBe "https://example.invalid/book.cbz"
        book.primaryReaderLink?.inferredFormat(book.title) shouldBe LibraryBookFormat.CBZ
        book.progress?.percent shouldBe 50
        book.isFavorite shouldBe true
    }
}
