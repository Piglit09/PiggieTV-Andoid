package org.jellyfin.mobile.auth.transport

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import okhttp3.Call
import okhttp3.Interceptor
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.Test
import java.io.IOException
import java.io.InterruptedIOException

class AuthenticatedTransportInterceptorTest {
    @Test
    fun `current exact-origin 401 attaches authorization strips epoch and publishes rejection`() {
        val session = FakeAuthTransportSession(current = snapshot(epoch = 7))
        val original = request(SERVER_ITEM_URL)
            .authTransportEpoch(7)
            .build()
        val exchange = exchange(original, status = 401)

        val response = AuthenticatedTransportInterceptor(session).intercept(exchange.chain)

        response shouldBe exchange.response
        exchange.proceededRequest?.header("Authorization") shouldBe AUTHORIZATION
        exchange.proceededRequest?.header(INTERNAL_AUTH_EPOCH_HEADER) shouldBe null
        session.acceptedRejections.map(AuthTransportSnapshot::epoch) shouldBe listOf(7L)
        verify(exactly = 1) { exchange.chain.proceed(any()) }
    }

    @Test
    fun `stream stays registered through body lifetime and close releases it`() {
        val session = MutableAuthTransportSession()
        session.activate(7L, SERVER_URL, "secret", AUTHORIZATION)
        val firstCall = mockk<Call>(relaxed = true)
        val firstExchange = streamingExchange(request(SERVER_ITEM_URL).build(), firstCall)

        val openResponse = AuthenticatedTransportInterceptor(session).intercept(firstExchange.chain)
        session.clear()

        verify(exactly = 1) { firstCall.cancel() }
        openResponse.close()

        session.activate(8L, SERVER_URL, "secret", AUTHORIZATION)
        val secondCall = mockk<Call>(relaxed = true)
        val secondExchange = streamingExchange(request(SERVER_ITEM_URL).build(), secondCall)
        val closedResponse = AuthenticatedTransportInterceptor(session).intercept(secondExchange.chain)

        closedResponse.close()
        session.clear()

        verify(exactly = 0) { secondCall.cancel() }
    }

    @Test
    fun `an unmarked exact-origin image request uses the current session`() {
        val session = FakeAuthTransportSession(current = snapshot(epoch = 4))
        val exchange = exchange(request(SERVER_IMAGE_URL).build(), status = 200)

        AuthenticatedTransportInterceptor(session).intercept(exchange.chain)

        exchange.proceededRequest?.header("Authorization") shouldBe AUTHORIZATION
        session.acceptedRejections shouldBe emptyList()
    }

    @Test
    fun `queued stale epoch is cancelled before transport and cannot reject the new session`() {
        val session = FakeAuthTransportSession(current = snapshot(epoch = 9))
        val original = request(SERVER_ITEM_URL)
            .header("Authorization", AUTHORIZATION)
            .authTransportEpoch(8)
            .build()
        val exchange = exchange(original, status = 401)

        shouldThrow<InterruptedIOException> {
            AuthenticatedTransportInterceptor(session).intercept(exchange.chain)
        }

        exchange.proceededRequest shouldBe null
        session.attemptedRejections shouldBe emptyList()
        session.acceptedRejections shouldBe emptyList()
        verify(exactly = 0) { exchange.chain.proceed(any()) }
    }

    @Test
    fun `logout racing exact-epoch call registration strips authorization and cannot reject`() {
        val session = FakeAuthTransportSession(
            current = snapshot(epoch = 10),
            registrationAllowed = false,
        )
        val original = request(SERVER_ITEM_URL)
            .authTransportEpoch(10)
            .build()
        val exchange = exchange(original, status = 401)

        AuthenticatedTransportInterceptor(session).intercept(exchange.chain)

        exchange.proceededRequest?.header("Authorization") shouldBe null
        exchange.proceededRequest?.header(INTERNAL_AUTH_EPOCH_HEADER) shouldBe null
        session.registrationAttempts.map(AuthTransportSnapshot::epoch) shouldBe listOf(10L)
        session.attemptedRejections shouldBe emptyList()
        session.acceptedRejections shouldBe emptyList()
    }

    @Test
    fun `current epoch replaces a mismatched Jellyfin authorization before transport`() {
        val session = FakeAuthTransportSession(current = snapshot(epoch = 5))
        val original = request(SERVER_ITEM_URL)
            .header("Authorization", "MediaBrowser Token=\"older\"")
            .authTransportEpoch(5)
            .build()
        val exchange = exchange(original, status = 401)

        AuthenticatedTransportInterceptor(session).intercept(exchange.chain)

        exchange.proceededRequest?.header("Authorization") shouldBe AUTHORIZATION
        session.acceptedRejections.map(AuthTransportSnapshot::epoch) shouldBe listOf(5L)
    }

    @Test
    fun `unmarked retired Jellyfin authorization is stripped after logout`() {
        val session = FakeAuthTransportSession(current = null)
        val original = request(SERVER_ITEM_URL)
            .header("Authorization", AUTHORIZATION)
            .build()
        val exchange = exchange(original, status = 401)

        AuthenticatedTransportInterceptor(session).intercept(exchange.chain)

        exchange.proceededRequest?.header("Authorization") shouldBe null
        session.attemptedRejections shouldBe emptyList()
    }

    @Test
    fun `a response captured before same-token relogin is rejected by the session adapter`() {
        val first = snapshot(epoch = 11)
        val session = FakeAuthTransportSession(current = first)
        val original = request(SERVER_ITEM_URL).authTransportEpoch(11).build()
        val exchange = exchange(original, status = 401) {
            session.current = snapshot(epoch = 12)
        }

        AuthenticatedTransportInterceptor(session).intercept(exchange.chain)

        session.attemptedRejections shouldBe listOf(first)
        session.acceptedRejections shouldBe emptyList()
    }

    @Test
    fun `excluded same-origin request receives no Jellyfin credential or rejection`() {
        val session = FakeAuthTransportSession(current = snapshot(epoch = 3))
        val original = request("https://media.example.test/Ptv/v1/signup")
            .authTransportRealm(AuthTransportRealm.EXCLUDED)
            .authTransportEpoch(3)
            .build()
        val exchange = exchange(original, status = 401)

        AuthenticatedTransportInterceptor(session).intercept(exchange.chain)

        exchange.proceededRequest?.header("Authorization") shouldBe null
        exchange.proceededRequest?.header(INTERNAL_AUTH_EPOCH_HEADER) shouldBe null
        session.attemptedRejections shouldBe emptyList()
    }

    @Test
    fun `explicit Jellyfin request never forwards credentials to a foreign origin`() {
        val session = FakeAuthTransportSession(current = snapshot(epoch = 2))
        val original = request("https://media.example.test.attacker.invalid/steal")
            .header("Authorization", AUTHORIZATION)
            .header("X-Emby-Authorization", AUTHORIZATION)
            .header("X-MediaBrowser-Authorization", AUTHORIZATION)
            .header("X-Emby-Token", "secret")
            .header("X-MediaBrowser-Token", "secret")
            .authTransportRealm(AuthTransportRealm.JELLYFIN)
            .authTransportEpoch(2)
            .build()
        val exchange = exchange(original, status = 401)

        AuthenticatedTransportInterceptor(session).intercept(exchange.chain)

        exchange.proceededRequest?.header("Authorization") shouldBe null
        exchange.proceededRequest?.header("X-Emby-Authorization") shouldBe null
        exchange.proceededRequest?.header("X-MediaBrowser-Authorization") shouldBe null
        exchange.proceededRequest?.header("X-Emby-Token") shouldBe null
        exchange.proceededRequest?.header("X-MediaBrowser-Token") shouldBe null
        exchange.proceededRequest?.header(INTERNAL_AUTH_EPOCH_HEADER) shouldBe null
        session.attemptedRejections shouldBe emptyList()
    }

    @Test
    fun `untagged foreign request cannot forward the current Jellyfin authorization`() {
        val session = FakeAuthTransportSession(current = snapshot(epoch = 2))
        val original = request("https://media.example.test.attacker.invalid/steal")
            .header("X-Emby-Token", "secret")
            .build()
        val exchange = exchange(original, status = 200)

        AuthenticatedTransportInterceptor(session).intercept(exchange.chain)

        exchange.proceededRequest?.header("X-Emby-Token") shouldBe null
        session.attemptedRejections shouldBe emptyList()
    }

    @Test
    fun `excluded restore credential is preserved but its 401 cannot reject the active session`() {
        val session = FakeAuthTransportSession(current = snapshot(epoch = 3))
        val candidateAuthorization = "MediaBrowser Token=\"restore-candidate\""
        val original = request(SERVER_ITEM_URL)
            .header("Authorization", candidateAuthorization)
            .authTransportRealm(AuthTransportRealm.EXCLUDED)
            .build()
        val exchange = exchange(original, status = 401)

        AuthenticatedTransportInterceptor(session).intercept(exchange.chain)

        exchange.proceededRequest?.header("Authorization") shouldBe candidateAuthorization
        session.attemptedRejections shouldBe emptyList()
    }

    @Test
    fun `non-401 HTTP responses do not reject authentication`() {
        listOf(200, 400, 403, 404, 429, 500, 503).forEach { status ->
            val session = FakeAuthTransportSession(current = snapshot(epoch = status.toLong()))
            val original = request(SERVER_ITEM_URL).authTransportEpoch(status.toLong()).build()

            AuthenticatedTransportInterceptor(session).intercept(exchange(original, status).chain)

            session.attemptedRejections shouldBe emptyList()
        }
    }

    @Test
    fun `foreign redirect destination 401 cannot reject the Jellyfin session`() {
        val session = FakeAuthTransportSession(current = snapshot(epoch = 14))
        val original = request(SERVER_ITEM_URL).authTransportEpoch(14).build()
        val foreignResponseRequest = request("https://cdn.example.test/protected").build()
        val chain = mockk<Interceptor.Chain>()
        every { chain.request() } returns original
        every { chain.proceed(any()) } returns response(foreignResponseRequest, 401)

        AuthenticatedTransportInterceptor(session).intercept(chain)

        session.attemptedRejections shouldBe emptyList()
    }

    @Test
    fun `network failure and cancellation pass through without auth rejection`() {
        listOf(IOException("offline"), InterruptedIOException("Canceled")).forEach { failure ->
            val session = FakeAuthTransportSession(current = snapshot(epoch = 6))
            val original = request(SERVER_ITEM_URL).authTransportEpoch(6).build()
            val chain = mockk<Interceptor.Chain>()
            every { chain.request() } returns original
            every { chain.proceed(any()) } throws failure

            shouldThrow<IOException> {
                AuthenticatedTransportInterceptor(session).intercept(chain)
            }

            session.attemptedRejections shouldBe emptyList()
            verify(exactly = 1) { chain.proceed(any()) }
        }
    }

    @Test
    fun `origin comparison includes scheme host and effective port`() {
        val server = requireNotNull("https://Media.Example.Test:443/jellyfin".toHttpUrlWithoutAuthenticationQuery())
        val sameOrigin = requireNotNull(
            "https://media.example.test/Items/1".toHttpUrlWithoutAuthenticationQuery(),
        )
        val insecureOrigin = requireNotNull(
            "http://media.example.test/Items/1".toHttpUrlWithoutAuthenticationQuery(),
        )
        val alternatePort = requireNotNull(
            "https://media.example.test:9443/Items/1".toHttpUrlWithoutAuthenticationQuery(),
        )
        val deceptiveSubdomain = requireNotNull(
            "https://media.example.test.attacker.invalid/Items/1".toHttpUrlWithoutAuthenticationQuery(),
        )

        server.hasSameOrigin(sameOrigin).shouldBeTrue()
        server.hasSameOrigin(insecureOrigin).shouldBeFalse()
        server.hasSameOrigin(alternatePort).shouldBeFalse()
        server.hasSameOrigin(deceptiveSubdomain).shouldBeFalse()
    }

    private fun snapshot(epoch: Long): AuthTransportSnapshot = AuthTransportSnapshot(
        epoch = epoch,
        serverUrl = SERVER_URL,
        accessToken = "secret",
        authorizationHeader = AUTHORIZATION,
    )

    private fun request(url: String): Request.Builder = Request.Builder().url(url).get()

    private fun exchange(request: Request, status: Int, beforeResponse: () -> Unit = {}): FakeExchange {
        val chain = mockk<Interceptor.Chain>()
        val exchange = FakeExchange(chain)
        every { chain.request() } returns request
        every { chain.proceed(any()) } answers {
            beforeResponse()
            val proceeded = firstArg<Request>()
            exchange.proceededRequest = proceeded
            response(proceeded, status).also { exchange.response = it }
        }
        return exchange
    }

    private fun streamingExchange(request: Request, call: Call): FakeExchange {
        val chain = mockk<Interceptor.Chain>()
        val exchange = FakeExchange(chain)
        every { chain.request() } returns request
        every { chain.call() } returns call
        every { chain.proceed(any()) } answers {
            val proceeded = firstArg<Request>()
            exchange.proceededRequest = proceeded
            Response.Builder()
                .request(proceeded)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("test")
                .body("streaming-body".toResponseBody())
                .build()
                .also { exchange.response = it }
        }
        return exchange
    }

    private fun response(request: Request, status: Int): Response = Response.Builder()
        .request(request)
        .protocol(Protocol.HTTP_1_1)
        .code(status)
        .message("test")
        .build()

    private class FakeExchange(val chain: Interceptor.Chain) {
        var proceededRequest: Request? = null
        var response: Response? = null
    }

    private class FakeAuthTransportSession(
        var current: AuthTransportSnapshot?,
        var registrationAllowed: Boolean = true,
    ) : AuthTransportSession {
        val registrationAttempts = mutableListOf<AuthTransportSnapshot>()
        val attemptedRejections = mutableListOf<AuthTransportSnapshot>()
        val acceptedRejections = mutableListOf<AuthTransportSnapshot>()
        private val acceptedEpochs = mutableSetOf<Long>()

        override fun currentSnapshot(): AuthTransportSnapshot? = current

        override fun registerAuthenticatedCall(
            snapshot: AuthTransportSnapshot,
            owner: Any,
            cancel: () -> Unit,
        ): Boolean {
            registrationAttempts += snapshot
            return registrationAllowed && current === snapshot
        }

        override fun publishAuthenticationRejected(snapshot: AuthTransportSnapshot): Boolean {
            attemptedRejections += snapshot
            val accepted = current?.epoch == snapshot.epoch && acceptedEpochs.add(snapshot.epoch)
            if (accepted) acceptedRejections += snapshot
            return accepted
        }
    }

    private companion object {
        const val SERVER_URL = "https://media.example.test/jellyfin"
        const val SERVER_ITEM_URL = "https://media.example.test/Items/1"
        const val SERVER_IMAGE_URL = "https://media.example.test/Items/1/Images/Primary"
        const val AUTHORIZATION = "MediaBrowser Token=\"secret\""
    }
}
