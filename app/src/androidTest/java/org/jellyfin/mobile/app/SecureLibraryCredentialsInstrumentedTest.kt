package org.jellyfin.mobile.app

import android.content.Context
import android.os.Build
import android.util.Base64
import androidx.test.platform.app.InstrumentationRegistry
import org.jellyfin.mobile.BuildConfig
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.security.KeyStore

class SecureLibraryCredentialsInstrumentedTest {
    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val preferences
        get() = context.getSharedPreferences(TEST_PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val storageConfig = SecureCredentialStorageConfig(
        preferencesName = TEST_PREFERENCES_NAME,
        modernKeyAlias = TEST_MODERN_KEY_ALIAS,
        legacyRsaKeyAlias = TEST_LEGACY_RSA_KEY_ALIAS,
    )

    @Before
    fun setUp() {
        assumeTrue(Build.VERSION.SDK_INT in Build.VERSION_CODES.LOLLIPOP..Build.VERSION_CODES.LOLLIPOP_MR1)
        assumeTrue(
            "Legacy credential test requires explicit opt-in.",
            InstrumentationRegistry.getArguments().getString(OPT_IN_ARGUMENT) == "true",
        )
        clearTestStorage()
    }

    @After
    fun tearDown() {
        clearTestStorage()
    }

    @Test
    fun legacyCredentialsRoundTripOverwriteDeleteAndFailClosed() {
        val credentials = SecureLibraryCredentials(context, storageConfig)
        credentials.username = USERNAME
        credentials.password = OLD_PASSWORD
        credentials.bearerToken = BEARER_TOKEN

        val firstPasswordEncoding = preferences.getString(KEY_PASSWORD, null)
        assertNotNull(firstPasswordEncoding)
        assertTrue(preferences.getString(KEY_USERNAME, null).orEmpty().startsWith(LEGACY_PREFIX))
        assertTrue(firstPasswordEncoding.orEmpty().startsWith(LEGACY_PREFIX))
        assertTrue(preferences.getString(KEY_BEARER_TOKEN, null).orEmpty().startsWith(LEGACY_PREFIX))
        assertFalse(preferences.getString(KEY_WRAPPED_AES, null).isNullOrBlank())
        assertNoPlaintextInPreferences(USERNAME, OLD_PASSWORD, BEARER_TOKEN)

        SecureLibraryCredentials(context, storageConfig).run {
            assertEquals(USERNAME, username)
            assertEquals(OLD_PASSWORD, password)
            assertEquals(BEARER_TOKEN, bearerToken)
        }

        credentials.password = NEW_PASSWORD
        val secondPasswordEncoding = preferences.getString(KEY_PASSWORD, null)
        assertNotEquals(firstPasswordEncoding, secondPasswordEncoding)
        assertEquals(NEW_PASSWORD, SecureLibraryCredentials(context, storageConfig).password)
        assertNoPlaintextInPreferences(OLD_PASSWORD, NEW_PASSWORD)

        credentials.username = null
        credentials.bearerToken = " "
        assertNull(credentials.username)
        assertNull(credentials.bearerToken)
        assertFalse(preferences.contains(KEY_USERNAME))
        assertFalse(preferences.contains(KEY_BEARER_TOKEN))

        preferences.edit().putString(KEY_PASSWORD, corruptLegacyCiphertext(secondPasswordEncoding!!)).commit()
        assertNull(SecureLibraryCredentials(context, storageConfig).password)

        val wrappedKey = preferences.getString(KEY_WRAPPED_AES, null)!!
        preferences.edit().putString(KEY_WRAPPED_AES, corruptBase64(wrappedKey)).commit()
        assertNull(SecureLibraryCredentials(context, storageConfig).password)

        credentials.password = RECOVERED_PASSWORD
        assertEquals(RECOVERED_PASSWORD, SecureLibraryCredentials(context, storageConfig).password)
        assertNoPlaintextInPreferences(RECOVERED_PASSWORD)

        credentials.password = null
        assertNull(credentials.password)
        assertFalse(preferences.contains(KEY_PASSWORD))
    }

    private fun assertNoPlaintextInPreferences(vararg markers: String) {
        val storedValues = preferences.all.values.map { value -> value.toString() }
        markers.forEach { marker ->
            assertFalse("Found plaintext marker in test preferences.", storedValues.any { marker in it })
        }
    }

    private fun corruptLegacyCiphertext(encoded: String): String {
        val parts = encoded.split(':').toMutableList()
        parts[2] = corruptBase64(parts[2])
        return parts.joinToString(":")
    }

    private fun corruptBase64(encoded: String): String {
        val bytes = Base64.decode(encoded, Base64.NO_WRAP)
        bytes[bytes.lastIndex] = (bytes.last().toInt() xor 0x01).toByte()
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    private fun clearTestStorage() {
        preferences.edit().clear().commit()
        KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }.run {
            listOf(TEST_MODERN_KEY_ALIAS, TEST_LEGACY_RSA_KEY_ALIAS).forEach { alias ->
                if (containsAlias(alias)) deleteEntry(alias)
            }
        }
    }

    private companion object {
        const val OPT_IN_ARGUMENT = "ptvLegacyCredentialTest"
        const val TEST_PREFERENCES_NAME = "${BuildConfig.APPLICATION_ID}.instrumentation.secure_credentials"
        const val TEST_MODERN_KEY_ALIAS = "${BuildConfig.APPLICATION_ID}.instrumentation.secure_credentials.aes"
        const val TEST_LEGACY_RSA_KEY_ALIAS = "${BuildConfig.APPLICATION_ID}.instrumentation.secure_credentials.rsa"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val LEGACY_PREFIX = "legacy-rsa-v1:"
        const val KEY_USERNAME = "library_username"
        const val KEY_PASSWORD = "library_password"
        const val KEY_BEARER_TOKEN = "library_bearer_token"
        const val KEY_WRAPPED_AES = "legacy_wrapped_aes_key"
        const val USERNAME = "PTV_TEST_USERNAME_DO_NOT_STORE_AS_PLAINTEXT_49172"
        const val OLD_PASSWORD = "PTV_TEST_OLD_PASSWORD_DO_NOT_STORE_AS_PLAINTEXT_56308"
        const val NEW_PASSWORD = "PTV_TEST_NEW_PASSWORD_DO_NOT_STORE_AS_PLAINTEXT_70421"
        const val BEARER_TOKEN = "PTV_TEST_BEARER_TOKEN_DO_NOT_STORE_AS_PLAINTEXT_82516"
        const val RECOVERED_PASSWORD = "PTV_TEST_RECOVERED_PASSWORD_DO_NOT_STORE_AS_PLAINTEXT_93645"
    }
}
