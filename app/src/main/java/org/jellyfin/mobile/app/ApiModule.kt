package org.jellyfin.mobile.app

import okhttp3.OkHttpClient
import org.jellyfin.mobile.auth.AuthSessionCoordinator
import org.jellyfin.mobile.auth.transport.ApiClientRouteConfiguration
import org.jellyfin.mobile.auth.transport.ApiRouteEpochInterceptor
import org.jellyfin.mobile.auth.transport.AuthTransportSession
import org.jellyfin.mobile.auth.transport.AuthenticatedTransportInterceptor
import org.jellyfin.mobile.auth.transport.EpochRoutedApiClient
import org.jellyfin.mobile.auth.transport.MutableAuthTransportSession
import org.jellyfin.mobile.utils.Constants
import org.jellyfin.sdk.Jellyfin
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.okhttp.OkHttpFactory
import org.jellyfin.sdk.createJellyfin
import org.jellyfin.sdk.model.ClientInfo
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

val apiModule = module {
    single { AuthSessionCoordinator() }
    single { MutableAuthTransportSession() }
    single<AuthTransportSession> { get<MutableAuthTransportSession>() }

    // Stable SDK identity. Route delegates inherit this exact identity for every auth method.
    single {
        createJellyfin {
            context = androidContext()
            clientInfo = ClientInfo(name = Constants.APP_INFO_NAME, version = Constants.APP_INFO_VERSION)
        }
    }

    single<ApiClient> {
        val jellyfin = get<Jellyfin>()
        val authTransportSession = get<AuthTransportSession>()
        val initialConfiguration = ApiClientRouteConfiguration(
            baseUrl = null,
            accessToken = null,
            clientInfo = requireNotNull(jellyfin.clientInfo),
            deviceInfo = requireNotNull(jellyfin.deviceInfo),
        )

        EpochRoutedApiClient(initialConfiguration, authTransportSession) { route ->
            val routeHttpClient = OkHttpClient.Builder()
                .addInterceptor(ApiRouteEpochInterceptor(route))
                .addInterceptor(AuthenticatedTransportInterceptor(authTransportSession))
                .build()
            val routeFactory = OkHttpFactory(routeHttpClient)
            createJellyfin {
                context = androidContext()
                clientInfo = route.clientInfo
                deviceInfo = route.deviceInfo
                apiClientFactory = routeFactory
                socketConnectionFactory = routeFactory
            }.createApi(
                baseUrl = route.baseUrl,
                accessToken = route.accessToken,
            )
        }
    }
}
