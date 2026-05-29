package org.jellyfin.mobile.app

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.content.edit
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class SecureLibraryCredentials(context: Context) {
    private val sharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

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
        return runCatching {
            val parts = encoded.split(':')
            val iv = Base64.decode(parts[0], Base64.NO_WRAP)
            val encrypted = Base64.decode(parts[1], Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.DECRYPT_MODE, getOrCreateSecretKey(), GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))
            }
            String(cipher.doFinal(encrypted), Charsets.UTF_8)
        }.getOrNull()?.takeIf(String::isNotBlank)
    }

    private fun setEncrypted(key: String, value: String?) {
        if (value.isNullOrBlank()) {
            sharedPreferences.edit { remove(key) }
            return
        }

        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, getOrCreateSecretKey())
        }
        val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        val iv = cipher.iv
        val encoded = "${Base64.encodeToString(iv, Base64.NO_WRAP)}:${Base64.encodeToString(encrypted, Base64.NO_WRAP)}"

        sharedPreferences.edit { putString(key, encoded) }
    }

    private fun getOrCreateSecretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey?.let { return it }

        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        keyGenerator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return keyGenerator.generateKey()
    }

    private companion object {
        const val PREFS_NAME = "library_secure_credentials"
        const val KEY_ALIAS = "piggietv_library_credentials"
        const val KEY_USERNAME = "library_username"
        const val KEY_PASSWORD = "library_password"
        const val KEY_BEARER_TOKEN = "library_bearer_token"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_TAG_LENGTH_BITS = 128
    }
}
