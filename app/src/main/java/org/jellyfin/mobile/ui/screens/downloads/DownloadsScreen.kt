package org.jellyfin.mobile.ui.screens.downloads

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Scaffold
import androidx.compose.material.Text
import androidx.compose.material.TopAppBar
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import org.jellyfin.mobile.R
import org.jellyfin.mobile.downloads.DownloadsViewModel
import org.jellyfin.mobile.ui.utils.PiggieTvBackground

@Composable
fun DownloadsScreen(viewModel: DownloadsViewModel = viewModel(), onBackPressed: () -> Unit = {},) {
    PiggieTvBackground(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding(),
            backgroundColor = MaterialTheme.colors.background.copy(alpha = 0f),
            topBar = {
                TopAppBar(
                    title = {
                        Text(text = stringResource(R.string.downloads))
                    },
                    navigationIcon = {
                        IconButton(
                            onClick = onBackPressed,
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                                contentDescription = null,
                            )
                        }
                    },
                    backgroundColor = MaterialTheme.colors.surface.copy(alpha = 0.92f),
                    contentColor = MaterialTheme.colors.onSurface,
                    elevation = 0.dp,
                )
            },
            content = { innerPadding ->
                DownloadsList(
                    viewModel = viewModel,
                    contentPadding = innerPadding,
                )
            },
        )
    }
}
