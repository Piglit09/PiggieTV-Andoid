package org.jellyfin.mobile.home

import android.os.Bundle
import androidx.compose.runtime.Composable
import org.jellyfin.mobile.data.entity.ServerEntity
import org.jellyfin.mobile.events.ActivityEvent
import org.jellyfin.mobile.events.ActivityEvent.DownloadFile
import org.jellyfin.mobile.events.ActivityEvent.OpenUrl
import org.jellyfin.mobile.events.ActivityEvent.ReadLibraryBook
import org.jellyfin.mobile.events.ActivityEventHandler
import org.jellyfin.mobile.feature.library.LibraryViewModel
import org.jellyfin.mobile.ui.ComposeFragment
import org.jellyfin.mobile.ui.screens.home.NativeHomeScreen
import org.jellyfin.mobile.ui.screens.home.NativeHomeViewModel
import org.jellyfin.mobile.utils.BackPressInterceptor
import org.jellyfin.mobile.utils.Constants
import org.jellyfin.mobile.utils.extensions.getParcelableCompat
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.viewModel

class NativeHomeFragment : ComposeFragment(), BackPressInterceptor {
    val server: ServerEntity by lazy {
        requireArguments().getParcelableCompat(Constants.FRAGMENT_WEB_VIEW_EXTRA_SERVER)!!
    }

    private val viewModel: NativeHomeViewModel by viewModel()
    private val libraryViewModel: LibraryViewModel by viewModel()
    private val activityEventHandler: ActivityEventHandler by inject()
    private var backHandler: (() -> Boolean)? = null

    @Composable
    override fun Content() {
        NativeHomeScreen(
            server = server,
            viewModel = viewModel,
            libraryViewModel = libraryViewModel,
            onOpenDownloads = { activityEventHandler.emit(ActivityEvent.OpenDownloads) },
            onOpenSettings = { activityEventHandler.emit(ActivityEvent.OpenSettings) },
            onOpenDashboard = { url -> activityEventHandler.emit(ActivityEvent.OpenUrl(url)) },
            onOpenExternalUrl = { url -> activityEventHandler.emit(ActivityEvent.OpenUrl(url)) },
            onOpenLibraryLink = { url -> activityEventHandler.emit(OpenUrl(url)) },
            onDownloadLibraryBook = { uri, title, filename ->
                activityEventHandler.emit(DownloadFile(uri, title, filename))
            },
            onReadLibraryBook = { uri, title, filename, mimeType ->
                activityEventHandler.emit(ReadLibraryBook(uri, title, filename, mimeType))
            },
            onSelectServer = { activityEventHandler.emit(ActivityEvent.SelectServer) },
            onPlay = { playOptions -> activityEventHandler.emit(ActivityEvent.LaunchNativePlayer(playOptions)) },
            onBackHandlerChanged = { handler -> backHandler = handler },
        )
    }

    override fun onInterceptBackPressed(): Boolean = backHandler?.invoke() == true
}
