package org.jellyfin.mobile.auth.transport

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class AuthenticationUrlSanitizerTest {
    @Test
    fun `authentication query parameters are stripped case-insensitively`() {
        val sanitized = requireNotNull(
            "https://media.example.test/Items/1?ApiKey=one&api_key=two&ACCESS_TOKEN=three&keep=yes&keep=two#art"
                .toHttpUrlWithoutAuthenticationQuery(),
        )

        sanitized.queryParameterNames shouldBe setOf("keep")
        sanitized.queryParameterValues("keep") shouldContainExactly listOf("yes", "two")
        sanitized.fragment shouldBe "art"
    }

    @Test
    fun `percent-encoded auth key spelling cannot bypass stripping`() {
        val sanitized = requireNotNull(
            "https://media.example.test/image?api%5Fkey=secret&x%2Demby%2Dtoken=other&safe=value"
                .toHttpUrlWithoutAuthenticationQuery(),
        )

        sanitized.queryParameterNames shouldBe setOf("safe")
        sanitized.queryParameter("safe") shouldBe "value"
    }

    @Test
    fun `non-auth query values and path are preserved`() {
        val original = requireNotNull(
            "https://media.example.test/jellyfin/Items/1?tag=a%20b&width=320"
                .toHttpUrlWithoutAuthenticationQuery(),
        )

        original.encodedPath shouldBe "/jellyfin/Items/1"
        original.queryParameter("tag") shouldBe "a b"
        original.queryParameter("width") shouldBe "320"
    }

    @Test
    fun `unparseable input is never returned as a supposedly safe URL`() {
        "not a URL?ApiKey=secret".toHttpUrlWithoutAuthenticationQuery() shouldBe null
    }
}
