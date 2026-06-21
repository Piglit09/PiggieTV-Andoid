package org.jellyfin.mobile.feature.library

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipOutputStream

class LibraryEpubReaderTest {
    @TempDir
    lateinit var tempDir: File

    @Test
    fun `parser uses OPF spine order and nav titles`() {
        val epub = createEpub(
            opf = """
                <?xml version="1.0" encoding="UTF-8"?>
                <package xmlns="http://www.idpf.org/2007/opf" version="3.0">
                  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                    <dc:title>PTV Test Book</dc:title>
                  </metadata>
                  <manifest>
                    <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
                    <item id="c1" href="chapter-one.xhtml" media-type="application/xhtml+xml"/>
                    <item id="c2" href="chapter-two.xhtml" media-type="application/xhtml+xml"/>
                  </manifest>
                  <spine>
                    <itemref idref="c2"/>
                    <itemref idref="c1"/>
                  </spine>
                </package>
            """.trimIndent(),
            entries = mapOf(
                "OEBPS/nav.xhtml" to """
                    <html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops">
                      <body>
                        <nav epub:type="toc">
                          <ol>
                            <li><a href="chapter-one.xhtml">Chapter One</a></li>
                            <li><a href="chapter-two.xhtml">Chapter Two</a></li>
                          </ol>
                        </nav>
                      </body>
                    </html>
                """.trimIndent(),
                "OEBPS/chapter-one.xhtml" to chapter("Ignored H1", "First chapter text."),
                "OEBPS/chapter-two.xhtml" to chapter("Ignored H1", "Second chapter text."),
            ),
        )

        val book = LibraryEpubReader.read(epub, charsPerPage = 500)

        book.title shouldBe "PTV Test Book"
        book.chapters shouldHaveSize 2
        book.chapters[0].title shouldBe "Chapter Two"
        book.pages[0].text.contains("Second chapter text.") shouldBe true
        book.chapters[1].title shouldBe "Chapter One"
    }

    @Test
    fun `parser falls back to NCX table of contents`() {
        val epub = createEpub(
            opf = """
                <package xmlns="http://www.idpf.org/2007/opf" version="2.0">
                  <manifest>
                    <item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>
                    <item id="c1" href="text/chapter.xhtml" media-type="application/xhtml+xml"/>
                  </manifest>
                  <spine toc="ncx">
                    <itemref idref="c1"/>
                  </spine>
                </package>
            """.trimIndent(),
            entries = mapOf(
                "OEBPS/toc.ncx" to """
                    <ncx xmlns="http://www.daisy.org/z3986/2005/ncx/">
                      <navMap>
                        <navPoint id="navPoint-1">
                          <navLabel><text>NCX Chapter</text></navLabel>
                          <content src="text/chapter.xhtml"/>
                        </navPoint>
                      </navMap>
                    </ncx>
                """.trimIndent(),
                "OEBPS/text/chapter.xhtml" to chapter("Fallback", "NCX text."),
            ),
        )

        val book = LibraryEpubReader.read(epub, charsPerPage = 500)

        book.chapters.single().title shouldBe "NCX Chapter"
        book.pages.single().chapterTitle shouldBe "NCX Chapter"
    }

    @Test
    fun `parser handles missing metadata and preserves paragraph breaks`() {
        val epub = createEpub(
            opf = """
                <package xmlns="http://www.idpf.org/2007/opf" version="3.0">
                  <manifest>
                    <item id="c1" href="chapter.xhtml" media-type="application/xhtml+xml"/>
                  </manifest>
                  <spine>
                    <itemref idref="c1"/>
                  </spine>
                </package>
            """.trimIndent(),
            entries = mapOf(
                "OEBPS/chapter.xhtml" to """
                    <html xmlns="http://www.w3.org/1999/xhtml">
                      <body><h1>Only Heading</h1><p>Para one.</p><p>Para two &amp; more.</p></body>
                    </html>
                """.trimIndent(),
            ),
        )

        val book = LibraryEpubReader.read(epub, charsPerPage = 500)

        book.title shouldBe null
        book.chapters.single().title shouldBe "Only Heading"
        book.pages.single().text.contains("Para one.\n\nPara two & more.") shouldBe true
    }

    @Test
    fun `parser keeps native text fidelity hints without webview`() {
        val epub = createEpub(
            opf = """
                <package xmlns="http://www.idpf.org/2007/opf" version="3.0">
                  <manifest>
                    <item id="c1" href="chapter.xhtml" media-type="application/xhtml+xml"/>
                  </manifest>
                  <spine>
                    <itemref idref="c1"/>
                  </spine>
                </package>
            """.trimIndent(),
            entries = mapOf(
                "OEBPS/chapter.xhtml" to """
                    <html xmlns="http://www.w3.org/1999/xhtml">
                      <body>
                        <h1>Display Heading</h1>
                        <p>A <strong>bold</strong> and <em>quiet</em> word<a href="#fn1">1</a>.</p>
                        <img alt="Map" src="map.jpg"/>
                        <pre>line one
                          line two</pre>
                      </body>
                    </html>
                """.trimIndent(),
            ),
        )

        val text = LibraryEpubReader.read(epub, charsPerPage = 800).pages.single().text

        text.contains("# Display Heading") shouldBe true
        text.contains("**bold**") shouldBe true
        text.contains("_quiet_") shouldBe true
        text.contains("1 [note]") shouldBe true
        text.contains("[Image: Map]") shouldBe true
        text.contains("line one\nline two") shouldBe true
    }

    @Test
    fun `parser fails corrupt epub without hanging`() {
        val corrupt = File(tempDir, "corrupt.epub").apply {
            writeText("not a zip")
        }

        shouldThrow<ZipException> {
            LibraryEpubReader.read(corrupt, charsPerPage = 500)
        }
    }

    private fun createEpub(opf: String, entries: Map<String, String>): File {
        val epub = File(tempDir, "book.epub")
        ZipOutputStream(epub.outputStream()).use { zip ->
            zip.putText(
                "META-INF/container.xml",
                """
                    <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
                      <rootfiles>
                        <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
                      </rootfiles>
                    </container>
                """.trimIndent(),
            )
            zip.putText("OEBPS/content.opf", opf)
            entries.forEach { (path, text) -> zip.putText(path, text) }
        }
        return epub
    }

    private fun ZipOutputStream.putText(path: String, text: String) {
        putNextEntry(ZipEntry(path))
        write(text.toByteArray())
        closeEntry()
    }

    private fun chapter(title: String, text: String): String =
        """
            <html xmlns="http://www.w3.org/1999/xhtml">
              <head><title>$title</title></head>
              <body><h1>$title</h1><p>$text</p></body>
            </html>
        """.trimIndent()
}
