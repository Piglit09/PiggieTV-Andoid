package org.jellyfin.mobile.auth.storage

import android.content.Context
import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import org.jellyfin.mobile.BuildConfig
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.security.KeyStore
import java.util.UUID

class SecureSessionTokenStoreInstrumentedTest {
    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val config = SecureSessionStorageConfig(
        metadataPreferencesName = "$TEST_PREFIX.metadata",
        credentialPreferencesName = "$TEST_PREFIX.credentials",
        keyMaterialPreferencesName = "$TEST_PREFIX.key_material",
        clearGuardPreferencesName = "$TEST_PREFIX.guard",
        modernKeyAlias = "$TEST_PREFIX.aes",
        legacyRsaKeyAlias = "$TEST_PREFIX.rsa",
        clearGuardKeyAlias = "$TEST_PREFIX.clear_pending",
    )

    @Before
    fun setUp() {
        clearTestStorage()
    }

    @After
    fun tearDown() {
        clearTestStorage()
    }

    @Test
    fun modernKeystoreRoundTripKeyLossAndFailClosedCleanup() {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
        val store = SecureSessionTokenStore(context, config)

        assertEquals(SecureSessionCommitResult.ACTIVATED, store.activate(CREDENTIAL))
        assertCredential(SecureSessionTokenStore(context, config).readResult(), CREDENTIAL)
        assertNoPlaintextInPreferences()

        loadKeyStore().deleteEntry(config.modernKeyAlias)
        assertEquals(SecureSessionReadResult.ClearPending, SecureSessionTokenStore(context, config).readResult())
        assertTrue(SecureSessionTokenStore(context, config).clear { true })
        assertEquals(SecureSessionReadResult.Missing, SecureSessionTokenStore(context, config).readResult())
        assertNoPlaintextInPreferences()
    }

    @Test
    fun clearKeepsGuardArmedUntilExternalCleanupCommits() {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
        val store = SecureSessionTokenStore(context, config)
        assertEquals(SecureSessionCommitResult.ACTIVATED, store.activate(CREDENTIAL))
        var callbackSawSecureDeletion = false

        assertFalse(
            store.clear {
                callbackSawSecureDeletion =
                    preferences(config.metadataPreferencesName).all.isEmpty() &&
                    preferences(config.credentialPreferencesName).all.isEmpty() &&
                    !loadKeyStore().containsAlias(config.modernKeyAlias)
                false
            },
        )

        assertTrue(callbackSawSecureDeletion)
        assertEquals(SecureSessionReadResult.ClearPending, SecureSessionTokenStore(context, config).readResult())
        assertTrue(SecureSessionTokenStore(context, config).clear { true })
        assertEquals(SecureSessionReadResult.Missing, SecureSessionTokenStore(context, config).readResult())
    }

    @Test
    fun androidFiveWrappedAesRoundTripWhenAvailable() {
        assumeTrue(Build.VERSION.SDK_INT in Build.VERSION_CODES.LOLLIPOP..Build.VERSION_CODES.LOLLIPOP_MR1)
        val store = SecureSessionTokenStore(context, config)

        assertEquals(SecureSessionCommitResult.ACTIVATED, store.activate(CREDENTIAL))
        assertCredential(store.readResult(), CREDENTIAL)
        assertTrue(
            preferences(config.keyMaterialPreferencesName).all.values.any { value ->
                value.toString().isNotBlank()
            },
        )
        assertNoPlaintextInPreferences()
        assertTrue(store.clear { true })
    }

    private fun assertCredential(result: SecureSessionReadResult, expected: SecureSessionCredential) {
        val actual = (result as SecureSessionReadResult.Available).credential
        assertEquals(expected.serverId, actual.serverId)
        assertEquals(expected.userId, actual.userId)
        assertEquals(expected.credentialInstanceId, actual.credentialInstanceId)
        assertEquals(expected.accessToken, actual.accessToken)
    }

    private fun assertNoPlaintextInPreferences() {
        listOf(
            config.metadataPreferencesName,
            config.credentialPreferencesName,
            config.keyMaterialPreferencesName,
            config.clearGuardPreferencesName,
        ).flatMap { name -> preferences(name).all.values }
            .forEach { value -> assertFalse(value.toString().contains(TOKEN)) }
    }

    private fun clearTestStorage() {
        listOf(
            config.metadataPreferencesName,
            config.credentialPreferencesName,
            config.keyMaterialPreferencesName,
            config.clearGuardPreferencesName,
        ).forEach { name -> preferences(name).edit().clear().commit() }
        loadKeyStore().run {
            listOf(config.modernKeyAlias, config.legacyRsaKeyAlias, config.clearGuardKeyAlias).forEach { alias ->
                if (containsAlias(alias)) deleteEntry(alias)
            }
        }
    }

    private fun preferences(name: String) = context.getSharedPreferences(name, Context.MODE_PRIVATE)

    private fun loadKeyStore(): KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TEST_PREFIX = "${BuildConfig.APPLICATION_ID}.instrumentation.mobile_secure_session"
        const val TOKEN = "PTV_MOBILE_KEYSTORE_TOKEN_DO_NOT_STORE_41692"
        val CREDENTIAL = SecureSessionCredential(
            serverId = 73,
            userId = UUID.fromString("73000000-0000-0000-0000-000000000073"),
            credentialInstanceId = UUID.fromString("74000000-0000-0000-0000-000000000074"),
            accessToken = TOKEN,
        )
    }
}
