package org.jellyfin.mobile.app

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class SecureCredentialEncodingTest {
    @Test
    fun `modern encoding preserves the existing two part format`() {
        val encoded = formatSecureCredentialEncoding(
            version = SecureCredentialCipherVersion.ANDROID_KEYSTORE_AES,
            ivBase64 = "modern-iv",
            ciphertextBase64 = "modern-ciphertext",
        )

        encoded shouldBe "modern-iv:modern-ciphertext"
        parseSecureCredentialEncoding(encoded) shouldBe SecureCredentialEncoding(
            version = SecureCredentialCipherVersion.ANDROID_KEYSTORE_AES,
            ivBase64 = "modern-iv",
            ciphertextBase64 = "modern-ciphertext",
        )
    }

    @Test
    fun `legacy encoding is versioned and round trips`() {
        val encoded = formatSecureCredentialEncoding(
            version = SecureCredentialCipherVersion.RSA_WRAPPED_AES,
            ivBase64 = "legacy-iv",
            ciphertextBase64 = "legacy-ciphertext",
        )

        encoded shouldBe "legacy-rsa-v1:legacy-iv:legacy-ciphertext"
        parseSecureCredentialEncoding(encoded) shouldBe SecureCredentialEncoding(
            version = SecureCredentialCipherVersion.RSA_WRAPPED_AES,
            ivBase64 = "legacy-iv",
            ciphertextBase64 = "legacy-ciphertext",
        )
    }

    @Test
    fun `malformed or unknown encodings are rejected`() {
        listOf(
            "",
            ":ciphertext",
            "iv:",
            "iv:ciphertext:extra",
            "unknown:iv:ciphertext",
            "legacy-rsa-v1::ciphertext",
            "legacy-rsa-v1:iv:",
        ).forEach { encoded ->
            parseSecureCredentialEncoding(encoded).shouldBeNull()
        }
    }
}
