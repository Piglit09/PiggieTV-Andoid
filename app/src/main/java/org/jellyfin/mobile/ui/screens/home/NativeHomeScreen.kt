package org.jellyfin.mobile.ui.screens.home

import android.net.Uri
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.AlertDialog
import androidx.compose.material.Button
import androidx.compose.material.ButtonDefaults
import androidx.compose.material.Card
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.DropdownMenu
import androidx.compose.material.DropdownMenuItem
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.LinearProgressIndicator
import androidx.compose.material.MaterialTheme
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.material.TextFieldDefaults
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.Casino
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import coil3.compose.AsyncImage
import kotlinx.coroutines.launch
import org.jellyfin.mobile.R
import org.jellyfin.mobile.data.entity.ServerEntity
import org.jellyfin.mobile.feature.library.LibraryScreen
import org.jellyfin.mobile.feature.library.LibraryViewModel
import org.jellyfin.mobile.player.interaction.PlayOptions
import org.jellyfin.mobile.reporting.MediaReportReason
import org.jellyfin.mobile.signup.NativeSignupRepository
import org.jellyfin.mobile.signup.NativeSignupRequest
import org.jellyfin.mobile.ui.utils.PiggieTvBackground
import org.jellyfin.mobile.ui.utils.PiggieTvColors
import org.jellyfin.mobile.utils.Constants
import org.jellyfin.sdk.model.api.BaseItemKind
import org.koin.compose.koinInject
import kotlin.time.Duration.Companion.ZERO

@Composable
fun NativeHomeScreen(
    server: ServerEntity,
    viewModel: NativeHomeViewModel,
    libraryViewModel: LibraryViewModel,
    onOpenDownloads: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenDashboard: (String) -> Unit,
    onOpenExternalUrl: (String) -> Unit,
    onOpenLibraryLink: (String) -> Unit,
    onDownloadLibraryBook: (Uri, String, String) -> Unit,
    onReadLibraryBook: (Uri, String, String, String?) -> Unit,
    onSelectServer: () -> Unit,
    onPlay: (PlayOptions) -> Unit,
    onBackHandlerChanged: ((() -> Boolean)?) -> Unit,
) {
    val uiState by viewModel.uiState.collectAsState()
    var reportItem by remember { mutableStateOf<NativeMediaItem?>(null) }
    var detailsSelection by remember { mutableStateOf<NativeMediaDetailsSelection?>(null) }
    var detailsHistory by remember { mutableStateOf<List<NativeMediaDetailsSelection>>(emptyList()) }

    fun openDetails(item: NativeMediaItem, siblings: List<NativeMediaItem>) {
        detailsSelection = NativeMediaDetailsSelection(item, siblings)
    }

    fun goBackFromDetails() {
        val parent = detailsHistory.lastOrNull()
        if (parent == null) {
            val closingDetails = detailsSelection
            detailsSelection = null
            if (closingDetails?.siblings?.isEmpty() == true && (uiState as? NativeHomeUiState.Content)?.selectedLibrary != null) {
                viewModel.closeLibrary()
            }
        } else {
            detailsHistory = detailsHistory.dropLast(1)
            detailsSelection = parent
        }
    }

    fun openFolderFromDetails(item: NativeMediaItem) {
        detailsSelection?.let { currentDetails ->
            detailsHistory = detailsHistory + currentDetails
        }
        detailsSelection = null
        viewModel.openFolder(item)
    }

    fun openChildDetails(item: NativeMediaItem, siblings: List<NativeMediaItem>) {
        detailsSelection?.let { currentDetails ->
            detailsHistory = detailsHistory + currentDetails
        }
        detailsSelection = NativeMediaDetailsSelection(item, siblings)
    }

    fun closeLibraryOrDetailsParent() {
        val parent = detailsHistory.lastOrNull()
        if (parent == null) {
            viewModel.closeLibrary()
        } else {
            detailsHistory = detailsHistory.dropLast(1)
            detailsSelection = parent
        }
    }

    LaunchedEffect(server.id) {
        viewModel.load(server)
        detailsSelection = null
        detailsHistory = emptyList()
    }

    SideEffect {
        onBackHandlerChanged {
            val state = uiState
            when {
                reportItem != null -> {
                    reportItem = null
                    true
                }
                detailsSelection != null -> {
                    goBackFromDetails()
                    true
                }
                state is NativeHomeUiState.Content && state.selectedLibrary != null -> {
                    closeLibraryOrDetailsParent()
                    true
                }
                else -> false
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose { onBackHandlerChanged(null) }
    }

    PiggieTvBackground(modifier = Modifier.fillMaxSize()) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val layout = remember(maxWidth) { PtvAdaptiveLayout.forWidth(maxWidth) }

            when (val state = uiState) {
                NativeHomeUiState.Loading -> LoadingScreen(layout = layout)
                is NativeHomeUiState.Login -> LoginScreen(
                    layout = layout,
                    state = state,
                    onSignIn = { username, password -> viewModel.signIn(server, username, password) },
                    onOpenDiscord = { onOpenExternalUrl(Constants.PIGGIETV_DISCORD_URL) },
                    onSelectServer = onSelectServer,
                )
                is NativeHomeUiState.Content -> {
                    val details = detailsSelection
                    if (details != null) {
                        MediaDetailsScreen(
                            layout = layout,
                            selection = details,
                            onBack = ::goBackFromDetails,
                            onPlay = { item, siblings -> onPlay(item.toPlayOptions(siblings)) },
                            onOpenFolder = ::openFolderFromDetails,
                            onOpenChild = ::openChildDetails,
                            loadChildren = viewModel::loadFolderItems,
                            onReport = { item -> reportItem = item },
                        )
                    } else if (state.selectedLibrary == null) {
                        HomeContent(
                            layout = layout,
                            state = state,
                            onRandomPlay = { viewModel.playRandomTitle(onPlay) },
                            onOpenDownloads = onOpenDownloads,
                            onOpenSettings = onOpenSettings,
                            onOpenDashboard = onOpenDashboard,
                            libraryViewModel = libraryViewModel,
                            onOpenLibraryLink = onOpenLibraryLink,
                            onDownloadLibraryBook = onDownloadLibraryBook,
                            onReadLibraryBook = onReadLibraryBook,
                            onBackHandlerChanged = onBackHandlerChanged,
                            onSignOut = {
                                detailsSelection = null
                                detailsHistory = emptyList()
                                viewModel.signOut(server)
                            },
                            onItemClick = { item ->
                                detailsHistory = emptyList()
                                openDetails(item, emptyList())
                            },
                            onItemPlay = { item, siblings ->
                                onPlay(item.toPlayOptions(siblings))
                            },
                            onReportItem = { item -> reportItem = item },
                            onLibraryClick = viewModel::openLibrary,
                        )
                    } else {
                        LibraryContent(
                            layout = layout,
                            state = state,
                            onBack = ::closeLibraryOrDetailsParent,
                            onItemClick = { item ->
                                val siblings = state.selectedLibrary.items
                                openDetails(item, siblings)
                            },
                            onItemPlay = { item, siblings ->
                                onPlay(item.toPlayOptions(siblings))
                            },
                            onReportItem = { item -> reportItem = item },
                        )
                    }
                }
                is NativeHomeUiState.Error -> ErrorScreen(
                    layout = layout,
                    message = state.message,
                    onRetry = { viewModel.load(server, force = true) },
                    onSelectServer = onSelectServer,
                )
            }

            reportItem?.let { item ->
                MediaReportDialog(
                    item = item,
                    onDismiss = { reportItem = null },
                    onSubmit = { reason, details ->
                        viewModel.submitMediaReport(item, reason, details)
                        reportItem = null
                    },
                )
            }
        }
    }
}

@Composable
private fun LoadingScreen(layout: PtvAdaptiveLayout) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Image(
                painter = painterResource(R.drawable.app_logo),
                contentDescription = null,
                modifier = Modifier
                    .fillMaxWidth(0.72f)
                    .widthIn(max = layout.loginMaxWidth)
                    .height(layout.loadingLogoHeight),
                contentScale = ContentScale.Fit,
            )
            CircularProgressIndicator(color = PiggieTvColors.Focus)
        }
    }
}

@Composable
private fun LoginScreen(
    layout: PtvAdaptiveLayout,
    state: NativeHomeUiState.Login,
    onSignIn: (String, String) -> Unit,
    onOpenDiscord: () -> Unit,
    onSelectServer: () -> Unit,
) {
    var username by rememberSaveable(state.serverName) { mutableStateOf(state.username) }
    var password by rememberSaveable(state.serverName) { mutableStateOf("") }
    var showSignup by rememberSaveable(state.serverName) { mutableStateOf(false) }

    if (showSignup) {
        NativeSignupScreen(
            layout = layout,
            server = state.serverName,
            onClose = { showSignup = false },
        )
        return
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding(),
        contentPadding = PaddingValues(horizontal = layout.edgePadding, vertical = layout.topPadding),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        item {
            Image(
                painter = painterResource(R.drawable.app_logo),
                contentDescription = null,
                modifier = Modifier
                    .fillMaxWidth(0.84f)
                    .widthIn(max = layout.loginMaxWidth)
                    .height(layout.loginLogoHeight),
                contentScale = ContentScale.Fit,
            )
        }
        item {
            Text(
                text = "Sign in to PiggieTV",
                color = PiggieTvColors.TextPrimary,
                style = MaterialTheme.typography.h5,
                fontWeight = FontWeight.Bold,
            )
        }
        item {
            Text(
                text = state.serverName,
                color = PiggieTvColors.TextSecondary,
                style = MaterialTheme.typography.body2,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (state.publicUsers.isNotEmpty()) {
            item {
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(horizontal = 2.dp),
                ) {
                    items(state.publicUsers) { user ->
                        Surface(
                            shape = MaterialTheme.shapes.medium,
                            color = if (username == user) PiggieTvColors.Focus.copy(alpha = 0.24f) else PiggieTvColors.Panel.copy(alpha = 0.82f),
                            border = BorderStroke(1.dp, PiggieTvColors.Border),
                            modifier = Modifier.clickable { username = user },
                        ) {
                            Text(
                                text = user,
                                color = PiggieTvColors.TextPrimary,
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
                                style = MaterialTheme.typography.body2,
                            )
                        }
                    }
                }
            }
        }
        item {
            PiggieTextField(
                value = username,
                onValueChange = { username = it },
                label = "Username",
                imeAction = ImeAction.Next,
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = layout.loginMaxWidth),
            )
        }
        item {
            PiggieTextField(
                value = password,
                onValueChange = { password = it },
                label = "Password",
                imeAction = ImeAction.Go,
                isPassword = true,
                onGo = { if (username.isNotBlank()) onSignIn(username, password) },
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = layout.loginMaxWidth),
            )
        }
        state.error?.let { error ->
            item {
                Text(
                    text = error,
                    color = PiggieTvColors.Accent,
                    style = MaterialTheme.typography.caption,
                    modifier = Modifier
                        .fillMaxWidth()
                        .widthIn(max = layout.loginMaxWidth),
                )
            }
        }
        item {
            Button(
                onClick = { onSignIn(username, password) },
                enabled = username.isNotBlank() && !state.isSigningIn,
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = layout.loginMaxWidth)
                    .heightIn(min = 50.dp),
                colors = ButtonDefaults.buttonColors(
                    backgroundColor = PiggieTvColors.Accent,
                    contentColor = PiggieTvColors.Night,
                    disabledBackgroundColor = PiggieTvColors.PanelHigh,
                    disabledContentColor = PiggieTvColors.TextSecondary,
                ),
                shape = MaterialTheme.shapes.medium,
            ) {
                if (state.isSigningIn) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        color = PiggieTvColors.TextPrimary,
                        strokeWidth = 2.dp,
                    )
                } else {
                    Text(text = "Sign In", fontWeight = FontWeight.Bold)
                }
            }
        }
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = layout.loginMaxWidth),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Button(
                    onClick = { showSignup = true },
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 46.dp),
                    colors = ButtonDefaults.buttonColors(
                        backgroundColor = PiggieTvColors.Focus,
                        contentColor = PiggieTvColors.Night,
                    ),
                    shape = MaterialTheme.shapes.medium,
                ) {
                    Icon(Icons.Outlined.PersonAdd, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(text = "Sign Up", fontWeight = FontWeight.Bold)
                }
                Button(
                    onClick = onOpenDiscord,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 46.dp),
                    colors = ButtonDefaults.buttonColors(
                        backgroundColor = Color(0xFF5865F2),
                        contentColor = PiggieTvColors.TextPrimary,
                    ),
                    shape = MaterialTheme.shapes.medium,
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_discord_ptv),
                        contentDescription = null,
                        tint = Color.Unspecified,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(text = "Discord", fontWeight = FontWeight.Bold)
                }
            }
        }
        item {
            TextButton(onClick = onSelectServer) {
                Text(text = "Use a different server", color = PiggieTvColors.FocusSoft)
            }
        }
        item {
            Text(
                text = stringResource(R.string.app_version_label, Constants.APP_INFO_VERSION),
                color = PiggieTvColors.TextSecondary,
                style = MaterialTheme.typography.caption,
            )
        }
    }
}

@Composable
private fun NativeSignupScreen(
    layout: PtvAdaptiveLayout,
    server: String,
    onClose: () -> Unit,
    signupRepository: NativeSignupRepository = koinInject(),
) {
    val scope = rememberCoroutineScope()
    var email by rememberSaveable(server) { mutableStateOf("") }
    var username by rememberSaveable(server) { mutableStateOf("") }
    var password by rememberSaveable(server) { mutableStateOf("") }
    var confirmPassword by rememberSaveable(server) { mutableStateOf("") }
    var isSubmitting by rememberSaveable(server) { mutableStateOf(false) }
    var created by rememberSaveable(server) { mutableStateOf(false) }
    var message by rememberSaveable(server) { mutableStateOf<String?>(null) }
    var messageTone by rememberSaveable(server) { mutableStateOf(SignupMessageTone.ERROR) }

    fun showError(text: String) {
        created = false
        messageTone = SignupMessageTone.ERROR
        message = text
    }

    fun submitSignup() {
        if (isSubmitting) return

        validateNativeSignup(email, username, password, confirmPassword)?.let { validationError ->
            showError(validationError)
            return
        }

        isSubmitting = true
        message = null
        scope.launch {
            runCatching {
                signupRepository.createUser(
                    serverUrl = server,
                    signupRequest = NativeSignupRequest(
                        email = email,
                        username = username,
                        password = password,
                        confirmPassword = confirmPassword,
                    ),
                )
            }.onSuccess { result ->
                password = ""
                confirmPassword = ""
                created = true
                messageTone = SignupMessageTone.SUCCESS
                message = result.message
            }.onFailure { error ->
                created = false
                messageTone = SignupMessageTone.ERROR
                message = error.message ?: "PiggieTV signup failed. Please try again."
            }
            isSubmitting = false
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = layout.edgePadding / 2, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onClose) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = null, tint = PiggieTvColors.TextPrimary)
            }
            Text(
                text = "Create PiggieTV Account",
                color = PiggieTvColors.TextPrimary,
                style = MaterialTheme.typography.h6,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            contentPadding = PaddingValues(horizontal = layout.edgePadding, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            item {
                Image(
                    painter = painterResource(R.drawable.app_logo),
                    contentDescription = null,
                    modifier = Modifier
                        .fillMaxWidth(0.68f)
                        .widthIn(max = layout.loginMaxWidth)
                        .height(layout.loadingLogoHeight),
                    contentScale = ContentScale.Fit,
                )
            }
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .widthIn(max = layout.loginMaxWidth),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = "Create your account",
                        color = PiggieTvColors.TextPrimary,
                        style = MaterialTheme.typography.h5,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = server,
                        color = PiggieTvColors.TextSecondary,
                        style = MaterialTheme.typography.body2,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            item {
                PiggieTextField(
                    value = email,
                    onValueChange = {
                        email = it
                        created = false
                    },
                    label = "Email",
                    imeAction = ImeAction.Next,
                    keyboardType = KeyboardType.Email,
                    modifier = Modifier
                        .fillMaxWidth()
                        .widthIn(max = layout.loginMaxWidth),
                )
            }
            item {
                PiggieTextField(
                    value = username,
                    onValueChange = {
                        username = it
                        created = false
                    },
                    label = "Username",
                    imeAction = ImeAction.Next,
                    modifier = Modifier
                        .fillMaxWidth()
                        .widthIn(max = layout.loginMaxWidth),
                )
            }
            item {
                PiggieTextField(
                    value = password,
                    onValueChange = {
                        password = it
                        created = false
                    },
                    label = "Password",
                    imeAction = ImeAction.Next,
                    isPassword = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .widthIn(max = layout.loginMaxWidth),
                )
            }
            item {
                PiggieTextField(
                    value = confirmPassword,
                    onValueChange = {
                        confirmPassword = it
                        created = false
                    },
                    label = "Confirm Password",
                    imeAction = ImeAction.Go,
                    isPassword = true,
                    onGo = ::submitSignup,
                    modifier = Modifier
                        .fillMaxWidth()
                        .widthIn(max = layout.loginMaxWidth),
                )
            }
            message?.let { text ->
                item {
                    val isSuccess = messageTone == SignupMessageTone.SUCCESS
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .widthIn(max = layout.loginMaxWidth),
                        color = if (isSuccess) PiggieTvColors.Focus.copy(alpha = 0.14f) else PiggieTvColors.Accent.copy(alpha = 0.14f),
                        shape = MaterialTheme.shapes.medium,
                        border = BorderStroke(
                            1.dp,
                            if (isSuccess) PiggieTvColors.Focus.copy(alpha = 0.54f) else PiggieTvColors.Accent.copy(alpha = 0.54f),
                        ),
                    ) {
                        Text(
                            text = text,
                            color = if (isSuccess) PiggieTvColors.FocusSoft else PiggieTvColors.Accent,
                            style = MaterialTheme.typography.body2,
                            modifier = Modifier.padding(14.dp),
                        )
                    }
                }
            }
            item {
                Button(
                    onClick = { if (created) onClose() else submitSignup() },
                    enabled = created || (!isSubmitting && email.isNotBlank() && username.isNotBlank() && password.isNotBlank()),
                    modifier = Modifier
                        .fillMaxWidth()
                        .widthIn(max = layout.loginMaxWidth)
                        .heightIn(min = 50.dp),
                    colors = ButtonDefaults.buttonColors(
                        backgroundColor = if (created) PiggieTvColors.Focus else PiggieTvColors.Accent,
                        contentColor = PiggieTvColors.Night,
                        disabledBackgroundColor = PiggieTvColors.PanelHigh,
                        disabledContentColor = PiggieTvColors.TextSecondary,
                    ),
                    shape = MaterialTheme.shapes.medium,
                ) {
                    if (isSubmitting) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            color = PiggieTvColors.TextPrimary,
                            strokeWidth = 2.dp,
                        )
                    } else {
                        if (!created) {
                            Icon(Icons.Outlined.PersonAdd, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                        }
                        Text(text = if (created) "Back to Sign In" else "Create Account", fontWeight = FontWeight.Bold)
                    }
                }
            }
            if (!created) {
                item {
                    TextButton(onClick = onClose) {
                        Text(text = "Already have an account? Sign In", color = PiggieTvColors.FocusSoft)
                    }
                }
            }
        }
    }
}
@Composable
private fun HomeContent(
    layout: PtvAdaptiveLayout,
    state: NativeHomeUiState.Content,
    onRandomPlay: () -> Unit,
    onOpenDownloads: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenDashboard: (String) -> Unit,
    libraryViewModel: LibraryViewModel,
    onOpenLibraryLink: (String) -> Unit,
    onDownloadLibraryBook: (Uri, String, String) -> Unit,
    onReadLibraryBook: (Uri, String, String, String?) -> Unit,
    onBackHandlerChanged: ((() -> Boolean)?) -> Unit,
    onSignOut: () -> Unit,
    onItemClick: (NativeMediaItem) -> Unit,
    onItemPlay: (NativeMediaItem, List<NativeMediaItem>) -> Unit,
    onReportItem: (NativeMediaItem) -> Unit,
    onLibraryClick: (NativeMediaItem) -> Unit,
) {
    var activeTab by rememberSaveable { mutableStateOf(NativeHomeTab.HOME) }
    val homeListState = rememberLazyListState()
    val showHeader by remember {
        derivedStateOf {
            activeTab != NativeHomeTab.HOME ||
                (homeListState.firstVisibleItemIndex == 0 && homeListState.firstVisibleItemScrollOffset < 24)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding(),
    ) {
        AnimatedVisibility(visible = showHeader) {
            HomeTopBar(
                layout = layout,
                userName = state.home.userName,
                dashboardUrl = state.home.dashboardUrl.takeIf { state.home.isAdmin },
                onRandomPlay = onRandomPlay,
                onOpenDownloads = onOpenDownloads,
                onOpenSettings = onOpenSettings,
                onOpenDashboard = onOpenDashboard,
                onSignOut = onSignOut,
            )
        }
        HomeTabs(
            layout = layout,
            activeTab = activeTab,
            onSelectTab = { tab -> activeTab = tab },
        )

        when (activeTab) {
            NativeHomeTab.HOME -> HomeRows(
                layout = layout,
                state = state,
                listState = homeListState,
                onItemClick = onItemClick,
                onItemPlay = onItemPlay,
                onReportItem = onReportItem,
                onLibraryClick = onLibraryClick,
            )
            NativeHomeTab.REQUESTS -> RequestsPortal(
                layout = layout,
                modifier = Modifier.weight(1f),
            )
            NativeHomeTab.LIBRARY -> LibraryScreen(
                viewModel = libraryViewModel,
                onOpenLink = onOpenLibraryLink,
                onDownload = onDownloadLibraryBook,
                onRead = onReadLibraryBook,
                onBackHandlerChanged = onBackHandlerChanged,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun HomeRows(
    layout: PtvAdaptiveLayout,
    state: NativeHomeUiState.Content,
    listState: androidx.compose.foundation.lazy.LazyListState,
    onItemClick: (NativeMediaItem) -> Unit,
    onItemPlay: (NativeMediaItem, List<NativeMediaItem>) -> Unit,
    onReportItem: (NativeMediaItem) -> Unit,
    onLibraryClick: (NativeMediaItem) -> Unit,
) {
    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize(),
        contentPadding = PaddingValues(bottom = layout.bottomPadding),
        verticalArrangement = Arrangement.spacedBy(layout.sectionSpacing),
    ) {
        state.home.hero?.let { hero ->
            item {
                HeroBanner(
                    layout = layout,
                    item = hero,
                    onClick = { onItemClick(hero) },
                    onPlay = { onItemPlay(hero, emptyList()) },
                    onReport = { onReportItem(hero) },
                )
            }
        }
        items(state.home.sections) { section ->
            MediaSection(
                layout = layout,
                section = section,
                onReportItem = onReportItem,
                onItemPlay = { item -> onItemPlay(item, section.items) },
                onItemClick = { item ->
                    if (section.opensLibraries) onLibraryClick(item) else onItemClick(item)
                },
            )
        }
    }
}

@Composable
private fun HomeTabs(layout: PtvAdaptiveLayout, activeTab: NativeHomeTab, onSelectTab: (NativeHomeTab) -> Unit) {
    Surface(
        modifier = Modifier
            .padding(horizontal = layout.edgePadding, vertical = 4.dp)
            .fillMaxWidth()
            .height(46.dp),
        color = PiggieTvColors.Night.copy(alpha = 0.58f),
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, PiggieTvColors.Border),
    ) {
        Row(
            modifier = Modifier.padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            HomeTabButton(
                text = "Watch",
                selected = activeTab == NativeHomeTab.HOME,
                onClick = { onSelectTab(NativeHomeTab.HOME) },
                modifier = Modifier.weight(1f),
            )
            HomeTabButton(
                text = "Requests",
                selected = activeTab == NativeHomeTab.REQUESTS,
                onClick = { onSelectTab(NativeHomeTab.REQUESTS) },
                modifier = Modifier.weight(1f),
            )
            HomeTabButton(
                text = "Library",
                selected = activeTab == NativeHomeTab.LIBRARY,
                onClick = { onSelectTab(NativeHomeTab.LIBRARY) },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun HomeTabButton(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier
            .fillMaxHeight()
            .clip(RoundedCornerShape(6.dp))
            .clickable(onClick = onClick),
        color = if (selected) PiggieTvColors.PanelHigh else PiggieTvColors.Night.copy(alpha = 0.04f),
        contentColor = if (selected) PiggieTvColors.TextPrimary else PiggieTvColors.TextSecondary,
        shape = RoundedCornerShape(6.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = text,
                style = MaterialTheme.typography.body2,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun RequestsPortal(layout: PtvAdaptiveLayout, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier
            .padding(horizontal = layout.edgePadding, vertical = 12.dp)
            .fillMaxWidth(),
        color = PiggieTvColors.Panel.copy(alpha = 0.84f),
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, PiggieTvColors.Border),
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                WebView(context).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.cacheMode = WebSettings.LOAD_DEFAULT
                    settings.mediaPlaybackRequiresUserGesture = false
                    webViewClient = WebViewClient()
                    loadUrl(Constants.PIGGIETV_REQUESTS_URL)
                }
            },
        )
    }
}

@Composable
private fun LibraryContent(
    layout: PtvAdaptiveLayout,
    state: NativeHomeUiState.Content,
    onBack: () -> Unit,
    onItemClick: (NativeMediaItem) -> Unit,
    onItemPlay: (NativeMediaItem, List<NativeMediaItem>) -> Unit,
    onReportItem: (NativeMediaItem) -> Unit,
) {
    val library = requireNotNull(state.selectedLibrary)
    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = layout.edgePadding / 2, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                    contentDescription = null,
                    tint = PiggieTvColors.TextPrimary,
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = library.title,
                    color = PiggieTvColors.TextPrimary,
                    style = MaterialTheme.typography.h6,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                library.subtitle?.let {
                    Text(text = it, color = PiggieTvColors.TextSecondary, style = MaterialTheme.typography.caption)
                }
            }
            if (state.isLoadingLibrary) {
                CircularProgressIndicator(
                    modifier = Modifier
                        .padding(horizontal = 12.dp)
                        .size(22.dp),
                    color = PiggieTvColors.Focus,
                    strokeWidth = 2.dp,
                )
            }
        }
        library.error?.let {
            Text(
                text = it,
                color = PiggieTvColors.Accent,
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp),
            )
        }
        if (library.items.isEmpty() && library.error == null) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(text = "Nothing here yet", color = PiggieTvColors.TextSecondary)
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(layout.gridMinWidth),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = layout.edgePadding, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(layout.gridSpacing),
                horizontalArrangement = Arrangement.spacedBy(layout.gridSpacing),
            ) {
                items(library.items, key = { item -> item.id.toString() }) { item ->
                    PosterCard(
                        layout = layout,
                        item = item,
                        onClick = { onItemClick(item) },
                        onPlay = { onItemPlay(item, library.items) },
                        onReport = { onReportItem(item) },
                        compact = false,
                    )
                }
            }
        }
    }
}

@Composable
private fun HomeTopBar(
    layout: PtvAdaptiveLayout,
    userName: String,
    dashboardUrl: String?,
    onRandomPlay: () -> Unit,
    onOpenDownloads: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenDashboard: (String) -> Unit,
    onSignOut: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = layout.edgePadding, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Image(
                painter = painterResource(R.drawable.app_logo),
                contentDescription = null,
                modifier = Modifier
                    .width(layout.topLogoWidth)
                    .height(layout.topLogoHeight),
                contentScale = ContentScale.Fit,
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Welcome back",
                    color = PiggieTvColors.TextSecondary,
                    style = MaterialTheme.typography.caption,
                )
                Text(
                    text = userName,
                    color = PiggieTvColors.TextPrimary,
                    style = MaterialTheme.typography.subtitle1,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (!layout.compactTopBar) {
                HomeActionButtons(
                    dashboardUrl = dashboardUrl,
                    onRandomPlay = onRandomPlay,
                    onOpenDownloads = onOpenDownloads,
                    onOpenSettings = onOpenSettings,
                    onOpenDashboard = onOpenDashboard,
                    onSignOut = onSignOut,
                )
            }
        }
        if (layout.compactTopBar) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                HomeActionButtons(
                    dashboardUrl = dashboardUrl,
                    onRandomPlay = onRandomPlay,
                    onOpenDownloads = onOpenDownloads,
                    onOpenSettings = onOpenSettings,
                    onOpenDashboard = onOpenDashboard,
                    onSignOut = onSignOut,
                )
            }
        }
    }
}

@Composable
private fun HomeActionButtons(
    dashboardUrl: String?,
    onRandomPlay: () -> Unit,
    onOpenDownloads: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenDashboard: (String) -> Unit,
    onSignOut: () -> Unit,
) {
    IconButton(onClick = onRandomPlay) {
        Icon(Icons.Outlined.Casino, contentDescription = null, tint = PiggieTvColors.Focus)
    }
    IconButton(onClick = onOpenDownloads) {
        Icon(Icons.Outlined.CloudDownload, contentDescription = null, tint = PiggieTvColors.Focus)
    }
    dashboardUrl?.let { url ->
        IconButton(onClick = { onOpenDashboard(url) }) {
            Icon(Icons.Outlined.Dashboard, contentDescription = null, tint = PiggieTvColors.Focus)
        }
    }
    IconButton(onClick = onOpenSettings) {
        Icon(Icons.Outlined.Settings, contentDescription = null, tint = PiggieTvColors.Focus)
    }
    IconButton(onClick = onSignOut) {
        Icon(Icons.AutoMirrored.Outlined.Logout, contentDescription = "Sign out", tint = PiggieTvColors.Accent)
    }
}

@Composable
private fun HeroBanner(
    layout: PtvAdaptiveLayout,
    item: NativeMediaItem,
    onClick: () -> Unit,
    onPlay: () -> Unit,
    onReport: () -> Unit,
) {
    Card(
        modifier = Modifier
            .padding(horizontal = layout.edgePadding)
            .fillMaxWidth()
            .height(layout.heroHeight)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(8.dp),
        backgroundColor = PiggieTvColors.PanelHigh,
        border = BorderStroke(1.dp, PiggieTvColors.Border),
        elevation = 0.dp,
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
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
                        Brush.horizontalGradient(
                            listOf(
                                PiggieTvColors.Night.copy(alpha = 0.92f),
                                PiggieTvColors.Panel.copy(alpha = 0.72f),
                                PiggieTvColors.Night.copy(alpha = 0.16f),
                            ),
                        ),
                    ),
            )
            Surface(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(10.dp),
                color = PiggieTvColors.Night.copy(alpha = 0.78f),
                shape = RoundedCornerShape(8.dp),
                border = BorderStroke(1.dp, PiggieTvColors.Border),
            ) {
                IconButton(onClick = onReport, modifier = Modifier.size(42.dp)) {
                    Icon(
                        Icons.Outlined.Flag,
                        contentDescription = "Report media",
                        tint = PiggieTvColors.Focus,
                    )
                }
            }
            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth(layout.heroTextWidthFraction)
                    .padding(layout.heroPadding),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = item.title,
                    color = PiggieTvColors.TextPrimary,
                    style = MaterialTheme.typography.h5,
                    fontWeight = FontWeight.ExtraBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                item.overview?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        text = it,
                        color = PiggieTvColors.TextSecondary,
                        style = MaterialTheme.typography.body2,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (item.isPlayable) {
                    Surface(
                        modifier = Modifier
                            .clip(MaterialTheme.shapes.medium)
                            .clickable(onClick = onPlay),
                        color = PiggieTvColors.Accent,
                        contentColor = PiggieTvColors.Night,
                        shape = MaterialTheme.shapes.medium,
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Icon(Icons.Outlined.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                            Text(text = "Play", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MediaSection(
    layout: PtvAdaptiveLayout,
    section: NativeMediaSection,
    onReportItem: (NativeMediaItem) -> Unit,
    onItemPlay: (NativeMediaItem) -> Unit,
    onItemClick: (NativeMediaItem) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (section.showGroupHeader && section.groupTitle != null) {
            Column(
                modifier = Modifier.padding(horizontal = layout.edgePadding),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                section.groupKicker?.let { kicker ->
                    Text(
                        text = kicker.uppercase(),
                        color = PiggieTvColors.FocusSoft,
                        style = MaterialTheme.typography.overline,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    text = section.groupTitle,
                    color = PiggieTvColors.TextPrimary,
                    style = MaterialTheme.typography.h5,
                    fontWeight = FontWeight.ExtraBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Column(
            modifier = Modifier.padding(horizontal = layout.edgePadding),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = section.title,
                color = PiggieTvColors.TextPrimary,
                style = MaterialTheme.typography.h6,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            section.rowKicker?.let { kicker ->
                Text(
                    text = kicker,
                    color = PiggieTvColors.TextSecondary,
                    style = MaterialTheme.typography.caption,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(layout.rowSpacing),
            contentPadding = PaddingValues(horizontal = layout.edgePadding),
        ) {
            items(section.items, key = { item -> item.id.toString() }) { item ->
                PosterCard(
                    layout = layout,
                    item = item,
                    onClick = { onItemClick(item) },
                    onPlay = { onItemPlay(item) },
                    onReport = { onReportItem(item) },
                    compact = true,
                    shape = section.shape,
                    widthOverride = layout.rowCardWidth(section),
                )
            }
        }
    }
}

@Composable
private fun PosterCard(
    layout: PtvAdaptiveLayout,
    item: NativeMediaItem,
    onClick: () -> Unit,
    onPlay: () -> Unit,
    onReport: () -> Unit,
    compact: Boolean,
    shape: PtvRowShape = PtvRowShape.PORTRAIT,
    widthOverride: Dp? = null,
) {
    val width = widthOverride ?: if (compact) layout.rowPosterWidth else layout.gridPosterWidth
    val aspectRatio = when (shape) {
        PtvRowShape.BACKDROP -> 16f / 9f
        PtvRowShape.SQUARE -> 1f
        PtvRowShape.PORTRAIT -> 0.68f
    }
    Column(
        modifier = Modifier
            .width(width)
            .clickable(onClick = onClick),
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(aspectRatio),
            shape = RoundedCornerShape(8.dp),
            backgroundColor = PiggieTvColors.PanelHigh,
            border = BorderStroke(1.dp, PiggieTvColors.Border),
            elevation = 0.dp,
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                AsyncImage(
                    model = item.posterUrl ?: item.backdropUrl,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                    placeholder = painterResource(R.drawable.ic_splash),
                    fallback = painterResource(R.drawable.ic_splash),
                )
                Surface(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(7.dp),
                    color = PiggieTvColors.Night.copy(alpha = 0.78f),
                    shape = RoundedCornerShape(8.dp),
                ) {
                    IconButton(onClick = onReport, modifier = Modifier.size(32.dp)) {
                        Icon(
                            Icons.Outlined.Flag,
                            contentDescription = "Report media",
                            tint = PiggieTvColors.Focus,
                            modifier = Modifier.size(17.dp),
                        )
                    }
                }
                if (item.isPlayable) {
                    Surface(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(7.dp),
                        color = PiggieTvColors.Night.copy(alpha = 0.78f),
                        shape = RoundedCornerShape(8.dp),
                    ) {
                        IconButton(onClick = onPlay, modifier = Modifier.size(32.dp)) {
                            Icon(
                                Icons.Outlined.PlayArrow,
                                contentDescription = "Play",
                                tint = PiggieTvColors.Focus,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                }
                item.progress?.takeIf { it > 0f }?.let { progress ->
                    LinearProgressIndicator(
                        progress = (progress / 100f).coerceIn(0f, 1f),
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .fillMaxWidth()
                            .height(4.dp),
                        color = PiggieTvColors.Accent,
                        backgroundColor = PiggieTvColors.Night.copy(alpha = 0.72f),
                    )
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
private fun MediaDetailsScreen(
    layout: PtvAdaptiveLayout,
    selection: NativeMediaDetailsSelection,
    onBack: () -> Unit,
    onPlay: (NativeMediaItem, List<NativeMediaItem>) -> Unit,
    onOpenFolder: (NativeMediaItem) -> Unit,
    onOpenChild: (NativeMediaItem, List<NativeMediaItem>) -> Unit,
    loadChildren: suspend (NativeMediaItem) -> List<NativeMediaItem>,
    onReport: (NativeMediaItem) -> Unit,
) {
    val mediaItem = selection.item
    val childItems by produceState<List<NativeMediaItem>?>(initialValue = null, mediaItem.id) {
        value = if (mediaItem.showsChildrenOnDetails) {
            runCatching { loadChildren(mediaItem) }.getOrDefault(emptyList())
        } else {
            emptyList()
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding(),
        contentPadding = PaddingValues(bottom = layout.bottomPadding),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        item {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(layout.heroHeight + 42.dp),
            ) {
                AsyncImage(
                    model = mediaItem.backdropUrl ?: mediaItem.posterUrl,
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
                                listOf(
                                    PiggieTvColors.Night.copy(alpha = 0.26f),
                                    PiggieTvColors.Night.copy(alpha = 0.76f),
                                    PiggieTvColors.Night,
                                ),
                            ),
                        ),
                )
                Row(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .fillMaxWidth()
                        .padding(horizontal = layout.edgePadding / 2, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = null, tint = PiggieTvColors.TextPrimary)
                    }
                    Spacer(modifier = Modifier.weight(1f))
                    IconButton(onClick = { onReport(mediaItem) }) {
                        Icon(Icons.Outlined.Flag, contentDescription = "Report media", tint = PiggieTvColors.Focus)
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
                Card(
                    modifier = Modifier
                        .width(layout.gridPosterWidth)
                        .aspectRatio(0.68f),
                    shape = RoundedCornerShape(8.dp),
                    backgroundColor = PiggieTvColors.PanelHigh,
                    border = BorderStroke(1.dp, PiggieTvColors.Border),
                    elevation = 0.dp,
                ) {
                    AsyncImage(
                        model = mediaItem.posterUrl ?: mediaItem.backdropUrl,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                        placeholder = painterResource(R.drawable.ic_splash),
                        fallback = painterResource(R.drawable.ic_splash),
                    )
                }
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = mediaItem.title,
                        color = PiggieTvColors.TextPrimary,
                        style = MaterialTheme.typography.h5,
                        fontWeight = FontWeight.ExtraBold,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis,
                    )
                    mediaItem.subtitle?.let {
                        Text(text = it, color = PiggieTvColors.TextSecondary, style = MaterialTheme.typography.body2)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (mediaItem.isPlayable) {
                            Button(
                                onClick = { onPlay(mediaItem, selection.siblings) },
                                colors = ButtonDefaults.buttonColors(
                                    backgroundColor = PiggieTvColors.Accent,
                                    contentColor = PiggieTvColors.Night,
                                ),
                                shape = MaterialTheme.shapes.medium,
                            ) {
                                Icon(Icons.Outlined.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(text = "Play", fontWeight = FontWeight.Bold)
                            }
                        }
                        if (mediaItem.isFolder && !mediaItem.showsChildrenOnDetails) {
                            Button(
                                onClick = { onOpenFolder(mediaItem) },
                                colors = ButtonDefaults.buttonColors(
                                    backgroundColor = PiggieTvColors.Focus,
                                    contentColor = PiggieTvColors.Night,
                                ),
                                shape = MaterialTheme.shapes.medium,
                            ) {
                                Icon(Icons.Outlined.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(text = "Browse", fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }
        mediaItem.overview?.takeIf { it.isNotBlank() }?.let { overview ->
            item {
                Text(
                    text = overview,
                    color = PiggieTvColors.TextSecondary,
                    style = MaterialTheme.typography.body1,
                    modifier = Modifier.padding(horizontal = layout.edgePadding),
                )
            }
        }
        if (mediaItem.showsChildrenOnDetails) {
            item {
                Text(
                    text = if (mediaItem.type == BaseItemKind.SERIES) "Seasons" else "Episodes",
                    color = PiggieTvColors.TextPrimary,
                    style = MaterialTheme.typography.h6,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = layout.edgePadding),
                )
            }
            when (val children = childItems) {
                null -> item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = layout.edgePadding, vertical = 12.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(22.dp),
                            color = PiggieTvColors.Focus,
                            strokeWidth = 2.dp,
                        )
                    }
                }
                else -> {
                    if (children.isEmpty()) {
                        item {
                            Text(
                                text = "Nothing here yet",
                                color = PiggieTvColors.TextSecondary,
                                modifier = Modifier.padding(horizontal = layout.edgePadding),
                            )
                        }
                    } else {
                        item {
                            LazyRow(
                                contentPadding = PaddingValues(horizontal = layout.edgePadding),
                                horizontalArrangement = Arrangement.spacedBy(layout.rowSpacing),
                            ) {
                                items(children, key = { child -> child.id.toString() }) { child ->
                                    PosterCard(
                                        layout = layout,
                                        item = child,
                                        onClick = { onOpenChild(child, children) },
                                        onPlay = { onPlay(child, children) },
                                        onReport = { onReport(child) },
                                        compact = false,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun PtvAdaptiveLayout.rowCardWidth(section: NativeMediaSection): Dp = when (section.shape) {
    PtvRowShape.BACKDROP -> rowPosterWidth * 1.48f
    PtvRowShape.SQUARE -> rowPosterWidth * 0.92f
    PtvRowShape.PORTRAIT -> when (section.presentation) {
        PtvRowPresentation.FEATURED -> rowPosterWidth * 1.08f
        PtvRowPresentation.COMPACT -> rowPosterWidth * 0.9f
        PtvRowPresentation.MINI -> rowPosterWidth * 0.82f
        PtvRowPresentation.LIBRARY_HUB,
        PtvRowPresentation.STANDARD -> rowPosterWidth
    }
}

@Composable
private fun MediaReportDialog(
    item: NativeMediaItem,
    onDismiss: () -> Unit,
    onSubmit: (MediaReportReason, String?) -> Unit,
) {
    var selectedReason by rememberSaveable(item.id.toString()) { mutableStateOf(MediaReportReason.SOUND_SYNC) }
    var menuExpanded by remember { mutableStateOf(false) }
    var customText by rememberSaveable(item.id.toString(), "custom") { mutableStateOf("") }
    val canSubmit = !selectedReason.requiresDetails || customText.isNotBlank()

    AlertDialog(
        onDismissRequest = onDismiss,
        backgroundColor = PiggieTvColors.PanelHigh,
        contentColor = PiggieTvColors.TextPrimary,
        shape = RoundedCornerShape(8.dp),
        title = {
            Text(text = "Report media", fontWeight = FontWeight.Bold)
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = item.title,
                    color = PiggieTvColors.TextPrimary,
                    style = MaterialTheme.typography.subtitle1,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Box {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { menuExpanded = true },
                        color = PiggieTvColors.Night.copy(alpha = 0.48f),
                        border = BorderStroke(1.dp, PiggieTvColors.Border),
                        shape = RoundedCornerShape(8.dp),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = selectedReason.displayName,
                                color = PiggieTvColors.TextPrimary,
                                modifier = Modifier.weight(1f),
                            )
                            Icon(
                                Icons.Outlined.ArrowDropDown,
                                contentDescription = null,
                                tint = PiggieTvColors.Focus,
                            )
                        }
                    }
                    DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                        MediaReportReason.entries.forEach { reason ->
                            DropdownMenuItem(
                                onClick = {
                                    selectedReason = reason
                                    menuExpanded = false
                                },
                            ) {
                                Text(text = reason.displayName)
                            }
                        }
                    }
                }
                if (selectedReason.requiresDetails) {
                    OutlinedTextField(
                        value = customText,
                        onValueChange = { customText = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(text = "Issue") },
                        minLines = 3,
                        maxLines = 5,
                        shape = RoundedCornerShape(8.dp),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
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
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onSubmit(selectedReason, customText.trim().takeIf(String::isNotBlank))
                },
                enabled = canSubmit,
                colors = ButtonDefaults.buttonColors(
                    backgroundColor = PiggieTvColors.Accent,
                    contentColor = PiggieTvColors.Night,
                    disabledBackgroundColor = PiggieTvColors.Panel,
                    disabledContentColor = PiggieTvColors.TextSecondary,
                ),
            ) {
                Text(text = "Send", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = "Cancel", color = PiggieTvColors.FocusSoft)
            }
        },
    )
}

@Composable
private fun ErrorScreen(layout: PtvAdaptiveLayout, message: String, onRetry: () -> Unit, onSelectServer: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(layout.edgePadding),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Image(
                painter = painterResource(R.drawable.ic_splash),
                contentDescription = null,
                modifier = Modifier.size(78.dp),
                contentScale = ContentScale.Fit,
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
            TextButton(onClick = onSelectServer) {
                Text(text = "Use a different server", color = PiggieTvColors.FocusSoft)
            }
        }
    }
}

@Composable
private fun PiggieTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    imeAction: ImeAction,
    modifier: Modifier = Modifier,
    isPassword: Boolean = false,
    keyboardType: KeyboardType = if (isPassword) KeyboardType.Password else KeyboardType.Text,
    onGo: () -> Unit = {},
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        label = { Text(text = label) },
        singleLine = true,
        shape = MaterialTheme.shapes.medium,
        visualTransformation = if (isPassword) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = imeAction),
        keyboardActions = KeyboardActions(onGo = { onGo() }),
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

private data class PtvAdaptiveLayout(
    val edgePadding: Dp,
    val topPadding: Dp,
    val bottomPadding: Dp,
    val sectionSpacing: Dp,
    val rowSpacing: Dp,
    val gridSpacing: Dp,
    val topLogoWidth: Dp,
    val topLogoHeight: Dp,
    val loadingLogoHeight: Dp,
    val loginLogoHeight: Dp,
    val loginMaxWidth: Dp,
    val heroHeight: Dp,
    val heroPadding: Dp,
    val heroTextWidthFraction: Float,
    val rowPosterWidth: Dp,
    val gridPosterWidth: Dp,
    val gridMinWidth: Dp,
    val compactTopBar: Boolean,
) {
    companion object {
        fun forWidth(width: Dp) = when {
            width < 600.dp -> PtvAdaptiveLayout(
                edgePadding = 16.dp,
                topPadding = 26.dp,
                bottomPadding = 26.dp,
                sectionSpacing = 22.dp,
                rowSpacing = 12.dp,
                gridSpacing = 12.dp,
                topLogoWidth = 126.dp,
                topLogoHeight = 56.dp,
                loadingLogoHeight = 92.dp,
                loginLogoHeight = 112.dp,
                loginMaxWidth = 420.dp,
                heroHeight = 204.dp,
                heroPadding = 16.dp,
                heroTextWidthFraction = 0.78f,
                rowPosterWidth = 132.dp,
                gridPosterWidth = 150.dp,
                gridMinWidth = 136.dp,
                compactTopBar = true,
            )
            width < 840.dp -> PtvAdaptiveLayout(
                edgePadding = 28.dp,
                topPadding = 34.dp,
                bottomPadding = 34.dp,
                sectionSpacing = 26.dp,
                rowSpacing = 14.dp,
                gridSpacing = 14.dp,
                topLogoWidth = 156.dp,
                topLogoHeight = 66.dp,
                loadingLogoHeight = 112.dp,
                loginLogoHeight = 136.dp,
                loginMaxWidth = 500.dp,
                heroHeight = 270.dp,
                heroPadding = 22.dp,
                heroTextWidthFraction = 0.62f,
                rowPosterWidth = 154.dp,
                gridPosterWidth = 166.dp,
                gridMinWidth = 158.dp,
                compactTopBar = false,
            )
            else -> PtvAdaptiveLayout(
                edgePadding = 48.dp,
                topPadding = 44.dp,
                bottomPadding = 44.dp,
                sectionSpacing = 30.dp,
                rowSpacing = 16.dp,
                gridSpacing = 16.dp,
                topLogoWidth = 184.dp,
                topLogoHeight = 76.dp,
                loadingLogoHeight = 132.dp,
                loginLogoHeight = 154.dp,
                loginMaxWidth = 540.dp,
                heroHeight = 330.dp,
                heroPadding = 28.dp,
                heroTextWidthFraction = 0.50f,
                rowPosterWidth = 174.dp,
                gridPosterWidth = 184.dp,
                gridMinWidth = 176.dp,
                compactTopBar = false,
            )
        }
    }
}

private enum class NativeHomeTab {
    HOME,
    REQUESTS,
    LIBRARY,
}

private enum class SignupMessageTone {
    SUCCESS,
    ERROR,
}

private data class NativeMediaDetailsSelection(
    val item: NativeMediaItem,
    val siblings: List<NativeMediaItem>,
)

private fun validateNativeSignup(email: String, username: String, password: String, confirmPassword: String): String? = when {
    email.isBlank() -> "Email is required."
    "@" !in email.trim() -> "Enter a valid email address."
    username.isBlank() -> "Username is required."
    password.isBlank() -> "Password is required."
    password != confirmPassword -> "Passwords do not match."
    else -> null
}

private val playableAudioKinds = setOf(BaseItemKind.AUDIO, BaseItemKind.AUDIO_BOOK)

private val NativeMediaItem.showsChildrenOnDetails: Boolean
    get() = type == BaseItemKind.SERIES || type == BaseItemKind.SEASON

private fun NativeMediaItem.toPlayOptions(siblings: List<NativeMediaItem> = emptyList()): PlayOptions {
    val queue = when {
        type in playableAudioKinds -> siblings
            .filter { sibling -> sibling.isPlayable && sibling.type in playableAudioKinds }
            .ifEmpty { listOf(this) }

        else -> listOf(this)
    }
    val queueIndex = queue.indexOfFirst { item -> item.id == id }.takeIf { it >= 0 } ?: 0

    return PlayOptions(
        ids = queue.map(NativeMediaItem::id),
        mediaSourceId = null,
        startIndex = queueIndex,
        startPosition = ZERO,
        audioStreamIndex = null,
        subtitleStreamIndex = null,
        playFromDownloads = false,
    )
}
