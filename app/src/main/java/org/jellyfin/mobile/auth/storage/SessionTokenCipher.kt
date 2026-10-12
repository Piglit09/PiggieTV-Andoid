@file:Suppress("DEPRECATION")

package org.jellyfin.mobile.auth.storage

import android.content.Context
import android.os.Build
import android.security.KeyPairGeneratorSpec
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.annotation.RequiresApi
import java.math.BigInteger
import java.security.GeneralSecurityException
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.PublicKey
import java.util.Calendar
import javax.crypto.AEADBadTagException
import javax.crypto.BadPaddingException
import javax.crypto.Cipher
import javax.crypto.IllegalBlockSizeException
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.security.auth.x500.X500Principal

internal enum class SessionTokenCipherVersion(val wireName: String) {
    ANDROID_KEYSTORE_AES_GCM("keystore-aes-gcm-v1"),
    RSA_WRAPPED_AES_GCM("rsa-wrapped-aes-gcm-v1"),
    ;

    companion object {
        fun fromWireName(value: String): SessionTokenCipherVersion? = entries.firstOrNull { it.wireName == value }
    }
}

internal data class EncryptedSessionToken(
    val version: SessionTokenCipherVersion,
    val ciphertext: ByteArray,
    val initializationVector: ByteArray,
)

internal enum class SecureClearGuardStatus { ARMED, DISARMED, UNAVAILABLE }

internal interface SessionTokenCipher {
    fun encrypt(token: ByteArray, additionalAuthenticatedData: ByteArray): EncryptedSessionToken

    fun decrypt(encryptedToken: EncryptedSessionToken, additionalAuthenticatedData: ByteArray): ByteArray

    /** Removes token keys and legacy wrapped-key material, but leaves the independent clear guard intact. */
    fun destroy(): Boolean

    fun armClearGuard(): Boolean

    fun clearGuardStatus(): SecureClearGuardStatus

    fun disarmClearGuard(): Boolean
}

internal class SessionTokenCorruptException(cause: Throwable? = null) : Exception(cause)

internal class SessionTokenStorageException(cause: Throwable? = null) : Exception(cause)

/**
 * Android Keystore-backed AES-GCM token protection.
 *
 * Android 6.0+ uses a non-exportable AES key directly. Android 5.x uses a random AES key wrapped
 * by a non-exportable Android Keystore RSA key, matching the repository's existing compatibility
 * mechanism for Reading credentials. Ciphertext is versioned so a device upgraded from Android
 * 5.x can still read its legacy-wrapped record before the next secure activation rotates it.
 */
internal class AndroidKeystoreSessionTokenCipher(
    context: Context,
    private val keyMaterialStore: SessionKeyValueStore,
    private val modernKeyAlias: String,
    private val legacyRsaKeyAlias: String,
    private val clearGuardKeyAlias: String,
) : SessionTokenCipher {
    private val applicationContext = context.applicationContext

    override fun encrypt(token: ByteArray, additionalAuthenticatedData: ByteArray): EncryptedSessionToken = try {
        val (secretKey, version) = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            getOrCreateModernSecretKey(modernKeyAlias) to SessionTokenCipherVersion.ANDROID_KEYSTORE_AES_GCM
        } else {
            getOrCreateLegacySecretKey() to SessionTokenCipherVersion.RSA_WRAPPED_AES_GCM
        }
        val cipher = Cipher.getInstance(AES_TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, secretKey)
            updateAAD(additionalAuthenticatedData)
        }
        EncryptedSessionToken(
            version = version,
            ciphertext = cipher.doFinal(token),
            initializationVector = cipher.iv,
        )
    } catch (error: SessionTokenCorruptException) {
        throw error
    } catch (error: GeneralSecurityException) {
        throw SessionTokenStorageException(error)
    } catch (error: java.io.IOException) {
        throw SessionTokenStorageException(error)
    }

    override fun decrypt(encryptedToken: EncryptedSessionToken, additionalAuthenticatedData: ByteArray): ByteArray =
        try {
            val secretKey = when (encryptedToken.version) {
                SessionTokenCipherVersion.ANDROID_KEYSTORE_AES_GCM -> existingModernSecretKey(modernKeyAlias)
                SessionTokenCipherVersion.RSA_WRAPPED_AES_GCM -> existingLegacySecretKey()
            } ?: throw SessionTokenCorruptException()
            try {
                Cipher.getInstance(AES_TRANSFORMATION).run {
                    init(
                        Cipher.DECRYPT_MODE,
                        secretKey,
                        GCMParameterSpec(GCM_TAG_LENGTH_BITS, encryptedToken.initializationVector),
                    )
                    updateAAD(additionalAuthenticatedData)
                    doFinal(encryptedToken.ciphertext)
                }
            } catch (error: AEADBadTagException) {
                throw SessionTokenCorruptException(error)
            } catch (error: GeneralSecurityException) {
                if (error.javaClass.name == PERMANENTLY_INVALIDATED_KEY_EXCEPTION) {
                    throw SessionTokenCorruptException(error)
                }
                throw error
            }
        } catch (error: SessionTokenCorruptException) {
            throw error
        } catch (error: GeneralSecurityException) {
            throw SessionTokenStorageException(error)
        } catch (error: java.io.IOException) {
            throw SessionTokenStorageException(error)
        }

    override fun destroy(): Boolean {
        val aliasesRemoved = runCatching {
            val keyStore = loadKeyStore()
            listOf(modernKeyAlias, legacyRsaKeyAlias).forEach { alias ->
                if (keyStore.containsAlias(alias)) keyStore.deleteEntry(alias)
            }
            listOf(modernKeyAlias, legacyRsaKeyAlias).none(keyStore::containsAlias)
        }.getOrDefault(false)
        val wrappedKeyRemoved = runCatching {
            keyMaterialStore.commit(removals = setOf(KEY_LEGACY_WRAPPED_AES)) &&
                keyMaterialStore.getString(KEY_LEGACY_WRAPPED_AES) == null
        }.getOrDefault(false)
        return aliasesRemoved && wrappedKeyRemoved
    }

    override fun armClearGuard(): Boolean = runCatching {
        val keyStore = loadKeyStore()
        if (!keyStore.containsAlias(clearGuardKeyAlias)) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                getOrCreateModernSecretKey(clearGuardKeyAlias)
            } else {
                getOrCreateLegacyRsaPublicKey(clearGuardKeyAlias)
            }
        }
        clearGuardStatus() == SecureClearGuardStatus.ARMED
    }.getOrDefault(false)

    override fun clearGuardStatus(): SecureClearGuardStatus = try {
        if (loadKeyStore().containsAlias(clearGuardKeyAlias)) {
            SecureClearGuardStatus.ARMED
        } else {
            SecureClearGuardStatus.DISARMED
        }
    } catch (_: GeneralSecurityException) {
        SecureClearGuardStatus.UNAVAILABLE
    } catch (_: java.io.IOException) {
        SecureClearGuardStatus.UNAVAILABLE
    }

    override fun disarmClearGuard(): Boolean = runCatching {
        val keyStore = loadKeyStore()
        if (keyStore.containsAlias(clearGuardKeyAlias)) keyStore.deleteEntry(clearGuardKeyAlias)
        !keyStore.containsAlias(clearGuardKeyAlias)
    }.getOrDefault(false)

    @RequiresApi(Build.VERSION_CODES.M)
    private fun getOrCreateModernSecretKey(alias: String): SecretKey {
        existingModernSecretKey(alias)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
            .apply {
                init(
                    KeyGenParameterSpec.Builder(
                        alias,
                        KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                    )
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setKeySize(AES_KEY_LENGTH_BITS)
                        .setRandomizedEncryptionRequired(true)
                        .build(),
                )
            }
            .generateKey()
    }

    private fun existingModernSecretKey(alias: String): SecretKey? {
        val keyStore = loadKeyStore()
        if (!keyStore.containsAlias(alias)) return null
        return (keyStore.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.secretKey
            ?: throw SessionTokenCorruptException()
    }

    @Synchronized
    private fun getOrCreateLegacySecretKey(): SecretKey {
        val encodedWrappedKey = keyMaterialStore.getString(KEY_LEGACY_WRAPPED_AES)
        if (!encodedWrappedKey.isNullOrBlank()) return unwrapLegacySecretKey(encodedWrappedKey)

        val wrappingKey = getOrCreateLegacyRsaPublicKey(legacyRsaKeyAlias)
        val secretKey = KeyGenerator.getInstance(AES_ALGORITHM).apply {
            // Android 5.x compatibility follows the repository's proven OPDS credential path.
            init(LEGACY_AES_KEY_LENGTH_BITS)
        }.generateKey()
        val wrappedKey = Cipher.getInstance(RSA_TRANSFORMATION).run {
            init(Cipher.ENCRYPT_MODE, wrappingKey)
            doFinal(secretKey.encoded)
        }
        val encoded = Base64.encodeToString(wrappedKey, Base64.NO_WRAP)
        if (
            !keyMaterialStore.commit(puts = mapOf(KEY_LEGACY_WRAPPED_AES to encoded)) ||
            keyMaterialStore.getString(KEY_LEGACY_WRAPPED_AES) != encoded
        ) {
            throw SessionTokenStorageException()
        }
        return secretKey
    }

    private fun existingLegacySecretKey(): SecretKey? {
        val encodedWrappedKey = keyMaterialStore.getString(KEY_LEGACY_WRAPPED_AES) ?: return null
        return unwrapLegacySecretKey(encodedWrappedKey)
    }

    private fun unwrapLegacySecretKey(encodedWrappedKey: String): SecretKey {
        val privateKey = loadKeyStore().getKey(legacyRsaKeyAlias, null) as? PrivateKey
            ?: corruptToken()
        val rawKey = try {
            Cipher.getInstance(RSA_TRANSFORMATION).run {
                init(Cipher.DECRYPT_MODE, privateKey)
                doFinal(Base64.decode(encodedWrappedKey, Base64.NO_WRAP))
            }
        } catch (error: IllegalArgumentException) {
            corruptToken(error)
        } catch (error: BadPaddingException) {
            corruptToken(error)
        } catch (error: IllegalBlockSizeException) {
            corruptToken(error)
        }
        if (rawKey.size !in AES_KEY_LENGTH_BYTES) corruptToken()
        return SecretKeySpec(rawKey, AES_ALGORITHM)
    }

    private fun corruptToken(cause: Throwable? = null): Nothing = throw SessionTokenCorruptException(cause)

    private fun getOrCreateLegacyRsaPublicKey(alias: String): PublicKey {
        val keyStore = loadKeyStore()
        keyStore.getCertificate(alias)?.publicKey?.let { return it }

        val start = Calendar.getInstance()
        val end = Calendar.getInstance().apply { add(Calendar.YEAR, LEGACY_CERTIFICATE_VALIDITY_YEARS) }
        val spec = KeyPairGeneratorSpec.Builder(applicationContext)
            .setAlias(alias)
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
        const val PERMANENTLY_INVALIDATED_KEY_EXCEPTION =
            "android.security.keystore.KeyPermanentlyInvalidatedException"
        const val KEY_LEGACY_WRAPPED_AES = "wrapped_session_aes_key"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val AES_ALGORITHM = "AES"
        const val RSA_ALGORITHM = "RSA"
        const val AES_TRANSFORMATION = "AES/GCM/NoPadding"
        const val RSA_TRANSFORMATION = "RSA/ECB/PKCS1Padding"
        const val AES_KEY_LENGTH_BITS = 256
        const val LEGACY_AES_KEY_LENGTH_BITS = 128
        const val LEGACY_RSA_KEY_SIZE_BITS = 2_048
        const val GCM_TAG_LENGTH_BITS = 128
        const val LEGACY_CERTIFICATE_VALIDITY_YEARS = 30
        const val LEGACY_CERTIFICATE_SUBJECT = "CN=PiggieTV mobile session credentials"
        val AES_KEY_LENGTH_BYTES = setOf(16, 24, 32)
    }
}
