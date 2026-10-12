package org.jellyfin.mobile.downloads

import android.Manifest.permission.WRITE_EXTERNAL_STORAGE
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager.PERMISSION_GRANTED
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Build.VERSION_CODES.P
import android.os.Build.VERSION_CODES.Q
import android.os.Environment
import android.provider.MediaStore
import android.util.AndroidException
import androidx.annotation.RequiresApi
import androidx.core.content.getSystemService
import androidx.core.net.toUri
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.DownloadService
import androidx.media3.exoplayer.scheduler.Requirements
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.jellyfin.mobile.MainActivity
import org.jellyfin.mobile.R
import org.jellyfin.mobile.app.AppPreferences
import org.jellyfin.mobile.auth.AuthSessionCoordinator
import org.jellyfin.mobile.auth.MobileAuthState
import org.jellyfin.mobile.auth.transport.INTERNAL_AUTH_EPOCH_HEADER
import org.jellyfin.mobile.auth.transport.hasSameOrigin
import org.jellyfin.mobile.auth.transport.toHttpUrlWithoutAuthenticationQuery
import org.jellyfin.mobile.data.dao.DownloadDao
import org.jellyfin.mobile.data.entity.DownloadEntity
import org.jellyfin.mobile.downloads.DownloadServiceUtil.getDownloadTracker
import org.jellyfin.mobile.player.deviceprofile.DeviceProfileBuilder
import org.jellyfin.mobile.player.source.JellyfinMediaSource
import org.jellyfin.mobile.player.source.LocalJellyfinMediaSource
import org.jellyfin.mobile.player.source.MediaSourceResolver
import org.jellyfin.mobile.utils.AndroidVersion
import org.jellyfin.mobile.utils.Constants
import org.jellyfin.mobile.utils.extractId
import org.jellyfin.mobile.utils.requestPermission
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.model.UUID
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.serializer.toUUID
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import org.koin.core.component.inject
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.net.URLConnection
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

class DownloadUtils(val context: Context, filename: String, downloadURL: String, private val downloadMethod: Int) :
    KoinComponent {
    private val mainActivity: MainActivity = context as MainActivity
    private val downloadFolder: File
    private val itemId: String
    private val itemUUID: UUID
    private val contentId: String
    private val downloadTarget = requireNotNull(AuthenticatedDownloadTarget.parse(downloadURL)) {
        "Unsupported or invalid Jellyfin download URL."
    }
    private val downloadUri: Uri = downloadTarget.url.toString().toUri()
    private val safeFilename = requireNotNull(filename.toSafeDownloadFileName()) {
        "Download filename is invalid."
    }
    private val downloadDao: DownloadDao by inject()
    private val apiClient: ApiClient = get()
    private val mediaSourceResolver: MediaSourceResolver by inject()
    private val deviceProfileBuilder: DeviceProfileBuilder by inject()
    private val deviceProfile = deviceProfileBuilder.getDeviceProfile()
    private val notificationManager: NotificationManager? by lazy { context.getSystemService() }
    private val connectivityManager: ConnectivityManager? by lazy { context.getSystemService() }
    private val appPreferences: AppPreferences by inject()
    private val authCoordinator: AuthSessionCoordinator by inject()
    private val dataSourceFactory: DataSource.Factory by inject()
    private var downloadTracker: DownloadTracker = getDownloadTracker()
    private val downloadOwner = requireNotNull(authCoordinator.state.value as? MobileAuthState.Authenticated) {
        "An authenticated session is required for downloads."
    }

    init {
        val configuredServer = requireNotNull(
            apiClient.baseUrl?.toHttpUrlWithoutAuthenticationQuery(),
        ) { "An authenticated Jellyfin server is required for downloads." }
        require(downloadTarget.url.hasSameOrigin(configuredServer)) {
            "Jellyfin downloads must use the authenticated server origin."
        }
        itemId = downloadTarget.itemId
        itemUUID = itemId.toUUID()
        contentId = itemUUID.toString()
        downloadFolder = File(context.filesDir, "/Downloads/$itemId/")
        downloadFolder.mkdirs()
    }

    suspend fun download() {
        createDownloadNotificationChannel()
        checkForDownloadMethod()
        val jellyfinMediaSource = retrieveJellyfinMediaSource()
        val isDestinationInternal = jellyfinMediaSource.getIsDestinationInternal()
        if (isDestinationInternal) {
            if (checkIfDownloadExists(itemId)) {
                removeDownloadRemains(jellyfinMediaSource)
            } else {
                downloadFiles(jellyfinMediaSource)
            }
        } else {
            downloadExternalMediaFile()
        }
    }

    @SuppressLint("InlinedApi")
    private fun checkForDownloadMethod() {
        val validConnection = when (downloadMethod) {
            DownloadMethod.WIFI_ONLY -> {
                setUnmeteredRequirement()
                !isNetworkMetered()
            }

            DownloadMethod.MOBILE_DATA -> {
                if (Build.VERSION.SDK_INT < P) {
                    !isNetworkRoamingCompat()
                } else {
                    !isNetworkRoaming()
                }
            }

            else -> true
        }

        if (!validConnection) throw IOException(context.getString(R.string.failed_network_method_check))
    }

    private fun setUnmeteredRequirement() {
        DownloadService.sendSetRequirements(
            context,
            JellyfinDownloadService::class.java,
            Requirements(Requirements.NETWORK_UNMETERED),
            false,
        )
    }

    private fun isNetworkMetered(): Boolean = connectivityManager?.isActiveNetworkMetered == true

    private fun isNetworkRoamingCompat(): Boolean =
        connectivityManager?.activeNetworkInfo?.isRoaming ?: throw AndroidException()

    @RequiresApi(P)
    private fun isNetworkRoaming(): Boolean {
        val network: Network = connectivityManager?.activeNetwork ?: throw AndroidException()
        val capabilities: NetworkCapabilities = connectivityManager?.getNetworkCapabilities(
            network,
        ) ?: throw AndroidException()
        return !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_ROAMING)
    }

    private suspend fun checkIfDownloadExists(itemId: String) = downloadDao.downloadExists(itemId)

    private suspend fun retrieveJellyfinMediaSource() = mediaSourceResolver.resolveMediaSource(
        itemId = itemUUID,
        mediaSourceId = itemId,
        deviceProfile = deviceProfile,
    ).getOrElse { throw IOException(context.getString(R.string.failed_information)) }

    // Only download shows and movies to internal storage
    private fun JellyfinMediaSource.getIsDestinationInternal() =
        appPreferences.downloadToInternal == true && item?.type in listOf(
            BaseItemKind.EPISODE,
            BaseItemKind.MOVIE,
            BaseItemKind.VIDEO,
            BaseItemKind.MUSIC_VIDEO,
        )

    private fun downloadFiles(jellyfinMediaSource: JellyfinMediaSource) {
        val jellyfinDownloadTracker = JellyfinDownloadTracker(jellyfinMediaSource)
        downloadTracker.addListener(jellyfinDownloadTracker)
        downloadMediaFile(jellyfinMediaSource)
        downloadExternalSubtitles(jellyfinMediaSource)
    }

    private fun downloadMediaFile(jellyfinMediaSource: JellyfinMediaSource) {
        val cacheKey = downloadUri.extractId()

        val downloadRequest = DownloadRequest.Builder(contentId, downloadUri)
            .setData(jellyfinMediaSource.item!!.name!!.encodeToByteArray())
            .setCustomCacheKey(AuthenticatedDownloadOwnership.encode(downloadOwner, cacheKey))
            .build()
        DownloadService.sendAddDownload(
            context,
            JellyfinDownloadService::class.java,
            downloadRequest,
            false,
        )
    }

    private fun downloadExternalSubtitles(jellyfinMediaSource: JellyfinMediaSource) {
        jellyfinMediaSource.externalSubtitleStreams.forEach {
            val subtitleDownloadUrl = requireNotNull(
                apiClient.createUrl(it.deliveryUrl).toHttpUrlWithoutAuthenticationQuery(),
            ) { "Jellyfin returned an invalid subtitle URL." }.toString().toUri()
            val subtitleCacheKey: String = subtitleDownloadUrl.extractId()

            val downloadRequest = DownloadRequest.Builder("$contentId:${it.index}", subtitleDownloadUrl)
                .setCustomCacheKey(AuthenticatedDownloadOwnership.encode(downloadOwner, subtitleCacheKey))
                .build()
            DownloadService.sendAddDownload(
                context,
                JellyfinDownloadService::class.java,
                downloadRequest,
                false,
            )
        }
    }

    private suspend fun storeDownloadSpecs(jellyfinMediaSource: JellyfinMediaSource) = LocalJellyfinMediaSource(
        jellyfinMediaSource,
        downloadFolder.canonicalPath,
        downloadTarget.url.toString(),
        downloadTracker.getDownloadSize(downloadUri),
    ).also {
        downloadDao.insert(DownloadEntity(it))
    }

    private suspend fun downloadExternalMediaFile() {
        if (!AndroidVersion.isAtLeastQ) {
            @Suppress("MagicNumber")
            val granted = withTimeout(2 * 60 * 1000) {
                suspendCoroutine { continuation ->
                    mainActivity.requestPermission(WRITE_EXTERNAL_STORAGE) { requestPermissionsResult ->
                        continuation.resume(requestPermissionsResult[WRITE_EXTERNAL_STORAGE] == PERMISSION_GRANTED)
                    }
                }
            }

            if (!granted) {
                throw IOException(context.getString(R.string.download_no_storage_permission))
            }
        }

        withContext(Dispatchers.IO) {
            if (AndroidVersion.isAtLeastQ) {
                downloadExternalToMediaStore()
            } else {
                downloadExternalToFile()
            }
        }
    }

    @RequiresApi(Q)
    @Suppress("ThrowsCount")
    private fun downloadExternalToMediaStore() {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, safeFilename)
            put(MediaStore.MediaColumns.MIME_TYPE, downloadMimeType())
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val destination = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("Unable to create the external download destination.")
        var published = false
        try {
            val output = resolver.openOutputStream(destination, "w")
                ?: throw IOException("Unable to open the external download destination.")
            output.use(::copyAuthenticatedDownload)

            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            if (resolver.update(destination, values, null, null) != 1) {
                throw IOException("Unable to publish the completed external download.")
            }
            published = true
        } finally {
            if (!published) resolver.delete(destination, null, null)
        }
    }

    @Suppress("ThrowsCount")
    private fun downloadExternalToFile() {
        val directory = File(appPreferences.downloadLocation).canonicalFile
        if (!directory.isDirectory) {
            throw IOException("The selected download directory is unavailable.")
        }
        val destination = File(directory, safeFilename).canonicalFile
        if (destination.parentFile != directory || destination.exists()) {
            throw IOException("The external download destination is unavailable.")
        }

        val temporaryFile = File.createTempFile(".piggietv-", ".download", directory)
        try {
            temporaryFile.outputStream().buffered().use(::copyAuthenticatedDownload)
            if (!temporaryFile.renameTo(destination)) {
                throw IOException("Unable to publish the completed external download.")
            }
        } finally {
            if (temporaryFile.exists()) temporaryFile.delete()
        }
    }

    private fun copyAuthenticatedDownload(output: OutputStream) {
        val current = authCoordinator.state.value as? MobileAuthState.Authenticated
        if (current == null || !current.matches(downloadOwner)) {
            throw IOException("AUTH_OPERATION_SUPERSEDED")
        }
        val dataSource = dataSourceFactory.createDataSource()
        try {
            dataSource.open(
                DataSpec.Builder()
                    .setUri(downloadUri)
                    .setHttpRequestHeaders(
                        mapOf(INTERNAL_AUTH_EPOCH_HEADER to current.credentialEpoch.toString()),
                    )
                    .build(),
            )
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val bytesRead = dataSource.read(buffer, 0, buffer.size)
                if (bytesRead == END_OF_INPUT) break
                output.write(buffer, 0, bytesRead)
            }
            output.flush()
        } finally {
            dataSource.close()
        }
    }

    private fun downloadMimeType(): String =
        URLConnection.guessContentTypeFromName(safeFilename) ?: DEFAULT_DOWNLOAD_MIME_TYPE

    private fun removeDownloadRemains(jellyfinMediaSource: JellyfinMediaSource) {
        downloadFolder.deleteRecursively()

        // Remove media file
        DownloadService.sendRemoveDownload(
            context,
            JellyfinDownloadService::class.java,
            contentId,
            false,
        )

        // Remove subtitles
        jellyfinMediaSource.externalSubtitleStreams.forEach {
            DownloadService.sendRemoveDownload(
                context,
                JellyfinDownloadService::class.java,
                "$contentId:${it.index}",
                false,
            )
        }
    }

    private fun createDownloadNotificationChannel() {
        if (AndroidVersion.isAtLeastO) {
            val notificationChannel = NotificationChannel(
                Constants.DOWNLOAD_NOTIFICATION_CHANNEL_ID,
                context.getString(R.string.downloads),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = context.getString(R.string.download_notifications_description)
            }
            notificationManager?.createNotificationChannel(notificationChannel)
        }
    }

    private inner class JellyfinDownloadTracker(val jellyfinMediaSource: JellyfinMediaSource) :
        DownloadTracker.Listener {
        override fun onDownloadsChanged() {
            if (downloadTracker.isDownloaded(downloadUri)) {
                runBlocking {
                    withContext(Dispatchers.IO) {
                        storeDownloadSpecs(jellyfinMediaSource)
                    }
                }
                downloadTracker.removeListener(this)
            } else if (downloadTracker.isFailed(downloadUri)) {
                removeDownloadRemains(jellyfinMediaSource)
                downloadTracker.removeListener(this)
            }
        }
    }

    private companion object {
        const val END_OF_INPUT = -1
        const val DEFAULT_DOWNLOAD_MIME_TYPE = "application/octet-stream"
    }
}

private fun MobileAuthState.Authenticated.matches(owner: MobileAuthState.Authenticated): Boolean =
    serverId == owner.serverId &&
        userId == owner.userId &&
        credentialInstanceId == owner.credentialInstanceId
