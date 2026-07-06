@file:Suppress("CyclomaticComplexMethod", "LongMethod", "TooManyFunctions")

package org.jellyfin.mobile.feature.music

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.AlertDialog
import androidx.compose.material.Button
import androidx.compose.material.ButtonDefaults
import androidx.compose.material.Card
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.Divider
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.LinearProgressIndicator
import androidx.compose.material.MaterialTheme
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.Slider
import androidx.compose.material.SliderDefaults
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.material.TextFieldDefaults
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.PlaylistAdd
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material.icons.outlined.RepeatOne
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Shuffle
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
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import coil3.compose.AsyncImage
import kotlinx.coroutines.delay
import org.jellyfin.mobile.R
import org.jellyfin.mobile.ui.utils.PiggieTvColors
import org.jellyfin.mobile.utils.requestPermission
import org.jellyfin.sdk.model.api.BaseItemKind

@Composable
fun MusicScreen(
    viewModel: MusicViewModel,
    onBackHandlerChanged: ((() -> Boolean)?) -> Unit,
    onScrollHeaderCollapsedChange: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.uiState.collectAsState()
    val playbackState by viewModel.playbackState.collectAsState()
    val context = LocalContext.current
    var showNowPlaying by rememberSaveable { mutableStateOf(false) }
    var activeTab by rememberSaveable { mutableStateOf(MusicBrowseTab.HOME) }
    var actionTarget by remember { mutableStateOf<MusicSongActionUiTarget?>(null) }
    val contentState = uiState as? MusicUiState.Content
    val actionMessage = contentState?.actionMessage

    fun requestMusicNotificationPermission() {
        requestMusicNotificationPermissionIfNeeded(
            context = context,
            onResult = viewModel::reportNotificationPermissionResult,
        )
    }

    fun playItem(item: MusicItem, queue: List<MusicItem>) {
        if (!item.isPlayable) {
            viewModel.openItem(item)
            return
        }

        requestMusicNotificationPermission()
        val playableQueue = queue.filter(MusicItem::isPlayable).ifEmpty { listOf(item) }
        viewModel.play(item, playableQueue)
    }

    LaunchedEffect(Unit) {
        viewModel.load()
    }

    LaunchedEffect(contentState?.selectedItem?.id, uiState is MusicUiState.Loading, uiState is MusicUiState.Error) {
        onScrollHeaderCollapsedChange(contentState?.selectedItem != null)
    }

    LaunchedEffect(actionMessage) {
        if (actionMessage != null) {
            delay(ACTION_MESSAGE_DURATION_MS)
            viewModel.clearActionMessage()
        }
    }

    LaunchedEffect(playbackState.currentItem?.id, playbackState.isPlaying, playbackState.isBuffering) {
        if (playbackState.hasCurrent && (playbackState.isPlaying || playbackState.isBuffering)) {
            showNowPlaying = true
        }
    }

    SideEffect {
        onBackHandlerChanged {
            val state = uiState
            if (showNowPlaying) {
                showNowPlaying = false
                true
            } else if (state is MusicUiState.Content && state.selectedItem != null) {
                viewModel.closeItem()
                true
            } else {
                false
            }
        }
    }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val layout = remember(maxWidth) { MusicAdaptiveLayout.forWidth(maxWidth) }

        Box(modifier = Modifier.fillMaxSize()) {
            when (val state = uiState) {
                MusicUiState.Loading -> MusicLoading()

                is MusicUiState.Error -> MusicError(
                    message = state.message,
                    onRetry = { viewModel.load(force = true) },
                )

                is MusicUiState.Content -> {
                    val selectedItem = state.selectedItem
                    if (selectedItem == null) {
                        MusicHomeContent(
                            layout = layout,
                            state = state,
                            activeTab = activeTab,
                            onSearch = viewModel::search,
                            onSelectTab = { tab -> activeTab = tab },
                            onItemClick = viewModel::openItem,
                            onPlay = ::playItem,
                            onOpenSongMenu = { item, queue ->
                                actionTarget = MusicSongActionUiTarget(item = item, queue = queue)
                            },
                            onShuffle = { queue ->
                                requestMusicNotificationPermission()
                                viewModel.shufflePlay(queue)
                            },
                            onLoadMoreSongs = viewModel::loadMoreSongs,
                            onScrollHeaderCollapsedChange = onScrollHeaderCollapsedChange,
                        )
                    } else {
                        MusicDetailContent(
                            layout = layout,
                            state = state,
                            item = selectedItem,
                            onBack = viewModel::closeItem,
                            onItemClick = viewModel::openItem,
                            onPlay = ::playItem,
                            onOpenSongMenu = { menuItem, queue ->
                                actionTarget = MusicSongActionUiTarget(item = menuItem, queue = queue)
                            },
                            onCreatePlaylist = viewModel::openSourcePlaylistDialog,
                            onRetryDetail = { viewModel.openItem(selectedItem) },
                        )
                    }
                }
            }

            MusicPlaybackLayer(
                viewModel = viewModel,
                playbackState = playbackState,
                layout = layout,
                actionMessage = actionMessage,
                showNowPlaying = showNowPlaying,
                onShowNowPlayingChange = { expanded -> showNowPlaying = expanded },
                onOpenSongMenu = { item, queue ->
                    actionTarget = MusicSongActionUiTarget(item = item, queue = queue)
                },
            )

            actionTarget?.let { target ->
                MusicSongActionDialog(
                    target = target,
                    isNotInterested = target.item.id in contentState?.notInterestedItemIds.orEmpty(),
                    onDismiss = { actionTarget = null },
                    onAction = { action ->
                        actionTarget = null
                        if (action.needsMusicNotificationPermission()) {
                            requestMusicNotificationPermission()
                        }
                        viewModel.performSongAction(action, target.item, target.queue)
                    },
                )
            }

            contentState?.playlistAction?.takeIf(MusicPlaylistActionState::isVisible)?.let { playlistAction ->
                MusicPlaylistDialog(
                    state = playlistAction,
                    onDismiss = viewModel::closePlaylistDialog,
                    onAddToPlaylist = viewModel::addPlaylistTargetToPlaylist,
                    onCreatePlaylist = viewModel::createPlaylist,
                )
            }
        }
    }
}

@Composable
private fun BoxScope.MusicPlaybackLayer(
    viewModel: MusicViewModel,
    playbackState: MusicPlaybackState,
    layout: MusicAdaptiveLayout,
    actionMessage: String?,
    showNowPlaying: Boolean,
    onShowNowPlayingChange: (Boolean) -> Unit,
    onOpenSongMenu: (MusicItem, List<MusicItem>) -> Unit,
) {
    if (playbackState.hasCurrent) {
        MusicMiniPlayer(
            state = playbackState,
            onExpand = { onShowNowPlayingChange(true) },
            onTogglePlay = viewModel::togglePlayPause,
            onPrevious = viewModel::previous,
            onNext = viewModel::next,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(horizontal = layout.edgePadding, vertical = 12.dp),
        )
    }

    if (playbackState.errorMessage != null) {
        MusicPlaybackErrorCard(
            message = playbackState.errorMessage.orEmpty(),
            canRetry = playbackState.canRetry,
            onRetry = viewModel::retryPlayback,
            onSkip = viewModel::skipPlaybackError,
            onDismiss = viewModel::clearPlaybackError,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(
                    start = layout.edgePadding,
                    end = layout.edgePadding,
                    bottom = if (playbackState.hasCurrent) 94.dp else 18.dp,
                ),
        )
    } else {
        actionMessage?.let { message ->
            MusicStatusMessage(
                message = message,
                isError = false,
                onDismiss = viewModel::clearActionMessage,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(
                        start = layout.edgePadding,
                        end = layout.edgePadding,
                        bottom = if (playbackState.hasCurrent) 94.dp else 18.dp,
                    ),
            )
        }
    }

    if (showNowPlaying && playbackState.hasCurrent) {
        MusicNowPlayingPanel(
            state = playbackState,
            actionMessage = actionMessage,
            onDismiss = { onShowNowPlayingChange(false) },
            onTogglePlay = viewModel::togglePlayPause,
            onPrevious = viewModel::previous,
            onNext = viewModel::next,
            onSeek = viewModel::seekTo,
            onShuffle = viewModel::toggleShuffle,
            onRepeat = viewModel::cycleRepeatMode,
            onFavorite = { playbackState.currentItem?.let(viewModel::toggleFavorite) },
            onAddToPlaylist = viewModel::addCurrentToDefaultPlaylist,
            onRetry = viewModel::retryPlayback,
            onSkip = viewModel::skipPlaybackError,
            onQueueItemClick = { item -> viewModel.playQueueItem(item, playbackState.queue) },
            onQueueItemMenu = { item -> onOpenSongMenu(item, playbackState.queue) },
            modifier = Modifier.fillMaxSize(),
        )
    }
}

private fun requestMusicNotificationPermissionIfNeeded(context: Context, onResult: (Boolean) -> Unit) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        return
    }

    if (
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
        PackageManager.PERMISSION_GRANTED
    ) {
        return
    }

    val activity = context.findActivity()
    if (activity == null) {
        onResult(false)
        return
    }

    activity.requestPermission(Manifest.permission.POST_NOTIFICATIONS) { permissions ->
        onResult(permissions[Manifest.permission.POST_NOTIFICATIONS] == PackageManager.PERMISSION_GRANTED)
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

private fun MusicSongAction.needsMusicNotificationPermission(): Boolean =
    this == MusicSongAction.PLAY ||
        this == MusicSongAction.START_MIX ||
        this == MusicSongAction.START_ARTIST_RADIO

@Composable
private fun MusicHomeContent(
    layout: MusicAdaptiveLayout,
    state: MusicUiState.Content,
    activeTab: MusicBrowseTab,
    onSearch: (String) -> Unit,
    onSelectTab: (MusicBrowseTab) -> Unit,
    onItemClick: (MusicItem) -> Unit,
    onPlay: (MusicItem, List<MusicItem>) -> Unit,
    onOpenSongMenu: (MusicItem, List<MusicItem>) -> Unit,
    onShuffle: (List<MusicItem>) -> Unit,
    onLoadMoreSongs: () -> Unit,
    onScrollHeaderCollapsedChange: (Boolean) -> Unit,
) {
    val home = state.home
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
        contentPadding = PaddingValues(
            start = layout.edgePadding,
            top = 14.dp,
            end = layout.edgePadding,
            bottom = layout.bottomPadding,
        ),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        item {
            MusicHero(
                layout = layout,
                home = home,
                onShuffle = {
                    onShuffle(home.songs)
                },
            )
        }
        item {
            MusicSearchField(
                query = state.searchQuery,
                isSearching = state.isSearching,
                onSearch = onSearch,
            )
        }
        if (state.searchQuery.isNotBlank()) {
            item {
                MusicSectionHeader(
                    title = "Search Results",
                    subtitle = state.searchError ?: "${state.searchResults.size} matches",
                )
            }
            if (state.isSearching) {
                item { MusicInlineLoading() }
            } else if (state.searchResults.isEmpty()) {
                item { MusicEmpty("No music matched your search.") }
            } else {
                items(state.searchResults, key = { item -> "search-${item.id}" }) { item ->
                    MusicListItem(
                        item = item,
                        queue = state.searchResults,
                        onClick = {
                            if (item.isPlayable) onPlay(item, state.searchResults) else onItemClick(item)
                        },
                        onPlay = { onPlay(item, state.searchResults) },
                        onOpenMenu = { onOpenSongMenu(item, state.searchResults) },
                    )
                }
            }
        } else {
            item {
                MusicBrowseTabs(activeTab = activeTab, onSelect = onSelectTab)
            }
            when (activeTab) {
                MusicBrowseTab.HOME -> home.sections.forEach { section ->
                    item(key = section.id) {
                        MusicHomeSection(
                            layout = layout,
                            section = section,
                            onItemClick = onItemClick,
                            onPlay = onPlay,
                            onOpenSongMenu = onOpenSongMenu,
                        )
                    }
                }

                MusicBrowseTab.ALBUMS -> musicListItems("albums", home.albums, onItemClick, onPlay, onOpenSongMenu)

                MusicBrowseTab.ARTISTS -> musicListItems("artists", home.artists, onItemClick, onPlay, onOpenSongMenu)

                MusicBrowseTab.SONGS -> musicSongsListItems(
                    items = home.songs,
                    totalCount = home.songsTotalCount,
                    isLoadingMore = state.isLoadingMoreSongs,
                    error = state.songsError,
                    onItemClick = onItemClick,
                    onPlay = onPlay,
                    onOpenSongMenu = onOpenSongMenu,
                    onLoadMore = onLoadMoreSongs,
                )

                MusicBrowseTab.GENRES -> musicListItems("genres", home.genres, onItemClick, onPlay, onOpenSongMenu)

                MusicBrowseTab.PLAYLISTS -> musicListItems("playlists", home.playlists, onItemClick, onPlay, onOpenSongMenu)

                MusicBrowseTab.LIKED -> musicListItems("liked", home.favorites, onItemClick, onPlay, onOpenSongMenu)
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.musicSongsListItems(
    items: List<MusicItem>,
    totalCount: Int,
    isLoadingMore: Boolean,
    error: String?,
    onItemClick: (MusicItem) -> Unit,
    onPlay: (MusicItem, List<MusicItem>) -> Unit,
    onOpenSongMenu: (MusicItem, List<MusicItem>) -> Unit,
    onLoadMore: () -> Unit,
) {
    if (items.isEmpty() && error != null) {
        item {
            MusicRetryRow(
                message = "Songs temporarily unavailable. $error",
                onRetry = onLoadMore,
            )
        }
    } else if (items.isEmpty()) {
        item { MusicEmpty("No songs were returned for this library.") }
    } else {
        items(items, key = { item -> "songs-${item.id}" }) { item ->
            MusicListItem(
                item = item,
                queue = items,
                onClick = {
                        if (item.isPlayable) onPlay(item, items) else onItemClick(item)
                    },
                    onPlay = { onPlay(item, items) },
                    onOpenMenu = { onOpenSongMenu(item, items) },
                )
            }
        }

    if (items.isNotEmpty()) error?.let { message ->
        item {
            MusicRetryRow(message = "Songs temporarily unavailable. $message", onRetry = onLoadMore)
        }
    }

    if (items.size < totalCount) {
        item {
            Button(
                onClick = onLoadMore,
                enabled = !isLoadingMore,
                colors = ButtonDefaults.buttonColors(
                    backgroundColor = PiggieTvColors.PanelHigh,
                    contentColor = PiggieTvColors.TextPrimary,
                    disabledBackgroundColor = PiggieTvColors.Panel,
                    disabledContentColor = PiggieTvColors.TextSecondary,
                ),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (isLoadingMore) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        color = PiggieTvColors.Focus,
                        strokeWidth = 2.dp,
                    )
                } else {
                    Text("Load More Songs (${items.size}/$totalCount)", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.musicListItems(
    keyPrefix: String,
    items: List<MusicItem>,
    onItemClick: (MusicItem) -> Unit,
    onPlay: (MusicItem, List<MusicItem>) -> Unit,
    onOpenSongMenu: (MusicItem, List<MusicItem>) -> Unit,
) {
    if (items.isEmpty()) {
        item { MusicEmpty("Nothing here yet.") }
    } else {
        items(items, key = { item -> "$keyPrefix-${item.id}" }) { item ->
            MusicListItem(
                item = item,
                queue = items,
                onClick = {
                    if (item.isPlayable) onPlay(item, items) else onItemClick(item)
                },
                onPlay = { onPlay(item, items) },
                onOpenMenu = { onOpenSongMenu(item, items) },
            )
        }
    }
}

@Composable
private fun MusicDetailContent(
    layout: MusicAdaptiveLayout,
    state: MusicUiState.Content,
    item: MusicItem,
    onBack: () -> Unit,
    onItemClick: (MusicItem) -> Unit,
    onPlay: (MusicItem, List<MusicItem>) -> Unit,
    onOpenSongMenu: (MusicItem, List<MusicItem>) -> Unit,
    onCreatePlaylist: (MusicItem) -> Unit,
    onRetryDetail: () -> Unit,
) {
    val children = state.selectedChildren
    val playableQueue = children.filter(MusicItem::isPlayable).ifEmpty {
        listOf(item).filter(MusicItem::isPlayable)
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = layout.bottomPadding),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        item {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(layout.detailHeroHeight),
            ) {
                AsyncImage(
                    model = item.backdropUrl ?: item.posterUrl,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                    placeholder = painterResource(R.drawable.ptv_splash_background),
                    fallback = painterResource(R.drawable.ptv_splash_background),
                )
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(
                                    PiggieTvColors.Night.copy(alpha = 0.22f),
                                    PiggieTvColors.Panel.copy(alpha = 0.72f),
                                    PiggieTvColors.Night,
                                ),
                            ),
                        ),
                )
                IconButton(
                    onClick = onBack,
                    modifier = Modifier.padding(horizontal = layout.edgePadding / 2, vertical = 8.dp),
                ) {
                    Icon(
                        Icons.AutoMirrored.Outlined.ArrowBack,
                        contentDescription = null,
                        tint = PiggieTvColors.TextPrimary,
                    )
                }
            }
        }
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = layout.edgePadding),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.Top,
            ) {
                MusicCover(item = item, width = layout.detailCoverWidth)
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = item.title,
                        color = PiggieTvColors.TextPrimary,
                        style = MaterialTheme.typography.h5,
                        fontWeight = FontWeight.ExtraBold,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis,
                    )
                    item.subtitle?.let {
                        Text(text = it, color = PiggieTvColors.TextSecondary, style = MaterialTheme.typography.body2)
                    }
                    if (playableQueue.isNotEmpty() || item.supportsSourcePlaylist()) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (playableQueue.isNotEmpty()) {
                                Button(
                                    onClick = { onPlay(playableQueue.first(), playableQueue) },
                                    colors = ButtonDefaults.buttonColors(
                                        backgroundColor = PiggieTvColors.Accent,
                                        contentColor = PiggieTvColors.Night,
                                    ),
                                    shape = MaterialTheme.shapes.medium,
                                ) {
                                    Icon(
                                        Icons.Outlined.PlayArrow,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp),
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(text = "Play", fontWeight = FontWeight.Bold)
                                }
                            }
                            if (item.supportsSourcePlaylist()) {
                                Button(
                                    onClick = { onCreatePlaylist(item) },
                                    colors = ButtonDefaults.buttonColors(
                                        backgroundColor = PiggieTvColors.PanelHigh,
                                        contentColor = PiggieTvColors.TextPrimary,
                                    ),
                                    shape = MaterialTheme.shapes.medium,
                                ) {
                                    Icon(
                                        Icons.AutoMirrored.Outlined.PlaylistAdd,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp),
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(text = "Playlist", fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }
            }
        }
        state.detailError?.let { error ->
            item {
                MusicRetryRow(
                    message = error,
                    onRetry = onRetryDetail,
                    modifier = Modifier.padding(horizontal = layout.edgePadding),
                )
            }
        }
        if (state.isLoadingDetail) {
            item { MusicSkeletonRows(modifier = Modifier.padding(horizontal = layout.edgePadding)) }
        } else if (children.isEmpty()) {
            item {
                if (item.isPlayable) {
                    MusicListItem(
                        item = item,
                        queue = listOf(item),
                        onClick = {},
                        onPlay = { onPlay(item, listOf(item)) },
                        onOpenMenu = { onOpenSongMenu(item, listOf(item)) },
                        modifier = Modifier.padding(horizontal = layout.edgePadding),
                    )
                } else {
                    MusicEmpty(
                        "No tracks were returned for this item.",
                        modifier = Modifier.padding(horizontal = layout.edgePadding),
                    )
                }
            }
        } else {
            items(children, key = { child -> "detail-${child.id}" }) { child ->
                MusicListItem(
                    item = child,
                    queue = playableQueue,
                    onClick = {
                        if (child.isPlayable) onPlay(child, playableQueue) else onItemClick(child)
                    },
                    onPlay = { onPlay(child, playableQueue) },
                    onOpenMenu = { onOpenSongMenu(child, playableQueue) },
                    modifier = Modifier.padding(horizontal = layout.edgePadding),
                )
            }
        }
    }
}

@Composable
private fun MusicHero(layout: MusicAdaptiveLayout, home: MusicHome, onShuffle: () -> Unit) {
    val songsCountText = when {
        home.songsError != null && home.songs.isEmpty() -> "Songs temporarily unavailable"
        else -> "${home.songsTotalCount} songs"
    }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = layout.heroHeight),
        color = PiggieTvColors.Panel.copy(alpha = 0.82f),
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, PiggieTvColors.Border),
    ) {
        Box(
            modifier = Modifier
                .background(
                    Brush.horizontalGradient(
                        colors = listOf(
                            PiggieTvColors.PanelHigh.copy(alpha = 0.86f),
                            PiggieTvColors.Night.copy(alpha = 0.80f),
                        ),
                    ),
                )
                .padding(layout.heroPadding),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 720.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = "PTV Music",
                    color = PiggieTvColors.TextPrimary,
                    style = MaterialTheme.typography.h4,
                    fontWeight = FontWeight.ExtraBold,
                )
                Text(
                    text = home.library?.name ?: "Jellyfin music library",
                    color = PiggieTvColors.TextSecondary,
                    style = MaterialTheme.typography.body2,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = listOf(
                        "${home.albumsTotalCount} albums",
                        "${home.artistsTotalCount} artists",
                        songsCountText,
                    ).joinToString("  "),
                    color = PiggieTvColors.FocusSoft,
                    style = MaterialTheme.typography.caption,
                )
                Button(
                    onClick = onShuffle,
                    enabled = home.songs.isNotEmpty(),
                    colors = ButtonDefaults.buttonColors(
                        backgroundColor = PiggieTvColors.Accent,
                        contentColor = PiggieTvColors.Night,
                        disabledBackgroundColor = PiggieTvColors.PanelHigh,
                        disabledContentColor = PiggieTvColors.TextSecondary,
                    ),
                    shape = MaterialTheme.shapes.medium,
                ) {
                    Icon(Icons.Outlined.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(text = "Shuffle Songs", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun MusicSearchField(query: String, isSearching: Boolean, onSearch: (String) -> Unit) {
    OutlinedTextField(
        value = query,
        onValueChange = onSearch,
        modifier = Modifier.fillMaxWidth(),
        label = { Text(text = "Search music") },
        singleLine = true,
        leadingIcon = {
            Icon(Icons.Outlined.Search, contentDescription = null, tint = PiggieTvColors.Focus)
        },
        trailingIcon = {
            if (isSearching) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    color = PiggieTvColors.Focus,
                    strokeWidth = 2.dp,
                )
            }
        },
        shape = MaterialTheme.shapes.medium,
        colors = TextFieldDefaults.outlinedTextFieldColors(
            textColor = PiggieTvColors.TextPrimary,
            cursorColor = PiggieTvColors.Focus,
            focusedBorderColor = PiggieTvColors.Focus,
            unfocusedBorderColor = PiggieTvColors.Border,
            focusedLabelColor = PiggieTvColors.Focus,
            unfocusedLabelColor = PiggieTvColors.TextSecondary,
            backgroundColor = PiggieTvColors.Night.copy(alpha = 0.48f),
        ),
    )
}

@Composable
private fun MusicBrowseTabs(activeTab: MusicBrowseTab, onSelect: (MusicBrowseTab) -> Unit) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(MusicBrowseTab.entries, key = MusicBrowseTab::name) { tab ->
            Surface(
                modifier = Modifier
                    .height(40.dp)
                    .widthIn(min = 84.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { onSelect(tab) },
                color = if (tab == activeTab) PiggieTvColors.PanelHigh else PiggieTvColors.Night.copy(alpha = 0.56f),
                border = BorderStroke(
                    1.dp,
                    if (tab == activeTab) PiggieTvColors.Focus.copy(alpha = 0.72f) else PiggieTvColors.Border,
                ),
                shape = RoundedCornerShape(8.dp),
            ) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = 12.dp)) {
                    Text(
                        text = tab.title,
                        color = if (tab == activeTab) PiggieTvColors.TextPrimary else PiggieTvColors.TextSecondary,
                        style = MaterialTheme.typography.body2,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun MusicHomeSection(
    layout: MusicAdaptiveLayout,
    section: MusicSection,
    onItemClick: (MusicItem) -> Unit,
    onPlay: (MusicItem, List<MusicItem>) -> Unit,
    onOpenSongMenu: (MusicItem, List<MusicItem>) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        MusicSectionHeader(title = section.title, subtitle = section.subtitle)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(section.items, key = { item -> "${section.id}-${item.id}" }) { item ->
                MusicCard(
                    item = item,
                    width = if (section.kind == MusicSectionKind.TRACK) layout.trackCardWidth else layout.cardWidth,
                    onClick = {
                        if (item.isPlayable) onPlay(item, section.items) else onItemClick(item)
                    },
                    onPlay = { onPlay(item, section.items) },
                    onOpenMenu = { onOpenSongMenu(item, section.items) },
                )
            }
        }
    }
}

@Composable
private fun MusicSectionHeader(title: String, subtitle: String?) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = title,
            color = PiggieTvColors.TextPrimary,
            style = MaterialTheme.typography.h6,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        subtitle?.let {
            Text(
                text = it,
                color = PiggieTvColors.TextSecondary,
                style = MaterialTheme.typography.caption,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun MusicCard(
    item: MusicItem,
    width: Dp,
    onClick: () -> Unit,
    onPlay: () -> Unit,
    onOpenMenu: () -> Unit,
) {
    Column(modifier = Modifier.width(width), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(8.dp))
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = { if (item.isPlayable) onOpenMenu() },
                ),
            backgroundColor = PiggieTvColors.PanelHigh,
            border = BorderStroke(1.dp, PiggieTvColors.Border),
            elevation = 0.dp,
        ) {
            Box {
                AsyncImage(
                    model = item.posterUrl ?: item.backdropUrl,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                    placeholder = painterResource(R.drawable.ic_splash),
                    fallback = painterResource(R.drawable.ic_music_note_white_24dp),
                )
                if (item.isPlayable) {
                    IconButton(
                        onClick = onPlay,
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(6.dp)
                            .background(PiggieTvColors.Accent, RoundedCornerShape(18.dp)),
                    ) {
                        Icon(Icons.Outlined.PlayArrow, contentDescription = null, tint = PiggieTvColors.Night)
                    }
                    IconButton(
                        onClick = onOpenMenu,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(6.dp)
                            .background(PiggieTvColors.Panel.copy(alpha = 0.84f), RoundedCornerShape(18.dp)),
                    ) {
                        Icon(Icons.Outlined.MoreVert, contentDescription = null, tint = PiggieTvColors.TextPrimary)
                    }
                }
            }
        }
        Text(
            text = item.title,
            color = PiggieTvColors.TextPrimary,
            style = MaterialTheme.typography.body2,
            fontWeight = FontWeight.Bold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        item.subtitle?.let {
            Text(
                text = it,
                color = PiggieTvColors.TextSecondary,
                style = MaterialTheme.typography.caption,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun MusicListItem(
    item: MusicItem,
    queue: List<MusicItem>,
    onClick: () -> Unit,
    onPlay: () -> Unit,
    onOpenMenu: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .combinedClickable(
                onClick = onClick,
                onLongClick = { if (item.isPlayable) onOpenMenu() },
            ),
        color = PiggieTvColors.Panel.copy(alpha = 0.70f),
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, PiggieTvColors.Border),
    ) {
        Row(
            modifier = Modifier.padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            MusicCover(item = item, width = 54.dp)
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = item.title,
                    color = PiggieTvColors.TextPrimary,
                    style = MaterialTheme.typography.body1,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = item.subtitle ?: item.type.serialName,
                    color = PiggieTvColors.TextSecondary,
                    style = MaterialTheme.typography.caption,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (item.isFavorite) {
                Text(text = "Liked", color = PiggieTvColors.AccentSoft, style = MaterialTheme.typography.caption)
            }
            if (item.isPlayable || queue.any(MusicItem::isPlayable)) {
                IconButton(onClick = onPlay) {
                    Icon(Icons.Outlined.PlayArrow, contentDescription = null, tint = PiggieTvColors.Focus)
                }
            }
            if (item.isPlayable) {
                IconButton(onClick = onOpenMenu) {
                    Icon(Icons.Outlined.MoreVert, contentDescription = null, tint = PiggieTvColors.TextSecondary)
                }
            }
        }
    }
}

@Composable
private fun MusicCover(item: MusicItem, width: Dp) {
    Card(
        modifier = Modifier
            .width(width)
            .aspectRatio(1f),
        shape = RoundedCornerShape(8.dp),
        backgroundColor = PiggieTvColors.PanelHigh,
        border = BorderStroke(1.dp, PiggieTvColors.Border),
        elevation = 0.dp,
    ) {
        AsyncImage(
            model = item.posterUrl ?: item.backdropUrl,
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
            placeholder = painterResource(R.drawable.ic_music_note_white_24dp),
            fallback = painterResource(R.drawable.ic_music_note_white_24dp),
        )
    }
}

@Composable
private fun MusicMiniPlayer(
    state: MusicPlaybackState,
    onExpand: () -> Unit,
    onTogglePlay: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val item = state.currentItem ?: return
    val progress = state.progressFraction()

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onExpand),
        color = PiggieTvColors.PanelHigh.copy(alpha = 0.96f),
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, PiggieTvColors.Focus.copy(alpha = 0.56f)),
    ) {
        Column {
            LinearProgressIndicator(
                progress = progress,
                modifier = Modifier.fillMaxWidth(),
                color = if (state.isBuffering) PiggieTvColors.AccentSoft else PiggieTvColors.Focus,
                backgroundColor = PiggieTvColors.Border.copy(alpha = 0.34f),
            )
            Row(
                modifier = Modifier.padding(10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                MusicCover(item = item, width = 46.dp)
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = item.title,
                        color = PiggieTvColors.TextPrimary,
                        style = MaterialTheme.typography.body2,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = item.subtitle ?: state.codecCapability?.label ?: "PTV Music",
                        color = PiggieTvColors.TextSecondary,
                        style = MaterialTheme.typography.caption,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    text = "${MusicPlaybackFormatting.elapsedTime(state.positionMs)} / " +
                        MusicPlaybackFormatting.durationTime(state.durationMs),
                    color = PiggieTvColors.TextSecondary,
                    style = MaterialTheme.typography.caption,
                )
                IconButton(onClick = onPrevious) {
                    Icon(
                        painterResource(R.drawable.ic_skip_previous_black_32dp),
                        contentDescription = null,
                        tint = PiggieTvColors.Focus,
                    )
                }
                IconButton(onClick = onTogglePlay) {
                    Icon(
                        if (state.isPlaying) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                        contentDescription = null,
                        tint = PiggieTvColors.Accent,
                    )
                }
                IconButton(onClick = onNext) {
                    Icon(
                        painterResource(R.drawable.ic_skip_next_black_32dp),
                        contentDescription = null,
                        tint = PiggieTvColors.Focus,
                    )
                }
            }
        }
    }
}

@Composable
private fun MusicNowPlayingPanel(
    state: MusicPlaybackState,
    actionMessage: String?,
    onDismiss: () -> Unit,
    onTogglePlay: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onSeek: (Long) -> Unit,
    onShuffle: () -> Unit,
    onRepeat: () -> Unit,
    onFavorite: () -> Unit,
    onAddToPlaylist: () -> Unit,
    onRetry: () -> Unit,
    onSkip: () -> Unit,
    onQueueItemClick: (MusicItem) -> Unit,
    onQueueItemMenu: (MusicItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    val item = state.currentItem ?: return
    var seekPreview by remember(item.id) { mutableStateOf<Float?>(null) }
    val seekValue = seekPreview ?: state.progressFraction()

    Surface(modifier = modifier, color = PiggieTvColors.Night) {
        BoxWithConstraints {
            val isWide = maxWidth >= 760.dp
            val coverWidth = when {
                maxWidth >= 1040.dp -> 330.dp
                isWide -> 260.dp
                else -> 240.dp
            }

            AsyncImage(
                model = item.backdropUrl ?: item.posterUrl,
                contentDescription = null,
                modifier = Modifier
                    .fillMaxSize()
                    .blur(28.dp),
                contentScale = ContentScale.Crop,
                placeholder = painterResource(R.drawable.ptv_splash_background),
                fallback = painterResource(R.drawable.ptv_splash_background),
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                PiggieTvColors.Night.copy(alpha = 0.48f),
                                PiggieTvColors.Panel.copy(alpha = 0.92f),
                                PiggieTvColors.Night,
                            ),
                        ),
                    ),
            )

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 22.dp, top = 12.dp, end = 22.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Outlined.Close, contentDescription = null, tint = PiggieTvColors.TextPrimary)
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Now Playing",
                                color = PiggieTvColors.TextPrimary,
                                style = MaterialTheme.typography.h6,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(
                                text = "Track ${state.currentIndex + 1} of ${state.queue.size}",
                                color = PiggieTvColors.TextSecondary,
                                style = MaterialTheme.typography.caption,
                            )
                        }
                        IconButton(onClick = onFavorite) {
                            Icon(
                                if (item.isFavorite) Icons.Outlined.Favorite else Icons.Outlined.FavoriteBorder,
                                contentDescription = null,
                                tint = if (item.isFavorite) PiggieTvColors.Accent else PiggieTvColors.Focus,
                            )
                        }
                        IconButton(onClick = onAddToPlaylist) {
                            Icon(
                                Icons.AutoMirrored.Outlined.PlaylistAdd,
                                contentDescription = null,
                                tint = PiggieTvColors.Focus,
                            )
                        }
                    }
                }

                item {
                    if (isWide) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(28.dp),
                        ) {
                            MusicNowPlayingArt(item = item, state = state, width = coverWidth)
                            MusicNowPlayingInfo(item = item, state = state, modifier = Modifier.weight(1f))
                        }
                    } else {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(14.dp),
                        ) {
                            MusicNowPlayingArt(item = item, state = state, width = coverWidth)
                            MusicNowPlayingInfo(item = item, state = state, modifier = Modifier.fillMaxWidth())
                        }
                    }
                }

                state.errorMessage?.let { message ->
                    item {
                        MusicPlaybackErrorCard(
                            message = message,
                            canRetry = state.canRetry,
                            onRetry = onRetry,
                            onSkip = onSkip,
                            onDismiss = {},
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }

                item {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Slider(
                            value = seekValue,
                            onValueChange = { value -> seekPreview = value },
                            onValueChangeFinished = {
                                onSeek((state.durationMs * (seekPreview ?: seekValue)).toLong())
                                seekPreview = null
                            },
                            enabled = state.canSeek,
                            colors = SliderDefaults.colors(
                                thumbColor = PiggieTvColors.Accent,
                                activeTrackColor = PiggieTvColors.Focus,
                                inactiveTrackColor = PiggieTvColors.Border,
                                disabledThumbColor = PiggieTvColors.TextSecondary,
                                disabledActiveTrackColor = PiggieTvColors.Border,
                            ),
                        )
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(
                                text = when {
                                    state.durationMs > 0 -> {
                                        MusicPlaybackFormatting.elapsedTime((state.durationMs * seekValue).toLong())
                                    }

                                    else -> MusicPlaybackFormatting.elapsedTime(state.positionMs)
                                },
                                color = PiggieTvColors.TextSecondary,
                                style = MaterialTheme.typography.caption,
                            )
                            Text(
                                text = MusicPlaybackFormatting.remainingTime(
                                    durationMs = state.durationMs,
                                    positionMs = state.positionMs,
                                ),
                                color = PiggieTvColors.TextSecondary,
                                style = MaterialTheme.typography.caption,
                            )
                        }
                    }
                }

                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(onClick = onShuffle) {
                            Icon(
                                Icons.Outlined.Shuffle,
                                contentDescription = null,
                                tint = if (state.shuffleEnabled) PiggieTvColors.Accent else PiggieTvColors.Focus,
                            )
                        }
                        IconButton(onClick = onPrevious) {
                            Icon(
                                painterResource(R.drawable.ic_skip_previous_black_32dp),
                                contentDescription = null,
                                tint = PiggieTvColors.Focus,
                                modifier = Modifier.size(34.dp),
                            )
                        }
                        IconButton(onClick = onTogglePlay, modifier = Modifier.size(64.dp)) {
                            Icon(
                                if (state.isPlaying) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                                contentDescription = null,
                                tint = PiggieTvColors.Accent,
                                modifier = Modifier.size(52.dp),
                            )
                        }
                        IconButton(onClick = onNext) {
                            Icon(
                                painterResource(R.drawable.ic_skip_next_black_32dp),
                                contentDescription = null,
                                tint = PiggieTvColors.Focus,
                                modifier = Modifier.size(34.dp),
                            )
                        }
                        IconButton(onClick = onRepeat) {
                            val repeatIcon = if (state.repeatMode == MusicRepeatMode.ONE) {
                                Icons.Outlined.RepeatOne
                            } else {
                                Icons.Outlined.Repeat
                            }
                            Icon(
                                repeatIcon,
                                contentDescription = null,
                                tint = if (state.repeatMode == MusicRepeatMode.NONE) {
                                    PiggieTvColors.Focus
                                } else {
                                    PiggieTvColors.Accent
                                },
                            )
                        }
                    }
                }

                actionMessage?.let { message ->
                    item {
                        MusicStatusMessage(
                            message = message,
                            isError = false,
                            onDismiss = {},
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }

                item {
                    MusicSectionHeader(
                        title = "Queue",
                        subtitle = "${state.queue.size} tracks",
                    )
                }

                items(state.queue, key = { queueItem -> "queue-${queueItem.id}" }) { queueItem ->
                    MusicQueueRow(
                        item = queueItem,
                        isCurrent = queueItem.id == item.id,
                        onClick = { onQueueItemClick(queueItem) },
                        onOpenMenu = { onQueueItemMenu(queueItem) },
                    )
                }
            }
        }
    }
}

@Composable
private fun MusicNowPlayingArt(item: MusicItem, state: MusicPlaybackState, width: Dp) {
    Box(contentAlignment = Alignment.Center) {
        MusicCover(item = item, width = width)
        if (state.isBuffering) {
            Box(
                modifier = Modifier
                    .width(width)
                    .aspectRatio(1f)
                    .background(PiggieTvColors.Night.copy(alpha = 0.54f), RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    CircularProgressIndicator(color = PiggieTvColors.Accent, strokeWidth = 3.dp)
                    Text(
                        text = "Buffering",
                        color = PiggieTvColors.TextPrimary,
                        style = MaterialTheme.typography.caption,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
    }
}

@Composable
private fun MusicNowPlayingInfo(item: MusicItem, state: MusicPlaybackState, modifier: Modifier = Modifier) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = item.title,
            color = PiggieTvColors.TextPrimary,
            style = MaterialTheme.typography.h4,
            fontWeight = FontWeight.ExtraBold,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = item.artist ?: "Unknown Artist",
            color = PiggieTvColors.FocusSoft,
            style = MaterialTheme.typography.body1,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = item.album ?: "Unknown Album",
            color = PiggieTvColors.TextSecondary,
            style = MaterialTheme.typography.body2,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        state.codecCapability?.let { capability ->
            Surface(
                color = when (capability.status) {
                    MusicCodecStatus.NATIVE, MusicCodecStatus.DIRECT_STREAM -> PiggieTvColors.Focus.copy(alpha = 0.16f)

                    MusicCodecStatus.TRANSCODE, MusicCodecStatus.LIMITED -> PiggieTvColors.Accent.copy(alpha = 0.16f)

                    MusicCodecStatus.UNKNOWN, MusicCodecStatus.UNSUPPORTED -> PiggieTvColors.PanelHigh.copy(
                        alpha = 0.74f,
                    )
                },
                shape = RoundedCornerShape(8.dp),
                border = BorderStroke(
                    1.dp,
                    if (capability.nativePlaybackExpected || capability.isDirectPath) {
                        PiggieTvColors.Focus
                    } else {
                        PiggieTvColors.Accent
                    },
                ),
            ) {
                Text(
                    text = capability.message,
                    color = PiggieTvColors.TextPrimary,
                    style = MaterialTheme.typography.caption,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                )
            }
        }
    }
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun MusicQueueRow(item: MusicItem, isCurrent: Boolean, onClick: () -> Unit, onOpenMenu: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .combinedClickable(
                onClick = onClick,
                onLongClick = onOpenMenu,
            ),
        color = if (isCurrent) {
            PiggieTvColors.Focus.copy(alpha = 0.18f)
        } else {
            PiggieTvColors.Panel.copy(alpha = 0.74f)
        },
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(
            1.dp,
            if (isCurrent) PiggieTvColors.Focus else PiggieTvColors.Border,
        ),
    ) {
        Row(
            modifier = Modifier.padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            MusicCover(item = item, width = 42.dp)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.title,
                    color = PiggieTvColors.TextPrimary,
                    style = MaterialTheme.typography.body2,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = item.subtitle ?: item.album ?: "Track",
                    color = PiggieTvColors.TextSecondary,
                    style = MaterialTheme.typography.caption,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (isCurrent) {
                Text(
                    text = "Playing",
                    color = PiggieTvColors.AccentSoft,
                    style = MaterialTheme.typography.caption,
                )
            }
            IconButton(onClick = onOpenMenu) {
                Icon(Icons.Outlined.MoreVert, contentDescription = null, tint = PiggieTvColors.TextSecondary)
            }
        }
    }
}

@Composable
private fun MusicStatusMessage(
    message: String,
    isError: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onDismiss),
        color = if (isError) {
            PiggieTvColors.Accent.copy(alpha = 0.18f)
        } else {
            PiggieTvColors.Focus.copy(alpha = 0.18f)
        },
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(
            1.dp,
            if (isError) PiggieTvColors.Accent else PiggieTvColors.Focus,
        ),
    ) {
        Text(
            text = message,
            color = PiggieTvColors.TextPrimary,
            style = MaterialTheme.typography.caption,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun MusicPlaybackErrorCard(
    message: String,
    canRetry: Boolean,
    onRetry: () -> Unit,
    onSkip: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.clip(RoundedCornerShape(8.dp)),
        color = PiggieTvColors.Accent.copy(alpha = 0.18f),
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, PiggieTvColors.Accent),
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = message,
                color = PiggieTvColors.TextPrimary,
                style = MaterialTheme.typography.caption,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (canRetry) {
                    Button(
                        onClick = onRetry,
                        colors = ButtonDefaults.buttonColors(
                            backgroundColor = PiggieTvColors.Accent,
                            contentColor = PiggieTvColors.Night,
                        ),
                        shape = RoundedCornerShape(8.dp),
                    ) {
                        Text("Retry", fontWeight = FontWeight.Bold)
                    }
                }
                Button(
                    onClick = onSkip,
                    colors = ButtonDefaults.buttonColors(
                        backgroundColor = PiggieTvColors.PanelHigh,
                        contentColor = PiggieTvColors.TextPrimary,
                    ),
                    shape = RoundedCornerShape(8.dp),
                ) {
                    Text("Skip", fontWeight = FontWeight.Bold)
                }
                TextButton(onClick = onDismiss) {
                    Text("Dismiss", color = PiggieTvColors.FocusSoft)
                }
            }
        }
    }
}

@Composable
private fun MusicSongActionDialog(
    target: MusicSongActionUiTarget,
    isNotInterested: Boolean,
    onDismiss: () -> Unit,
    onAction: (MusicSongAction) -> Unit,
) {
    val item = target.item
    val likeLabel = if (item.isFavorite) "Unlike" else "Like"
    val notInterestedLabel = if (isNotInterested) "Undo Not Interested" else "Not Interested"
    val artistEnabled = MusicArtistNavigation.hasArtistTarget(item)

    AlertDialog(
        onDismissRequest = onDismiss,
        backgroundColor = PiggieTvColors.Panel,
        contentColor = PiggieTvColors.TextPrimary,
        shape = RoundedCornerShape(8.dp),
        title = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = item.title,
                    color = PiggieTvColors.TextPrimary,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = item.artist ?: item.album ?: "PTV Music",
                    color = PiggieTvColors.TextSecondary,
                    style = MaterialTheme.typography.caption,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                MusicSongActionRow(
                    label = "Play",
                    icon = { Icon(Icons.Outlined.PlayArrow, contentDescription = null) },
                    enabled = item.isPlayable,
                    onClick = { onAction(MusicSongAction.PLAY) },
                )
                MusicSongActionRow(
                    label = "Start Mix",
                    icon = { Icon(painterResource(R.drawable.ic_music_note_white_24dp), contentDescription = null) },
                    enabled = item.isPlayable,
                    onClick = { onAction(MusicSongAction.START_MIX) },
                )
                MusicSongActionRow(
                    label = "Artist Radio",
                    icon = { Icon(painterResource(R.drawable.ic_artist), contentDescription = null) },
                    enabled = artistEnabled,
                    onClick = { onAction(MusicSongAction.START_ARTIST_RADIO) },
                )
                MusicSongActionRow(
                    label = "Play Next",
                    icon = { Icon(painterResource(R.drawable.ic_skip_next_black_32dp), contentDescription = null) },
                    enabled = item.isPlayable,
                    onClick = { onAction(MusicSongAction.PLAY_NEXT) },
                )
                MusicSongActionRow(
                    label = "Add to Queue",
                    icon = { Icon(Icons.AutoMirrored.Outlined.PlaylistAdd, contentDescription = null) },
                    enabled = item.isPlayable,
                    onClick = { onAction(MusicSongAction.ADD_TO_QUEUE) },
                )
                MusicSongActionRow(
                    label = "Add to Playlist",
                    icon = { Icon(painterResource(R.drawable.ic_playlist), contentDescription = null) },
                    enabled = item.isPlayable,
                    onClick = { onAction(MusicSongAction.ADD_TO_PLAYLIST) },
                )
                MusicSongActionRow(
                    label = "Download",
                    icon = { Icon(painterResource(R.drawable.ic_music_note_white_24dp), contentDescription = null) },
                    enabled = true,
                    onClick = { onAction(MusicSongAction.DOWNLOAD) },
                )
                MusicSongActionRow(
                    label = "Go to Artist",
                    icon = { Icon(painterResource(R.drawable.ic_artist), contentDescription = null) },
                    enabled = artistEnabled,
                    onClick = { onAction(MusicSongAction.GO_TO_ARTIST) },
                )
                Divider(color = PiggieTvColors.Border.copy(alpha = 0.58f), modifier = Modifier.padding(vertical = 4.dp))
                MusicSongActionRow(
                    label = notInterestedLabel,
                    icon = { Icon(painterResource(R.drawable.ic_report_ptv), contentDescription = null) },
                    enabled = item.isPlayable,
                    onClick = { onAction(MusicSongAction.NOT_INTERESTED) },
                )
                MusicSongActionRow(
                    label = likeLabel,
                    icon = {
                        Icon(
                            if (item.isFavorite) Icons.Outlined.Favorite else Icons.Outlined.FavoriteBorder,
                            contentDescription = null,
                        )
                    },
                    enabled = item.isPlayable,
                    onClick = { onAction(MusicSongAction.TOGGLE_FAVORITE) },
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close", color = PiggieTvColors.FocusSoft)
            }
        },
    )
}

@Composable
private fun MusicSongActionRow(
    label: String,
    icon: @Composable () -> Unit,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(enabled = enabled, onClick = onClick),
        color = if (enabled) PiggieTvColors.PanelHigh.copy(alpha = 0.82f) else PiggieTvColors.Night.copy(alpha = 0.32f),
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, PiggieTvColors.Border.copy(alpha = if (enabled) 0.78f else 0.34f)),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                modifier = Modifier.size(24.dp),
                contentAlignment = Alignment.Center,
            ) {
                IconTintBox(
                    tint = if (enabled) {
                        PiggieTvColors.Focus
                    } else {
                        PiggieTvColors.TextSecondary.copy(alpha = 0.54f)
                    },
                    content = icon,
                )
            }
            Text(
                text = label,
                color = if (enabled) PiggieTvColors.TextPrimary else PiggieTvColors.TextSecondary.copy(alpha = 0.58f),
                style = MaterialTheme.typography.body2,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun IconTintBox(tint: androidx.compose.ui.graphics.Color, content: @Composable () -> Unit) {
    androidx.compose.runtime.CompositionLocalProvider(
        androidx.compose.material.LocalContentColor provides tint,
        content = content,
    )
}

@Composable
private fun MusicPlaylistDialog(
    state: MusicPlaylistActionState,
    onDismiss: () -> Unit,
    onAddToPlaylist: (MusicItem) -> Unit,
    onCreatePlaylist: (String) -> Unit,
) {
    var playlistName by remember(state.target?.item?.id, state.target?.type) {
        mutableStateOf(state.defaultName)
    }
    val target = state.target ?: return

    AlertDialog(
        onDismissRequest = onDismiss,
        backgroundColor = PiggieTvColors.Panel,
        contentColor = PiggieTvColors.TextPrimary,
        shape = RoundedCornerShape(8.dp),
        title = {
            Text(
                text = target.title,
                color = PiggieTvColors.TextPrimary,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = playlistName,
                    onValueChange = { playlistName = it },
                    label = { Text("New playlist name") },
                    singleLine = true,
                    colors = TextFieldDefaults.outlinedTextFieldColors(
                        textColor = PiggieTvColors.TextPrimary,
                        cursorColor = PiggieTvColors.Focus,
                        focusedBorderColor = PiggieTvColors.Focus,
                        unfocusedBorderColor = PiggieTvColors.Border,
                        focusedLabelColor = PiggieTvColors.Focus,
                        unfocusedLabelColor = PiggieTvColors.TextSecondary,
                        backgroundColor = PiggieTvColors.Night.copy(alpha = 0.48f),
                    ),
                )
                state.errorMessage?.let { error ->
                    Text(
                        text = error,
                        color = PiggieTvColors.Accent,
                        style = MaterialTheme.typography.caption,
                    )
                }
                Text(
                    text = "Existing playlists",
                    color = PiggieTvColors.TextSecondary,
                    style = MaterialTheme.typography.caption,
                    fontWeight = FontWeight.Bold,
                )
                if (state.isLoading) {
                    MusicInlineLoading()
                } else if (state.playlists.isEmpty()) {
                    MusicEmpty("No playlists yet. Create one above.")
                } else {
                    LazyColumn(
                        modifier = Modifier.heightIn(max = 220.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(state.playlists, key = { playlist -> "playlist-dialog-${playlist.id}" }) { playlist ->
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable { onAddToPlaylist(playlist) },
                                color = PiggieTvColors.PanelHigh,
                                shape = RoundedCornerShape(8.dp),
                                border = BorderStroke(1.dp, PiggieTvColors.Border),
                            ) {
                                Row(
                                    modifier = Modifier.padding(10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    Icon(
                                        painter = painterResource(R.drawable.ic_playlist),
                                        contentDescription = null,
                                        tint = PiggieTvColors.Focus,
                                    )
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = playlist.title,
                                            color = PiggieTvColors.TextPrimary,
                                            style = MaterialTheme.typography.body2,
                                            fontWeight = FontWeight.Bold,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        Text(
                                            text = playlist.subtitle ?: "Playlist",
                                            color = PiggieTvColors.TextSecondary,
                                            style = MaterialTheme.typography.caption,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onCreatePlaylist(playlistName) },
                enabled = !state.isLoading,
            ) {
                Text(target.createButtonText, color = PiggieTvColors.Accent)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = PiggieTvColors.FocusSoft)
            }
        },
    )
}

@Composable
private fun MusicRetryRow(message: String, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = PiggieTvColors.Accent.copy(alpha = 0.14f),
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, PiggieTvColors.Accent.copy(alpha = 0.64f)),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = message,
                color = PiggieTvColors.TextPrimary,
                style = MaterialTheme.typography.caption,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onRetry) {
                Text("Retry", color = PiggieTvColors.Accent)
            }
        }
    }
}

@Composable
private fun MusicSkeletonRows(modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        repeat(SKELETON_ROW_COUNT) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(74.dp),
                color = PiggieTvColors.Panel.copy(alpha = 0.64f),
                shape = RoundedCornerShape(8.dp),
                border = BorderStroke(1.dp, PiggieTvColors.Border),
            ) {
                Row(
                    modifier = Modifier.padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Surface(
                        modifier = Modifier.size(52.dp),
                        color = PiggieTvColors.PanelHigh,
                        shape = RoundedCornerShape(8.dp),
                    ) {}
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.weight(1f)) {
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth(SKELETON_TITLE_WIDTH_FRACTION)
                                .height(12.dp),
                            color = PiggieTvColors.PanelHigh,
                            shape = RoundedCornerShape(8.dp),
                        ) {}
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth(SKELETON_SUBTITLE_WIDTH_FRACTION)
                                .height(10.dp),
                            color = PiggieTvColors.PanelHigh.copy(alpha = 0.7f),
                            shape = RoundedCornerShape(8.dp),
                        ) {}
                    }
                }
            }
        }
    }
}

private fun MusicPlaybackState.progressFraction(): Float {
    return MusicPlaybackFormatting.progressFraction(positionMs = positionMs, durationMs = durationMs)
}

private fun MusicItem.supportsSourcePlaylist(): Boolean = type in setOf(
    BaseItemKind.AUDIO,
    BaseItemKind.MUSIC_ALBUM,
    BaseItemKind.MUSIC_ARTIST,
    BaseItemKind.GENRE,
    BaseItemKind.MUSIC_GENRE,
)

private data class MusicSongActionUiTarget(
    val item: MusicItem,
    val queue: List<MusicItem>,
)

private const val ACTION_MESSAGE_DURATION_MS = 2_600L
private const val SKELETON_ROW_COUNT = 4
private const val SKELETON_TITLE_WIDTH_FRACTION = 0.74f
private const val SKELETON_SUBTITLE_WIDTH_FRACTION = 0.46f

@Composable
private fun MusicLoading() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = PiggieTvColors.Focus)
    }
}

@Composable
private fun MusicInlineLoading() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 18.dp),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(24.dp),
            color = PiggieTvColors.Focus,
            strokeWidth = 2.dp,
        )
    }
}

@Composable
private fun MusicError(message: String, onRetry: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(
                painterResource(R.drawable.ic_music_note_white_24dp),
                contentDescription = null,
                tint = PiggieTvColors.Focus,
                modifier = Modifier.size(44.dp),
            )
            Text(text = message, color = PiggieTvColors.TextPrimary, style = MaterialTheme.typography.body1)
            Button(
                onClick = onRetry,
                colors = ButtonDefaults.buttonColors(
                    backgroundColor = PiggieTvColors.Accent,
                    contentColor = PiggieTvColors.Night,
                ),
                shape = MaterialTheme.shapes.medium,
            ) {
                Text(text = "Retry", fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun MusicEmpty(message: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = PiggieTvColors.Panel.copy(alpha = 0.72f),
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

private enum class MusicBrowseTab(val title: String) {
    HOME("Home"),
    ALBUMS("Albums"),
    ARTISTS("Artists"),
    SONGS("Songs"),
    GENRES("Genres"),
    PLAYLISTS("Playlists"),
    LIKED("Liked"),
}

private data class MusicAdaptiveLayout(
    val edgePadding: Dp,
    val bottomPadding: Dp,
    val heroHeight: Dp,
    val heroPadding: Dp,
    val cardWidth: Dp,
    val trackCardWidth: Dp,
    val detailCoverWidth: Dp,
    val detailHeroHeight: Dp,
) {
    companion object {
        fun forWidth(width: Dp) = when {
            width < 600.dp -> MusicAdaptiveLayout(
                edgePadding = 16.dp,
                bottomPadding = 112.dp,
                heroHeight = 182.dp,
                heroPadding = 16.dp,
                cardWidth = 132.dp,
                trackCardWidth = 150.dp,
                detailCoverWidth = 128.dp,
                detailHeroHeight = 218.dp,
            )

            width < 840.dp -> MusicAdaptiveLayout(
                edgePadding = 28.dp,
                bottomPadding = 118.dp,
                heroHeight = 214.dp,
                heroPadding = 22.dp,
                cardWidth = 150.dp,
                trackCardWidth = 166.dp,
                detailCoverWidth = 150.dp,
                detailHeroHeight = 278.dp,
            )

            else -> MusicAdaptiveLayout(
                edgePadding = 48.dp,
                bottomPadding = 124.dp,
                heroHeight = 238.dp,
                heroPadding = 28.dp,
                cardWidth = 168.dp,
                trackCardWidth = 184.dp,
                detailCoverWidth = 172.dp,
                detailHeroHeight = 330.dp,
            )
        }
    }
}
