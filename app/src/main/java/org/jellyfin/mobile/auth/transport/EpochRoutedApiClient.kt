package org.jellyfin.mobile.auth.transport

import okhttp3.Interceptor
import okhttp3.Response
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.HttpClientOptions
import org.jellyfin.sdk.api.client.HttpMethod
import org.jellyfin.sdk.api.client.RawResponse
import org.jellyfin.sdk.api.sockets.SocketApi
import org.jellyfin.sdk.model.ClientInfo
import org.jellyfin.sdk.model.DeviceInfo

/** Mutable SDK configuration copied into an immutable route before each delegated operation. */
internal data class ApiClientRouteConfiguration(
    val baseUrl: String?,
    val accessToken: String?,
    val clientInfo: ClientInfo,
    val deviceInfo: DeviceInfo,
) {
    override fun toString(): String = "ApiClientRouteConfiguration(" +
        "baseUrl=[REDACTED], accessToken=[REDACTED], clientInfo=$clientInfo, deviceInfo=$deviceInfo)"
}

/**
 * Immutable route supplied to a delegate factory.
 *
 * A route-bound OkHttp delegate should install [ApiRouteEpochInterceptor] immediately before
 * [AuthenticatedTransportInterceptor]. The latter always removes the marker before I/O.
 */
internal data class ApiClientRouteSnapshot(val epoch: Long?, val configuration: ApiClientRouteConfiguration) {
    val baseUrl: String? get() = configuration.baseUrl
    val accessToken: String? get() = configuration.accessToken
    val clientInfo: ClientInfo get() = configuration.clientInfo
    val deviceInfo: DeviceInfo get() = configuration.deviceInfo
    val internalEpochHeaderValue: String? get() = epoch?.toString()

    override fun toString(): String = "ApiClientRouteSnapshot(epoch=$epoch, configuration=$configuration)"
}

internal fun interface EpochRoutedApiClientDelegateFactory {
    /** Returns a route-bound delegate that will never be mutated by [EpochRoutedApiClient]. */
    fun create(route: ApiClientRouteSnapshot): ApiClient
}

/**
 * Marks an epoch-bound active route as Jellyfin-owned. Candidate login and restore routes are
 * excluded so they retain their own optional credential without inheriting the active session.
 */
internal class ApiRouteEpochInterceptor(private val route: ApiClientRouteSnapshot) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request().newBuilder()
            .authTransportRealm(
                if (route.epoch == null) AuthTransportRealm.EXCLUDED else AuthTransportRealm.JELLYFIN,
            )
            .authTransportEpoch(route.epoch)
            .build()
        return chain.proceed(request)
    }
}

/**
 * SDK facade that prevents a mutable ApiClient from changing the route of an in-flight request.
 *
 * Each request captures configuration and authentication epoch once. A new delegate is created
 * when either value changes; older requests keep their original route and epoch marker.
 */
internal class EpochRoutedApiClient(
    initialConfiguration: ApiClientRouteConfiguration,
    private val authSession: AuthTransportSession,
    private val delegateFactory: EpochRoutedApiClientDelegateFactory,
) : ApiClient() {
    private data class CachedDelegate(val route: ApiClientRouteSnapshot, val delegate: ApiClient)

    private val routeLock = Any()

    @Volatile
    private var configuration = initialConfiguration
    private var cachedDelegate: CachedDelegate? = null

    override val baseUrl: String? get() = configuration.baseUrl
    override val accessToken: String? get() = configuration.accessToken
    override val clientInfo: ClientInfo get() = configuration.clientInfo
    override val deviceInfo: DeviceInfo get() = configuration.deviceInfo
    override val httpClientOptions: HttpClientOptions get() = currentDelegate().httpClientOptions
    override val webSocket: SocketApi get() = currentDelegate().webSocket

    override fun update(baseUrl: String?, accessToken: String?, clientInfo: ClientInfo, deviceInfo: DeviceInfo) {
        synchronized(routeLock) {
            configuration = ApiClientRouteConfiguration(baseUrl, accessToken, clientInfo, deviceInfo)
            cachedDelegate = null
        }
    }

    override suspend fun request(
        method: HttpMethod,
        pathTemplate: String,
        pathParameters: Map<String, Any?>,
        queryParameters: Map<String, Any?>,
        requestBody: Any?,
    ): RawResponse {
        val route = captureRoute()
        return delegateFor(route).request(method, pathTemplate, pathParameters, queryParameters, requestBody)
    }

    private fun currentDelegate(): ApiClient = delegateFor(captureRoute())

    private fun captureRoute(): ApiClientRouteSnapshot = authSession.withCurrentSnapshot { currentSession ->
        val capturedConfiguration = configuration
        val configuredOrigin = capturedConfiguration.baseUrl?.toHttpUrlWithoutAuthenticationQuery()
        val epoch = currentSession?.takeIf { snapshot ->
            val sessionOrigin = snapshot.serverOrigin
            configuredOrigin != null &&
                sessionOrigin?.hasSameOrigin(configuredOrigin) == true &&
                snapshot.matchesAccessToken(capturedConfiguration.accessToken)
        }?.epoch
        ApiClientRouteSnapshot(epoch, capturedConfiguration)
    }

    private fun delegateFor(route: ApiClientRouteSnapshot): ApiClient = synchronized(routeLock) {
        cachedDelegate?.takeIf { cached -> cached.route == route }?.delegate
            ?: delegateFactory.create(route).also { delegate ->
                cachedDelegate = CachedDelegate(route, delegate)
            }
    }
}
