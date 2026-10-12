package org.jellyfin.mobile.auth.storage

import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.UUID

class SecureSessionTokenStoreTest {
    @Test
    fun `activation verifies secure state before callback and stores no plaintext`() {
        val fixture = fixture(FIRST_RECORD_ID)
        var callbackObservedActiveRecord = false
        var callbackObservedPendingActivation = false

        fixture.store.activate(CURRENT_CREDENTIAL) {
            callbackObservedActiveRecord = fixture.metadata.getString(ACTIVE_RECORD_KEY) == FIRST_RECORD_ID
            callbackObservedPendingActivation =
                fixture.metadata.getString(PENDING_ACTIVATION_RECORD_KEY) == FIRST_RECORD_ID
            true
        } shouldBe SecureSessionCommitResult.ACTIVATED

        callbackObservedActiveRecord.shouldBeTrue()
        callbackObservedPendingActivation.shouldBeTrue()
        fixture.metadata.keys().contains(PENDING_ACTIVATION_RECORD_KEY).shouldBeFalse()
        fixture.store.readResult().assertAvailable(CURRENT_CREDENTIAL)
        (fixture.metadata.values() + fixture.credentials.values()).forEach { value ->
            value.contains(CURRENT_TOKEN).shouldBeFalse()
        }
        CURRENT_CREDENTIAL.toString().contains(CURRENT_TOKEN).shouldBeFalse()
        CURRENT_CREDENTIAL.toString().contains(CURRENT_USER_ID.toString()).shouldBeFalse()
        CURRENT_CREDENTIAL.toString().contains(CURRENT_CREDENTIAL_INSTANCE_ID.toString()).shouldBeFalse()
    }

    @Test
    fun `restart accepts verified active pending activation and clears journal`() {
        val fixture = fixture(FIRST_RECORD_ID, SECOND_RECORD_ID)
        fixture.store.activate(CURRENT_CREDENTIAL) shouldBe SecureSessionCommitResult.ACTIVATED
        fixture.metadata.commit(
            puts = mapOf(PENDING_ACTIVATION_RECORD_KEY to FIRST_RECORD_ID),
        ).shouldBeTrue()

        fixture.restartedStore().readResult().assertAvailable(CURRENT_CREDENTIAL)

        fixture.metadata.keys().contains(PENDING_ACTIVATION_RECORD_KEY).shouldBeFalse()
    }

    @Test
    fun `restart discards pending candidate that never became active`() {
        val fixture = fixture(FIRST_RECORD_ID, SECOND_RECORD_ID)
        val stagedFixture = fixture(SECOND_RECORD_ID)
        fixture.store.activate(CURRENT_CREDENTIAL) shouldBe SecureSessionCommitResult.ACTIVATED
        stagedFixture.store.activate(REPLACEMENT_CREDENTIAL) shouldBe SecureSessionCommitResult.ACTIVATED
        fixture.metadata.commit(
            puts = stagedFixture.metadata.entries().filterKeys { key -> key.startsWith("record.") } +
                (PENDING_ACTIVATION_RECORD_KEY to SECOND_RECORD_ID),
        ).shouldBeTrue()
        fixture.credentials.commit(
            puts = stagedFixture.credentials.entries().filterKeys { key -> key.startsWith("record.") },
        ).shouldBeTrue()

        fixture.restartedStore().readResult().assertAvailable(CURRENT_CREDENTIAL)

        (fixture.metadata.keys() + fixture.credentials.keys()).none { key -> SECOND_RECORD_ID in key }.shouldBeTrue()
        fixture.metadata.keys().contains(PENDING_ACTIVATION_RECORD_KEY).shouldBeFalse()
    }

    @Test
    fun `failed legacy cleanup restores previous active session and removes candidate`() {
        val fixture = fixture(FIRST_RECORD_ID, SECOND_RECORD_ID)
        fixture.store.activate(CURRENT_CREDENTIAL) shouldBe SecureSessionCommitResult.ACTIVATED

        fixture.store.activate(REPLACEMENT_CREDENTIAL) { false } shouldBe
            SecureSessionCommitResult.POST_ACTIVATION_CALLBACK_FAILED_ROLLED_BACK

        fixture.store.readResult().assertAvailable(CURRENT_CREDENTIAL)
        fixture.metadata.getString(ACTIVE_RECORD_KEY) shouldBe FIRST_RECORD_ID
        (fixture.metadata.keys() + fixture.credentials.keys()).none { SECOND_RECORD_ID in it }.shouldBeTrue()
    }

    @Test
    fun `throwing legacy cleanup with no prior session rolls back to missing`() {
        val fixture = fixture(FIRST_RECORD_ID)

        fixture.store.activate(CURRENT_CREDENTIAL) { error("Room transaction rolled back") } shouldBe
            SecureSessionCommitResult.POST_ACTIVATION_CALLBACK_FAILED_ROLLED_BACK

        fixture.store.readResult() shouldBe SecureSessionReadResult.Missing
        fixture.metadata.keys().isEmpty().shouldBeTrue()
        fixture.credentials.keys().isEmpty().shouldBeTrue()
    }

    @Test
    fun `failed secure write leaves previous active session untouched`() {
        val fixture = fixture(FIRST_RECORD_ID, SECOND_RECORD_ID)
        fixture.store.activate(CURRENT_CREDENTIAL) shouldBe SecureSessionCommitResult.ACTIVATED
        fixture.credentials.failNextCommit = true

        fixture.store.activate(REPLACEMENT_CREDENTIAL) shouldBe SecureSessionCommitResult.STORAGE_FAILURE

        fixture.store.readResult().assertAvailable(CURRENT_CREDENTIAL)
        fixture.metadata.getString(ACTIVE_RECORD_KEY) shouldBe FIRST_RECORD_ID
    }

    @Test
    fun `failed initial secure write never authorizes plaintext deletion`() {
        val fixture = fixture(FIRST_RECORD_ID)
        fixture.credentials.failNextCommit = true
        var plaintextDeletionAuthorized = false

        fixture.store.activate(CURRENT_CREDENTIAL) {
            plaintextDeletionAuthorized = true
            true
        } shouldBe SecureSessionCommitResult.STORAGE_FAILURE

        plaintextDeletionAuthorized.shouldBeFalse()
        fixture.store.readResult() shouldBe SecureSessionReadResult.Missing
    }

    @Test
    fun `AAD metadata substitution clears tampered state`() {
        val fixture = fixture(FIRST_RECORD_ID)
        fixture.store.activate(CURRENT_CREDENTIAL) shouldBe SecureSessionCommitResult.ACTIVATED
        fixture.metadata.commit(puts = mapOf("record.$FIRST_RECORD_ID.server_id" to "90210"))

        fixture.store.readResult() shouldBe SecureSessionReadResult.ClearPending
        fixture.store.clear { true }.shouldBeTrue()
        fixture.store.readResult() shouldBe SecureSessionReadResult.Missing
    }

    @Test
    fun `credential instance substitution is authenticated and clears tampered state`() {
        val fixture = fixture(FIRST_RECORD_ID)
        fixture.store.activate(CURRENT_CREDENTIAL) shouldBe SecureSessionCommitResult.ACTIVATED
        fixture.metadata.commit(
            puts = mapOf(
                "record.$FIRST_RECORD_ID.credential_instance_id" to REPLACEMENT_CREDENTIAL_INSTANCE_ID.toString(),
            ),
        )

        fixture.store.readResult() shouldBe SecureSessionReadResult.ClearPending
        fixture.store.clear { true }.shouldBeTrue()
        fixture.store.readResult() shouldBe SecureSessionReadResult.Missing
    }

    @Test
    fun `device-bound key loss clears ciphertext and does not loop`() {
        val fixture = fixture(FIRST_RECORD_ID)
        fixture.store.activate(CURRENT_CREDENTIAL) shouldBe SecureSessionCommitResult.ACTIVATED
        fixture.cipher.keyAvailable = false

        fixture.store.readResult() shouldBe SecureSessionReadResult.ClearPending
        fixture.store.clear { true }.shouldBeTrue()
        fixture.store.readResult() shouldBe SecureSessionReadResult.Missing
        fixture.metadata.keys().isEmpty().shouldBeTrue()
        fixture.credentials.keys().isEmpty().shouldBeTrue()
    }

    @Test
    fun `transient cipher failure preserves recoverable secure state`() {
        val fixture = fixture(FIRST_RECORD_ID)
        fixture.store.activate(CURRENT_CREDENTIAL) shouldBe SecureSessionCommitResult.ACTIVATED
        fixture.cipher.failDecryptAsStorageError = true

        fixture.store.readResult() shouldBe SecureSessionReadResult.StorageFailure
        fixture.metadata.keys().isNotEmpty().shouldBeTrue()
        fixture.credentials.keys().isNotEmpty().shouldBeTrue()
    }

    @Test
    fun `interrupted clear is resumed from durable guard on next read`() {
        val fixture = fixture(FIRST_RECORD_ID)
        fixture.store.activate(CURRENT_CREDENTIAL) shouldBe SecureSessionCommitResult.ACTIVATED
        fixture.credentials.failClear = true

        fixture.store.clear { true }.shouldBeFalse()
        fixture.guard.keys().isNotEmpty().shouldBeTrue()

        fixture.credentials.failClear = false
        fixture.store.readResult() shouldBe SecureSessionReadResult.ClearPending
        fixture.store.clear { true }.shouldBeTrue()
        fixture.store.readResult() shouldBe SecureSessionReadResult.Missing
        fixture.guard.keys().isEmpty().shouldBeTrue()
        fixture.cipher.guardArmed.shouldBeFalse()
    }

    @Test
    fun `external clear transaction runs after key deletion and failure keeps guard armed`() {
        val fixture = fixture(FIRST_RECORD_ID)
        fixture.store.activate(CURRENT_CREDENTIAL) shouldBe SecureSessionCommitResult.ACTIVATED
        var callbackObservedSecureDeletion = false

        fixture.store.clear {
            callbackObservedSecureDeletion =
                fixture.metadata.keys().isEmpty() &&
                fixture.credentials.keys().isEmpty() &&
                !fixture.cipher.keyAvailable &&
                fixture.cipher.guardArmed
            false
        }.shouldBeFalse()

        callbackObservedSecureDeletion.shouldBeTrue()
        fixture.store.readResult() shouldBe SecureSessionReadResult.ClearPending
        fixture.store.activate(REPLACEMENT_CREDENTIAL) shouldBe SecureSessionCommitResult.STORAGE_FAILURE
        fixture.store.clear { true }.shouldBeTrue()
        fixture.store.readResult() shouldBe SecureSessionReadResult.Missing
    }

    @Test
    fun `logout preparation arms durable guard before reading retiring credential`() {
        val fixture = fixture(FIRST_RECORD_ID, SECOND_RECORD_ID)
        fixture.store.activate(CURRENT_CREDENTIAL) shouldBe SecureSessionCommitResult.ACTIVATED
        fixture.cipher.guardObservedDuringDecrypt = null

        val preparation = fixture.store.prepareFailClosedLogout()

        val prepared = preparation as SecureSessionLogoutPreparationResult.Prepared
        prepared.credential.assertCredential(CURRENT_CREDENTIAL)
        fixture.cipher.guardObservedDuringDecrypt shouldBe true
        fixture.guard.keys().isNotEmpty().shouldBeTrue()
        fixture.cipher.guardArmed.shouldBeTrue()
        fixture.restartedStore().readResult() shouldBe SecureSessionReadResult.ClearPending
    }

    @Test
    fun `new login atomically replaces a runtime-rejected credential behind the clear guard`() {
        val fixture = fixture(FIRST_RECORD_ID, SECOND_RECORD_ID)
        fixture.store.activate(CURRENT_CREDENTIAL) shouldBe SecureSessionCommitResult.ACTIVATED
        fixture.store.prepareFailClosedClear().shouldBeTrue()

        fixture.store.activateAfterPreparedClear(REPLACEMENT_CREDENTIAL) shouldBe
            SecureSessionCommitResult.ACTIVATED

        fixture.store.readResult().assertAvailable(REPLACEMENT_CREDENTIAL)
        (fixture.metadata.values() + fixture.credentials.values()).forEach { value ->
            value.contains(CURRENT_TOKEN).shouldBeFalse()
        }
    }

    @Test
    fun `invalid candidate never executes plaintext cleanup callback`() {
        val fixture = fixture(FIRST_RECORD_ID)
        var callbackCalled = false

        fixture.store.activate(
            SecureSessionCredential(
                serverId = 0,
                userId = CURRENT_USER_ID,
                credentialInstanceId = CURRENT_CREDENTIAL_INSTANCE_ID,
                accessToken = CURRENT_TOKEN,
            ),
        ) {
            callbackCalled = true
            true
        } shouldBe SecureSessionCommitResult.INVALID_CANDIDATE

        callbackCalled.shouldBeFalse()
        fixture.store.readResult() shouldBe SecureSessionReadResult.Missing
    }

    @Test
    fun `zero credential instance id is rejected`() {
        val fixture = fixture(FIRST_RECORD_ID)

        fixture.store.activate(
            SecureSessionCredential(
                serverId = CURRENT_CREDENTIAL.serverId,
                userId = CURRENT_USER_ID,
                credentialInstanceId = UUID(0, 0),
                accessToken = CURRENT_TOKEN,
            ),
        ) shouldBe SecureSessionCommitResult.INVALID_CANDIDATE

        fixture.store.readResult() shouldBe SecureSessionReadResult.Missing
    }

    private fun fixture(vararg recordIds: String): Fixture {
        val metadata = FakeKeyValueStore()
        val credentials = FakeKeyValueStore()
        val guard = FakeKeyValueStore()
        val cipher = FakeSessionTokenCipher()
        val ids = ArrayDeque(recordIds.toList())
        return Fixture(
            metadata = metadata,
            credentials = credentials,
            guard = guard,
            cipher = cipher,
            store = SecureSessionTokenStore(
                metadataStore = metadata,
                credentialStore = credentials,
                clearGuardStore = guard,
                tokenCipher = cipher,
                recordIdFactory = { ids.removeFirst() },
            ),
        )
    }

    private fun SecureSessionReadResult.assertAvailable(expected: SecureSessionCredential) {
        (this as SecureSessionReadResult.Available).credential.assertCredential(expected)
    }

    private fun SecureSessionCredential?.assertCredential(expected: SecureSessionCredential) {
        val actual = requireNotNull(this)
        actual.serverId shouldBe expected.serverId
        actual.userId shouldBe expected.userId
        actual.credentialInstanceId shouldBe expected.credentialInstanceId
        actual.accessToken shouldBe expected.accessToken
    }

    private fun Fixture.restartedStore(): SecureSessionTokenStore = SecureSessionTokenStore(
        metadataStore = metadata,
        credentialStore = credentials,
        clearGuardStore = guard,
        tokenCipher = cipher,
        recordIdFactory = { SECOND_RECORD_ID },
    )

    private data class Fixture(
        val metadata: FakeKeyValueStore,
        val credentials: FakeKeyValueStore,
        val guard: FakeKeyValueStore,
        val cipher: FakeSessionTokenCipher,
        val store: SecureSessionTokenStore,
    )

    private class FakeKeyValueStore : SessionKeyValueStore {
        private val values = linkedMapOf<String, String>()
        var failNextCommit = false
        var failClear = false

        override fun getString(key: String): String? = values[key]

        override fun keys(): Set<String> = values.keys.toSet()

        override fun commit(puts: Map<String, String>, removals: Set<String>, clear: Boolean): Boolean {
            if (failNextCommit) {
                failNextCommit = false
                return false
            }
            if (clear && failClear) return false
            if (clear) values.clear()
            removals.forEach(values::remove)
            values.putAll(puts)
            return true
        }

        fun entries(): Map<String, String> = values.toMap()

        fun values(): List<String> = values.values.toList()
    }

    private class FakeSessionTokenCipher : SessionTokenCipher {
        var keyAvailable = true
        var failDecryptAsStorageError = false
        var guardArmed = false
        var guardObservedDuringDecrypt: Boolean? = null

        override fun encrypt(token: ByteArray, additionalAuthenticatedData: ByteArray): EncryptedSessionToken {
            keyAvailable = true
            val aadHash = MessageDigest.getInstance("SHA-256").digest(additionalAuthenticatedData).copyOf(8)
            val obfuscatedToken = token.map { value -> (value.toInt() xor MASK).toByte() }.toByteArray()
            return EncryptedSessionToken(
                version = SessionTokenCipherVersion.ANDROID_KEYSTORE_AES_GCM,
                ciphertext = aadHash + obfuscatedToken + ByteArray(TAG_BYTES),
                initializationVector = ByteBuffer.allocate(IV_BYTES)
                    .putLong(additionalAuthenticatedData.size.toLong())
                    .array(),
            )
        }

        override fun decrypt(
            encryptedToken: EncryptedSessionToken,
            additionalAuthenticatedData: ByteArray,
        ): ByteArray {
            guardObservedDuringDecrypt = guardArmed
            if (failDecryptAsStorageError) failStorage()
            if (!keyAvailable) failCorrupt()
            val expectedHash = MessageDigest.getInstance("SHA-256").digest(additionalAuthenticatedData).copyOf(8)
            if (!encryptedToken.ciphertext.copyOfRange(0, 8).contentEquals(expectedHash)) {
                failCorrupt()
            }
            return encryptedToken.ciphertext
                .copyOfRange(8, encryptedToken.ciphertext.size - TAG_BYTES)
                .map { value -> (value.toInt() xor MASK).toByte() }
                .toByteArray()
        }

        private fun failStorage(): Nothing = throw SessionTokenStorageException()

        private fun failCorrupt(): Nothing = throw SessionTokenCorruptException()

        override fun destroy(): Boolean {
            keyAvailable = false
            return true
        }

        override fun armClearGuard(): Boolean {
            guardArmed = true
            return true
        }

        override fun clearGuardStatus(): SecureClearGuardStatus =
            if (guardArmed) SecureClearGuardStatus.ARMED else SecureClearGuardStatus.DISARMED

        override fun disarmClearGuard(): Boolean {
            guardArmed = false
            return true
        }

        private companion object {
            const val MASK = 0x5A
            const val TAG_BYTES = 16
            const val IV_BYTES = 12
        }
    }

    private companion object {
        const val ACTIVE_RECORD_KEY = "active_record_id"
        const val PENDING_ACTIVATION_RECORD_KEY = "pending_activation_record_id"
        const val FIRST_RECORD_ID = "00000000-0000-0000-0000-000000000101"
        const val SECOND_RECORD_ID = "00000000-0000-0000-0000-000000000202"
        const val CURRENT_TOKEN = "PTV_CURRENT_TOKEN_DO_NOT_STORE_73104"
        const val REPLACEMENT_TOKEN = "PTV_REPLACEMENT_TOKEN_DO_NOT_STORE_84215"
        val CURRENT_USER_ID: UUID = UUID.fromString("10000000-0000-0000-0000-000000000001")
        val REPLACEMENT_USER_ID: UUID = UUID.fromString("20000000-0000-0000-0000-000000000002")
        val CURRENT_CREDENTIAL_INSTANCE_ID: UUID = UUID.fromString("30000000-0000-0000-0000-000000000003")
        val REPLACEMENT_CREDENTIAL_INSTANCE_ID: UUID = UUID.fromString("40000000-0000-0000-0000-000000000004")
        val CURRENT_CREDENTIAL = SecureSessionCredential(
            serverId = 41,
            userId = CURRENT_USER_ID,
            credentialInstanceId = CURRENT_CREDENTIAL_INSTANCE_ID,
            accessToken = CURRENT_TOKEN,
        )
        val REPLACEMENT_CREDENTIAL = SecureSessionCredential(
            serverId = 42,
            userId = REPLACEMENT_USER_ID,
            credentialInstanceId = REPLACEMENT_CREDENTIAL_INSTANCE_ID,
            accessToken = REPLACEMENT_TOKEN,
        )
    }
}
