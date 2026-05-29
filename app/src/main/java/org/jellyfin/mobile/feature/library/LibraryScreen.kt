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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Button
import androidx.compose.material.ButtonDefaults
import androidx.compose.material.Card
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Download
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
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
    onOpenLink: (String) -> Unit,
    onDownload: (Uri, String, String) -> Unit,
    onRead: (Uri, String, String, String?) -> Unit,
    onBackHandlerChanged: ((() -> Boolean)?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.uiState.collectAsState()

    LaunchedEffect(Unit) {
        viewModel.load()
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
                        home = state.home,
                        onBookClick = viewModel::selectBook,
                        onRetry = { viewModel.load(force = true) },
                    )
                } else {
                    BookDetail(
                        layout = layout,
                        book = selectedBook,
                        isLoading = state.isLoadingDetail,
                        onBack = viewModel::closeBook,
                        onOpenLink = onOpenLink,
                        onDownload = onDownload,
                        onRead = onRead,
                    )
                }
            }
        }
    }
}

@Composable
private fun LibraryHomeContent(
    layout: LibraryAdaptiveLayout,
    home: LibraryHome,
    onBookClick: (LibraryBook) -> Unit,
    onRetry: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = layout.edgePadding, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(22.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(Icons.AutoMirrored.Outlined.MenuBook, contentDescription = null, tint = PiggieTvColors.Focus, modifier = Modifier.size(32.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = "Library", color = PiggieTvColors.TextPrimary, style = MaterialTheme.typography.h5, fontWeight = FontWeight.ExtraBold)
                    Text(text = home.serverBaseUrl, color = PiggieTvColors.TextSecondary, style = MaterialTheme.typography.caption, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                TextButton(onClick = onRetry) {
                    Text(text = "Refresh", color = PiggieTvColors.FocusSoft)
                }
            }
        }

        if (home.recentBooks.isNotEmpty()) {
            item {
                BookRow(title = "Recent books", books = home.recentBooks, posterWidth = layout.rowPosterWidth, onBookClick = onBookClick)
            }
        }

        item {
            Text(text = "All books", color = PiggieTvColors.TextPrimary, style = MaterialTheme.typography.h6, fontWeight = FontWeight.Bold)
        }

        if (home.allBooks.isEmpty()) {
            item { EmptyLibrary() }
        } else {
            item {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(layout.gridMinWidth),
                    modifier = Modifier.heightIn(max = layout.gridHeight),
                    userScrollEnabled = false,
                    verticalArrangement = Arrangement.spacedBy(layout.gridSpacing),
                    horizontalArrangement = Arrangement.spacedBy(layout.gridSpacing),
                ) {
                    items(home.allBooks, key = LibraryBook::id) { book ->
                        BookCard(book = book, posterWidth = layout.gridPosterWidth, onClick = { onBookClick(book) })
                    }
                }
            }
        }

        if (home.authors.isNotEmpty() || home.series.isNotEmpty() || home.categories.isNotEmpty()) {
            item {
                FacetSummary(home = home)
            }
        }
    }
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
        Text(text = book.title, color = PiggieTvColors.TextPrimary, style = MaterialTheme.typography.body2, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
        book.subtitle?.let {
            Text(text = it, color = PiggieTvColors.TextSecondary, style = MaterialTheme.typography.caption, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun BookDetail(
    layout: LibraryAdaptiveLayout,
    book: LibraryBook,
    isLoading: Boolean,
    onBack: () -> Unit,
    onOpenLink: (String) -> Unit,
    onDownload: (Uri, String, String) -> Unit,
    onRead: (Uri, String, String, String?) -> Unit,
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
                    BookActions(book = book, onOpenLink = onOpenLink, onDownload = onDownload, onRead = onRead)
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
    onOpenLink: (String) -> Unit,
    onDownload: (Uri, String, String) -> Unit,
    onRead: (Uri, String, String, String?) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        book.readLinks.firstOrNull()?.let { link ->
            Button(
                onClick = { onOpenLink(link.href) },
                colors = ButtonDefaults.buttonColors(backgroundColor = PiggieTvColors.Accent, contentColor = PiggieTvColors.Night),
                shape = MaterialTheme.shapes.medium,
            ) {
                Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text(text = "Open", fontWeight = FontWeight.Bold)
            }
        }
        book.acquisitionLinks.firstOrNull()?.let { link ->
            Button(
                onClick = { onRead(Uri.parse(link.href), book.title, book.downloadFilename(link), link.type) },
                colors = ButtonDefaults.buttonColors(backgroundColor = PiggieTvColors.Accent, contentColor = PiggieTvColors.Night),
                shape = MaterialTheme.shapes.medium,
            ) {
                Icon(Icons.AutoMirrored.Outlined.MenuBook, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text(text = "Read", fontWeight = FontWeight.Bold)
            }
            Button(
                onClick = { onDownload(Uri.parse(link.href), book.title, book.downloadFilename(link)) },
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
                    "${home.categories.size} tags",
                ).joinToString("   "),
                color = PiggieTvColors.TextSecondary,
                style = MaterialTheme.typography.body2,
            )
        }
    }
}

@Composable
private fun EmptyLibrary() {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = PiggieTvColors.Panel.copy(alpha = 0.76f),
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, PiggieTvColors.Border),
    ) {
        Text(
            text = "No books were returned by the OPDS feed.",
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
            Text(text = "Library login required", color = PiggieTvColors.TextPrimary, style = MaterialTheme.typography.h6, fontWeight = FontWeight.Bold)
            Text(
                text = "Add your Library username and password in PiggieTV Settings, then retry.",
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
    val extension = when (link.type) {
        "application/pdf" -> "pdf"
        "application/x-mobipocket-ebook" -> "mobi"
        "application/vnd.amazon.ebook" -> "azw3"
        "application/x-cbz",
        "application/vnd.comicbook+zip",
        -> "cbz"
        "application/x-cbr",
        "application/vnd.comicbook-rar",
        -> "cbr"
        "text/plain" -> "txt"
        "text/html" -> "html"
        else -> "epub"
    }
    return title
        .lowercase()
        .replace(Regex("[^a-z0-9]+"), "-")
        .trim('-')
        .ifBlank { "book" } + ".$extension"
}
