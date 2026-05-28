package org.jellyfin.mobile.ui.screens.connect

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material.Button
import androidx.compose.material.ButtonDefaults
import androidx.compose.material.OutlinedButton
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.jellyfin.mobile.MainViewModel
import org.jellyfin.mobile.R
import org.jellyfin.mobile.events.ActivityEvent
import org.jellyfin.mobile.events.ActivityEventHandler
import org.jellyfin.mobile.ui.utils.PiggieTvBackground
import org.jellyfin.mobile.ui.utils.PiggieTvColors
import org.jellyfin.mobile.utils.Constants
import org.koin.compose.koinInject

@Composable
fun ConnectScreen(
    mainViewModel: MainViewModel,
    showExternalConnectionError: Boolean,
    activityEventHandler: ActivityEventHandler = koinInject(),
) {
    PiggieTvBackground(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .padding(horizontal = 20.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            LogoHeader()
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colors.surface.copy(alpha = 0.92f),
                contentColor = MaterialTheme.colors.onSurface,
                shape = MaterialTheme.shapes.medium,
                border = BorderStroke(1.dp, PiggieTvColors.Border),
            ) {
                ServerSelection(
                    modifier = Modifier.padding(18.dp),
                    showExternalConnectionError = showExternalConnectionError,
                    onConnected = { hostname ->
                        mainViewModel.switchServer(hostname)
                    },
                )
            }
            StyledTextButton(
                onClick = { activityEventHandler.emit(ActivityEvent.OpenUrl(Constants.PIGGIETV_REQUESTS_URL)) },
                text = stringResource(R.string.request_media_button_text),
                style = ButtonStyle.Ghost,
            )
            StyledTextButton(
                onClick = { activityEventHandler.emit(ActivityEvent.OpenDownloads) },
                text = stringResource(R.string.view_downloads),
                style = ButtonStyle.Ghost,
            )
        }
    }
}

@Stable
@Composable
fun LogoHeader() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Image(
            painter = painterResource(R.drawable.app_logo),
            modifier = Modifier
                .fillMaxWidth(0.86f)
                .height(112.dp),
            contentDescription = stringResource(R.string.app_name),
            contentScale = ContentScale.Fit,
        )
    }
}

@Stable
enum class ButtonStyle {
    Primary,
    Secondary,
    Ghost,
}

@Composable
fun StyledTextButton(
    text: String,
    enabled: Boolean = true,
    style: ButtonStyle = ButtonStyle.Secondary,
    onClick: () -> Unit,
) {
    val modifier = Modifier
        .fillMaxWidth()
        .heightIn(min = 48.dp)
        .padding(vertical = 4.dp)

    when (style) {
        ButtonStyle.Primary -> Button(
            onClick = onClick,
            modifier = modifier,
            enabled = enabled,
            shape = MaterialTheme.shapes.medium,
            colors = ButtonDefaults.buttonColors(
                backgroundColor = PiggieTvColors.Accent,
                contentColor = PiggieTvColors.Night,
                disabledBackgroundColor = MaterialTheme.colors.surface,
                disabledContentColor = PiggieTvColors.TextSecondary,
            ),
        ) {
            Text(text = text, fontWeight = FontWeight.Bold)
        }

        ButtonStyle.Secondary -> OutlinedButton(
            onClick = onClick,
            modifier = modifier,
            enabled = enabled,
            shape = MaterialTheme.shapes.medium,
            border = BorderStroke(1.dp, PiggieTvColors.Border),
            colors = ButtonDefaults.outlinedButtonColors(
                backgroundColor = MaterialTheme.colors.surface.copy(alpha = 0.48f),
                contentColor = PiggieTvColors.Focus,
                disabledContentColor = PiggieTvColors.TextSecondary,
            ),
        ) {
            Text(text = text, fontWeight = FontWeight.Bold)
        }

        ButtonStyle.Ghost -> TextButton(
            onClick = onClick,
            modifier = modifier,
            enabled = enabled,
            colors = ButtonDefaults.textButtonColors(
                contentColor = PiggieTvColors.FocusSoft,
                disabledContentColor = PiggieTvColors.TextSecondary,
            ),
        ) {
            Text(text = text)
        }
    }
}
