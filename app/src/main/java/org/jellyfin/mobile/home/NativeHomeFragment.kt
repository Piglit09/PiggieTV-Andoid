package org.jellyfin.mobile.home

import android.os.Bundle
import androidx.compose.runtime.Composable
import org.jellyfin.mobile.data.entity.ServerEntity
import org.jellyfin.mobile.events.ActivityEvent
import org.jellyfin.mobile.events.ActivityEventHandler
import org.jellyfin.mobile.ui.ComposeFragment
import org.jellyfin.mobile.ui.screens.home.NativeHomeScreen
import org.jellyfin.mobile.ui.screens.home.NativeHomeViewModel
import org.jellyfin.mobile.utils.Constants
import org.jellyfin.mobile.utils.extensions.getParcelableCompat
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.viewModel

class NativeHomeFragment : ComposeFragment() {
    val server: ServerEntity by lazy {
        requireArguments().getParcelableCompat(Constants.FRAGMENT_WEB_VIEW_EXTRA_SERVER)!!
    }

    private val viewModel: NativeHomeViewModel by viewModel()
    private val activityEventHandler: ActivityEventHandler by inject()

    @Composable
    override fun Content() {
        NativeHomeScreen(
            server = server,
            viewModel = viewModel,
            onOpenDownloads = { activityEventHandler.emit(ActivityEvent.OpenDownloads) },
            onOpenSettings = { activityEventHandler.emit(ActivityEvent.OpenSettings) },
            onOpenDashboard = { url -> activityEventHandler.emit(ActivityEvent.OpenUrl(url)) },
            onOpenExternalUrl = { url -> activityEventHandler.emit(ActivityEvent.OpenUrl(url)) },
            onSelectServer = { activityEventHandler.emit(ActivityEvent.SelectServer) },
            onPlay = { playOptions -> activityEventHandler.emit(ActivityEvent.LaunchNativePlayer(playOptions)) },
        )
    }
}
