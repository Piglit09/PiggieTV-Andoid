@file:Suppress(
    "ArgumentListWrapping",
    "BinaryExpressionWrapping",
    "BlankLineBetweenWhenConditions",
    "ComplexCondition",
    "FunctionExpressionBody",
    "FunctionSignature",
    "LongMethod",
    "MagicNumber",
    "MaximumLineLength",
    "ParameterListWrapping",
    "TooManyFunctions",
)

package org.jellyfin.mobile.feature.library

import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.AlertDialog
import androidx.compose.material.Button
import androidx.compose.material.ButtonDefaults
import androidx.compose.material.Card
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.LinearProgressIndicator
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.material.TextField
import androidx.compose.material.TextFieldDefaults
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Search
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import org.jellyfin.mobile.R
import org.jellyfin.mobile.ui.utils.PiggieTvColors

@Composable
fun LibraryScreen(
    viewModel: LibraryViewModel,
    title: String = "Library",
    onDownload: (Uri, String, String) -> Unit,
    onRead: (Uri, String, String, String?, String?, String?) -> Unit,
    onBackHandlerChanged: ((() -> Boolean)?) -> Unit,
    onScrollHeaderCollapsedChange: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.uiState.collectAsState()
    val selectedBookId = (uiState as? LibraryUiState.Content)?.selectedBook?.id

    LaunchedEffect(Unit) {
        viewModel.load()
    }

    LaunchedEffect(selectedBookId, uiState is LibraryUiState.Loading, uiState is LibraryUiState.Error) {
        onScrollHeaderCollapsedChange(selectedBookId != null)
    }

    SideEffect {
        onBackHandlerChanged {
            val state = uiState
            if (state is LibraryUiState.Content && state.selectedBook != null) {
                viewModel.closeBook()
                true
            } else {
                false
            }
        }
    }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val layout = remember(maxWidth) { LibraryAdaptiveLayout.forWidth(maxWidth) }

        when (val state = uiState) {
            LibraryUiState.Loading -> LibraryLoading()
            LibraryUiState.LoginRequired -> LibraryLoginRequired(onRetry = { viewModel.load(force = true) })
            is LibraryUiState.Error -> LibraryError(message = state.message, onRetry = { viewModel.load(force = true) })
            is LibraryUiState.Content -> {
                val selectedBook = state.selectedBook
                if (selectedBook == null) {
                    LibraryHomeContent(
                        layout = layout,
                        title = title,
                        home = state.home,
                        onBookClick = viewModel::selectBook,
                        onRetry = { viewModel.load(force = true) },
                        onScrollHeaderCollapsedChange = onScrollHeaderCollapsedChange,
                    )
                } else {
                    BookDetail(
                        layout = layout,
                        book = selectedBook,
                        isLoading = state.isLoadingDetail,
                        onBack = viewModel::closeBook,
                        onDownload = onDownload,
                        onRead = onRead,
                        onStartOver = viewModel::clearProgress,
                        onToggleFavorite = viewModel::toggleFavorite,
                    )
                }
            }
        }
    }
}

@Composable
private fun LibraryHomeContent(
    layout: LibraryAdaptiveLayout,
    title: String,
    home: LibraryHome,
    onBookClick: (LibraryBook) -> Unit,
    onRetry: () -> Unit,
    onScrollHeaderCollapsedChange: (Boolean) -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    val filteredBooks = remember(home.allBooks, query) {
        home.allBooks.filterForQuery(query)
    }
    val listState = rememberLazyListState()
    val headerCollapsed by remember {
        derivedStateOf {
            listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 96
        }
    }

    LaunchedEffect(headerCollapsed) {
        onScrollHeaderCollapsedChange(headerCollapsed)
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = layout.edgePadding, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(Icons.AutoMirrored.Outlined.MenuBook, contentDescription = null, tint = PiggieTvColors.Focus, modifier = Modifier.size(32.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = title, color = PiggieTvColors.TextPrimary, style = MaterialTheme.typography.h5, fontWeight = FontWeight.ExtraBold)
                    Text(
                        text = home.sourceLabel,
                        color = if (home.isJellyfinBacked) PiggieTvColors.FocusSoft else PiggieTvColors.TextSecondary,
                        style = MaterialTheme.typography.caption,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                TextButton(onClick = onRetry) {
                    Text(text = "Refresh", color = PiggieTvColors.FocusSoft)
                }
            }
        }

        item {
            BookSearchField(
                query = query,
                onQueryChange = { query = it },
            )
        }

        if (query.isNotBlank()) {
            item {
                Text(
                    text = "Search results",
                    color = PiggieTvColors.TextPrimary,
                    style = MaterialTheme.typography.h6,
                    fontWeight = FontWeight.Bold,
                )
            }
            item {
                if (filteredBooks.isEmpty()) {
                    EmptyLibrary(message = "No reading items match \"$query\".")
                } else {
                    BookGrid(
                        books = filteredBooks,
                        layout = layout,
                        onBookClick = onBookClick,
                    )
                }
            }
        }

        if (home.continueReading.isNotEmpty()) {
            item {
                BookRow(
                    title = "Continue Reading",
                    books = home.continueReading,
                    posterWidth = layout.rowPosterWidth,
                    onBookClick = onBookClick,
                )
            }
        }

        if (home.recentBooks.isNotEmpty()) {
            item {
                BookRow(title = "Recently Added", books = home.recentBooks, posterWidth = layout.rowPosterWidth, onBookClick = onBookClick)
            }
        }

        if (home.favorites.isNotEmpty()) {
            item {
                BookRow(title = "Favorites", books = home.favorites, posterWidth = layout.rowPosterWidth, onBookClick = onBookClick)
            }
        }

        if (home.manga.isNotEmpty()) {
            item {
                BookRow(title = "Manga", books = home.manga, posterWidth = layout.rowPosterWidth, onBookClick = onBookClick)
            }
        }

        if (home.comics.isNotEmpty()) {
            item {
                BookRow(title = "Comics", books = home.comics, posterWidth = layout.rowPosterWidth, onBookClick = onBookClick)
            }
        }

        item {
            Text(text = "All Reading", color = PiggieTvColors.TextPrimary, style = MaterialTheme.typography.h6, fontWeight = FontWeight.Bold)
        }

        if (home.allBooks.isEmpty()) {
            item { EmptyLibrary() }
        } else {
            item {
                BookGrid(
                    books = home.allBooks,
                    layout = layout,
                    onBookClick = onBookClick,
                )
            }
        }

        item {
            FacetSummary(home = home)
        }
    }
}

@Composable
private fun BookSearchField(query: String, onQueryChange: (String) -> Unit) {
    TextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        placeholder = { Text(text = "Search books, comics, manga, authors, series, or genres") },
        leadingIcon = {
            Icon(Icons.Outlined.Search, contentDescription = null, tint = PiggieTvColors.FocusSoft)
        },
        colors = TextFieldDefaults.textFieldColors(
            textColor = PiggieTvColors.TextPrimary,
            backgroundColor = PiggieTvColors.PanelHigh,
            cursorColor = PiggieTvColors.Focus,
            focusedIndicatorColor = PiggieTvColors.Focus,
            unfocusedIndicatorColor = PiggieTvColors.Border,
            placeholderColor = PiggieTvColors.TextSecondary,
        ),
        shape = RoundedCornerShape(8.dp),
    )
}

@Composable
private fun BookRow(title: String, books: List<LibraryBook>, posterWidth: Dp, onBookClick: (LibraryBook) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(text = title, color = PiggieTvColors.TextPrimary, style = MaterialTheme.typography.h6, fontWeight = FontWeight.Bold)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(books, key = LibraryBook::id) { book ->
                BookCard(book = book, posterWidth = posterWidth, onClick = { onBookClick(book) })
            }
        }
    }
}

@Composable
private fun BookCard(book: LibraryBook, posterWidth: Dp, onClick: () -> Unit) {
    Column(modifier = Modifier.width(posterWidth), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(0.66f)
                .clip(RoundedCornerShape(8.dp))
                .clickable(onClick = onClick),
            backgroundColor = PiggieTvColors.PanelHigh,
            border = BorderStroke(1.dp, PiggieTvColors.Border),
            elevation = 0.dp,
        ) {
            Box {
                AsyncImage(
                    model = book.coverUrl,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                    placeholder = androidx.compose.ui.res.painterResource(R.drawable.ic_splash),
                    fallback = androidx.compose.ui.res.painterResource(R.drawable.ic_splash),
                )
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(1.dp),
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            BookFormatPill(book.format)
            if (book.isFavorite) {
                Icon(Icons.Outlined.Favorite, contentDescription = null, tint = PiggieTvColors.Focus, modifier = Modifier.size(15.dp))
            }
        }
        book.progress?.let { progress ->
            LinearProgressIndicator(
                progress = progress.progress.coerceIn(0f, 1f),
                modifier = Modifier.fillMaxWidth(),
                color = PiggieTvColors.Accent,
                backgroundColor = PiggieTvColors.Panel,
            )
        }
        Text(text = book.title, color = PiggieTvColors.TextPrimary, style = MaterialTheme.typography.body2, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
        book.subtitle?.let {
            Text(text = it, color = PiggieTvColors.TextSecondary, style = MaterialTheme.typography.caption, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun BookGrid(
    books: List<LibraryBook>,
    layout: LibraryAdaptiveLayout,
    onBookClick: (LibraryBook) -> Unit,
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(layout.gridMinWidth),
        modifier = Modifier.heightIn(max = layout.gridHeight),
        userScrollEnabled = false,
        verticalArrangement = Arrangement.spacedBy(layout.gridSpacing),
        horizontalArrangement = Arrangement.spacedBy(layout.gridSpacing),
    ) {
        items(books, key = LibraryBook::id) { book ->
            BookCard(book = book, posterWidth = layout.gridPosterWidth, onClick = { onBookClick(book) })
        }
    }
}

@Composable
private fun BookDetail(
    layout: LibraryAdaptiveLayout,
    book: LibraryBook,
    isLoading: Boolean,
    onBack: () -> Unit,
    onDownload: (Uri, String, String) -> Unit,
    onRead: (Uri, String, String, String?, String?, String?) -> Unit,
    onStartOver: (LibraryBook) -> Unit,
    onToggleFavorite: (LibraryBook) -> Unit,
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding(),
        contentPadding = PaddingValues(bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        item {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = layout.detailHeroHeight),
            ) {
                AsyncImage(
                    model = book.coverUrl,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                    placeholder = androidx.compose.ui.res.painterResource(R.drawable.ptv_splash_background),
                    fallback = androidx.compose.ui.res.painterResource(R.drawable.ptv_splash_background),
                )
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                listOf(
                                    PiggieTvColors.Night.copy(alpha = 0.18f),
                                    PiggieTvColors.Panel.copy(alpha = 0.80f),
                                    PiggieTvColors.Night,
                                ),
                            ),
                        ),
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = layout.edgePadding / 2, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = null, tint = PiggieTvColors.TextPrimary)
                    }
                    Spacer(modifier = Modifier.weight(1f))
                    if (isLoading) {
                        CircularProgressIndicator(modifier = Modifier.size(22.dp), color = PiggieTvColors.Focus, strokeWidth = 2.dp)
                    }
                }
            }
        }

        item {
            Row(
                modifier = Modifier
                    .padding(horizontal = layout.edgePadding)
                    .fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.Top,
            ) {
                BookCover(book = book, width = layout.detailPosterWidth)
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    Text(text = book.title, color = PiggieTvColors.TextPrimary, style = MaterialTheme.typography.h5, fontWeight = FontWeight.ExtraBold, maxLines = 4, overflow = TextOverflow.Ellipsis)
                    book.authors.takeIf(List<String>::isNotEmpty)?.let {
                        Text(text = it.joinToString(), color = PiggieTvColors.TextSecondary, style = MaterialTheme.typography.body2)
                    }
                    book.series?.let {
                        Text(text = it, color = PiggieTvColors.FocusSoft, style = MaterialTheme.typography.caption, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    BookMetadata(book = book)
                    BookActions(
                        book = book,
                        onDownload = onDownload,
                        onRead = onRead,
                        onStartOver = onStartOver,
                        onToggleFavorite = onToggleFavorite,
                    )
                }
            }
        }

        book.summary?.takeIf(String::isNotBlank)?.let { summary ->
            item {
                Text(
                    text = summary,
                    color = PiggieTvColors.TextSecondary,
                    style = MaterialTheme.typography.body1,
                    modifier = Modifier.padding(horizontal = layout.edgePadding),
                )
            }
        }
    }
}

@Composable
private fun BookMetadata(book: LibraryBook) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            BookFormatPill(book.format)
            book.fileSizeBytes?.let { size ->
                Text(text = size.toFileSizeLabel(), color = PiggieTvColors.TextSecondary, style = MaterialTheme.typography.caption)
            }
        }
        book.categories.takeIf(List<String>::isNotEmpty)?.let { categories ->
            Text(
                text = categories.take(MAX_DETAIL_CATEGORIES).joinToString("  /  "),
                color = PiggieTvColors.TextSecondary,
                style = MaterialTheme.typography.caption,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        book.progress?.let { progress ->
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = "Reading progress: ${progress.percent.coerceIn(0, 100)}%",
                    color = PiggieTvColors.TextSecondary,
                    style = MaterialTheme.typography.caption,
                )
                LinearProgressIndicator(
                    progress = progress.progress.coerceIn(0f, 1f),
                    modifier = Modifier.fillMaxWidth(),
                    color = PiggieTvColors.Accent,
                    backgroundColor = PiggieTvColors.Panel,
                )
            }
        }
    }
}

@Composable
private fun BookCover(book: LibraryBook, width: Dp) {
    Card(
        modifier = Modifier.width(width).aspectRatio(0.66f),
        shape = RoundedCornerShape(8.dp),
        backgroundColor = PiggieTvColors.PanelHigh,
        border = BorderStroke(1.dp, PiggieTvColors.Border),
        elevation = 0.dp,
    ) {
        AsyncImage(
            model = book.coverUrl,
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
            placeholder = androidx.compose.ui.res.painterResource(R.drawable.ic_splash),
            fallback = androidx.compose.ui.res.painterResource(R.drawable.ic_splash),
        )
    }
}

@Composable
private fun BookActions(
    book: LibraryBook,
    onDownload: (Uri, String, String) -> Unit,
    onRead: (Uri, String, String, String?, String?, String?) -> Unit,
    onStartOver: (LibraryBook) -> Unit,
    onToggleFavorite: (LibraryBook) -> Unit,
) {
    val link = book.primaryReaderLink
    val status = book.supportStatus
    var confirmStartOver by rememberSaveable(book.readerKey) { mutableStateOf(false) }

    if (confirmStartOver) {
        AlertDialog(
            onDismissRequest = { confirmStartOver = false },
            title = {
                Text(text = "Start over?", color = PiggieTvColors.TextPrimary)
            },
            text = {
                Text(
                    text = "PTV will clear the saved local reading position for this book and reopen it from the beginning.",
                    color = PiggieTvColors.TextSecondary,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmStartOver = false
                        onStartOver(book)
                        if (link != null && status == LibraryFormatSupport.NATIVE) {
                            onRead(
                                Uri.parse(link.href),
                                book.title,
                                book.downloadFilename(link),
                                link.type,
                                book.readerKey,
                                book.jellyfinItemId,
                            )
                        }
                    },
                ) {
                    Text(text = "Start Over", color = PiggieTvColors.FocusSoft)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmStartOver = false }) {
                    Text(text = "Cancel", color = PiggieTvColors.TextSecondary)
                }
            },
            backgroundColor = PiggieTvColors.PanelHigh,
            shape = RoundedCornerShape(8.dp),
        )
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { onToggleFavorite(book) }) {
                Icon(
                    if (book.isFavorite) Icons.Outlined.Favorite else Icons.Outlined.FavoriteBorder,
                    contentDescription = null,
                    tint = PiggieTvColors.FocusSoft,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(text = if (book.isFavorite) "Favorited" else "Favorite", color = PiggieTvColors.FocusSoft)
            }
            if (book.progress != null) {
                TextButton(
                    onClick = { confirmStartOver = true },
                ) {
                    Text(text = "Start Over", color = PiggieTvColors.FocusSoft)
                }
            }
        }

        if (link != null && status == LibraryFormatSupport.NATIVE) {
            Button(
                onClick = {
                    onRead(
                        Uri.parse(link.href),
                        book.title,
                        book.downloadFilename(link),
                        link.type,
                        book.readerKey,
                        book.jellyfinItemId,
                    )
                },
                colors = ButtonDefaults.buttonColors(backgroundColor = PiggieTvColors.Accent, contentColor = PiggieTvColors.Night),
                shape = MaterialTheme.shapes.medium,
            ) {
                Icon(Icons.AutoMirrored.Outlined.MenuBook, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text(text = if (book.progress != null) "Continue Reading" else "Open Reader", fontWeight = FontWeight.Bold)
            }
        } else {
            UnsupportedFormatNotice(format = book.format)
        }

        book.acquisitionLinks.firstOrNull()?.let { downloadLink ->
            Button(
                onClick = { onDownload(Uri.parse(downloadLink.href), book.title, book.downloadFilename(downloadLink)) },
                colors = ButtonDefaults.buttonColors(backgroundColor = PiggieTvColors.Focus, contentColor = PiggieTvColors.Night),
                shape = MaterialTheme.shapes.medium,
            ) {
                Icon(Icons.Outlined.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text(text = "Download", fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun UnsupportedFormatNotice(format: LibraryBookFormat) {
    val message = format.unsupportedMessage()
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = PiggieTvColors.PanelHigh,
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, PiggieTvColors.Border),
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(text = message.title, color = PiggieTvColors.TextPrimary, style = MaterialTheme.typography.subtitle2, fontWeight = FontWeight.Bold)
            Text(text = message.message, color = PiggieTvColors.TextSecondary, style = MaterialTheme.typography.caption)
        }
    }
}

@Composable
private fun FacetSummary(home: LibraryHome) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = PiggieTvColors.Panel.copy(alpha = 0.76f),
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, PiggieTvColors.Border),
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(text = "Browse", color = PiggieTvColors.TextPrimary, style = MaterialTheme.typography.subtitle1, fontWeight = FontWeight.Bold)
            Text(
                text = listOf(
                    "${home.authors.size} authors",
                    "${home.series.size} series",
                    "${home.collections.size} collections",
                    "${home.genres.size.coerceAtLeast(home.categories.size)} genres",
                ).joinToString("   "),
                color = PiggieTvColors.TextSecondary,
                style = MaterialTheme.typography.body2,
            )
            if (
                home.authors.isEmpty() &&
                home.series.isEmpty() &&
                home.collections.isEmpty() &&
                home.genres.isEmpty() &&
                home.categories.isEmpty()
            ) {
                Text(
                    text = if (home.isJellyfinBacked) {
                        "No author, series, collection, or genre metadata was returned by Jellyfin."
                    } else {
                        "No author, series, collection, or genre facets were returned by the optional OPDS fallback."
                    },
                    color = PiggieTvColors.TextSecondary,
                    style = MaterialTheme.typography.caption,
                )
            }
        }
    }
}

@Composable
private fun EmptyLibrary(message: String = "No reading items were returned by Jellyfin Reading.") {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = PiggieTvColors.Panel.copy(alpha = 0.76f),
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, PiggieTvColors.Border),
    ) {
        Text(
            text = message,
            color = PiggieTvColors.TextSecondary,
            modifier = Modifier.padding(18.dp),
        )
    }
}

@Composable
private fun LibraryLoading() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = PiggieTvColors.Focus)
    }
}

@Composable
private fun LibraryError(message: String, onRetry: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Icons.AutoMirrored.Outlined.MenuBook, contentDescription = null, tint = PiggieTvColors.Focus, modifier = Modifier.size(44.dp))
            Text(text = message, color = PiggieTvColors.TextPrimary, style = MaterialTheme.typography.body1)
            Button(
                onClick = onRetry,
                colors = ButtonDefaults.buttonColors(backgroundColor = PiggieTvColors.Accent, contentColor = PiggieTvColors.Night),
                shape = MaterialTheme.shapes.medium,
            ) {
                Text(text = "Retry", fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun LibraryLoginRequired(onRetry: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Icons.AutoMirrored.Outlined.MenuBook, contentDescription = null, tint = PiggieTvColors.Focus, modifier = Modifier.size(44.dp))
            Text(text = "OPDS fallback login required", color = PiggieTvColors.TextPrimary, style = MaterialTheme.typography.h6, fontWeight = FontWeight.Bold)
            Text(
                text = "Add optional OPDS fallback credentials in PiggieTV Settings, then retry.",
                color = PiggieTvColors.TextSecondary,
                style = MaterialTheme.typography.body2,
            )
            Button(
                onClick = onRetry,
                colors = ButtonDefaults.buttonColors(backgroundColor = PiggieTvColors.Accent, contentColor = PiggieTvColors.Night),
                shape = MaterialTheme.shapes.medium,
            ) {
                Text(text = "Retry", fontWeight = FontWeight.Bold)
            }
        }
    }
}

private data class LibraryAdaptiveLayout(
    val edgePadding: Dp,
    val rowPosterWidth: Dp,
    val gridPosterWidth: Dp,
    val detailPosterWidth: Dp,
    val gridMinWidth: Dp,
    val gridSpacing: Dp,
    val gridHeight: Dp,
    val detailHeroHeight: Dp,
) {
    companion object {
        fun forWidth(width: Dp) = when {
            width < 600.dp -> LibraryAdaptiveLayout(
                edgePadding = 16.dp,
                rowPosterWidth = 116.dp,
                gridPosterWidth = 142.dp,
                detailPosterWidth = 128.dp,
                gridMinWidth = 136.dp,
                gridSpacing = 12.dp,
                gridHeight = 920.dp,
                detailHeroHeight = 218.dp,
            )
            width < 840.dp -> LibraryAdaptiveLayout(
                edgePadding = 28.dp,
                rowPosterWidth = 132.dp,
                gridPosterWidth = 158.dp,
                detailPosterWidth = 150.dp,
                gridMinWidth = 154.dp,
                gridSpacing = 14.dp,
                gridHeight = 980.dp,
                detailHeroHeight = 278.dp,
            )
            else -> LibraryAdaptiveLayout(
                edgePadding = 48.dp,
                rowPosterWidth = 148.dp,
                gridPosterWidth = 176.dp,
                detailPosterWidth = 172.dp,
                gridMinWidth = 170.dp,
                gridSpacing = 16.dp,
                gridHeight = 1100.dp,
                detailHeroHeight = 330.dp,
            )
        }
    }
}

private fun LibraryBook.downloadFilename(link: LibraryLink): String {
    val extension = link.inferredFormat(title).preferredExtension
    return title
        .lowercase()
        .replace(Regex("[^a-z0-9]+"), "-")
        .trim('-')
        .ifBlank { "book" } + ".$extension"
}

@Composable
private fun BookFormatPill(format: LibraryBookFormat) {
    Surface(
        color = when (format.supportStatus()) {
            LibraryFormatSupport.NATIVE -> PiggieTvColors.Focus.copy(alpha = 0.26f)
            LibraryFormatSupport.LIMITED -> PiggieTvColors.Accent.copy(alpha = 0.22f)
            LibraryFormatSupport.UNSUPPORTED,
            LibraryFormatSupport.UNKNOWN,
            -> PiggieTvColors.PanelHigh
        },
        shape = RoundedCornerShape(6.dp),
        border = BorderStroke(1.dp, PiggieTvColors.Border),
    ) {
        Text(
            text = format.label,
            color = PiggieTvColors.TextPrimary,
            style = MaterialTheme.typography.overline,
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
        )
    }
}

private fun List<LibraryBook>.filterForQuery(query: String): List<LibraryBook> {
    val normalizedQuery = query.trim().lowercase()
    if (normalizedQuery.isBlank()) return this
    return filter { book ->
        listOf(
            book.title,
            book.subtitle.orEmpty(),
            book.authors.joinToString(" "),
            book.series.orEmpty(),
            book.categories.joinToString(" "),
            book.format.label,
        ).any { value -> normalizedQuery in value.lowercase() }
    }
}

private fun Long.toFileSizeLabel(): String {
    if (this <= 0L) return "Unknown size"
    val units = listOf("B", "KB", "MB", "GB")
    var value = toDouble()
    var unitIndex = 0
    while (value >= 1024.0 && unitIndex < units.lastIndex) {
        value /= 1024.0
        unitIndex += 1
    }
    return when (unitIndex) {
        0 -> "${value.toInt()} ${units[unitIndex]}"
        else -> "%.1f %s".format(value, units[unitIndex])
    }
}

private const val MAX_DETAIL_CATEGORIES = 6
