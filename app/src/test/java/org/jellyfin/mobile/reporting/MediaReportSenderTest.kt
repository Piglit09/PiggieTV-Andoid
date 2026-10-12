package org.jellyfin.mobile.reporting

import com.sun.net.httpserver.HttpServer
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.jellyfin.mobile.auth.transport.AuthTransportSession
import org.jellyfin.mobile.auth.transport.AuthTransportSnapshot
import org.jellyfin.mobile.auth.transport.AuthenticatedTransportInterceptor
import org.jellyfin.mobile.auth.transport.INTERNAL_AUTH_EPOCH_HEADER
import org.jellyfin.sdk.api.client.ApiClient
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger

class MediaReportSenderTest {
    @Test
    fun `report endpoint stays on the configured server and preserves its base path`() {
        buildMediaReportEndpoint("https://ptv.example/jellyfin?old=query#fragment").toString() shouldBe
            "https://ptv.example/jellyfin/Ptv/v1/reports/media"
    }

    @Test
    fun `invalid or missing server URL disables reporting`() {
        buildMediaReportEndpoint(null) shouldBe null
        buildMediaReportEndpoint("") shouldBe null
        buildMediaReportEndpoint("not a URL") shouldBe null
    }

    @Test
    fun `payload uses stable values limits user text and omits user identity`() {
        val payload = buildMediaReportPayload(
            target = MediaReportTarget(
                itemId = " item-1 ",
                title = " Title ",
                subtitle = " Subtitle ",
                type = "Movie",
                source = MediaReportSource.PLAYBACK,
                playbackPositionMs = -1,
                mediaSourceId = "source-1",
                playMethod = "DirectPlay",
            ),
            reason = MediaReportReason.SUBTITLE_SYNC,
            details = "x".repeat(2_100),
        )

        payload["schemaVersion"]?.toString() shouldBe "1"
        payload["itemId"]?.toString() shouldBe "\"item-1\""
        payload["source"]?.toString() shouldBe "\"playback\""
        payload["reason"]?.toString() shouldBe "\"subtitle-sync\""
        payload["playbackPositionMs"]?.toString() shouldBe "0"
        payload["details"]?.toString()?.length shouldBe 1_026
        payload.containsKey("title") shouldBe false
        payload.containsKey("subtitle") shouldBe false
        payload.containsKey("userName") shouldBe false
        payload.containsKey("destination") shouldBe false
    }

    @Test
    fun `server responses map to truthful delivery results`() {
        classifyMediaReportResponse(204) shouldBe MediaReportDeliveryResult.SENT
        classifyMediaReportResponse(404) shouldBe MediaReportDeliveryResult.UNAVAILABLE
        classifyMediaReportResponse(503) shouldBe MediaReportDeliveryResult.UNAVAILABLE
        classifyMediaReportResponse(429) shouldBe MediaReportDeliveryResult.RATE_LIMITED
        classifyMediaReportResponse(401) shouldBe MediaReportDeliveryResult.FAILED
        classifyMediaReportResponse(500) shouldBe MediaReportDeliveryResult.FAILED
    }

    @Test
    fun `sender posts one privacy-minimized request through authenticated transport`() {
        val outcome = execute(status = 202)

        outcome.result shouldBe MediaReportDeliveryResult.SENT
        outcome.requestCount shouldBe 1
        outcome.request?.method shouldBe "POST"
        outcome.request?.url?.encodedPath shouldBe "/jellyfin/Ptv/v1/reports/media"
        outcome.request?.header("Authorization") shouldBe AUTHORIZATION
        outcome.request?.header(INTERNAL_AUTH_EPOCH_HEADER) shouldBe null
        val payload = Json.parseToJsonElement(requireNotNull(outcome.requestBody)).jsonObject
        payload["schemaVersion"]?.toString() shouldBe "1"
        payload["itemId"]?.toString() shouldBe "\"0123456789abcdef0123456789abcdef\""
        payload["reason"]?.toString() shouldBe "\"subtitle-sync\""
        payload.containsKey("title") shouldBe false
        payload.containsKey("subtitle") shouldBe false
        payload.containsKey("userId") shouldBe false
        outcome.session.acceptedRejections shouldBe emptyList()
    }

    @Test
    fun `ordinary endpoint and network failures neither retry nor reject authentication`() {
        listOf(
            404 to MediaReportDeliveryResult.UNAVAILABLE,
            500 to MediaReportDeliveryResult.FAILED,
        ).forEach { (status, expected) ->
            val outcome = execute(status = status)
            outcome.result shouldBe expected
            outcome.requestCount shouldBe 1
            outcome.session.acceptedRejections shouldBe emptyList()
        }

        val networkFailure = execute(failure = IOException("offline"))
        networkFailure.result shouldBe MediaReportDeliveryResult.FAILED
        networkFailure.requestCount shouldBe 1
        networkFailure.session.acceptedRejections shouldBe emptyList()
    }

    @Test
    fun `server follow-up cannot replay the one-shot report body`() {
        val requestCount = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0)
        server.createContext("/") { exchange ->
            exchange.requestBody.use { it.readBytes() }
            val attempt = requestCount.incrementAndGet()
            val body = "{}".toByteArray()
            if (attempt == 1) exchange.responseHeaders.add("Retry-After", "0")
            exchange.sendResponseHeaders(if (attempt == 1) 503 else 202, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()

        try {
            val serverBaseUrl = "http://${server.address.address.hostAddress}:${server.address.port}/jellyfin"
            val session = FakeAuthTransportSession(snapshot(serverUrl = serverBaseUrl))
            val apiClient = mockk<ApiClient>()
            every { apiClient.baseUrl } returns serverBaseUrl
            every { apiClient.accessToken } returns ACCESS_TOKEN
            val sender = MediaReportSender(
                okHttpClient = OkHttpClient.Builder()
                    .addInterceptor(AuthenticatedTransportInterceptor(session))
                    .build(),
                apiClient = apiClient,
                authTransportSession = session,
                ioDispatcher = Dispatchers.Unconfined,
            )

            send(sender) shouldBe MediaReportDeliveryResult.UNAVAILABLE
            requestCount.get() shouldBe 1
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `candidate route credentials cannot inherit the active session`() {
        val missingToken = execute(apiToken = null)
        missingToken.result shouldBe MediaReportDeliveryResult.UNAVAILABLE
        missingToken.requestCount shouldBe 0

        val differentToken = execute(apiToken = "candidate-token")
        differentToken.result shouldBe MediaReportDeliveryResult.UNAVAILABLE
        differentToken.requestCount shouldBe 0
    }

    @Test
    fun `session replacement after atomic capture silently supersedes the report`() {
        val session = FakeAuthTransportSession(snapshot())
        session.replacementAfterCapture = snapshot(epoch = AUTH_EPOCH + 1)

        val error = assertThrows(CancellationException::class.java) {
            execute(session = session)
        }

        error.message shouldBe "AUTH_OPERATION_SUPERSEDED"
        session.acceptedRejections shouldBe emptyList()
    }

    @Test
    fun `genuine current-session 401 reaches centralized recovery without a sender retry`() {
        val outcome = execute(status = 401)

        outcome.result shouldBe MediaReportDeliveryResult.FAILED
        outcome.requestCount shouldBe 1
        outcome.session.acceptedRejections.map(AuthTransportSnapshot::epoch) shouldBe listOf(AUTH_EPOCH)
    }

    private fun execute(
        status: Int = 202,
        failure: IOException? = null,
        apiToken: String? = ACCESS_TOKEN,
        session: FakeAuthTransportSession = FakeAuthTransportSession(snapshot()),
    ): HttpOutcome {
        var requestCount = 0
        var capturedRequest: Request? = null
        var requestBody: String? = null
        val terminal = Interceptor { chain ->
            requestCount += 1
            capturedRequest = chain.request()
            requestBody = chain.request().body?.let { body ->
                Buffer().also(body::writeTo).readUtf8()
            }
            failure?.let { throw it }
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(status)
                .message("test")
                .body("{}".toResponseBody())
                .build()
        }
        val apiClient = mockk<ApiClient>()
        every { apiClient.baseUrl } returns SERVER_BASE_URL
        every { apiClient.accessToken } returns apiToken
        val sender = MediaReportSender(
            okHttpClient = OkHttpClient.Builder()
                .addInterceptor(AuthenticatedTransportInterceptor(session))
                .addInterceptor(terminal)
                .build(),
            apiClient = apiClient,
            authTransportSession = session,
            ioDispatcher = Dispatchers.Unconfined,
        )
        val result = send(sender)
        return HttpOutcome(result, requestCount, capturedRequest, requestBody, session)
    }

    private fun send(sender: MediaReportSender): MediaReportDeliveryResult = runBlocking {
        sender.send(
            target = MediaReportTarget(
                itemId = "0123456789abcdef0123456789abcdef",
                title = "Private title",
                subtitle = "Private subtitle",
                type = "Movie",
                source = MediaReportSource.PLAYBACK,
                playbackPositionMs = 120_000,
                mediaSourceId = "source-1",
                playMethod = "DirectPlay",
            ),
            reason = MediaReportReason.SUBTITLE_SYNC,
            details = "Subtitles are late.",
        )
    }

    private fun snapshot(
        epoch: Long = AUTH_EPOCH,
        serverUrl: String = SERVER_BASE_URL,
        accessToken: String = ACCESS_TOKEN,
    ) = AuthTransportSnapshot(
        epoch = epoch,
        serverUrl = serverUrl,
        accessToken = accessToken,
        authorizationHeader = AUTHORIZATION,
    )

    private data class HttpOutcome(
        val result: MediaReportDeliveryResult,
        val requestCount: Int,
        val request: Request?,
        val requestBody: String?,
        val session: FakeAuthTransportSession,
    )

    private class FakeAuthTransportSession(var current: AuthTransportSnapshot?) : AuthTransportSession {
        val acceptedRejections = mutableListOf<AuthTransportSnapshot>()
        var replacementAfterCapture: AuthTransportSnapshot? = null

        override fun currentSnapshot(): AuthTransportSnapshot? = current

        override fun <T> withCurrentSnapshot(block: (AuthTransportSnapshot?) -> T): T {
            val result = block(current)
            replacementAfterCapture?.let { replacement ->
                current = replacement
                replacementAfterCapture = null
            }
            return result
        }

        override fun registerAuthenticatedCall(
            snapshot: AuthTransportSnapshot,
            owner: Any,
            cancel: () -> Unit,
        ): Boolean = current === snapshot

        override fun publishAuthenticationRejected(snapshot: AuthTransportSnapshot): Boolean {
            val accepted = current === snapshot
            if (accepted) acceptedRejections += snapshot
            return accepted
        }
    }

    private companion object {
        const val SERVER_BASE_URL = "https://media.example.test/jellyfin"
        const val ACCESS_TOKEN = "synthetic-token"
        const val AUTHORIZATION = "MediaBrowser Token=\"synthetic-token\""
        const val AUTH_EPOCH = 42L
    }
}
