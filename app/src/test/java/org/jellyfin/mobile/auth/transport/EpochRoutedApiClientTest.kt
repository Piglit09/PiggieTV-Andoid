package org.jellyfin.mobile.auth.transport

import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.Request
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.HttpClientOptions
import org.jellyfin.sdk.api.client.HttpMethod
import org.jellyfin.sdk.api.client.RawResponse
import org.jellyfin.sdk.api.sockets.SocketApi
import org.jellyfin.sdk.api.sockets.SocketApiState
import org.jellyfin.sdk.model.ClientInfo
import org.jellyfin.sdk.model.DeviceInfo
import org.jellyfin.sdk.model.api.OutboundWebSocketMessage
import org.junit.jupiter.api.Test
import kotlin.reflect.KClass

class EpochRoutedApiClientTest {
    @Test
    fun `requests reuse an immutable delegate until configuration or epoch changes`() = runBlocking<Unit> {
        val authSession = MutableAuthTransportSession(
            snapshot(epoch = 1, serverUrl = SERVER_URL, accessToken = "token-one"),
        )
        val factory = RecordingDelegateFactory()
        val client = EpochRoutedApiClient(configuration(token = "token-one"), authSession, factory)

        client.request(HttpMethod.GET, "/Users/Me", emptyMap(), emptyMap(), null)
        client.request(HttpMethod.GET, "/Items", emptyMap(), emptyMap(), null)

        factory.routes.size shouldBe 1
        factory.routes.single().epoch shouldBe 1L
        factory.delegates.single().requests shouldBe listOf("GET /Users/Me", "GET /Items")

        authSession.current = snapshot(epoch = 2, serverUrl = SERVER_URL, accessToken = "token-one")
        client.request(HttpMethod.GET, "/Users/Me", emptyMap(), emptyMap(), null)

        factory.routes.map(ApiClientRouteSnapshot::epoch) shouldBe listOf(1L, 2L)
        factory.routes.first().epoch shouldBe 1L

        authSession.current = snapshot(epoch = 3, serverUrl = SERVER_URL, accessToken = "token-two")
        client.update(
            baseUrl = SERVER_URL,
            accessToken = "token-two",
            clientInfo = CLIENT_INFO,
            deviceInfo = DEVICE_INFO,
        )
        client.request(HttpMethod.GET, "/Items", emptyMap(), emptyMap(), null)

        factory.routes.map(ApiClientRouteSnapshot::accessToken) shouldBe
            listOf("token-one", "token-one", "token-two")
        factory.delegates.all { delegate -> delegate.updateCalls == 0 }.shouldBeTrue()
    }

    @Test
    fun `a session for another origin produces no authenticated epoch route`() = runBlocking<Unit> {
        val authSession = MutableAuthTransportSession(
            snapshot(epoch = 5, serverUrl = "https://other.example.test", accessToken = "secret-token"),
        )
        val factory = RecordingDelegateFactory()
        val client = EpochRoutedApiClient(configuration(token = "secret-token"), authSession, factory)

        client.request(HttpMethod.GET, "/System/Info", emptyMap(), emptyMap(), null)

        factory.routes.single().epoch shouldBe null
        factory.routes.single().internalEpochHeaderValue shouldBe null
    }

    @Test
    fun `same-origin server-only candidate does not inherit the active epoch`() = runBlocking<Unit> {
        val authSession = MutableAuthTransportSession(
            snapshot(epoch = 6, serverUrl = SERVER_URL, accessToken = "active-token"),
        )
        val factory = RecordingDelegateFactory()
        val client = EpochRoutedApiClient(configuration(token = null), authSession, factory)

        client.request(HttpMethod.GET, "/System/Info/Public", emptyMap(), emptyMap(), null)

        factory.routes.single().epoch shouldBe null
    }

    @Test
    fun `same-origin restore candidate token does not inherit the active epoch`() = runBlocking<Unit> {
        val authSession = MutableAuthTransportSession(
            snapshot(epoch = 7, serverUrl = SERVER_URL, accessToken = "active-token"),
        )
        val factory = RecordingDelegateFactory()
        val client = EpochRoutedApiClient(configuration(token = "restore-candidate"), authSession, factory)

        client.request(HttpMethod.GET, "/Users/Me", emptyMap(), emptyMap(), null)

        factory.routes.single().epoch shouldBe null
        factory.routes.single().accessToken shouldBe "restore-candidate"
    }

    @Test
    fun `route diagnostics redact URL and access token`() {
        val route = ApiClientRouteSnapshot(
            epoch = 8,
            configuration = configuration(token = "do-not-print"),
        )

        route.toString().contains("do-not-print").shouldBeFalse()
        route.toString().contains(SERVER_URL).shouldBeFalse()
        route.internalEpochHeaderValue shouldBe "8"
    }

    @Test
    fun `route epoch interceptor stamps an internal Jellyfin marker`() {
        val route = ApiClientRouteSnapshot(epoch = 12, configuration = configuration(token = "token"))
        val original = Request.Builder().url("https://media.example.test/Items/1").build()
        val chain = mockk<Interceptor.Chain>()
        var proceeded: Request? = null
        every { chain.request() } returns original
        every { chain.proceed(any()) } answers {
            proceeded = firstArg()
            mockk()
        }

        ApiRouteEpochInterceptor(route).intercept(chain)

        proceeded?.header(INTERNAL_AUTH_EPOCH_HEADER) shouldBe "12"
        proceeded?.tag(AuthTransportRealm::class.java) shouldBe AuthTransportRealm.JELLYFIN
    }

    @Test
    fun `candidate route is excluded from active-session injection`() {
        val route = ApiClientRouteSnapshot(epoch = null, configuration = configuration(token = "candidate"))
        val original = Request.Builder().url("https://media.example.test/Users/AuthenticateByName").build()
        val chain = mockk<Interceptor.Chain>()
        var proceeded: Request? = null
        every { chain.request() } returns original
        every { chain.proceed(any()) } answers {
            proceeded = firstArg()
            mockk()
        }

        ApiRouteEpochInterceptor(route).intercept(chain)

        proceeded?.header(INTERNAL_AUTH_EPOCH_HEADER) shouldBe null
        proceeded?.tag(AuthTransportRealm::class.java) shouldBe AuthTransportRealm.EXCLUDED
    }

    private fun configuration(token: String?): ApiClientRouteConfiguration = ApiClientRouteConfiguration(
        baseUrl = SERVER_URL,
        accessToken = token,
        clientInfo = CLIENT_INFO,
        deviceInfo = DEVICE_INFO,
    )

    private fun snapshot(epoch: Long, serverUrl: String, accessToken: String): AuthTransportSnapshot =
        AuthTransportSnapshot(
            epoch = epoch,
            serverUrl = serverUrl,
            accessToken = accessToken,
            authorizationHeader = "MediaBrowser Token=\"$accessToken\"",
        )

    private class MutableAuthTransportSession(var current: AuthTransportSnapshot?) : AuthTransportSession {
        override fun currentSnapshot(): AuthTransportSnapshot? = current

        override fun publishAuthenticationRejected(snapshot: AuthTransportSnapshot): Boolean = false
    }

    private class RecordingDelegateFactory : EpochRoutedApiClientDelegateFactory {
        val routes = mutableListOf<ApiClientRouteSnapshot>()
        val delegates = mutableListOf<FakeApiClient>()

        override fun create(route: ApiClientRouteSnapshot): ApiClient {
            routes += route
            return FakeApiClient(route).also(delegates::add)
        }
    }

    private class FakeApiClient(private val route: ApiClientRouteSnapshot) : ApiClient() {
        val requests = mutableListOf<String>()
        var updateCalls = 0

        override val baseUrl: String? = route.baseUrl
        override val accessToken: String? = route.accessToken
        override val clientInfo: ClientInfo = route.clientInfo
        override val deviceInfo: DeviceInfo = route.deviceInfo
        override val httpClientOptions: HttpClientOptions = HttpClientOptions()
        override val webSocket: SocketApi = EmptySocketApi

        override fun update(baseUrl: String?, accessToken: String?, clientInfo: ClientInfo, deviceInfo: DeviceInfo) {
            updateCalls += 1
        }

        override suspend fun request(
            method: HttpMethod,
            pathTemplate: String,
            pathParameters: Map<String, Any?>,
            queryParameters: Map<String, Any?>,
            requestBody: Any?,
        ): RawResponse {
            requests += "${method.name} $pathTemplate"
            return RawResponse("{}".toByteArray(), 200, emptyMap())
        }
    }

    private object EmptySocketApi : SocketApi {
        override val state = MutableStateFlow<SocketApiState>(SocketApiState.Disconnected())

        override fun subscribeAll(): Flow<OutboundWebSocketMessage> = emptyFlow()

        override fun <T : OutboundWebSocketMessage> subscribe(messageType: KClass<T>): Flow<T> = emptyFlow()
    }

    private companion object {
        const val SERVER_URL = "https://media.example.test/jellyfin"
        val CLIENT_INFO = ClientInfo(name = "test", version = "1")
        val DEVICE_INFO = DeviceInfo(id = "device", name = "Test Device")
    }
}
