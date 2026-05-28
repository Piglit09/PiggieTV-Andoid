package org.jellyfin.mobile.ui.screens.downloads

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import org.jellyfin.mobile.R
import org.jellyfin.mobile.data.entity.DownloadEntity
import org.jellyfin.mobile.downloads.DownloadsViewModel
import org.jellyfin.mobile.ui.utils.PiggieTvColors
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.imageApi
import org.jellyfin.sdk.model.api.ImageType
import org.koin.compose.koinInject

@Composable
fun DownloadsList(viewModel: DownloadsViewModel = viewModel(), contentPadding: PaddingValues = PaddingValues.Zero,) {
    val downloads by viewModel.downloads.collectAsState()
    if (downloads.isEmpty()) {
        EmptyDownloads(contentPadding = contentPadding)
    } else {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(
                downloads,
                key = DownloadEntity::itemId,
            ) { download ->
                DownloadItem(
                    download,
                    modifier = Modifier.combinedClickable(
                        onClick = { viewModel.playDownload(download) },
                        onLongClick = { viewModel.removeDownload(download) },
                    ),
                )
            }
        }
    }
}

@Composable
fun DownloadItem(download: DownloadEntity, modifier: Modifier = Modifier,) {
    val context = LocalContext.current
    val apiClient: ApiClient = koinInject()

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colors.surface.copy(alpha = 0.9f),
        contentColor = MaterialTheme.colors.onSurface,
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(1.dp, PiggieTvColors.Border),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val maxSize = LocalResources.current.getDimensionPixelSize(R.dimen.movie_thumbnail_list_size)
            val url = remember(download.mediaSource.itemId) {
                apiClient.imageApi.getItemImageUrl(
                    itemId = download.mediaSource.itemId,
                    imageType = ImageType.PRIMARY,
                    maxWidth = maxSize,
                    maxHeight = maxSize,
                )
            }
            AsyncImage(
                model = url,
                modifier = Modifier
                    .size(64.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(PiggieTvColors.PanelHigh),
                placeholder = painterResource(R.drawable.ic_local_movies_white_64),
                fallback = painterResource(R.drawable.ic_local_movies_white_64),
                contentDescription = null,
                contentScale = ContentScale.Crop,
            )
            Column(modifier = Modifier.weight(1f)) {
                val name = remember(download.mediaSource.itemId) {
                    download.mediaSource.getName(context)
                }
                Text(
                    text = name,
                    color = PiggieTvColors.TextPrimary,
                    overflow = TextOverflow.Ellipsis,
                    maxLines = 1,
                    style = MaterialTheme.typography.subtitle1,
                )
                Text(
                    text = download.fileSize,
                    color = PiggieTvColors.TextSecondary,
                    overflow = TextOverflow.Ellipsis,
                    maxLines = 1,
                    style = MaterialTheme.typography.body2,
                )
            }
        }
    }
}

@Composable
private fun EmptyDownloads(contentPadding: PaddingValues) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding)
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Image(
                painter = painterResource(R.drawable.app_logo),
                modifier = Modifier
                    .fillMaxWidth(0.82f)
                    .height(104.dp),
                contentDescription = null,
                contentScale = ContentScale.Fit,
            )
            Text(
                text = androidx.compose.ui.res.stringResource(R.string.downloads_empty_title),
                color = PiggieTvColors.TextPrimary,
                style = MaterialTheme.typography.h6,
            )
            Text(
                text = androidx.compose.ui.res.stringResource(R.string.downloads_empty_message),
                color = PiggieTvColors.TextSecondary,
                style = MaterialTheme.typography.body2,
            )
        }
    }
}
