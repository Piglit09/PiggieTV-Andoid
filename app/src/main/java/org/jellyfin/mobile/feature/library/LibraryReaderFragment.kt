package org.jellyfin.mobile.feature.library

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.Paint
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.LinearProgressIndicator
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jellyfin.mobile.ui.ComposeFragment
import org.jellyfin.mobile.ui.utils.PiggieTvBackground
import org.jellyfin.mobile.ui.utils.PiggieTvColors
import org.jellyfin.mobile.utils.Constants
import org.jellyfin.mobile.utils.extensions.getParcelableCompat
import org.koin.android.ext.android.inject
import timber.log.Timber
import java.io.File
import java.io.IOException
import java.net.URI
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile
import kotlin.math.roundToInt

class LibraryReaderFragment : ComposeFragment() {
    private val okHttpClient: OkHttpClient by inject()
    private val readerClient by lazy {
        okHttpClient.newBuilder()
            .connectTimeout(READER_CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(READER_READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(READER_READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .callTimeout(READER_CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()
    }

    private var state by mutableStateOf<ReaderUiState>(ReaderUiState.Preparing(0f, "Preparing reader"))
    private var document: ReaderDocument? = null
    private val renderedPages = mutableMapOf<Int, ReaderPage>()

    @Composable
    override fun Content() {
        val title = requireArguments().getString(Constants.EXTRA_LIBRARY_READER_TITLE).orEmpty()
        val currentState = state

        LaunchedEffect(Unit) {
            prepareReader()
        }

        DisposableEffect(Unit) {
            onDispose {
                document?.close()
                renderedPages.values.forEach { page -> (page as? ReaderPage.Image)?.bitmap?.recycle() }
            }
        }

        PiggieTvBackground(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding(),
            ) {
                ReaderTopBar(title = title, onBack = { parentFragmentManager.popBackStack() })
                when (currentState) {
                    is ReaderUiState.Preparing -> PreparingReader(currentState)
                    is ReaderUiState.Ready -> ReaderPages(currentState)
                    is ReaderUiState.Error -> ReaderError(currentState.message)
                }
            }
        }
    }

    private fun prepareReader() {
        viewLifecycleOwner.lifecycleScope.launch {
            runCatching {
                val args = requireArguments()
                val uri = requireNotNull(args.getParcelableCompat<Uri>(Constants.EXTRA_LIBRARY_READER_URI))
                val filename = requireNotNull(args.getString(Constants.EXTRA_LIBRARY_READER_FILENAME))
                val mimeType = args.getString(Constants.EXTRA_LIBRARY_READER_MIME_TYPE)
                val file = downloadToReaderCache(uri, filename)
                val loadedDocument = withContext(Dispatchers.IO) {
                    ReaderDocument.open(file, mimeType)
                }

                document = loadedDocument
                state = ReaderUiState.Ready(
                    pageCount = loadedDocument.pageCount,
                    pages = emptyMap(),
                    status = "Loading first pages",
                )
                renderPagesAround(firstVisiblePage = 0, lookAhead = INITIAL_PAGE_PRELOAD)
            }.onFailure { error ->
                Timber.w(error, "Could not prepare native Library reader")
                state = ReaderUiState.Error(error.message ?: "Could not prepare this book for reading.")
            }
        }
    }

    private suspend fun downloadToReaderCache(uri: Uri, filename: String): File = withContext(Dispatchers.IO) {
        val readerDir = File(requireContext().cacheDir, READER_CACHE_DIR).apply { mkdirs() }
        val target = File(readerDir, filename)
        var lastFailure: IOException? = null

        uri.downloadCandidates().forEachIndexed { index, candidate ->
            val candidateTarget = File(readerDir, "$filename.part")
            val request = Request.Builder().url(candidate.toString()).build()
            runCatching {
                readerClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        throw IOException("Library reader download failed with HTTP ${response.code}")
                    }
                    val body = response.body ?: throw IOException("Library reader download returned an empty body")
                    val contentLength = body.contentLength().takeIf { it > 0L }
                    var bytesCopied = 0L

                    body.byteStream().use { input ->
                        candidateTarget.outputStream().use { output ->
                            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                            while (true) {
                                val read = input.read(buffer)
                                if (read == -1) break
                                output.write(buffer, 0, read)
                                bytesCopied += read
                                contentLength?.let { total ->
                                    val progress = (bytesCopied.toFloat() / total).coerceIn(0f, 1f)
                                    state = ReaderUiState.Preparing(progress, "Downloading book")
                                }
                            }
                        }
                    }
                }
                if (target.exists()) target.delete()
                if (!candidateTarget.renameTo(target)) {
                    candidateTarget.copyTo(target, overwrite = true)
                    candidateTarget.delete()
                }
                return@withContext target
            }.onFailure { error ->
                candidateTarget.delete()
                lastFailure = IOException(
                    if (index == 0) {
                        error.message
                    } else {
                        "Library reader fallback download failed: ${error.message}"
                    },
                    error,
                )
            }
        }

        throw lastFailure ?: IOException("Library reader download failed.")
    }

    private fun Uri.downloadCandidates(): List<Uri> {
        val original = this
        val fallback = runCatching {
            val uri = URI(toString())
            val path = uri.rawPath ?: return@runCatching null
            if (!path.startsWith(OPDS_DOWNLOAD_PREFIX)) return@runCatching null
            URI(
                uri.scheme,
                uri.rawAuthority,
                DOWNLOAD_PREFIX + path.removePrefix(OPDS_DOWNLOAD_PREFIX),
                uri.rawQuery,
                uri.rawFragment,
            ).toString().let(Uri::parse)
        }.getOrNull()

        return listOfNotNull(original, fallback).distinctBy(Uri::toString)
    }

    private fun renderPagesAround(firstVisiblePage: Int, lookAhead: Int = PAGE_LOOKAHEAD) {
        val loadedDocument = document ?: return
        val ready = state as? ReaderUiState.Ready ?: return
        val endExclusive = (firstVisiblePage + lookAhead).coerceAtMost(loadedDocument.pageCount)
        val missingPages = (firstVisiblePage until endExclusive).filterNot(renderedPages::containsKey)
        if (missingPages.isEmpty()) return

        state = ready.copy(status = "Loading pages")
        viewLifecycleOwner.lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                missingPages.forEach { pageIndex ->
                    renderedPages[pageIndex] = loadedDocument.renderPage(pageIndex, TARGET_PAGE_WIDTH_PX)
                }
            }
            state = ReaderUiState.Ready(
                pageCount = loadedDocument.pageCount,
                pages = renderedPages.toSortedMap(),
                status = null,
            )
        }
    }

    @Composable
    private fun ReaderPages(ready: ReaderUiState.Ready) {
        val listState = rememberLazyListState()

        LaunchedEffect(listState.firstVisibleItemIndex, ready.pageCount) {
            renderPagesAround(listState.firstVisibleItemIndex)
        }

        Box(modifier = Modifier.fillMaxSize()) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items((0 until ready.pageCount).toList()) { pageIndex ->
                    when (val page = ready.pages[pageIndex]) {
                        is ReaderPage.Image -> Image(
                            bitmap = page.bitmap.asImageBitmap(),
                            contentDescription = null,
                            modifier = Modifier.fillMaxWidth(),
                            contentScale = ContentScale.FillWidth,
                        )
                        is ReaderPage.Text -> Text(
                            text = page.text,
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(PiggieTvColors.Night.copy(alpha = 0.86f))
                                .padding(18.dp),
                            color = PiggieTvColors.TextPrimary,
                            style = MaterialTheme.typography.body1,
                        )
                        null -> PagePlaceholder(pageIndex)
                    }
                }
            }

            ready.status?.let { status ->
                Row(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(14.dp)
                        .background(PiggieTvColors.PanelHigh.copy(alpha = 0.92f), MaterialTheme.shapes.medium)
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(modifier = Modifier.width(18.dp), color = PiggieTvColors.Focus, strokeWidth = 2.dp)
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(text = status, color = PiggieTvColors.TextPrimary, style = MaterialTheme.typography.caption)
                }
            }
        }
    }

    @Composable
    private fun ReaderTopBar(title: String, onBack: () -> Unit) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = null, tint = PiggieTvColors.TextPrimary)
            }
            Text(
                text = title,
                modifier = Modifier.weight(1f),
                color = PiggieTvColors.TextPrimary,
                style = MaterialTheme.typography.subtitle1,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }

    @Composable
    private fun PreparingReader(preparing: ReaderUiState.Preparing) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                CircularProgressIndicator(color = PiggieTvColors.Focus)
                Text(text = preparing.message, color = PiggieTvColors.TextPrimary, style = MaterialTheme.typography.body1)
                LinearProgressIndicator(
                    progress = preparing.progress.coerceIn(0f, 1f),
                    modifier = Modifier.fillMaxWidth(),
                    color = PiggieTvColors.Accent,
                    backgroundColor = PiggieTvColors.PanelHigh,
                )
            }
        }
    }

    @Composable
    private fun PagePlaceholder(pageIndex: Int) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(520.dp)
                .background(PiggieTvColors.Panel.copy(alpha = 0.82f), MaterialTheme.shapes.medium),
            contentAlignment = Alignment.Center,
        ) {
            Text(text = "Loading page ${pageIndex + 1}", color = PiggieTvColors.TextSecondary)
        }
    }

    @Composable
    private fun ReaderError(message: String) {
        Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            Text(text = message, color = PiggieTvColors.TextPrimary, style = MaterialTheme.typography.body1)
        }
    }

    private sealed interface ReaderUiState {
        data class Preparing(val progress: Float, val message: String) : ReaderUiState
        data class Ready(
            val pageCount: Int,
            val pages: Map<Int, ReaderPage>,
            val status: String?,
        ) : ReaderUiState
        data class Error(val message: String) : ReaderUiState
    }

    private sealed interface ReaderPage {
        data class Image(val bitmap: Bitmap) : ReaderPage
        data class Text(val text: String) : ReaderPage
    }

    private sealed interface ReaderDocument : AutoCloseable {
        val pageCount: Int
        fun renderPage(index: Int, targetWidth: Int): ReaderPage

        class Pdf(private val descriptor: ParcelFileDescriptor) : ReaderDocument {
            private val renderer = PdfRenderer(descriptor)
            override val pageCount: Int get() = renderer.pageCount

            override fun renderPage(index: Int, targetWidth: Int): ReaderPage.Image = synchronized(renderer) {
                renderer.openPage(index).use { page ->
                    val scale = targetWidth.toFloat() / page.width.toFloat()
                    val height = (page.height * scale).roundToInt().coerceAtLeast(1)
                    val bitmap = Bitmap.createBitmap(targetWidth, height, Bitmap.Config.ARGB_8888)
                    bitmap.eraseColor(AndroidColor.WHITE)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    ReaderPage.Image(bitmap)
                }
            }

            override fun close() {
                renderer.close()
                descriptor.close()
            }
        }

        class ZipImages(private val zipFile: ZipFile, private val entries: List<String>) : ReaderDocument {
            override val pageCount: Int get() = entries.size

            override fun renderPage(index: Int, targetWidth: Int): ReaderPage.Image {
                val entry = requireNotNull(zipFile.getEntry(entries[index]))
                val original = zipFile.getInputStream(entry).use(BitmapFactory::decodeStream)
                val scale = targetWidth.toFloat() / original.width.toFloat()
                val height = (original.height * scale).roundToInt().coerceAtLeast(1)
                val scaled = Bitmap.createScaledBitmap(original, targetWidth, height, true)
                if (scaled !== original) original.recycle()
                return ReaderPage.Image(scaled)
            }

            override fun close() = zipFile.close()
        }

        class TextPages(private val pages: List<String>) : ReaderDocument {
            override val pageCount: Int get() = pages.size
            override fun renderPage(index: Int, targetWidth: Int): ReaderPage.Text = ReaderPage.Text(pages[index])
            override fun close() = Unit
        }

        companion object {
            fun open(file: File, mimeType: String?): ReaderDocument = when {
                mimeType == "application/pdf" || file.extension.equals("pdf", ignoreCase = true) -> {
                    Pdf(ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY))
                }
                mimeType in COMIC_MIME_TYPES || file.extension.lowercase() in setOf("cbz") -> {
                    val zipFile = ZipFile(file)
                    val entries = zipFile.entries().asSequence()
                        .map { it.name }
                        .filter(::isImagePath)
                        .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it })
                        .toList()
                    if (entries.isEmpty()) throw IOException("No readable comic pages found.")
                    ZipImages(zipFile, entries)
                }
                mimeType == "application/epub+zip" || file.extension.equals("epub", ignoreCase = true) -> {
                    TextPages(readEpubTextPages(file))
                }
                mimeType == "text/plain" || file.extension.equals("txt", ignoreCase = true) -> {
                    TextPages(file.readText().chunked(TEXT_PAGE_CHARS))
                }
                else -> throw IOException("This format is not available in the native reader yet.")
            }

            private fun readEpubTextPages(file: File): List<String> {
                ZipFile(file).use { zipFile ->
                    val text = zipFile.entries().asSequence()
                        .filter { entry -> entry.name.endsWith(".xhtml", true) || entry.name.endsWith(".html", true) }
                        .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
                        .joinToString("\n\n") { entry ->
                            zipFile.getInputStream(entry).bufferedReader().use { reader ->
                                reader.readText()
                                    .replace(Regex("<script[\\s\\S]*?</script>", RegexOption.IGNORE_CASE), "")
                                    .replace(Regex("<style[\\s\\S]*?</style>", RegexOption.IGNORE_CASE), "")
                                    .replace(Regex("<[^>]+>"), " ")
                                    .replace("&nbsp;", " ")
                                    .replace("&amp;", "&")
                                    .replace(Regex("\\s+"), " ")
                                    .trim()
                            }
                        }
                    return text.chunked(TEXT_PAGE_CHARS).ifEmpty { listOf("No readable text found in this EPUB.") }
                }
            }

            private fun isImagePath(path: String): Boolean =
                path.endsWith(".jpg", true) ||
                    path.endsWith(".jpeg", true) ||
                    path.endsWith(".png", true) ||
                    path.endsWith(".webp", true)
        }
    }

    private companion object {
        const val READER_CACHE_DIR = "library-reader"
        const val TARGET_PAGE_WIDTH_PX = 1200
        const val INITIAL_PAGE_PRELOAD = 10
        const val PAGE_LOOKAHEAD = 6
        const val TEXT_PAGE_CHARS = 2400
        const val READER_CONNECT_TIMEOUT_SECONDS = 30L
        const val READER_READ_TIMEOUT_SECONDS = 180L
        const val READER_CALL_TIMEOUT_SECONDS = 600L
        const val OPDS_DOWNLOAD_PREFIX = "/opds/download/"
        const val DOWNLOAD_PREFIX = "/download/"
        val COMIC_MIME_TYPES = setOf(
            "application/x-cbz",
            "application/vnd.comicbook+zip",
        )
    }
}
