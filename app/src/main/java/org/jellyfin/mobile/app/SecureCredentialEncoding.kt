package org.jellyfin.mobile.app

internal enum class SecureCredentialCipherVersion {
    ANDROID_KEYSTORE_AES,
    RSA_WRAPPED_AES,
}

internal data class SecureCredentialEncoding(
    val version: SecureCredentialCipherVersion,
    val ivBase64: String,
    val ciphertextBase64: String,
)

internal fun parseSecureCredentialEncoding(encoded: String): SecureCredentialEncoding? {
    val parts = encoded.split(':')
    val parsed = when {
        parts.size == MODERN_PART_COUNT -> SecureCredentialEncoding(
            version = SecureCredentialCipherVersion.ANDROID_KEYSTORE_AES,
            ivBase64 = parts[0],
            ciphertextBase64 = parts[1],
        )

        parts.size == LEGACY_PART_COUNT && parts[0] == LEGACY_PREFIX -> SecureCredentialEncoding(
            version = SecureCredentialCipherVersion.RSA_WRAPPED_AES,
            ivBase64 = parts[1],
            ciphertextBase64 = parts[2],
        )

        else -> return null
    }
    return parsed.takeIf { it.ivBase64.isNotBlank() && it.ciphertextBase64.isNotBlank() }
}

internal fun formatSecureCredentialEncoding(
    version: SecureCredentialCipherVersion,
    ivBase64: String,
    ciphertextBase64: String,
): String = when (version) {
    SecureCredentialCipherVersion.ANDROID_KEYSTORE_AES -> "$ivBase64:$ciphertextBase64"
    SecureCredentialCipherVersion.RSA_WRAPPED_AES -> "$LEGACY_PREFIX:$ivBase64:$ciphertextBase64"
}

private const val LEGACY_PREFIX = "legacy-rsa-v1"
private const val MODERN_PART_COUNT = 2
private const val LEGACY_PART_COUNT = 3
