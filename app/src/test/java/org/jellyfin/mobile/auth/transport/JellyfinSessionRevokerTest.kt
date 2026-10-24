package org.jellyfin.mobile.auth.transport

import io.kotest.matchers.shouldBe
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.Test

class JellyfinSessionRevokerTest {
    @Test
    fun `revocation is isolated and sends the retiring credential only to the logout route`() {
        var captured: Request? = null
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                captured = chain.request()
                response(chain, HTTP_NO_CONTENT)
            }
            .build()
        val revoker = OkHttpJellyfinSessionRevoker(client)

        revoker.revoke("https://example.test/jellyfin", AUTHORIZATION) shouldBe true

        captured?.url.toString() shouldBe "https://example.test/jellyfin/Sessions/Logout"
        captured?.header("Authorization") shouldBe AUTHORIZATION
        captured?.tag(AuthTransportRealm::class.java) shouldBe AuthTransportRealm.EXCLUDED
    }

    @Test
    fun `redirect cannot report successful revocation`() {
        val redirecting = OkHttpClient.Builder()
            .addInterceptor { chain -> response(chain, HTTP_REDIRECT) }
            .build()

        OkHttpJellyfinSessionRevoker(redirecting)
            .revoke("https://example.test", AUTHORIZATION) shouldBe false
    }

    private fun response(chain: Interceptor.Chain, status: Int) = Response.Builder()
        .request(chain.request())
        .protocol(Protocol.HTTP_1_1)
        .code(status)
        .message("test")
        .body(ByteArray(0).toResponseBody())
        .build()

    private companion object {
        const val HTTP_NO_CONTENT = 204
        const val HTTP_REDIRECT = 302
        const val AUTHORIZATION = "MediaBrowser Token=\"retiring-secret\""
    }
}
