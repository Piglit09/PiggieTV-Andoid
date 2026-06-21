@file:Suppress(
    "ArgumentListWrapping",
    "BinaryExpressionWrapping",
    "BlankLineBetweenWhenConditions",
    "ClassSignature",
    "CyclomaticComplexMethod",
    "FunctionLiteral",
    "FunctionSignature",
    "ImportOrdering",
    "LargeClass",
    "LongMethod",
    "MagicNumber",
    "MaximumLineLength",
    "TooManyFunctions",
)

package org.jellyfin.mobile.feature.library

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color as AndroidColor
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.view.KeyEvent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.LinearProgressIndicator
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Slider
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jellyfin.mobile.ui.ComposeFragment
import org.jellyfin.mobile.ui.utils.PiggieTvBackground
import org.jellyfin.mobile.ui.utils.PiggieTvColors
import org.jellyfin.mobile.utils.Constants
import org.jellyfin.mobile.utils.extensions.getParcelableCompat
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.util.AuthorizationHeaderBuilder
import org.jellyfin.sdk.api.client.extensions.itemsApi
import org.jellyfin.sdk.model.api.UpdateUserItemDataDto
import org.jellyfin.sdk.model.serializer.toUUIDOrNull
import org.koin.android.ext.android.inject
import timber.log.Timber
import java.io.File
import java.io.IOException
import java.net.URI
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile
import kotlin.coroutines.coroutineContext
import kotlin.math.roundToInt

class LibraryReaderFragment : ComposeFragment() {
    private val okHttpClient: OkHttpClient by inject()
    private val readerStore: LibraryReaderStore by inject()
    private val apiClient: ApiClient by inject()
    private val readerClient by lazy {
        okHttpClient.newBuilder()
            .connectTimeout(READER_CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(READER_READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(READER_READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .callTimeout(READER_CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()
    }

    private var state by mutableStateOf<ReaderUiState>(ReaderUiState.Preparing(0f, "Preparing reader"))
    private var settings by mutableStateOf(LibraryReaderSettings())
    private var lastRenderDurationMs by mutableStateOf<Long?>(null)
    private var lastReaderError by mutableStateOf<String?>(null)
    private var memoryWarningState by mutableStateOf("OK")
    private var document: ReaderDocument? = null
    private var renderJob: Job? = null
    private var readerGeneration: Long = 0L
    private val renderedPages = mutableMapOf<Int, ReaderPage>()
    private val bitmapCachePolicy by lazy {
        calculateLibraryBitmapCachePolicy(
            maxMemoryBytes = Runtime.getRuntime().maxMemory(),
            requestedLookAheadPages = PAGE_LOOKAHEAD,
        )
    }
    private var readerKey: String = ""
    private var readerTitle: String = ""
    private var jellyfinItemId: String? = null
    private var lastServerProgressSyncAtMs = 0L
    private var lastServerProgressPageIndex = -1

    override fun onDestroyView() {
        ++readerGeneration
        cancelActiveRendering()
        document?.close()
        document = null
        clearRenderedPages()
        super.onDestroyView()
    }

    @Composable
    override fun Content() {
        val title = requireArguments().getString(Constants.EXTRA_LIBRARY_READER_TITLE).orEmpty()
        val currentState = state
        val currentSettings = settings

        LaunchedEffect(Unit) {
            settings = readerStore.loadSettings()
            prepareReader()
        }

        DisposableEffect(Unit) {
            onDispose {
                cancelActiveRendering()
                document?.close()
                document = null
                clearRenderedPages()
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
                    is ReaderUiState.Ready -> ReaderPages(
                        ready = currentState,
                        settings = currentSettings,
                        onSettingsChange = ::updateSettings,
                    )
                    is ReaderUiState.Error -> ReaderError(currentState.message)
                }
            }
        }
    }

    private fun prepareReader() {
        val generation = ++readerGeneration
        cancelActiveRendering()
        document?.close()
        document = null
        clearRenderedPages()
        lastRenderDurationMs = null
        lastReaderError = null
        memoryWarningState = "OK"
        jellyfinItemId = null
        lastServerProgressSyncAtMs = 0L
        lastServerProgressPageIndex = -1

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val args = requireArguments()
                val uri = requireNotNull(args.getParcelableCompat<Uri>(Constants.EXTRA_LIBRARY_READER_URI))
                val filename = requireNotNull(args.getString(Constants.EXTRA_LIBRARY_READER_FILENAME))
                val mimeType = args.getString(Constants.EXTRA_LIBRARY_READER_MIME_TYPE)
                val providedReaderKey = args.getString(Constants.EXTRA_LIBRARY_READER_KEY)
                val format = detectLibraryBookFormat(mimeType, filename, uri.toString())
                val supportStatus = format.supportStatus()
                if (supportStatus != LibraryFormatSupport.NATIVE) {
                    val message = format.unsupportedMessage()
                    state = ReaderUiState.Error("${message.title}\n\n${message.message}")
                    return@launch
                }

                readerTitle = args.getString(Constants.EXTRA_LIBRARY_READER_TITLE).orEmpty()
                readerKey = providedReaderKey ?: uri.toString()
                jellyfinItemId = args.getString(Constants.EXTRA_LIBRARY_READER_ITEM_ID)
                val file = downloadToReaderCache(uri, filename)
                val loadedDocument = withContext(Dispatchers.IO) {
                    withTimeoutOrNull<ReaderDocument>(DOCUMENT_OPEN_TIMEOUT_MS) {
                        ReaderDocument.open(file, mimeType, format)
                    } ?: throw IOException("PTV Books took too long to prepare this file. Try a smaller file or reopen the reader.")
                }
                if (generation != readerGeneration) {
                    loadedDocument.close()
                    return@launch
                }
                val resumeState = readerStore.loadResume(readerKey)
                val initialPageIndex = resumeState
                    ?.pageIndex
                    ?.coerceIn(0, loadedDocument.pageCount.coerceAtLeast(1) - 1)
                    ?: 0

                document = loadedDocument
                state = ReaderUiState.Ready(
                    readerKey = readerKey,
                    title = readerTitle,
                    format = format,
                    initialPageIndex = initialPageIndex,
                    pageCount = loadedDocument.pageCount,
                    chapters = loadedDocument.chapters,
                    pages = emptyMap(),
                    resumeMessage = initialPageIndex.takeIf { it > 0 }?.let { pageIndex ->
                        "Resumed on ${loadedDocument.chapters.chapterForPageIndex(pageIndex, loadedDocument.pageCount).title}, page ${pageIndex + 1}"
                    },
                    status = "Loading first pages",
                )
                renderPagesAround(firstVisiblePage = initialPageIndex, lookAhead = INITIAL_PAGE_PRELOAD)
            } catch (error: CancellationException) {
                throw error
            } catch (error: OutOfMemoryError) {
                memoryWarningState = "Low memory while opening reader."
                showPrepareError(error)
            } catch (error: IOException) {
                showPrepareError(error)
            } catch (error: IllegalArgumentException) {
                showPrepareError(error)
            } catch (error: SecurityException) {
                showPrepareError(error)
            }
        }
    }

    private fun showPrepareError(error: Throwable) {
        Timber.w(error, "Could not prepare native Library reader")
        lastReaderError = error.message ?: "Could not prepare this book for reading."
        state = ReaderUiState.Error(lastReaderError ?: "Could not prepare this book for reading.")
    }

    private suspend fun downloadToReaderCache(uri: Uri, filename: String): File = withContext(Dispatchers.IO) {
        val readerDir = File(requireContext().cacheDir, READER_CACHE_DIR).apply { mkdirs() }
        val target = File(readerDir, filename)
        var lastFailure: IOException? = null

        uri.downloadCandidates().forEachIndexed { index, candidate ->
            val candidateTarget = File(readerDir, "$filename.part")
            val request = Request.Builder()
                .url(candidate.toString())
                .addJellyfinAuthorizationIfNeeded(candidate)
                .build()
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
                                coroutineContext.ensureActive()
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
                    if (bytesCopied <= 0L) throw IOException("Library reader download produced an empty file")
                }
                if (target.exists()) target.delete()
                if (!candidateTarget.renameTo(target)) {
                    candidateTarget.copyTo(target, overwrite = true)
                    candidateTarget.delete()
                }
                return@withContext target
            }.onFailure { error ->
                if (error is CancellationException) throw error
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

    private fun Request.Builder.addJellyfinAuthorizationIfNeeded(uri: Uri): Request.Builder {
        val jellyfinAuthority = apiClient.baseUrl?.let(Uri::parse)?.authority
        val accessToken = apiClient.accessToken
        if (jellyfinAuthority != null && uri.authority == jellyfinAuthority && accessToken != null) {
            header(
                "Authorization",
                AuthorizationHeaderBuilder.buildHeader(
                    clientName = apiClient.clientInfo.name,
                    clientVersion = apiClient.clientInfo.version,
                    deviceId = apiClient.deviceInfo.id,
                    deviceName = apiClient.deviceInfo.name,
                    accessToken = accessToken,
                ),
            )
        }
        return this
    }

    private fun renderPagesAround(firstVisiblePage: Int, lookAhead: Int = PAGE_LOOKAHEAD) {
        val loadedDocument = document ?: return
        val ready = state as? ReaderUiState.Ready ?: return
        val generation = readerGeneration
        val endExclusive = (firstVisiblePage + lookAhead).coerceAtMost(loadedDocument.pageCount)
        val startInclusive = (firstVisiblePage - bitmapCachePolicy.lookBehindPages).coerceAtLeast(0)
        val missingPages = (startInclusive until endExclusive).filterNot(renderedPages::containsKey)
        if (missingPages.isEmpty()) return

        state = ready.copy(status = "Loading pages")
        cancelActiveRendering()
        renderJob = viewLifecycleOwner.lifecycleScope.launch {
            val currentSettings = settings
            val targetWidth = (TARGET_PAGE_WIDTH_PX * currentSettings.zoomScale).roundToInt().coerceAtLeast(1)
            val results = withContext(Dispatchers.IO) {
                missingPages.map { pageIndex ->
                    renderPageSafely(
                        document = loadedDocument,
                        pageIndex = pageIndex,
                        targetWidth = targetWidth,
                        settings = currentSettings,
                    )
                }
            }
            if (generation != readerGeneration || document !== loadedDocument) {
                results.recycleImages()
                return@launch
            }

            if (results.any(PageRenderResult::memoryPressure)) {
                clearRenderedPages()
                memoryWarningState = "Memory pressure while rendering; cache cleared."
            }
            results.forEach { result ->
                renderedPages[result.pageIndex] = result.page
                lastRenderDurationMs = result.durationMs
                result.errorMessage?.let { message -> lastReaderError = message }
            }

            evictRenderedImagesAround(firstVisiblePage)
            state = ReaderUiState.Ready(
                readerKey = ready.readerKey,
                title = ready.title,
                format = ready.format,
                initialPageIndex = ready.initialPageIndex,
                pageCount = loadedDocument.pageCount,
                chapters = ready.chapters,
                pages = renderedPages.toSortedMap(),
                resumeMessage = ready.resumeMessage,
                status = null,
            )
        }
    }

    private suspend fun renderPageSafely(
        document: ReaderDocument,
        pageIndex: Int,
        targetWidth: Int,
        settings: LibraryReaderSettings,
    ): PageRenderResult {
        coroutineContext.ensureActive()
        val startedAtMs = System.currentTimeMillis()
        val renderedPage = runCatching {
            withTimeoutOrNull(PAGE_RENDER_TIMEOUT_MS) {
                document.renderPage(
                    index = pageIndex,
                    targetWidth = targetWidth,
                    settings = settings,
                )
            } ?: ReaderPage.Error("PTV Books timed out while rendering this page. Try Retry or reopen the reader.")
        }.getOrElse { error ->
            if (error is CancellationException) throw error
            Timber.w(error, "PTV Books could not render page $pageIndex")
            val message = when (error) {
                is OutOfMemoryError -> "PTV Books ran low on memory while rendering this page. The cache was cleared; try Retry."
                else -> "PTV Books could not render this page. The file may be corrupt or too large."
            }
            ReaderPage.Error(message)
        }
        val durationMs = (System.currentTimeMillis() - startedAtMs).coerceAtLeast(0)
        val errorMessage = (renderedPage as? ReaderPage.Error)?.message
        return PageRenderResult(
            pageIndex = pageIndex,
            page = renderedPage,
            durationMs = durationMs,
            errorMessage = errorMessage,
            memoryPressure = errorMessage?.contains("low on memory", ignoreCase = true) == true,
        )
    }

    @Composable
    private fun ReaderPages(
        ready: ReaderUiState.Ready,
        settings: LibraryReaderSettings,
        onSettingsChange: (LibraryReaderSettings) -> Unit,
    ) {
        if (settings.singlePageMode) {
            SinglePageReader(
                ready = ready,
                settings = settings,
                onSettingsChange = onSettingsChange,
            )
            return
        }

        val listState = rememberLazyListState(initialFirstVisibleItemIndex = ready.initialPageIndex)
        val scope = rememberCoroutineScope()
        var controlsVisible by rememberSaveable(ready.readerKey) { mutableStateOf(true) }
        var showToc by rememberSaveable("${ready.readerKey}-toc") { mutableStateOf(false) }
        var showStatus by rememberSaveable("${ready.readerKey}-status") { mutableStateOf(false) }
        fun jumpToPage(pageIndex: Int) {
            scope.launch {
                listState.animateScrollToItem(pageIndex.coerceIn(0, ready.pageCount.coerceAtLeast(1) - 1))
            }
        }

        LaunchedEffect(listState.firstVisibleItemIndex, ready.pageCount) {
            val pageIndex = listState.firstVisibleItemIndex.coerceIn(0, ready.pageCount.coerceAtLeast(1) - 1)
            renderPagesAround(pageIndex)
            saveResume(ready, pageIndex)
        }

        LaunchedEffect(
            controlsVisible,
            listState.firstVisibleItemIndex,
            ready.readerKey,
            showToc,
            showStatus,
        ) {
            if (controlsVisible && !showToc && !showStatus) {
                delay(READER_CONTROLS_AUTO_HIDE_MS)
                controlsVisible = false
            }
        }

        Box(modifier = Modifier.fillMaxSize()) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items((0 until ready.pageCount).toList()) { pageIndex ->
                    ReaderPageContent(
                        page = ready.pages[pageIndex],
                        pageIndex = pageIndex,
                        settings = settings,
                        onRetry = { retryPage(pageIndex) },
                    )
                }
            }

            ready.status?.let { status ->
                ReaderStatusBubble(
                    modifier = Modifier.align(Alignment.BottomCenter),
                    status = status,
                )
            }

            if (showToc) {
                ReaderTocPanel(
                    modifier = Modifier.align(Alignment.TopCenter),
                    chapters = ready.chapters,
                    currentPageIndex = listState.firstVisibleItemIndex,
                    onChapterSelected = { chapter ->
                        showToc = false
                        jumpToPage(chapter.startPageIndex)
                    },
                )
            }

            if (showStatus) {
                ReaderDebugStatusPanel(
                    modifier = Modifier.align(Alignment.TopCenter),
                    rows = readerDebugRows(ready, listState.firstVisibleItemIndex),
                    onDismiss = { showStatus = false },
                )
            }

            if (controlsVisible) {
                ready.resumeMessage?.let { message ->
                    ReaderResumeBanner(
                        modifier = Modifier.align(Alignment.TopCenter),
                        message = message,
                    )
                }
                ReaderControlsOverlay(
                    modifier = Modifier.align(Alignment.BottomCenter),
                    pageIndex = listState.firstVisibleItemIndex,
                    pageCount = ready.pageCount,
                    currentChapter = ready.chapterForPage(listState.firstVisibleItemIndex),
                    format = ready.format,
                    settings = settings,
                    onSettingsChange = onSettingsChange,
                    onPageSelected = ::jumpToPage,
                    onToggleToc = { showToc = !showToc },
                    onToggleStatus = { showStatus = !showStatus },
                    onToggleControls = { controlsVisible = false },
                )
            } else {
                ReaderCollapsedControls(
                    modifier = Modifier.align(Alignment.BottomCenter),
                    pageIndex = listState.firstVisibleItemIndex,
                    pageCount = ready.pageCount,
                    currentChapter = ready.chapterForPage(listState.firstVisibleItemIndex),
                    onExpand = { controlsVisible = true },
                )
            }
        }
    }

    @Composable
    private fun SinglePageReader(
        ready: ReaderUiState.Ready,
        settings: LibraryReaderSettings,
        onSettingsChange: (LibraryReaderSettings) -> Unit,
    ) {
        var pageIndex by rememberSaveable(ready.readerKey) { mutableStateOf(ready.initialPageIndex) }
        var controlsVisible by rememberSaveable("${ready.readerKey}-controls") { mutableStateOf(true) }
        var showToc by rememberSaveable("${ready.readerKey}-toc") { mutableStateOf(false) }
        var showStatus by rememberSaveable("${ready.readerKey}-status") { mutableStateOf(false) }
        fun jumpToPage(nextPageIndex: Int) {
            pageIndex = nextPageIndex.coerceIn(0, ready.pageCount.coerceAtLeast(1) - 1)
        }

        LaunchedEffect(pageIndex, ready.pageCount) {
            val safePageIndex = pageIndex.coerceIn(0, ready.pageCount.coerceAtLeast(1) - 1)
            renderPagesAround(safePageIndex, lookAhead = 1)
            saveResume(ready, safePageIndex)
        }

        LaunchedEffect(controlsVisible, pageIndex, ready.readerKey, showToc, showStatus) {
            if (controlsVisible && !showToc && !showStatus) {
                delay(READER_CONTROLS_AUTO_HIDE_MS)
                controlsVisible = false
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .focusable()
                .onKeyEvent { keyEvent ->
                    if (keyEvent.nativeKeyEvent.action != KeyEvent.ACTION_UP) return@onKeyEvent false
                    when (keyEvent.nativeKeyEvent.keyCode) {
                        KeyEvent.KEYCODE_DPAD_LEFT,
                        KeyEvent.KEYCODE_PAGE_UP,
                        -> {
                            jumpToPage(if (settings.rightToLeftManga) pageIndex + 1 else pageIndex - 1)
                            true
                        }
                        KeyEvent.KEYCODE_DPAD_RIGHT,
                        KeyEvent.KEYCODE_PAGE_DOWN,
                        KeyEvent.KEYCODE_SPACE,
                        -> {
                            jumpToPage(if (settings.rightToLeftManga) pageIndex - 1 else pageIndex + 1)
                            true
                        }
                        KeyEvent.KEYCODE_DPAD_CENTER,
                        KeyEvent.KEYCODE_ENTER,
                        -> {
                            controlsVisible = !controlsVisible
                            true
                        }
                        else -> false
                    }
                },
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 10.dp, vertical = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                ReaderPageContent(
                    page = ready.pages[pageIndex],
                    pageIndex = pageIndex,
                    settings = settings,
                    onRetry = { retryPage(pageIndex) },
                )
            }
            ReaderTapZones(
                onPrevious = { jumpToPage(pageIndex - 1) },
                onToggleControls = { controlsVisible = !controlsVisible },
                onNext = { jumpToPage(pageIndex + 1) },
            )
            if (controlsVisible) {
                Row(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 74.dp)
                        .background(PiggieTvColors.PanelHigh.copy(alpha = 0.90f), MaterialTheme.shapes.medium)
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(
                        enabled = pageIndex > 0,
                        onClick = { jumpToPage(pageIndex - 1) },
                    ) {
                        Text(text = "Previous", color = PiggieTvColors.FocusSoft)
                    }
                    Text(
                        text = "${pageIndex + 1} / ${ready.pageCount}",
                        color = PiggieTvColors.TextPrimary,
                        style = MaterialTheme.typography.caption,
                    )
                    TextButton(
                        enabled = pageIndex < ready.pageCount - 1,
                        onClick = { jumpToPage(pageIndex + 1) },
                    ) {
                        Text(text = "Next", color = PiggieTvColors.FocusSoft)
                    }
                }
            }
            ready.status?.let { status ->
                ReaderStatusBubble(
                    modifier = Modifier.align(Alignment.BottomCenter),
                    status = status,
                )
            }
            if (showToc) {
                ReaderTocPanel(
                    modifier = Modifier.align(Alignment.TopCenter),
                    chapters = ready.chapters,
                    currentPageIndex = pageIndex,
                    onChapterSelected = { chapter ->
                        showToc = false
                        jumpToPage(chapter.startPageIndex)
                    },
                )
            }
            if (showStatus) {
                ReaderDebugStatusPanel(
                    modifier = Modifier.align(Alignment.TopCenter),
                    rows = readerDebugRows(ready, pageIndex),
                    onDismiss = { showStatus = false },
                )
            }
            if (controlsVisible) {
                ready.resumeMessage?.let { message ->
                    ReaderResumeBanner(
                        modifier = Modifier.align(Alignment.TopCenter),
                        message = message,
                    )
                }
                ReaderControlsOverlay(
                    modifier = Modifier.align(Alignment.BottomCenter),
                    pageIndex = pageIndex,
                    pageCount = ready.pageCount,
                    currentChapter = ready.chapterForPage(pageIndex),
                    format = ready.format,
                    settings = settings,
                    onSettingsChange = onSettingsChange,
                    onPageSelected = ::jumpToPage,
                    onToggleToc = { showToc = !showToc },
                    onToggleStatus = { showStatus = !showStatus },
                    onToggleControls = { controlsVisible = false },
                )
            } else {
                ReaderCollapsedControls(
                    modifier = Modifier.align(Alignment.BottomCenter),
                    pageIndex = pageIndex,
                    pageCount = ready.pageCount,
                    currentChapter = ready.chapterForPage(pageIndex),
                    onExpand = { controlsVisible = true },
                )
            }
        }
    }

    @Composable
    private fun ReaderCollapsedControls(
        pageIndex: Int,
        pageCount: Int,
        currentChapter: LibraryReaderChapter?,
        onExpand: () -> Unit,
        modifier: Modifier = Modifier,
    ) {
        Surface(
            modifier = modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            color = PiggieTvColors.PanelHigh.copy(alpha = 0.84f),
            shape = MaterialTheme.shapes.medium,
        ) {
            Row(
                modifier = Modifier.padding(start = 12.dp, end = 6.dp, top = 5.dp, bottom = 5.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f, fill = false)) {
                    Text(
                        text = "${pageIndex + 1} / ${pageCount.coerceAtLeast(1)}",
                        color = PiggieTvColors.TextPrimary,
                        style = MaterialTheme.typography.caption,
                        fontWeight = FontWeight.Bold,
                    )
                    currentChapter?.title?.takeIf(String::isNotBlank)?.let { chapterTitle ->
                        Text(
                            text = chapterTitle,
                            color = PiggieTvColors.TextSecondary,
                            style = MaterialTheme.typography.caption,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                IconButton(onClick = onExpand) {
                    Icon(
                        Icons.Outlined.Settings,
                        contentDescription = "Show reader controls",
                        tint = PiggieTvColors.FocusSoft,
                    )
                }
            }
        }
    }

    @Composable
    private fun ReaderPageContent(
        page: ReaderPage?,
        pageIndex: Int,
        settings: LibraryReaderSettings,
        onRetry: () -> Unit,
    ) {
        when (page) {
            is ReaderPage.Image -> {
                val scrollState = rememberScrollState()
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(scrollState),
                    contentAlignment = Alignment.Center,
                ) {
                    Image(
                        bitmap = page.bitmap.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier.fillMaxWidth(),
                        contentScale = when (settings.imageFitMode) {
                            LibraryReaderFitMode.WIDTH -> ContentScale.FillWidth
                            LibraryReaderFitMode.HEIGHT -> ContentScale.Fit
                        },
                    )
                }
            }
            is ReaderPage.Text -> Text(
                text = page.text,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(settings.pageBackgroundColor())
                    .padding((18 * settings.marginScale).roundToInt().dp),
                color = settings.textColor(),
                fontSize = (16 * settings.fontScale).sp,
                lineHeight = (23 * settings.fontScale * settings.lineSpacingScale).sp,
            )
            is ReaderPage.Error -> Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(PiggieTvColors.PanelHigh)
                    .padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = page.message,
                    color = PiggieTvColors.TextPrimary,
                    style = MaterialTheme.typography.body1,
                )
                TextButton(onClick = onRetry) {
                    Text(text = "Retry", color = PiggieTvColors.FocusSoft)
                }
            }
            null -> PagePlaceholder(pageIndex)
        }
    }

    @Composable
    private fun ReaderTapZones(
        onPrevious: () -> Unit,
        onToggleControls: () -> Unit,
        onNext: () -> Unit,
    ) {
        Row(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxSize()
                    .clickable(onClick = onPrevious),
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxSize()
                    .clickable(onClick = onToggleControls),
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxSize()
                    .clickable(onClick = onNext),
            )
        }
    }

    @Composable
    private fun ReaderStatusBubble(modifier: Modifier, status: String) {
        Row(
            modifier = modifier
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

    @Composable
    private fun ReaderControlsOverlay(
        modifier: Modifier,
        pageIndex: Int,
        pageCount: Int,
        currentChapter: LibraryReaderChapter,
        format: LibraryBookFormat,
        settings: LibraryReaderSettings,
        onSettingsChange: (LibraryReaderSettings) -> Unit,
        onPageSelected: (Int) -> Unit,
        onToggleToc: () -> Unit,
        onToggleStatus: () -> Unit,
        onToggleControls: () -> Unit,
    ) {
        val progressPercent = (calculateLibraryProgress(pageIndex, pageCount) * 100f).roundToInt()
        Column(
            modifier = modifier
                .padding(10.dp)
                .background(PiggieTvColors.PanelHigh.copy(alpha = 0.92f), MaterialTheme.shapes.medium)
                .padding(horizontal = 10.dp, vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = "${currentChapter.title}  |  Page ${(pageIndex + 1).coerceAtMost(pageCount)} of $pageCount  |  $progressPercent%",
                color = PiggieTvColors.TextPrimary,
                style = MaterialTheme.typography.caption,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Slider(
                value = pageIndex.toFloat().coerceIn(0f, (pageCount - 1).coerceAtLeast(0).toFloat()),
                onValueChange = { value -> onPageSelected(value.roundToInt()) },
                valueRange = 0f..(pageCount - 1).coerceAtLeast(0).toFloat(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { onPageSelected(0) }) {
                    Text(text = "Start", color = PiggieTvColors.FocusSoft)
                }
                TextButton(onClick = { onPageSelected(pageCount - 1) }) {
                    Text(text = "End", color = PiggieTvColors.FocusSoft)
                }
                if (format == LibraryBookFormat.EPUB) {
                    TextButton(onClick = onToggleToc) {
                        Text(text = "Chapters", color = PiggieTvColors.FocusSoft)
                    }
                }
                TextButton(onClick = onToggleStatus) {
                    Text(text = "Status", color = PiggieTvColors.FocusSoft)
                }
                TextButton(onClick = { onSettingsChange(settings.copy(theme = settings.theme.next())) }) {
                    Text(text = settings.theme.label(), color = PiggieTvColors.FocusSoft)
                }
                if (format in setOf(LibraryBookFormat.EPUB, LibraryBookFormat.TXT)) {
                    TextButton(onClick = { onSettingsChange(settings.copy(fontScale = settings.fontScale - FONT_STEP)) }) {
                        Text(text = "A-", color = PiggieTvColors.FocusSoft)
                    }
                    TextButton(onClick = { onSettingsChange(settings.copy(fontScale = settings.fontScale + FONT_STEP)) }) {
                        Text(text = "A+", color = PiggieTvColors.FocusSoft)
                    }
                    TextButton(onClick = {
                        val nextSpacing = when {
                            settings.lineSpacingScale >= LibraryReaderSettings.MAX_LINE_SPACING_SCALE -> LibraryReaderSettings.MIN_LINE_SPACING_SCALE
                            else -> settings.lineSpacingScale + LINE_STEP
                        }
                        onSettingsChange(settings.copy(lineSpacingScale = nextSpacing))
                    }) {
                        Text(text = "Lines", color = PiggieTvColors.FocusSoft)
                    }
                }
                if (format in setOf(LibraryBookFormat.PDF, LibraryBookFormat.CBZ)) {
                    TextButton(onClick = { onSettingsChange(settings.copy(zoomScale = settings.zoomScale - ZOOM_STEP)) }) {
                        Text(text = "Zoom-", color = PiggieTvColors.FocusSoft)
                    }
                    TextButton(onClick = { onSettingsChange(settings.copy(zoomScale = settings.zoomScale + ZOOM_STEP)) }) {
                        Text(text = "Zoom+", color = PiggieTvColors.FocusSoft)
                    }
                    TextButton(onClick = { onSettingsChange(settings.copy(imageFitMode = settings.imageFitMode.next())) }) {
                        Text(text = settings.imageFitMode.label(), color = PiggieTvColors.FocusSoft)
                    }
                    TextButton(onClick = { onSettingsChange(settings.copy(singlePageMode = !settings.singlePageMode)) }) {
                        Text(text = if (settings.singlePageMode) "Scroll" else "Single", color = PiggieTvColors.FocusSoft)
                    }
                }
                if (format == LibraryBookFormat.CBZ) {
                    TextButton(onClick = { onSettingsChange(settings.copy(rightToLeftManga = !settings.rightToLeftManga)) }) {
                        Text(text = if (settings.rightToLeftManga) "RTL" else "LTR", color = PiggieTvColors.FocusSoft)
                    }
                }
                TextButton(onClick = onToggleControls) {
                    Text(text = "Hide", color = PiggieTvColors.FocusSoft)
                }
            }
        }
    }

    @Composable
    private fun ReaderTocPanel(
        modifier: Modifier,
        chapters: List<LibraryReaderChapter>,
        currentPageIndex: Int,
        onChapterSelected: (LibraryReaderChapter) -> Unit,
    ) {
        Surface(
            modifier = modifier
                .fillMaxWidth()
                .padding(14.dp),
            color = PiggieTvColors.PanelHigh.copy(alpha = 0.96f),
            shape = MaterialTheme.shapes.medium,
        ) {
            LazyColumn(
                modifier = Modifier.height(280.dp),
                contentPadding = PaddingValues(10.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(chapters, key = LibraryReaderChapter::index) { chapter ->
                    TextButton(onClick = { onChapterSelected(chapter) }) {
                        Text(
                            text = chapter.title,
                            color = if (chapter.containsPage(currentPageIndex)) PiggieTvColors.FocusSoft else PiggieTvColors.TextPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }

    @Composable
    private fun ReaderDebugStatusPanel(
        modifier: Modifier,
        rows: List<Pair<String, String>>,
        onDismiss: () -> Unit,
    ) {
        Surface(
            modifier = modifier
                .fillMaxWidth()
                .padding(14.dp),
            color = PiggieTvColors.PanelHigh.copy(alpha = 0.97f),
            shape = MaterialTheme.shapes.medium,
        ) {
            LazyColumn(
                modifier = Modifier.height(330.dp),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "PTV Reader Status",
                            modifier = Modifier.weight(1f),
                            color = PiggieTvColors.TextPrimary,
                            style = MaterialTheme.typography.subtitle2,
                            fontWeight = FontWeight.Bold,
                        )
                        TextButton(onClick = onDismiss) {
                            Text(text = "Close", color = PiggieTvColors.FocusSoft)
                        }
                    }
                }
                items(rows) { row ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(
                            text = row.first,
                            modifier = Modifier.weight(0.42f),
                            color = PiggieTvColors.TextSecondary,
                            style = MaterialTheme.typography.caption,
                        )
                        Text(
                            text = row.second,
                            modifier = Modifier.weight(0.58f),
                            color = PiggieTvColors.TextPrimary,
                            style = MaterialTheme.typography.caption,
                        )
                    }
                }
            }
        }
    }

    @Composable
    private fun ReaderResumeBanner(modifier: Modifier, message: String) {
        Surface(
            modifier = modifier.padding(top = 8.dp),
            color = PiggieTvColors.PanelHigh.copy(alpha = 0.92f),
            shape = MaterialTheme.shapes.medium,
        ) {
            Text(
                text = message,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                color = PiggieTvColors.TextPrimary,
                style = MaterialTheme.typography.caption,
            )
        }
    }

    private fun updateSettings(updatedSettings: LibraryReaderSettings) {
        settings = updatedSettings.normalized()
        readerStore.saveSettings(settings)
        clearRenderedPages()
        (state as? ReaderUiState.Ready)?.let { ready ->
            renderPagesAround(ready.initialPageIndex)
        }
    }

    private fun retryPage(pageIndex: Int) {
        (renderedPages.remove(pageIndex) as? ReaderPage.Image)?.bitmap?.recycle()
        (state as? ReaderUiState.Ready)?.let { ready ->
            state = ready.copy(pages = renderedPages.toSortedMap(), status = "Retrying page")
            renderPagesAround(pageIndex, lookAhead = 1)
        }
    }

    private fun readerDebugRows(ready: ReaderUiState.Ready, pageIndex: Int): List<Pair<String, String>> {
        val chapter = ready.chapterForPage(pageIndex)
        val bitmapBytes = ready.pages.bitmapBytes()
        return listOf(
            "Format" to ready.format.label,
            "Current chapter" to "${chapter.index + 1} / ${ready.chapters.size.coerceAtLeast(1)} - ${chapter.title}",
            "Current page" to "${(pageIndex + 1).coerceAtMost(ready.pageCount)} / ${ready.pageCount}",
            "Progress" to "${(calculateLibraryProgress(pageIndex, ready.pageCount) * 100f).roundToInt()}%",
            "Cache budget" to bitmapCachePolicy.budgetBytes.readableBytes(),
            "Cached pages" to "${ready.pages.size} (${bitmapBytes.readableBytes()})",
            "Prefetch window" to "${bitmapCachePolicy.lookBehindPages} back / ${bitmapCachePolicy.lookAheadPages} ahead",
            "Last render" to (lastRenderDurationMs?.let { "$it ms" } ?: "Unavailable"),
            "Last error" to (lastReaderError ?: "None"),
            "Resume source" to "Local",
            "Memory state" to memoryWarningState,
        )
    }

    private fun saveResume(ready: ReaderUiState.Ready, pageIndex: Int) {
        val chapter = ready.chapterForPage(pageIndex)
        val progress = calculateLibraryProgress(pageIndex, ready.pageCount)
        val resumeState = LibraryReaderResumeState(
            readerKey = ready.readerKey,
            title = ready.title,
            pageIndex = pageIndex,
            pageCount = ready.pageCount,
            progress = progress,
            updatedAtMs = System.currentTimeMillis(),
            chapterIndex = chapter.index,
            chapterTitle = chapter.title,
            chapterPageIndex = pageIndex - chapter.startPageIndex,
        )
        readerStore.saveResume(resumeState)
        syncJellyfinProgressIfNeeded(resumeState)
    }

    private fun syncJellyfinProgressIfNeeded(resumeState: LibraryReaderResumeState) {
        val itemId = jellyfinItemId?.toUUIDOrNull() ?: return
        val nowMs = System.currentTimeMillis()
        val pageDelta = kotlin.math.abs(resumeState.pageIndex - lastServerProgressPageIndex)
        val timeDeltaMs = nowMs - lastServerProgressSyncAtMs
        if (
            lastServerProgressPageIndex >= 0 &&
            pageDelta < SERVER_PROGRESS_PAGE_DELTA &&
            timeDeltaMs < SERVER_PROGRESS_MIN_INTERVAL_MS
        ) {
            return
        }

        lastServerProgressPageIndex = resumeState.pageIndex
        lastServerProgressSyncAtMs = nowMs
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            runCatching {
                apiClient.itemsApi.updateItemUserData(
                    itemId = itemId,
                    data = UpdateUserItemDataDto(
                        playedPercentage = (resumeState.progress.coerceIn(0f, 1f) * 100.0).coerceIn(0.0, 100.0),
                        played = resumeState.progress >= SERVER_PROGRESS_PLAYED_THRESHOLD,
                    ),
                )
            }.onFailure { error ->
                Timber.w(error, "PTV Books could not sync Jellyfin reading progress")
            }
        }
    }

    private fun ReaderUiState.Ready.chapterForPage(pageIndex: Int): LibraryReaderChapter =
        chapters.chapterForPageIndex(pageIndex, pageCount)

    private fun List<LibraryReaderChapter>.chapterForPageIndex(
        pageIndex: Int,
        fallbackPageCount: Int,
    ): LibraryReaderChapter =
        firstOrNull { chapter -> chapter.containsPage(pageIndex) }
            ?: lastOrNull { chapter -> chapter.startPageIndex <= pageIndex }
            ?: LibraryReaderChapter(
                index = 0,
                title = "Pages",
                startPageIndex = 0,
                pageCount = fallbackPageCount,
            )

    private fun cancelActiveRendering() {
        renderJob?.cancel()
        renderJob = null
    }

    private fun clearRenderedPages() {
        renderedPages.values.forEach { page -> (page as? ReaderPage.Image)?.bitmap?.recycle() }
        renderedPages.clear()
    }

    private fun Map<Int, ReaderPage>.bitmapBytes(): Long =
        values.sumOf { page -> (page as? ReaderPage.Image)?.bitmap?.byteCount?.toLong() ?: 0L }

    private fun List<PageRenderResult>.recycleImages() {
        forEach { result -> (result.page as? ReaderPage.Image)?.bitmap?.recycle() }
    }

    private fun evictRenderedImagesAround(anchorPageIndex: Int) {
        val minPage = (anchorPageIndex - bitmapCachePolicy.lookBehindPages).coerceAtLeast(0)
        val maxPage = anchorPageIndex + bitmapCachePolicy.lookAheadPages
        var bitmapBytes = renderedPages.bitmapBytes()
        val startedOverBudget = bitmapBytes > bitmapCachePolicy.budgetBytes

        renderedPages.keys
            .filter { pageIndex -> pageIndex < minPage || pageIndex > maxPage }
            .sortedByDescending { pageIndex -> kotlin.math.abs(pageIndex - anchorPageIndex) }
            .forEach { pageIndex ->
                val page = renderedPages[pageIndex]
                if (page is ReaderPage.Image && bitmapBytes > bitmapCachePolicy.budgetBytes) {
                    bitmapBytes -= page.bitmap.byteCount.toLong()
                    renderedPages.remove(pageIndex)
                    page.bitmap.recycle()
                }
            }
        memoryWarningState = when {
            renderedPages.bitmapBytes() > bitmapCachePolicy.budgetBytes -> "Nearby pages exceed cache budget."
            startedOverBudget -> "Cache evicted distant pages."
            bitmapCachePolicy.lookAheadPages <= 1 -> "Low-memory cache window."
            else -> "OK"
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
            val readerKey: String,
            val title: String,
            val format: LibraryBookFormat,
            val initialPageIndex: Int,
            val pageCount: Int,
            val chapters: List<LibraryReaderChapter>,
            val pages: Map<Int, ReaderPage>,
            val resumeMessage: String?,
            val status: String?,
        ) : ReaderUiState
        data class Error(val message: String) : ReaderUiState
    }

    private sealed interface ReaderPage {
        data class Image(val bitmap: Bitmap) : ReaderPage
        data class Text(
            val text: String,
            val chapterIndex: Int? = null,
            val chapterTitle: String? = null,
        ) : ReaderPage
        data class Error(val message: String) : ReaderPage
    }

    private data class PageRenderResult(
        val pageIndex: Int,
        val page: ReaderPage,
        val durationMs: Long,
        val errorMessage: String?,
        val memoryPressure: Boolean,
    )

    private sealed interface ReaderDocument : AutoCloseable {
        val pageCount: Int
        val chapters: List<LibraryReaderChapter>
        fun renderPage(index: Int, targetWidth: Int, settings: LibraryReaderSettings): ReaderPage

        class Pdf(private val descriptor: ParcelFileDescriptor) : ReaderDocument {
            private val renderer = PdfRenderer(descriptor)
            override val pageCount: Int get() = renderer.pageCount
            override val chapters: List<LibraryReaderChapter>
                get() = singleChapter(pageCount)

            override fun renderPage(
                index: Int,
                targetWidth: Int,
                settings: LibraryReaderSettings,
            ): ReaderPage.Image = synchronized(renderer) {
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
            override val chapters: List<LibraryReaderChapter> = singleChapter(entries.size, "Comic pages")

            override fun renderPage(
                index: Int,
                targetWidth: Int,
                settings: LibraryReaderSettings,
            ): ReaderPage.Image {
                val actualIndex = if (settings.rightToLeftManga) entries.lastIndex - index else index
                val entry = requireNotNull(zipFile.getEntry(entries[actualIndex]))
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                zipFile.getInputStream(entry).use { input -> BitmapFactory.decodeStream(input, null, bounds) }
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                    throw IOException("PTV Books could not decode this comic page.")
                }
                val decodeOptions = BitmapFactory.Options().apply {
                    inSampleSize = calculateLibraryImageSampleSize(bounds.outWidth, targetWidth)
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                }
                val original = zipFile.getInputStream(entry).use { input ->
                    BitmapFactory.decodeStream(input, null, decodeOptions)
                } ?: throw IOException("PTV Books could not decode this comic page.")
                val scale = targetWidth.toFloat() / original.width.toFloat()
                val height = (original.height * scale).roundToInt().coerceAtLeast(1)
                val scaled = Bitmap.createScaledBitmap(original, targetWidth, height, true)
                if (scaled !== original) original.recycle()
                return ReaderPage.Image(scaled)
            }

            override fun close() = zipFile.close()
        }

        class TextPages(
            private val pages: List<ReaderPage.Text>,
            override val chapters: List<LibraryReaderChapter>,
        ) : ReaderDocument {
            override val pageCount: Int get() = pages.size
            override fun renderPage(
                index: Int,
                targetWidth: Int,
                settings: LibraryReaderSettings,
            ): ReaderPage.Text = pages[index]
            override fun close() = Unit
        }

        companion object {
            fun open(file: File, mimeType: String?, format: LibraryBookFormat): ReaderDocument = when {
                format == LibraryBookFormat.PDF || mimeType == "application/pdf" || file.extension.equals("pdf", ignoreCase = true) -> {
                    Pdf(ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY))
                }
                format == LibraryBookFormat.CBZ || mimeType in COMIC_MIME_TYPES || file.extension.lowercase() in setOf("cbz") -> {
                    val zipFile = ZipFile(file)
                    val entries = zipFile.entries().asSequence()
                        .map { it.name }
                        .filter(::isImagePath)
                        .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it })
                        .toList()
                    if (entries.isEmpty()) throw IOException("No readable comic pages found.")
                    ZipImages(zipFile, entries)
                }
                format == LibraryBookFormat.EPUB || mimeType == "application/epub+zip" || file.extension.equals("epub", ignoreCase = true) -> {
                    val epub = LibraryEpubReader.read(file, TEXT_PAGE_CHARS)
                    TextPages(
                        pages = epub.pages.map { page ->
                            ReaderPage.Text(
                                text = page.text,
                                chapterIndex = page.chapterIndex,
                                chapterTitle = page.chapterTitle,
                            )
                        },
                        chapters = epub.chapters,
                    )
                }
                format == LibraryBookFormat.TXT || mimeType == "text/plain" || file.extension.equals("txt", ignoreCase = true) -> {
                    val pages = file.readText().chunked(TEXT_PAGE_CHARS)
                    TextPages(
                        pages = pages.map { page -> ReaderPage.Text(page) },
                        chapters = singleChapter(pages.size, "Text"),
                    )
                }
                else -> throw IOException("This format is not available in the native reader yet.")
            }

            private fun isImagePath(path: String): Boolean =
                path.endsWith(".jpg", true) ||
                    path.endsWith(".jpeg", true) ||
                    path.endsWith(".png", true) ||
                    path.endsWith(".webp", true)

            private fun singleChapter(pageCount: Int, title: String = "Pages"): List<LibraryReaderChapter> =
                listOf(
                    LibraryReaderChapter(
                        index = 0,
                        title = title,
                        startPageIndex = 0,
                        pageCount = pageCount,
                    ),
                )
        }
    }

    private companion object {
        const val READER_CACHE_DIR = "library-reader"
        const val TARGET_PAGE_WIDTH_PX = 1200
        const val INITIAL_PAGE_PRELOAD = 10
        const val PAGE_LOOKAHEAD = 6
        const val TEXT_PAGE_CHARS = 2400
        const val DOCUMENT_OPEN_TIMEOUT_MS = 120_000L
        const val PAGE_RENDER_TIMEOUT_MS = 45_000L
        const val READER_CONTROLS_AUTO_HIDE_MS = 30_000L
        const val SERVER_PROGRESS_MIN_INTERVAL_MS = 15_000L
        const val SERVER_PROGRESS_PAGE_DELTA = 3
        const val SERVER_PROGRESS_PLAYED_THRESHOLD = 0.98f
        const val READER_CONNECT_TIMEOUT_SECONDS = 30L
        const val READER_READ_TIMEOUT_SECONDS = 180L
        const val READER_CALL_TIMEOUT_SECONDS = 600L
        const val OPDS_DOWNLOAD_PREFIX = "/opds/download/"
        const val DOWNLOAD_PREFIX = "/download/"
        const val FONT_STEP = 0.08f
        const val LINE_STEP = 0.12f
        const val ZOOM_STEP = 0.15f
        val COMIC_MIME_TYPES = setOf(
            "application/x-cbz",
            "application/vnd.comicbook+zip",
        )
    }
}

private fun LibraryReaderTheme.next(): LibraryReaderTheme = when (this) {
    LibraryReaderTheme.DARK -> LibraryReaderTheme.LIGHT
    LibraryReaderTheme.LIGHT -> LibraryReaderTheme.SEPIA
    LibraryReaderTheme.SEPIA -> LibraryReaderTheme.DARK
}

private fun LibraryReaderTheme.label(): String = when (this) {
    LibraryReaderTheme.DARK -> "Dark"
    LibraryReaderTheme.LIGHT -> "Light"
    LibraryReaderTheme.SEPIA -> "Sepia"
}

private fun LibraryReaderFitMode.next(): LibraryReaderFitMode = when (this) {
    LibraryReaderFitMode.WIDTH -> LibraryReaderFitMode.HEIGHT
    LibraryReaderFitMode.HEIGHT -> LibraryReaderFitMode.WIDTH
}

private fun LibraryReaderFitMode.label(): String = when (this) {
    LibraryReaderFitMode.WIDTH -> "Fit Width"
    LibraryReaderFitMode.HEIGHT -> "Fit Height"
}

private fun LibraryReaderSettings.pageBackgroundColor(): Color = when (theme) {
    LibraryReaderTheme.DARK -> Color(0xFF101418)
    LibraryReaderTheme.LIGHT -> Color(0xFFF8F5EF)
    LibraryReaderTheme.SEPIA -> Color(0xFFEBD9B8)
}

private fun LibraryReaderSettings.textColor(): Color = when (theme) {
    LibraryReaderTheme.DARK -> Color(0xFFEAF0F5)
    LibraryReaderTheme.LIGHT -> Color(0xFF201F1B)
    LibraryReaderTheme.SEPIA -> Color(0xFF2F251A)
}

private fun Long.readableBytes(): String {
    val megabytes = toDouble() / (1024.0 * 1024.0)
    return "${(megabytes * 10.0).roundToInt() / 10.0} MB"
}
