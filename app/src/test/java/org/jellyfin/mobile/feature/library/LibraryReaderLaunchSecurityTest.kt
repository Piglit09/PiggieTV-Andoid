@file:Suppress("LongMethod", "MaximumLineLength")

package org.jellyfin.mobile.feature.library

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class LibraryReaderLaunchSecurityTest {
    @Test
    fun `signed OPDS URL becomes an irreversible persistent identity`() {
        val signedUrl = SIGNED_URL
        val readerKey = opdsReaderIdentity(signedUrl)
        val serializedResume = LibraryReaderResumeStateSerializer.encode(
            LibraryReaderResumeState(
                readerKey = readerKey,
                title = signedUrl,
                pageIndex = 1,
                pageCount = 10,
                progress = 0.2f,
                updatedAtMs = 42,
                chapterTitle = signedUrl,
            ),
        )

        assertTrue(readerKey.matches(Regex("opds:sha256:[0-9a-f]{64}")))
        assertFalse(readerKey.contains("X-Amz-Credential"))
        assertFalse(readerKey.contains("X-Amz-Signature"))
        assertFalse(readerKey.contains("secret-signature"))
        assertFalse(serializedResume.contains(signedUrl))
        assertFalse(serializedResume.contains("secret-signature"))
        assertEquals(64, signedUrl.stableStorageHash().length)
    }

    @Test
    fun `unsafe legacy reader identities are rejected from resume and favorites`() {
        val rawResume = """
            {
              "readerKey":"$SIGNED_URL",
              "title":"Book",
              "pageIndex":1,
              "pageCount":10,
              "progress":0.2,
              "updatedAtMs":42
            }
        """.trimIndent()
        val safeKey = opdsReaderIdentity("urn:book:stable-id")

        assertNull(LibraryReaderResumeStateSerializer.decode(rawResume))
        assertEquals(setOf(safeKey), listOf(SIGNED_URL, safeKey, "legacy-book").safePersistentReaderKeys())
    }

    @Test
    fun `library and OPDS model diagnostics redact signed URLs`() {
        val libraryLink = LibraryLink("Open", SIGNED_URL, "application/epub+zip", "acquisition")
        val book = LibraryBook(
            id = SIGNED_URL,
            title = SIGNED_URL,
            subtitle = null,
            authors = emptyList(),
            summary = SIGNED_URL,
            coverUrl = SIGNED_URL,
            categories = emptyList(),
            series = null,
            updated = null,
            detailUrl = SIGNED_URL,
            acquisitionLinks = listOf(libraryLink),
            readLinks = emptyList(),
        )
        val facet = LibraryFacet(SIGNED_URL, SIGNED_URL, null, SIGNED_URL)
        val home = LibraryHome(
            serverBaseUrl = SIGNED_URL,
            allBooks = listOf(book),
            recentBooks = listOf(book),
            authors = listOf(facet),
            series = emptyList(),
            categories = emptyList(),
        )
        val opdsLink = OpdsLink(SIGNED_URL, "acquisition", "application/epub+zip", SIGNED_URL, null)
        val opdsEntry = OpdsEntry(
            id = SIGNED_URL,
            title = SIGNED_URL,
            subtitle = null,
            summary = SIGNED_URL,
            authors = emptyList(),
            categories = emptyList(),
            updated = null,
            links = listOf(opdsLink),
        )
        val feed = OpdsFeed(SIGNED_URL, SIGNED_URL, listOf(opdsEntry), listOf(opdsLink))

        listOf(libraryLink, book, facet, home, opdsLink, opdsEntry, feed).forEach { value ->
            assertFalse(value.toString().contains("X-Amz-Signature"), value::class.simpleName)
            assertFalse(value.toString().contains("secret-signature"), value::class.simpleName)
            assertFalse(value.toString().contains(SIGNED_URL), value::class.simpleName)
        }
    }

    @Test
    fun `runtime signed URL stays in bounded process memory behind an opaque ID`() {
        val generatedIds = listOf(REQUEST_ID_ONE, REQUEST_ID_TWO).iterator()
        val registry = LibraryReaderLaunchRequestRegistry(
            capacity = 1,
            requestIdFactory = generatedIds::next,
        )
        val firstRequest = launchRequest(SIGNED_URL, "urn:book:first")
        val firstId = registry.register(firstRequest)

        assertEquals(REQUEST_ID_ONE, firstId)
        assertFalse(firstId.contains("secret-signature"))
        assertEquals(SIGNED_URL, registry.resolve(firstId)?.uri)
        assertFalse(firstRequest.toString().contains(SIGNED_URL))

        val secondId = registry.register(launchRequest("https://trusted.example/book-2.epub", "urn:book:second"))
        assertEquals(REQUEST_ID_TWO, secondId)
        assertNull(registry.resolve(firstId))
        assertEquals(1, registry.size())
    }

    @Test
    fun `fragment arguments contain only the opaque launch request ID`() {
        val handlerSource = sourceFile(HANDLER_SOURCE).toFile().readText()
        val readerBranch = handlerSource
            .substringAfter("is ActivityEvent.ReadLibraryBook ->")
            .substringBefore("is ActivityEvent.RemoveDownload ->")
        val bundleBlock = readerBranch.substringAfter("Bundle().apply").substringBefore("}")
        val fragmentSource = sourceFile(FRAGMENT_SOURCE).toFile().readText()
        val constantsSource = sourceFile(CONSTANTS_SOURCE).toFile().readText()
        val viewModelSource = sourceFile(VIEW_MODEL_SOURCE).toFile().readText()

        assertTrue(bundleBlock.contains("putString(Constants.EXTRA_LIBRARY_READER_REQUEST_ID, requestId)"))
        assertFalse(bundleBlock.contains("putParcelable"))
        assertFalse(bundleBlock.contains("uri"))
        assertTrue(fragmentSource.contains("readerLaunchRequests::resolve"))
        assertFalse(fragmentSource.contains("EXTRA_LIBRARY_READER_URI"))
        assertFalse(constantsSource.contains("EXTRA_LIBRARY_READER_URI"))
        assertFalse(constantsSource.contains("EXTRA_LIBRARY_READER_KEY"))
        assertFalse(viewModelSource.contains("LibraryUiState.Error(error.message"))
        assertFalse(fragmentSource.contains("Timber.w(error, \"Could not prepare native Library reader\")"))
        assertFalse(fragmentSource.contains("Library reader fallback download failed: ${'$'}{error.message}"))
    }

    private fun launchRequest(uri: String, entryId: String) = LibraryReaderLaunchRequest(
        uri = uri,
        title = "Book",
        filename = "book.epub",
        mimeType = "application/epub+zip",
        readerKey = opdsReaderIdentity(entryId),
        jellyfinItemId = null,
        source = LibraryBookSource.OPDS,
    )

    private fun sourceFile(relativePath: String): Path {
        val workingDirectory = Path.of(System.getProperty("user.dir"))
        val fromRoot = workingDirectory.resolve("app").resolve(relativePath)
        if (Files.isRegularFile(fromRoot)) return fromRoot

        val fromApp = workingDirectory.resolve(relativePath)
        check(Files.isRegularFile(fromApp)) { "Source file was not found: $relativePath" }
        return fromApp
    }

    private companion object {
        const val SIGNED_URL =
            "https://trusted.example/book.epub?X-Amz-Credential=credential&X-Amz-Signature=secret-signature"
        const val REQUEST_ID_ONE = "00000000-0000-4000-8000-000000000001"
        const val REQUEST_ID_TWO = "00000000-0000-4000-8000-000000000002"
        const val HANDLER_SOURCE = "src/main/java/org/jellyfin/mobile/events/ActivityEventHandler.kt"
        const val FRAGMENT_SOURCE = "src/main/java/org/jellyfin/mobile/feature/library/LibraryReaderFragment.kt"
        const val CONSTANTS_SOURCE = "src/main/java/org/jellyfin/mobile/utils/Constants.kt"
        const val VIEW_MODEL_SOURCE = "src/main/java/org/jellyfin/mobile/feature/library/LibraryViewModel.kt"
    }
}
