package org.jellyfin.mobile.app

import android.content.Context
import androidx.media3.common.util.Util
import androidx.media3.database.DatabaseProvider
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.source.SingleSampleMediaSource
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ts.TsExtractor
import coil3.ImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import okhttp3.Credentials
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jellyfin.mobile.MainViewModel
import org.jellyfin.mobile.auth.AuthSessionCoordinator
import org.jellyfin.mobile.auth.storage.SecureSessionTokenStore
import org.jellyfin.mobile.auth.transport.AuthTransportRealm
import org.jellyfin.mobile.auth.transport.AuthTransportSession
import org.jellyfin.mobile.auth.transport.AuthenticatedTransportInterceptor
import org.jellyfin.mobile.auth.transport.INTERNAL_AUTH_EPOCH_HEADER
import org.jellyfin.mobile.auth.transport.INTERNAL_LIBRARY_AUTH_HEADER
import org.jellyfin.mobile.auth.transport.INTERNAL_LIBRARY_AUTH_VALUE
import org.jellyfin.mobile.auth.transport.JellyfinSessionRevoker
import org.jellyfin.mobile.auth.transport.OkHttpJellyfinSessionRevoker
import org.jellyfin.mobile.auth.transport.authTransportRealm
import org.jellyfin.mobile.auth.transport.hasSameOrigin
import org.jellyfin.mobile.bridge.MediaSegments
import org.jellyfin.mobile.bridge.NativePlayer
import org.jellyfin.mobile.data.LegacyTokenResidueRemediation
import org.jellyfin.mobile.downloads.AUTHENTICATED_DOWNLOAD_DATA_SOURCE
import org.jellyfin.mobile.downloads.AuthenticatedDownloadIndexSanitizer
import org.jellyfin.mobile.downloads.AuthenticatedDownloadOwnership
import org.jellyfin.mobile.downloads.DownloadsViewModel
import org.jellyfin.mobile.events.ActivityEventHandler
import org.jellyfin.mobile.feature.library.LibraryReaderFragment
import org.jellyfin.mobile.feature.library.LibraryReaderLaunchRequestRegistry
import org.jellyfin.mobile.feature.library.LibraryReaderStore
import org.jellyfin.mobile.feature.library.LibraryRepository
import org.jellyfin.mobile.feature.library.LibraryViewModel
import org.jellyfin.mobile.feature.library.OpdsClient
import org.jellyfin.mobile.feature.music.MusicPlaybackController
import org.jellyfin.mobile.feature.music.MusicRepository
import org.jellyfin.mobile.feature.music.MusicSongActionHandler
import org.jellyfin.mobile.feature.music.MusicViewModel
import org.jellyfin.mobile.feature.music.auto.PtvMusicAutoResumeCoordinator
import org.jellyfin.mobile.feature.music.auto.PtvMusicAutoResumeStore
import org.jellyfin.mobile.feature.music.auto.PtvMusicSessionInvalidator
import org.jellyfin.mobile.home.NativeHomeFragment
import org.jellyfin.mobile.player.deviceprofile.DeviceProfileBuilder
import org.jellyfin.mobile.player.interaction.PlayerEvent
import org.jellyfin.mobile.player.mediasegments.MediaSegmentRepository
import org.jellyfin.mobile.player.qualityoptions.QualityOptionsProvider
import org.jellyfin.mobile.player.source.MediaSourceResolver
import org.jellyfin.mobile.player.ui.PlayerFragment
import org.jellyfin.mobile.reporting.MediaReportSender
import org.jellyfin.mobile.setup.ConnectionHelper
import org.jellyfin.mobile.signup.NativeSignupRepository
import org.jellyfin.mobile.ui.screens.home.NativeHomeViewModel
import org.jellyfin.mobile.utils.Constants
import org.jellyfin.mobile.utils.PermissionRequestHelper
import org.jellyfin.mobile.utils.extractId
import org.jellyfin.mobile.utils.isLowRamDevice
import org.jellyfin.mobile.webapp.RemoteVolumeProvider
import org.jellyfin.mobile.webapp.WebViewFragment
import org.jellyfin.mobile.webapp.WebappFunctionChannel
import org.koin.android.ext.koin.androidApplication
import org.koin.androidx.fragment.dsl.fragment
import org.koin.core.module.dsl.viewModel
import org.koin.core.qualifier.named
import org.koin.dsl.module
import java.io.File
import java.io.InterruptedIOException

const val PLAYER_EVENT_CHANNEL = "PlayerEventChannel"
private const val TS_SEARCH_PACKETS = 1800

val applicationModule = module {
    single { AppPreferences(androidApplication()) }
    single { SecureSessionTokenStore(androidApplication()) }
    single { InMemoryCookieJar() }
    single {
        val appPreferences: AppPreferences = get()
        val authTransportSession: AuthTransportSession = get()
        OkHttpClient.Builder()
            .cookieJar(get<InMemoryCookieJar>())
            .addInterceptor { chain ->
                val request = chain.request()
                val libraryBaseUrl = appPreferences.libraryServerBaseUrl.toHttpUrlOrNull()
                val cleanRequest = request.newBuilder()
                    .removeHeader(INTERNAL_LIBRARY_AUTH_HEADER)
                    .apply {
                        if (request.header(INTERNAL_LIBRARY_AUTH_HEADER) == INTERNAL_LIBRARY_AUTH_VALUE) {
                            authTransportRealm(AuthTransportRealm.LIBRARY)
                        }
                    }
                    .build()
                val authenticatedRequest = if (shouldApplyLibraryAuthentication(
                        request = request,
                        libraryBaseUrl = libraryBaseUrl,
                    )
                ) {
                    val builder = cleanRequest.newBuilder()
                        .authTransportRealm(AuthTransportRealm.LIBRARY)
                    appPreferences.libraryBearerToken?.let { token ->
                        builder.header("Authorization", "Bearer $token")
                    } ?: appPreferences.libraryUsername?.let { username ->
                        builder.header(
                            "Authorization",
                            Credentials.basic(username, appPreferences.libraryPassword.orEmpty()),
                        )
                    }
                    builder.build()
                } else {
                    cleanRequest
                }

                chain.proceed(authenticatedRequest)
            }
            .addInterceptor(AuthenticatedTransportInterceptor(authTransportSession))
            .build()
    }
    single {
        ImageLoader.Builder(androidApplication())
            .components {
                add(OkHttpNetworkFetcherFactory(get<OkHttpClient>()))
            }
            .build()
    }
    single { PermissionRequestHelper() }
    single<JellyfinSessionRevoker> { OkHttpJellyfinSessionRevoker(get()) }
    single {
        MediaReportSender(
            okHttpClient = get(),
            apiClient = get(),
            authTransportSession = get(),
            ioDispatcher = Dispatchers.IO,
        )
    }
    single { NativeSignupRepository(get()) }
    single { OpdsClient(get()) }
    single { LibraryReaderLaunchRequestRegistry() }
    single { LibraryReaderStore(androidApplication()) }
    single { LibraryRepository(get(), get(), get()) }
    single {
        val apiClientController: ApiClientController = get()
        MusicRepository(
            apiClient = get(),
            context = androidApplication(),
            savedUserIdProvider = { apiClientController.authenticatedUserId },
        )
    }
    single { PtvMusicAutoResumeStore(androidApplication(), get()) }
    single { PtvMusicSessionInvalidator(get()) }
    single { MusicPlaybackController(get(), get(), get(), get(), get(), get(), get()) }
    single { MusicSongActionHandler(get(), get()) }
    single { PtvMusicAutoResumeCoordinator(get(), get(), get()) }
    single { RemoteVolumeProvider(get()) }
    single(named(PLAYER_EVENT_CHANNEL)) { Channel<PlayerEvent>() }

    // Controllers
    single {
        ApiClientController(
            application = androidApplication(),
            appPreferences = get(),
            jellyfin = get(),
            apiClient = get(),
            serverDao = get(),
            userDao = get(),
            musicSessionInvalidator = get(),
            secureSessionTokenStore = get(),
            legacyTokenResidueRemediation = get<LegacyTokenResidueRemediation>(),
            authCoordinator = get(),
            authTransportSession = get(),
            sessionRevoker = get(),
        )
    }

    // Event handlers and channels
    single { ActivityEventHandler(get(), get()) }
    single { WebappFunctionChannel() }

    // Bridge interfaces
    single { NativePlayer(get(), get(), get(named(PLAYER_EVENT_CHANNEL))) }
    single { MediaSegments(get()) }

    // ViewModels
    viewModel { MainViewModel(get(), get(), get()) }
    viewModel { DownloadsViewModel() }
    viewModel { NativeHomeViewModel(get(), get(), get(), get()) }
    viewModel { LibraryViewModel(get(), get()) }
    viewModel { MusicViewModel(get(), get(), get(), get()) }

    // Fragments
    fragment { WebViewFragment() }
    fragment { NativeHomeFragment() }
    fragment { LibraryReaderFragment() }
    fragment { PlayerFragment() }

    // Connection helper
    single { ConnectionHelper(get(), get()) }

    // Media player helpers
    single { MediaSourceResolver(get()) }
    single { DeviceProfileBuilder(get()) }
    single { QualityOptionsProvider() }
    single { MediaSegmentRepository() }

    // ExoPlayer factories
    single<DatabaseProvider> {
        val dbProvider = StandaloneDatabaseProvider(get<Context>())
        dbProvider
    }
    single { AuthenticatedDownloadIndexSanitizer(get<DatabaseProvider>()) }
    single<Cache> {
        val downloadPath = File(get<Context>().filesDir, Constants.DOWNLOAD_PATH)
        if (!downloadPath.exists()) {
            downloadPath.mkdirs()
        }
        val cache = SimpleCache(downloadPath, NoOpCacheEvictor(), get())
        cache
    }

    single<DataSource.Factory> {
        val context: Context = get()
        val authTransportSession: AuthTransportSession = get()

        val baseDataSourceFactory = OkHttpDataSource.Factory(get<OkHttpClient>())
            .setUserAgent(Util.getUserAgent(context, Constants.APP_INFO_NAME))

        val dataSourceFactory = DefaultDataSource.Factory(context, baseDataSourceFactory)
        ResolvingDataSource.Factory(dataSourceFactory) { dataSpec: DataSpec ->
            val requestUrl = dataSpec.uri.toString().toHttpUrlOrNull()
            val snapshot = authTransportSession.currentSnapshot()
            if (requestUrl != null && snapshot?.serverOrigin?.hasSameOrigin(requestUrl) == true) {
                dataSpec.withRequestHeaders(
                    dataSpec.httpRequestHeaders + (INTERNAL_AUTH_EPOCH_HEADER to snapshot.epoch.toString()),
                )
            } else {
                dataSpec
            }
        }
    }

    single<DataSource.Factory>(named(AUTHENTICATED_DOWNLOAD_DATA_SOURCE)) {
        val authCoordinator: AuthSessionCoordinator = get()
        ResolvingDataSource.Factory(get<DataSource.Factory>()) { dataSpec ->
            if (!AuthenticatedDownloadOwnership.isAuthorized(dataSpec.key, authCoordinator.state.value)) {
                throw InterruptedIOException("AUTH_OPERATION_SUPERSEDED")
            }
            dataSpec
        }
    }

    single<CacheDataSource.Factory> {
        // Create a read-only cache data source factory using the download cache.
        CacheDataSource.Factory()
            .setCache(get())
            .setUpstreamDataSourceFactory(get<DataSource.Factory>())
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
            .setCacheWriteDataSinkFactory(null)
            .setCacheKeyFactory { spec ->
                spec.uri.extractId()
            }
    }

    single<MediaSource.Factory> {
        val context: Context = get()
        val extractorsFactory = DefaultExtractorsFactory().apply {
            // https://github.com/google/ExoPlayer/issues/8571
            setTsExtractorTimestampSearchBytes(
                when {
                    !context.isLowRamDevice -> TS_SEARCH_PACKETS * TsExtractor.TS_PACKET_SIZE

                    // 3x default
                    else -> TsExtractor.DEFAULT_TIMESTAMP_SEARCH_BYTES
                },
            )
        }
        DefaultMediaSourceFactory(get<CacheDataSource.Factory>(), extractorsFactory)
    }
    single { ProgressiveMediaSource.Factory(get<CacheDataSource.Factory>()) }
    single { HlsMediaSource.Factory(get<CacheDataSource.Factory>()) }
    single { SingleSampleMediaSource.Factory(get<CacheDataSource.Factory>()) }
}

/**
 * Only explicitly library-owned traffic may receive Reading/OPDS credentials. Jellyfin, excluded,
 * and unowned traffic remains under its own transport policy even when services share an origin.
 */
internal fun shouldApplyLibraryAuthentication(request: Request, libraryBaseUrl: okhttp3.HttpUrl?): Boolean {
    val realm = request.tag(AuthTransportRealm::class.java)
    return libraryBaseUrl?.hasSameOrigin(request.url) == true &&
        request.header("Authorization") == null &&
        realm != AuthTransportRealm.JELLYFIN &&
        realm != AuthTransportRealm.EXCLUDED &&
        request.header(INTERNAL_AUTH_EPOCH_HEADER) == null &&
        (
            request.header(INTERNAL_LIBRARY_AUTH_HEADER) == INTERNAL_LIBRARY_AUTH_VALUE ||
                realm == AuthTransportRealm.LIBRARY
            )
}
