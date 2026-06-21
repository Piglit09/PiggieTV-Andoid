@file:Suppress(
    "ArgumentListWrapping",
    "BinaryExpressionWrapping",
    "CyclomaticComplexMethod",
    "FunctionExpressionBody",
    "FunctionSignature",
    "MaximumLineLength",
    "NestedBlockDepth",
    "ReturnCount",
    "ClassSignature",
    "LongMethod",
    "TooManyFunctions",
)

package org.jellyfin.mobile.feature.library

import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory

internal data class LibraryEpubBook(
    val title: String?,
    val pages: List<LibraryEpubPage>,
    val chapters: List<LibraryReaderChapter>,
)

internal data class LibraryEpubPage(
    val chapterIndex: Int,
    val chapterTitle: String,
    val text: String,
)

internal object LibraryEpubReader {
    fun read(file: File, charsPerPage: Int = DEFAULT_TEXT_PAGE_CHARS): LibraryEpubBook =
        ZipFile(file).use { zipFile ->
            read(zipFile, charsPerPage)
        }

    fun read(zipFile: ZipFile, charsPerPage: Int = DEFAULT_TEXT_PAGE_CHARS): LibraryEpubBook {
        val packagePath = findPackagePath(zipFile)
        val packageRoot = zipFile.readXml(packagePath)
        val packageDir = packagePath.substringBeforeLast('/', missingDelimiterValue = "")
        val manifest = packageRoot.descendants("item").associate { item ->
            val id = item.attr("id")
            id to EpubManifestItem(
                id = id,
                href = item.attr("href"),
                mediaType = item.attr("media-type"),
                properties = item.attr("properties"),
            )
        }
        val spineIds = packageRoot.descendants("itemref")
            .mapNotNull { itemRef -> itemRef.attr("idref").takeIf(String::isNotBlank) }
        val bookTitle = packageRoot.descendants("title").firstOrNull()?.textContent?.cleanText()
        val tocTitles = readTableOfContents(zipFile, packageRoot, manifest, packageDir)
        val spineItems = spineIds
            .mapNotNull(manifest::get)
            .filter { item -> item.href.isNotBlank() }
            .ifEmpty {
                manifest.values.filter { item ->
                    item.mediaType in XHTML_MEDIA_TYPES || item.href.endsWith(".html", true) || item.href.endsWith(".xhtml", true)
                }
            }

        val pages = mutableListOf<LibraryEpubPage>()
        val chapters = mutableListOf<LibraryReaderChapter>()
        spineItems.forEachIndexed { chapterIndex, item ->
            val href = resolveZipPath(packageDir, item.href)
            val html = zipFile.readTextOrNull(href).orEmpty()
            if (html.isBlank()) return@forEachIndexed

            val chapterTitle = tocTitles[href]
                ?: extractHtmlTitle(html)
                ?: "Chapter ${chapterIndex + 1}"
            val text = htmlToReadableText(html).ifBlank { chapterTitle }
            val chapterPages = splitReadableText(text, charsPerPage).ifEmpty { listOf(chapterTitle) }
            val startPageIndex = pages.size

            chapterPages.forEach { pageText ->
                pages += LibraryEpubPage(
                    chapterIndex = chapterIndex,
                    chapterTitle = chapterTitle,
                    text = pageText,
                )
            }
            chapters += LibraryReaderChapter(
                index = chapterIndex,
                title = chapterTitle,
                startPageIndex = startPageIndex,
                pageCount = chapterPages.size,
                href = href,
            )
        }

        if (pages.isEmpty()) {
            val fallbackText = readFallbackText(zipFile)
            val fallbackPages = splitReadableText(fallbackText, charsPerPage)
                .ifEmpty { listOf("No readable text found in this EPUB.") }
            return LibraryEpubBook(
                title = bookTitle,
                pages = fallbackPages.map { text ->
                    LibraryEpubPage(
                        chapterIndex = 0,
                        chapterTitle = bookTitle ?: "EPUB",
                        text = text,
                    )
                },
                chapters = listOf(
                    LibraryReaderChapter(
                        index = 0,
                        title = bookTitle ?: "EPUB",
                        startPageIndex = 0,
                        pageCount = fallbackPages.size,
                    ),
                ),
            )
        }

        return LibraryEpubBook(
            title = bookTitle,
            pages = pages,
            chapters = chapters,
        )
    }

    private fun findPackagePath(zipFile: ZipFile): String {
        val containerRoot = zipFile.readTextOrNull(CONTAINER_PATH)
            ?.let(::parseXml)
        val packagePath = containerRoot
            ?.descendants("rootfile")
            ?.firstOrNull()
            ?.attr("full-path")
            ?.takeIf(String::isNotBlank)

        if (packagePath != null && zipFile.getEntry(packagePath) != null) return packagePath

        return zipFile.entries().asSequence()
            .map { entry -> entry.name }
            .firstOrNull { path -> path.endsWith(".opf", ignoreCase = true) }
            ?: throw IOException("This EPUB is missing its OPF package document.")
    }

    private fun readTableOfContents(
        zipFile: ZipFile,
        packageRoot: Element,
        manifest: Map<String, EpubManifestItem>,
        packageDir: String,
    ): Map<String, String> {
        val navItem = manifest.values.firstOrNull { item -> "nav" in item.properties.split(' ') }
        val navToc = navItem
            ?.let { item -> readNavDocument(zipFile, resolveZipPath(packageDir, item.href), packageDir) }
            .orEmpty()
        if (navToc.isNotEmpty()) return navToc

        val ncxId = packageRoot.descendants("spine").firstOrNull()?.attr("toc")
        val ncxItem = ncxId?.let(manifest::get)
            ?: manifest.values.firstOrNull { item -> item.mediaType == NCX_MEDIA_TYPE }
        return ncxItem
            ?.let { item -> readNcxDocument(zipFile, resolveZipPath(packageDir, item.href), packageDir) }
            .orEmpty()
    }

    private fun readNavDocument(zipFile: ZipFile, navPath: String, packageDir: String): Map<String, String> {
        val root = zipFile.readTextOrNull(navPath)?.let(::parseXml) ?: return emptyMap()
        val nav = root.descendants("nav").firstOrNull { element ->
            val type = element.attr("type").ifBlank { element.attr("epub:type") }
            "toc" in type.lowercase()
        } ?: root

        return nav.descendants("a").mapNotNull { link ->
            val href = link.attr("href").takeIf(String::isNotBlank) ?: return@mapNotNull null
            val title = link.textContent.cleanText().takeIf(String::isNotBlank) ?: return@mapNotNull null
            resolveZipPath(packageDir, href) to title
        }.toMap()
    }

    private fun readNcxDocument(zipFile: ZipFile, ncxPath: String, packageDir: String): Map<String, String> {
        val root = zipFile.readTextOrNull(ncxPath)?.let(::parseXml) ?: return emptyMap()
        return root.descendants("navPoint").mapNotNull { navPoint ->
            val src = navPoint.descendants("content")
                .firstOrNull()
                ?.attr("src")
                ?.takeIf(String::isNotBlank)
                ?: return@mapNotNull null
            val title = navPoint.descendants("text")
                .firstOrNull()
                ?.textContent
                ?.cleanText()
                ?.takeIf(String::isNotBlank)
                ?: return@mapNotNull null
            resolveZipPath(packageDir, src) to title
        }.toMap()
    }

    private fun readFallbackText(zipFile: ZipFile): String =
        zipFile.entries().asSequence()
            .filter { entry -> entry.name.endsWith(".html", true) || entry.name.endsWith(".xhtml", true) }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { entry -> entry.name })
            .joinToString("\n\n") { entry ->
                htmlToReadableText(zipFile.getInputStream(entry).bufferedReader().use { reader -> reader.readText() })
            }

    private fun splitReadableText(text: String, charsPerPage: Int): List<String> {
        if (text.length <= charsPerPage) return listOf(text)

        val pages = mutableListOf<String>()
        val paragraphs = text.split(Regex("\n{2,}"))
        val current = StringBuilder()
        paragraphs.forEach { paragraph ->
            if (current.length + paragraph.length + 2 > charsPerPage && current.isNotBlank()) {
                pages += current.toString().trim()
                current.clear()
            }
            if (paragraph.length > charsPerPage) {
                paragraph.chunked(charsPerPage).forEach { chunk ->
                    if (current.isNotBlank()) {
                        pages += current.toString().trim()
                        current.clear()
                    }
                    pages += chunk.trim()
                }
            } else {
                if (current.isNotBlank()) current.append("\n\n")
                current.append(paragraph)
            }
        }
        if (current.isNotBlank()) pages += current.toString().trim()
        return pages.filter(String::isNotBlank)
    }

    private fun extractHtmlTitle(html: String): String? {
        val heading = Regex("<h[1-3][^>]*>([\\s\\S]*?)</h[1-3]>", RegexOption.IGNORE_CASE)
            .find(html)
            ?.groupValues
            ?.getOrNull(1)
            ?.stripTags()
            ?.cleanText()
        if (!heading.isNullOrBlank()) return heading

        return Regex("<title[^>]*>([\\s\\S]*?)</title>", RegexOption.IGNORE_CASE)
            .find(html)
            ?.groupValues
            ?.getOrNull(1)
            ?.stripTags()
            ?.cleanText()
            ?.takeIf(String::isNotBlank)
    }

    private fun htmlToReadableText(html: String): String =
        html
            .replace(Regex("<script[\\s\\S]*?</script>", RegexOption.IGNORE_CASE), "")
            .replace(Regex("<style[\\s\\S]*?</style>", RegexOption.IGNORE_CASE), "")
            .replace(Regex("<pre[^>]*>([\\s\\S]*?)</pre>", RegexOption.IGNORE_CASE)) { match ->
                "\n\n${match.groupValues[1].stripTags().decodeBasicHtmlEntities().trim()}\n\n"
            }
            .replace(Regex("<code[^>]*>([\\s\\S]*?)</code>", RegexOption.IGNORE_CASE)) { match ->
                "`" + match.groupValues[1].stripTags().decodeBasicHtmlEntities().trim() + "`"
            }
            .replace(Regex("<img\\b([^>]*)>", RegexOption.IGNORE_CASE)) { match ->
                val alt = match.groupValues[1].htmlAttribute("alt")
                    ?: match.groupValues[1].htmlAttribute("title")
                    ?: match.groupValues[1].htmlAttribute("src")?.substringAfterLast('/')
                "\n\n[Image: ${alt?.cleanText()?.takeIf(String::isNotBlank) ?: "skipped"}]\n\n"
            }
            .replace(Regex("<h([1-6])[^>]*>([\\s\\S]*?)</h[1-6]>", RegexOption.IGNORE_CASE)) { match ->
                val level = match.groupValues[1].toIntOrNull()?.coerceIn(1, 6) ?: 2
                val marker = "#".repeat(level.coerceAtMost(3))
                "\n\n$marker ${match.groupValues[2].stripTags().cleanText()}\n\n"
            }
            .replace(Regex("<(strong|b)\\b[^>]*>([\\s\\S]*?)</(strong|b)>", RegexOption.IGNORE_CASE)) { match ->
                "**${match.groupValues[2].stripTags().cleanText()}**"
            }
            .replace(Regex("<(em|i)\\b[^>]*>([\\s\\S]*?)</(em|i)>", RegexOption.IGNORE_CASE)) { match ->
                "_${match.groupValues[2].stripTags().cleanText()}_"
            }
            .replace(Regex("<a\\b([^>]*)>([\\s\\S]*?)</a>", RegexOption.IGNORE_CASE)) { match ->
                val attributes = match.groupValues[1]
                val label = match.groupValues[2].stripTags().cleanText()
                val href = attributes.htmlAttribute("href").orEmpty()
                val type = attributes.htmlAttribute("type").orEmpty() + " " + attributes.htmlAttribute("epub:type").orEmpty()
                val isNote = href.contains("#") && (
                    href.contains("note", ignoreCase = true) ||
                        href.contains("fn", ignoreCase = true) ||
                        type.contains("noteref", ignoreCase = true)
                    )
                if (isNote && label.isNotBlank()) "$label [note]" else label
            }
            .replace(Regex("<blockquote[^>]*>", RegexOption.IGNORE_CASE), "\n\n> ")
            .replace(Regex("</blockquote>", RegexOption.IGNORE_CASE), "\n\n")
            .replace(Regex("<li[^>]*>", RegexOption.IGNORE_CASE), "\n- ")
            .replace(Regex("</li>", RegexOption.IGNORE_CASE), "\n")
            .replace(Regex("</?title[^>]*>", RegexOption.IGNORE_CASE), "\n\n")
            .replace(Regex("</(p|div|section|article|blockquote|li)>", RegexOption.IGNORE_CASE), "\n\n")
            .replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
            .stripTags()
            .decodeBasicHtmlEntities()
            .lines()
            .joinToString("\n") { line -> line.trim().replace(Regex("[ \\t]+"), " ") }
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()

    private fun ZipFile.readXml(path: String): Element =
        readTextOrNull(path)?.let(::parseXml)
            ?: throw IOException("This EPUB is missing $path.")

    private fun ZipFile.readTextOrNull(path: String): String? {
        val cleanPath = path.substringBefore('#').trimStart('/')
        val entry = getEntry(cleanPath) ?: return null
        return getInputStream(entry).bufferedReader().use { reader -> reader.readText() }
    }

    private fun parseXml(text: String): Element =
        DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
            runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
            isExpandEntityReferences = false
        }.newDocumentBuilder().parse(ByteArrayInputStream(text.toByteArray())).documentElement

    private fun Element.descendants(localName: String): List<Element> {
        val matches = mutableListOf<Element>()
        fun visit(node: Node) {
            if (node is Element && node.matchesName(localName)) matches += node
            val children = node.childNodes
            for (index in 0 until children.length) visit(children.item(index))
        }
        visit(this)
        return matches
    }

    private fun Element.attr(name: String): String {
        val direct = getAttribute(name).orEmpty().trim()
        if (direct.isNotBlank()) return direct
        for (index in 0 until attributes.length) {
            val node = attributes.item(index)
            if (node.localName == name || node.nodeName.endsWith(":$name")) return node.nodeValue.orEmpty().trim()
        }
        return ""
    }

    private fun Node.matchesName(name: String): Boolean =
        localName == name || nodeName == name || nodeName.endsWith(":$name")

    private fun resolveZipPath(baseDir: String, href: String): String {
        val cleanHref = href.substringBefore('#').trimStart('/')
        val combined = if (baseDir.isBlank()) cleanHref else "$baseDir/$cleanHref"
        val normalized = mutableListOf<String>()
        combined.split('/').forEach { segment ->
            when (segment) {
                "", "." -> Unit
                ".." -> if (normalized.isNotEmpty()) normalized.removeAt(normalized.lastIndex)
                else -> normalized += segment
            }
        }
        return normalized.joinToString("/")
    }

    private fun String.stripTags(): String =
        replace(Regex("<[^>]+>"), " ")

    private fun String.htmlAttribute(name: String): String? {
        val pattern = Regex("""\b$name\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
        return pattern.find(this)?.groupValues?.getOrNull(1)
    }

    private fun String.cleanText(): String =
        decodeBasicHtmlEntities()
            .replace(Regex("\\s+"), " ")
            .trim()

    private fun String.decodeBasicHtmlEntities(): String =
        replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace(Regex("&#(\\d+);")) { match ->
                match.groupValues[1].toIntOrNull()?.toChar()?.toString() ?: match.value
            }
            .replace(Regex("&#x([0-9a-fA-F]+);")) { match ->
                match.groupValues[1].toIntOrNull(HEX_RADIX)?.toChar()?.toString() ?: match.value
            }

    private data class EpubManifestItem(
        val id: String,
        val href: String,
        val mediaType: String,
        val properties: String,
    )

    private const val CONTAINER_PATH = "META-INF/container.xml"
    private const val NCX_MEDIA_TYPE = "application/x-dtbncx+xml"
    private const val DEFAULT_TEXT_PAGE_CHARS = 2400
    private const val HEX_RADIX = 16
    private val XHTML_MEDIA_TYPES = setOf(
        "application/xhtml+xml",
        "text/html",
    )
}
