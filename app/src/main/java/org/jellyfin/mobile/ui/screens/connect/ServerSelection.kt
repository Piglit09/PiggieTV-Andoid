package org.jellyfin.mobile.ui.screens.connect

import android.view.KeyEvent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.ExitTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.MaterialTheme
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.material.TextFieldDefaults
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.jellyfin.mobile.R
import org.jellyfin.mobile.app.ApiClientController
import org.jellyfin.mobile.setup.ConnectionHelper
import org.jellyfin.mobile.ui.state.CheckUrlState
import org.jellyfin.mobile.ui.state.ServerSelectionMode
import org.jellyfin.mobile.ui.utils.CenterRow
import org.jellyfin.mobile.ui.utils.PiggieTvColors
import org.jellyfin.mobile.utils.Constants
import org.koin.compose.koinInject

@OptIn(ExperimentalComposeUiApi::class)
@Suppress("LongMethod")
@Composable
fun ServerSelection(
    modifier: Modifier = Modifier,
    showExternalConnectionError: Boolean,
    apiClientController: ApiClientController = koinInject(),
    connectionHelper: ConnectionHelper = koinInject(),
    onConnected: suspend (String) -> Unit,
) {
    val coroutineScope = rememberCoroutineScope()
    val keyboardController = LocalSoftwareKeyboardController.current
    var serverSelectionMode by remember { mutableStateOf(ServerSelectionMode.ADDRESS) }
    var hostname by remember { mutableStateOf("") }
    val serverSuggestions = remember { mutableStateListOf<ServerSuggestion>() }
    var checkUrlState by remember<MutableState<CheckUrlState>> { mutableStateOf(CheckUrlState.Unchecked) }
    var externalError by remember { mutableStateOf(showExternalConnectionError) }

    // Prefill currently selected server if available
    LaunchedEffect(Unit) {
        val server = apiClientController.loadSavedServer()
        if (server != null) {
            hostname = server.hostname
        }
    }

    LaunchedEffect(Unit) {
        serverSuggestions += ServerSuggestion(
            type = ServerSuggestion.Type.FEATURED,
            name = Constants.PIGGIETV_DEFAULT_SERVER_NAME,
            address = Constants.PIGGIETV_DEFAULT_SERVER_URL,
            timestamp = Long.MAX_VALUE,
        )

        // Suggest saved servers
        apiClientController.loadPreviouslyUsedServers().mapTo(serverSuggestions) { server ->
            ServerSuggestion(
                type = ServerSuggestion.Type.SAVED,
                name = server.hostname,
                address = server.hostname,
                timestamp = server.lastUsedTimestamp,
            )
        }

        // Prepend discovered servers to suggestions
        connectionHelper.discoverServersAsFlow().collect { serverInfo ->
            serverSuggestions.removeIf { existing -> existing.address == serverInfo.address }
            serverSuggestions.add(
                index = 0,
                ServerSuggestion(
                    type = ServerSuggestion.Type.DISCOVERED,
                    name = serverInfo.name,
                    address = serverInfo.address,
                    timestamp = System.currentTimeMillis(),
                ),
            )
        }
    }

    fun onSubmit(url: String = hostname) {
        externalError = false
        checkUrlState = CheckUrlState.Pending
        hostname = url
        coroutineScope.launch {
            val state = connectionHelper.checkServerUrl(url)
            checkUrlState = state
            if (state is CheckUrlState.Success) {
                onConnected(state.address)
            }
        }
    }

    Column(modifier = modifier) {
        Text(
            text = stringResource(R.string.connect_to_server_title),
            modifier = Modifier.padding(bottom = 8.dp),
            color = PiggieTvColors.TextPrimary,
            style = MaterialTheme.typography.h5,
            fontWeight = FontWeight.Bold,
        )
        Crossfade(
            targetState = serverSelectionMode,
            label = "Server selection mode",
        ) { selectionType ->
            when (selectionType) {
                ServerSelectionMode.ADDRESS -> AddressSelection(
                    text = hostname,
                    errorText = when {
                        externalError -> stringResource(R.string.connection_error_cannot_connect)
                        else -> (checkUrlState as? CheckUrlState.Error)?.message
                    },
                    loading = checkUrlState is CheckUrlState.Pending,
                    onTextChange = { value ->
                        externalError = false
                        checkUrlState = CheckUrlState.Unchecked
                        hostname = value
                    },
                    onDiscoveryClick = {
                        externalError = false
                        keyboardController?.hide()
                        serverSelectionMode = ServerSelectionMode.AUTO_DISCOVERY
                    },
                    onPiggieTvClick = {
                        onSubmit(Constants.PIGGIETV_DEFAULT_SERVER_URL)
                    },
                    onSubmit = {
                        onSubmit()
                    },
                )

                ServerSelectionMode.AUTO_DISCOVERY -> ServerDiscoveryList(
                    serverSuggestions = serverSuggestions,
                    onGoBack = {
                        serverSelectionMode = ServerSelectionMode.ADDRESS
                    },
                    onSelectServer = { url ->
                        hostname = url
                        serverSelectionMode = ServerSelectionMode.ADDRESS
                        onSubmit()
                    },
                )
            }
        }
    }
}

@Stable
@Composable
private fun AddressSelection(
    text: String,
    errorText: String?,
    loading: Boolean,
    onTextChange: (String) -> Unit,
    onDiscoveryClick: () -> Unit,
    onPiggieTvClick: () -> Unit,
    onSubmit: () -> Unit,
) {
    Column {
        ServerUrlField(
            text = text,
            errorText = errorText,
            onTextChange = onTextChange,
            onSubmit = onSubmit,
        )
        AnimatedErrorText(errorText = errorText)
        if (!loading) {
            Spacer(modifier = Modifier.height(12.dp))
            StyledTextButton(
                text = stringResource(R.string.connect_button_text),
                enabled = text.isNotBlank(),
                style = ButtonStyle.Secondary,
                onClick = onSubmit,
            )
            StyledTextButton(
                text = stringResource(R.string.connect_to_piggietv_button_text),
                style = ButtonStyle.Primary,
                onClick = onPiggieTvClick,
            )
            StyledTextButton(
                text = stringResource(R.string.choose_server_button_text),
                style = ButtonStyle.Ghost,
                onClick = onDiscoveryClick,
            )
        } else {
            CenterRow {
                CircularProgressIndicator(
                    modifier = Modifier.padding(top = 16.dp, bottom = 8.dp),
                    color = PiggieTvColors.Focus,
                )
            }
        }
    }
}

@Stable
@Composable
private fun ServerUrlField(text: String, errorText: String?, onTextChange: (String) -> Unit, onSubmit: () -> Unit,) {
    OutlinedTextField(
        value = text,
        onValueChange = onTextChange,
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp)
            .onKeyEvent { keyEvent ->
                when (keyEvent.nativeKeyEvent.keyCode) {
                    KeyEvent.KEYCODE_ENTER -> {
                        onSubmit()
                        true
                    }

                    else -> false
                }
            },
        label = {
            Text(text = stringResource(R.string.host_input_hint))
        },
        shape = MaterialTheme.shapes.medium,
        isError = errorText != null,
        colors = TextFieldDefaults.outlinedTextFieldColors(
            textColor = PiggieTvColors.TextPrimary,
            cursorColor = PiggieTvColors.Focus,
            focusedBorderColor = PiggieTvColors.Focus,
            unfocusedBorderColor = PiggieTvColors.Border,
            errorBorderColor = MaterialTheme.colors.error,
            focusedLabelColor = PiggieTvColors.Focus,
            unfocusedLabelColor = PiggieTvColors.TextSecondary,
            backgroundColor = PiggieTvColors.Night.copy(alpha = 0.45f),
        ),
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Uri,
            imeAction = ImeAction.Go,
        ),
        keyboardActions = KeyboardActions(
            onGo = {
                onSubmit()
            },
        ),
        singleLine = true,
    )
}

@Stable
@Composable
private fun AnimatedErrorText(errorText: String?,) {
    AnimatedVisibility(
        visible = errorText != null,
        exit = ExitTransition.None,
    ) {
        Text(
            text = errorText.orEmpty(),
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp),
            color = MaterialTheme.colors.error,
            style = MaterialTheme.typography.caption,
        )
    }
}

@Stable
@Composable
private fun ServerDiscoveryList(
    serverSuggestions: SnapshotStateList<ServerSuggestion>,
    onGoBack: () -> Unit,
    onSelectServer: (String) -> Unit,
) {
    Column {
        Row(
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onGoBack) {
                Icon(imageVector = Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = null)
            }
            Text(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 8.dp),
                text = stringResource(R.string.available_servers_title),
                color = PiggieTvColors.TextPrimary,
                fontWeight = FontWeight.Bold,
            )
            CircularProgressIndicator(
                modifier = Modifier
                    .padding(horizontal = 12.dp)
                    .size(24.dp),
                color = PiggieTvColors.Focus,
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        Surface(
            modifier = Modifier
                .padding(bottom = 16.dp)
                .fillMaxSize(),
            color = MaterialTheme.colors.surface.copy(alpha = 0.72f),
            shape = MaterialTheme.shapes.medium,
            border = BorderStroke(1.dp, PiggieTvColors.Border),
        ) {
            LazyColumn(
                contentPadding = PaddingValues(vertical = 6.dp),
            ) {
                items(serverSuggestions) { server ->
                    ServerDiscoveryItem(
                        serverSuggestion = server,
                        onClickServer = {
                            onSelectServer(server.address)
                        },
                    )
                }
            }
        }
    }
}

@Stable
@Composable
private fun ServerDiscoveryItem(serverSuggestion: ServerSuggestion, onClickServer: () -> Unit,) {
    val isFeatured = serverSuggestion.type == ServerSuggestion.Type.FEATURED
    val borderColor = if (isFeatured) PiggieTvColors.Focus else PiggieTvColors.Border
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 5.dp)
            .clickable(onClick = onClickServer),
        color = if (isFeatured) PiggieTvColors.PanelHigh else MaterialTheme.colors.surface,
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(1.dp, borderColor),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (isFeatured) {
                Image(
                    painter = painterResource(R.drawable.ic_splash),
                    modifier = Modifier.size(34.dp),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                )
            } else {
                Box(
                    modifier = Modifier.size(34.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .background(PiggieTvColors.Accent, CircleShape),
                    )
                }
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = serverSuggestion.name,
                    color = PiggieTvColors.TextPrimary,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = serverSuggestion.address,
                    color = PiggieTvColors.TextSecondary,
                    style = MaterialTheme.typography.caption,
                )
            }
        }
    }
}
