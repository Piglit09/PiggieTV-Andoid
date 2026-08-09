@file:Suppress("DEPRECATION", "UseKtx")

package org.jellyfin.mobile.app

import android.content.Context
import android.os.Build
import android.security.KeyPairGeneratorSpec
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.annotation.RequiresApi
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.PublicKey
import java.util.Calendar
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.security.auth.x500.X500Principal

class SecureLibraryCredentials internal constructor(
    context: Context,
    private val storageConfig: SecureCredentialStorageConfig,
) {
    constructor(context: Context) : this(context, SecureCredentialStorageConfig.PRODUCTION)

    private val applicationContext = context.applicationContext
    private val sharedPreferences = applicationContext.getSharedPreferences(
        storageConfig.preferencesName,
        Context.MODE_PRIVATE,
    )

    var username: String?
        get() = getEncrypted(KEY_USERNAME)
        set(value) = setEncrypted(KEY_USERNAME, value)

    var password: String?
        get() = getEncrypted(KEY_PASSWORD)
        set(value) = setEncrypted(KEY_PASSWORD, value)

    var bearerToken: String?
        get() = getEncrypted(KEY_BEARER_TOKEN)
        set(value) = setEncrypted(KEY_BEARER_TOKEN, value)

    private fun getEncrypted(key: String): String? {
        val encoded = sharedPreferences.getString(key, null)?.takeIf(String::isNotBlank) ?: return null
        val parsed = parseSecureCredentialEncoding(encoded) ?: return null
        val decrypted = runCatching {
            val secretKey = when (parsed.version) {
                SecureCredentialCipherVersion.ANDROID_KEYSTORE_AES -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) getOrCreateModernSecretKey() else null
                }

                SecureCredentialCipherVersion.RSA_WRAPPED_AES -> getLegacySecretKey(createIfMissing = false)
            } ?: return@runCatching null

            val iv = Base64.decode(parsed.ivBase64, Base64.NO_WRAP)
            val ciphertext = Base64.decode(parsed.ciphertextBase64, Base64.NO_WRAP)
            val cipher = Cipher.getInstance(AES_TRANSFORMATION).apply {
                init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))
            }
            String(cipher.doFinal(ciphertext), Charsets.UTF_8)
        }.getOrNull()?.takeIf(String::isNotBlank)

        if (
            decrypted != null &&
            parsed.version == SecureCredentialCipherVersion.RSA_WRAPPED_AES &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
        ) {
            try {
                setEncrypted(key, decrypted)
            } catch (_: Exception) {
                // Keep the readable legacy value; migration can retry on the next access.
            }
        }
        return decrypted
    }

    private fun setEncrypted(key: String, value: String?) {
        if (value.isNullOrBlank()) {
            if (!sharedPreferences.edit().remove(key).commit()) throw secureStorageFailure()
            return
        }

        val encoded = try {
            val (secretKey, version) = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                getOrCreateModernSecretKey() to SecureCredentialCipherVersion.ANDROID_KEYSTORE_AES
            } else {
                val legacyKey = getLegacySecretKey(createIfMissing = true) ?: throw secureStorageFailure()
                legacyKey to SecureCredentialCipherVersion.RSA_WRAPPED_AES
            }
            val cipher = Cipher.getInstance(AES_TRANSFORMATION).apply {
                init(Cipher.ENCRYPT_MODE, secretKey)
            }
            val ciphertext = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
            formatSecureCredentialEncoding(
                version = version,
                ivBase64 = Base64.encodeToString(cipher.iv, Base64.NO_WRAP),
                ciphertextBase64 = Base64.encodeToString(ciphertext, Base64.NO_WRAP),
            )
        } catch (exception: Exception) {
            throw secureStorageFailure(exception)
        }

        if (!sharedPreferences.edit().putString(key, encoded).commit()) throw secureStorageFailure()
    }

    @RequiresApi(Build.VERSION_CODES.M)
    @Synchronized
    private fun getOrCreateModernSecretKey(): SecretKey {
        val keyStore = loadKeyStore()
        (
            keyStore.getEntry(
                storageConfig.modernKeyAlias,
                null,
            ) as? KeyStore.SecretKeyEntry
            )?.secretKey?.let { return it }

        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        keyGenerator.init(
            KeyGenParameterSpec.Builder(
                storageConfig.modernKeyAlias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return keyGenerator.generateKey()
    }

    @Synchronized
    private fun getLegacySecretKey(createIfMissing: Boolean): SecretKey? {
        val encodedWrappedKey = sharedPreferences.getString(KEY_LEGACY_WRAPPED_AES, null)
        if (!encodedWrappedKey.isNullOrBlank()) {
            runCatching { unwrapLegacySecretKey(encodedWrappedKey) }.getOrNull()?.let { return it }
            if (!createIfMissing) return null
            if (!sharedPreferences.edit().remove(KEY_LEGACY_WRAPPED_AES).commit()) return null
        }
        if (!createIfMissing) return null

        val wrappingKey = getOrCreateLegacyWrappingPublicKey()
        val secretKey = KeyGenerator.getInstance(AES_ALGORITHM).apply {
            init(AES_KEY_LENGTH_BITS)
        }.generateKey()
        val wrappedKey = Cipher.getInstance(RSA_TRANSFORMATION).run {
            init(Cipher.ENCRYPT_MODE, wrappingKey)
            doFinal(secretKey.encoded)
        }
        val stored = sharedPreferences.edit()
            .putString(KEY_LEGACY_WRAPPED_AES, Base64.encodeToString(wrappedKey, Base64.NO_WRAP))
            .commit()
        return secretKey.takeIf { stored }
    }

    private fun unwrapLegacySecretKey(encodedWrappedKey: String): SecretKey? {
        val privateKey = loadKeyStore().getKey(storageConfig.legacyRsaKeyAlias, null) as? PrivateKey ?: return null
        val rawKey = Cipher.getInstance(RSA_TRANSFORMATION).run {
            init(Cipher.DECRYPT_MODE, privateKey)
            doFinal(Base64.decode(encodedWrappedKey, Base64.NO_WRAP))
        }
        if (rawKey.size !in AES_KEY_LENGTH_BYTES) return null
        return SecretKeySpec(rawKey, AES_ALGORITHM)
    }

    private fun getOrCreateLegacyWrappingPublicKey(): PublicKey {
        val keyStore = loadKeyStore()
        keyStore.getCertificate(storageConfig.legacyRsaKeyAlias)?.publicKey?.let { return it }

        val start = Calendar.getInstance()
        val end = Calendar.getInstance().apply { add(Calendar.YEAR, LEGACY_CERTIFICATE_VALIDITY_YEARS) }
        val spec = KeyPairGeneratorSpec.Builder(applicationContext)
            .setAlias(storageConfig.legacyRsaKeyAlias)
            .setKeySize(LEGACY_RSA_KEY_SIZE_BITS)
            .setSubject(X500Principal(LEGACY_CERTIFICATE_SUBJECT))
            .setSerialNumber(BigInteger.ONE)
            .setStartDate(start.time)
            .setEndDate(end.time)
            .build()
        return KeyPairGenerator.getInstance(RSA_ALGORITHM, ANDROID_KEYSTORE).run {
            initialize(spec)
            generateKeyPair().public
        }
    }

    private fun loadKeyStore(): KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    private companion object {
        const val KEY_USERNAME = "library_username"
        const val KEY_PASSWORD = "library_password"
        const val KEY_BEARER_TOKEN = "library_bearer_token"
        const val KEY_LEGACY_WRAPPED_AES = "legacy_wrapped_aes_key"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val AES_ALGORITHM = "AES"
        const val RSA_ALGORITHM = "RSA"
        const val AES_TRANSFORMATION = "AES/GCM/NoPadding"
        const val RSA_TRANSFORMATION = "RSA/ECB/PKCS1Padding"
        const val AES_KEY_LENGTH_BITS = 128
        const val LEGACY_RSA_KEY_SIZE_BITS = 2_048
        const val GCM_TAG_LENGTH_BITS = 128
        const val LEGACY_CERTIFICATE_VALIDITY_YEARS = 30
        const val LEGACY_CERTIFICATE_SUBJECT = "CN=PiggieTV library credentials"
        val AES_KEY_LENGTH_BYTES = setOf(16, 24, 32)
    }

    private fun secureStorageFailure(cause: Exception? = null): IllegalStateException =
        IllegalStateException("Could not securely store library credentials.", cause)
}

internal data class SecureCredentialStorageConfig(
    val preferencesName: String,
    val modernKeyAlias: String,
    val legacyRsaKeyAlias: String,
) {
    companion object {
        val PRODUCTION = SecureCredentialStorageConfig(
            preferencesName = "library_secure_credentials",
            modernKeyAlias = "piggietv_library_credentials",
            legacyRsaKeyAlias = "piggietv_library_credentials_legacy_rsa",
        )
    }
}
