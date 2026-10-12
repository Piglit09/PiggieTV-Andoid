package org.jellyfin.mobile.feature.library

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.io.IOException

class OpdsAuthenticatedOriginTest {
    @Test
    fun `relative and exact-origin feed URLs remain authenticated`() {
        resolveAuthenticatedOpdsUrl("https://trusted.example/opds", "books/1") shouldBe
            "https://trusted.example/opds/books/1"
        resolveAuthenticatedOpdsUrl("https://trusted.example/opds", "https://trusted.example/details/1") shouldBe
            "https://trusted.example/details/1"
    }

    @Test
    fun `cross-origin and lookalike feed URLs are rejected before credentials are attached`() {
        shouldThrow<IOException> {
            resolveAuthenticatedOpdsUrl("https://trusted.example/opds", "https://attacker.example/book")
        }
        shouldThrow<IOException> {
            resolveAuthenticatedOpdsUrl("https://trusted.example/opds", "https://trusted.example.evil/book")
        }
        shouldThrow<IOException> {
            resolveAuthenticatedOpdsUrl("https://trusted.example/opds", "http://trusted.example/book")
        }
        shouldThrow<IOException> {
            resolveAuthenticatedOpdsUrl("https://trusted.example/opds", "https://trusted.example:444/book")
        }
    }

    @Test
    fun `OPDS links cannot retain URL-carried credentials in models or reader state`() {
        sanitizeOpdsLinkUrl(
            "https://reader:password@trusted.example/book.epub?token=secret&format=epub#credential",
        ) shouldBe "https://trusted.example/book.epub?format=epub"
    }

    @Test
    fun `signed acquisition query remains available only for the live request`() {
        resolveOpdsLinkUrl(
            "https://trusted.example/opds",
            "book.epub?X-Amz-Credential=credential&X-Amz-Signature=secret-signature",
        ) shouldBe "https://trusted.example/opds/book.epub?X-Amz-Credential=credential&X-Amz-Signature=secret-signature"
    }

    @Test
    fun `malformed OPDS link is dropped without propagating its raw credential material`() {
        resolveOpdsLinkUrl(
            "https://trusted.example/opds",
            "https://[broken.invalid/book.epub?X-Amz-Signature=secret-signature",
        ).shouldBeNull()
    }
}
