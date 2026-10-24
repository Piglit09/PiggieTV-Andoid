package org.jellyfin.mobile.auth.storage

import android.content.Context
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.Base64
import java.util.UUID

/** A Jellyfin credential whose diagnostic representation always redacts session identity and secret material. */
class SecureSessionCredential(
    val serverId: Long,
    val userId: UUID,
    val credentialInstanceId: UUID,
    val accessToken: String,
) {
    override fun toString(): String = "SecureSessionCredential(serverId=[REDACTED], userId=[REDACTED], " +
        "credentialInstanceId=[REDACTED], accessToken=[REDACTED])"
}

sealed interface SecureSessionReadResult {
    data class Available(val credential: SecureSessionCredential) : SecureSessionReadResult

    data object Missing : SecureSessionReadResult

    /** Secure material is gone, but the caller must transactionally clear external legacy state. */
    data object ClearPending : SecureSessionReadResult

    /** Storage could not establish a fail-closed read or cleanup guarantee. */
    data object StorageFailure : SecureSessionReadResult
}

enum class SecureSessionCommitResult {
    ACTIVATED,
    INVALID_CANDIDATE,
    STORAGE_FAILURE,

    /** The caller's plaintext-cleanup transaction failed and the prior active record was restored. */
    POST_ACTIVATION_CALLBACK_FAILED_ROLLED_BACK,
}

sealed interface SecureSessionLogoutPreparationResult {
    /** The fail-closed barrier is durable; [credential] may be used only for isolated revocation. */
    data class Prepared(val credential: SecureSessionCredential?) : SecureSessionLogoutPreparationResult

    /** The barrier or active credential could not be verified. An armed barrier is never disarmed here. */
    data object StorageFailure : SecureSessionLogoutPreparationResult
}

/**
 * Stages, decrypts, verifies, and atomically activates one Jellyfin access token.
 *
 * [activate] runs [afterVerifiedActivation] only while the newly written record is active and has
 * been read/decrypted back exactly. A migration caller should clear its legacy Room token inside a
 * database transaction and return `true` only after that transaction commits. Returning `false` or
 * throwing restores the previous active secure record and removes the staged candidate.
 */
@Suppress("ReturnCount", "TooManyFunctions")
class SecureSessionTokenStore internal constructor(
    private val metadataStore: SessionKeyValueStore,
    private val credentialStore: SessionKeyValueStore,
    private val clearGuardStore: SessionKeyValueStore,
    private val tokenCipher: SessionTokenCipher,
    private val recordIdFactory: () -> String = { UUID.randomUUID().toString() },
) {
    constructor(context: Context) : this(context, SecureSessionStorageConfig.PRODUCTION)

    internal constructor(context: Context, config: SecureSessionStorageConfig) : this(
        metadataStore = context.sessionKeyValueStore(config.metadataPreferencesName),
        credentialStore = context.sessionKeyValueStore(config.credentialPreferencesName),
        clearGuardStore = context.sessionKeyValueStore(config.clearGuardPreferencesName),
        tokenCipher = AndroidKeystoreSessionTokenCipher(
            context = context,
            keyMaterialStore = context.sessionKeyValueStore(config.keyMaterialPreferencesName),
            modernKeyAlias = config.modernKeyAlias,
            legacyRsaKeyAlias = config.legacyRsaKeyAlias,
            clearGuardKeyAlias = config.clearGuardKeyAlias,
        ),
    )

    fun activate(
        candidate: SecureSessionCredential,
        afterVerifiedActivation: () -> Boolean = { true },
    ): SecureSessionCommitResult = synchronized(PROCESS_LOCK) {
        try {
            activateLocked(candidate, afterVerifiedActivation)
        } catch (_: Exception) {
            SecureSessionCommitResult.STORAGE_FAILURE
        }
    }

    /**
     * Replaces a credential after runtime rejection has armed the durable clear barrier.
     *
     * The rejected record and key are destroyed before the barrier is disarmed and the new
     * candidate is staged. Process death at any point can therefore restore neither credential
     * until the new record has been fully verified and activated.
     */
    fun activateAfterPreparedClear(
        candidate: SecureSessionCredential,
        afterVerifiedActivation: () -> Boolean = { true },
    ): SecureSessionCommitResult = synchronized(PROCESS_LOCK) {
        try {
            when (clearBarrierStatus()) {
                SecureClearGuardStatus.ARMED -> {
                    if (!finishSecureClearLocked() || !disarmClearBarrierLocked()) {
                        return@synchronized SecureSessionCommitResult.STORAGE_FAILURE
                    }
                }

                SecureClearGuardStatus.DISARMED -> Unit

                SecureClearGuardStatus.UNAVAILABLE ->
                    return@synchronized SecureSessionCommitResult.STORAGE_FAILURE
            }
            activateLocked(candidate, afterVerifiedActivation)
        } catch (_: Exception) {
            SecureSessionCommitResult.STORAGE_FAILURE
        }
    }

    fun readResult(): SecureSessionReadResult = synchronized(PROCESS_LOCK) {
        try {
            readLocked()
        } catch (_: Exception) {
            SecureSessionReadResult.StorageFailure
        }
    }

    /** Compatibility convenience; callers needing failure classification should use [readResult]. */
    fun read(): SecureSessionCredential? = (readResult() as? SecureSessionReadResult.Available)?.credential

    /**
     * Durably records fail-closed local deletion before a bounded remote logout attempt begins.
     * A process restart that observes this barrier finishes secure deletion instead of restoring
     * the retiring credential.
     */
    fun prepareFailClosedClear(): Boolean = synchronized(PROCESS_LOCK) {
        try {
            armClearBarrierLocked() && clearBarrierStatus() == SecureClearGuardStatus.ARMED
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Durably prevents future session restoration before exposing the retiring credential for a
     * bounded, isolated server-revocation attempt. The credential is read directly while holding
     * [PROCESS_LOCK] because an ordinary [readResult] must honor the newly armed clear barrier.
     */
    fun prepareFailClosedLogout(): SecureSessionLogoutPreparationResult = synchronized(PROCESS_LOCK) {
        try {
            if (!armClearBarrierLocked() || clearBarrierStatus() != SecureClearGuardStatus.ARMED) {
                return@synchronized SecureSessionLogoutPreparationResult.StorageFailure
            }

            val activeId = activeRecordId()
                ?: return@synchronized SecureSessionLogoutPreparationResult.Prepared(null)
            when (val record = readSecureRecord(activeId)) {
                is SecureRecordRead.Available -> SecureSessionLogoutPreparationResult.Prepared(record.credential)
                SecureRecordRead.Corrupt -> SecureSessionLogoutPreparationResult.Prepared(null)
                SecureRecordRead.StorageFailure -> SecureSessionLogoutPreparationResult.StorageFailure
            }
        } catch (_: Exception) {
            SecureSessionLogoutPreparationResult.StorageFailure
        }
    }

    /**
     * Arms two independent durable clear markers, clears every record and token key, verifies the
     * empty state, and only then disarms the markers. An interrupted clear resumes on the next read.
     */
    fun clear(afterSecureDeletion: () -> Boolean): Boolean = synchronized(PROCESS_LOCK) {
        try {
            clearLocked(afterSecureDeletion)
        } catch (_: Exception) {
            false
        }
    }

    @Suppress("CyclomaticComplexMethod", "LongMethod")
    private fun activateLocked(
        candidate: SecureSessionCredential,
        afterVerifiedActivation: () -> Boolean,
    ): SecureSessionCommitResult {
        if (!candidate.isStructurallyValid()) return SecureSessionCommitResult.INVALID_CANDIDATE
        if (clearBarrierStatus() != SecureClearGuardStatus.DISARMED) {
            return SecureSessionCommitResult.STORAGE_FAILURE
        }
        when (recoverPendingActivationLocked()) {
            PendingActivationRecovery.RECOVERED -> Unit

            PendingActivationRecovery.CORRUPT -> {
                clearInvalidStateLocked()
                return SecureSessionCommitResult.STORAGE_FAILURE
            }

            PendingActivationRecovery.STORAGE_FAILURE -> return SecureSessionCommitResult.STORAGE_FAILURE
        }

        val previousActiveId = activeRecordId()
        val recordId = recordIdFactory()
        if (!recordId.isValidRecordId()) return SecureSessionCommitResult.STORAGE_FAILURE
        val metadata = SessionMetadata(
            schemaVersion = SESSION_SCHEMA_VERSION,
            recordId = recordId,
            serverId = candidate.serverId,
            userId = candidate.userId,
            credentialInstanceId = candidate.credentialInstanceId,
        )

        val encryptedToken = encrypt(candidate.accessToken, metadata)
            ?: return SecureSessionCommitResult.STORAGE_FAILURE
        if (!writeCredentials(recordId, encryptedToken) || !writeMetadata(metadata)) {
            removeRecord(recordId)
            return SecureSessionCommitResult.STORAGE_FAILURE
        }
        if (!verifyRecord(recordId, candidate)) {
            removeRecord(recordId)
            return SecureSessionCommitResult.STORAGE_FAILURE
        }

        if (!setPendingActivationRecordId(recordId)) {
            removeRecord(recordId)
            clearPendingActivationRecordId(recordId)
            return SecureSessionCommitResult.STORAGE_FAILURE
        }

        if (!setActiveRecordId(recordId) || activeRecordId() != recordId || !verifyRecord(recordId, candidate)) {
            restoreActiveRecord(previousActiveId)
            removeRecord(recordId)
            clearPendingActivationRecordId(recordId)
            return SecureSessionCommitResult.STORAGE_FAILURE
        }

        val callbackSucceeded = runCatching(afterVerifiedActivation).getOrDefault(false)
        if (!callbackSucceeded) {
            val rollbackGuardArmed = armClearBarrierLocked() &&
                clearBarrierStatus() == SecureClearGuardStatus.ARMED
            val removed = removeRecord(recordId)
            val restored = restoreActiveRecord(previousActiveId)
            val pendingCleared = clearPendingActivationRecordId(recordId)
            val rollbackCompleted = restored && removed && pendingCleared
            val guardDisarmed = !rollbackGuardArmed || (rollbackCompleted && disarmClearBarrierLocked())
            return if (rollbackCompleted && guardDisarmed) {
                SecureSessionCommitResult.POST_ACTIVATION_CALLBACK_FAILED_ROLLED_BACK
            } else {
                SecureSessionCommitResult.STORAGE_FAILURE
            }
        }

        clearPendingActivationRecordId(recordId)
        cleanupInactiveRecords(recordId)
        return SecureSessionCommitResult.ACTIVATED
    }

    private fun readLocked(): SecureSessionReadResult {
        when (clearBarrierStatus()) {
            SecureClearGuardStatus.ARMED -> return if (finishSecureClearLocked()) {
                SecureSessionReadResult.ClearPending
            } else {
                SecureSessionReadResult.StorageFailure
            }

            SecureClearGuardStatus.UNAVAILABLE -> return SecureSessionReadResult.StorageFailure

            SecureClearGuardStatus.DISARMED -> Unit
        }

        when (recoverPendingActivationLocked()) {
            PendingActivationRecovery.RECOVERED -> Unit
            PendingActivationRecovery.CORRUPT -> return clearInvalidStateLocked()
            PendingActivationRecovery.STORAGE_FAILURE -> return SecureSessionReadResult.StorageFailure
        }

        val activeId = activeRecordId()
        if (activeId == null) {
            return if (metadataStore.keys().isEmpty() && credentialStore.keys().isEmpty()) {
                SecureSessionReadResult.Missing
            } else {
                clearInvalidStateLocked()
            }
        }

        return when (val record = readSecureRecord(activeId)) {
            is SecureRecordRead.Available -> {
                cleanupInactiveRecords(activeId)
                SecureSessionReadResult.Available(record.credential)
            }

            SecureRecordRead.Corrupt -> clearInvalidStateLocked()

            SecureRecordRead.StorageFailure -> SecureSessionReadResult.StorageFailure
        }
    }

    private fun readSecureRecord(recordId: String): SecureRecordRead {
        if (!recordId.isValidRecordId()) return SecureRecordRead.Corrupt
        val metadata = readMetadata(recordId) ?: return SecureRecordRead.Corrupt
        val encrypted = readCredentials(recordId) ?: return SecureRecordRead.Corrupt
        val plaintext = try {
            tokenCipher.decrypt(encrypted, additionalAuthenticatedData(metadata))
        } catch (_: SessionTokenCorruptException) {
            return SecureRecordRead.Corrupt
        } catch (_: Exception) {
            return SecureRecordRead.StorageFailure
        }

        return try {
            val token = String(plaintext, Charsets.UTF_8)
            if (token.isBlank()) {
                SecureRecordRead.Corrupt
            } else {
                SecureRecordRead.Available(
                    SecureSessionCredential(
                        serverId = metadata.serverId,
                        userId = metadata.userId,
                        credentialInstanceId = metadata.credentialInstanceId,
                        accessToken = token,
                    ),
                )
            }
        } finally {
            plaintext.fill(0)
        }
    }

    private fun encrypt(token: String, metadata: SessionMetadata): EncryptedSessionToken? {
        val plaintext = token.toByteArray(Charsets.UTF_8)
        return try {
            tokenCipher.encrypt(plaintext, additionalAuthenticatedData(metadata))
                .takeIf { encryptedToken -> encryptedToken.isStructurallyValid() }
        } catch (_: Exception) {
            null
        } finally {
            plaintext.fill(0)
        }
    }

    private fun verifyRecord(recordId: String, expected: SecureSessionCredential): Boolean =
        when (val record = readSecureRecord(recordId)) {
            is SecureRecordRead.Available ->
                record.credential.serverId == expected.serverId &&
                    record.credential.userId == expected.userId &&
                    record.credential.credentialInstanceId == expected.credentialInstanceId &&
                    record.credential.accessToken == expected.accessToken

            SecureRecordRead.Corrupt,
            SecureRecordRead.StorageFailure,
            -> false
        }

    private fun writeMetadata(metadata: SessionMetadata): Boolean {
        val values = mapOf(
            metadataKey(metadata.recordId, FIELD_SCHEMA) to metadata.schemaVersion.toString(),
            metadataKey(metadata.recordId, FIELD_SERVER_ID) to metadata.serverId.toString(),
            metadataKey(metadata.recordId, FIELD_USER_ID) to metadata.userId.toString(),
            metadataKey(metadata.recordId, FIELD_CREDENTIAL_INSTANCE_ID) to metadata.credentialInstanceId.toString(),
        )
        return metadataStore.commit(puts = values) && readMetadata(metadata.recordId) == metadata
    }

    private fun readMetadata(recordId: String): SessionMetadata? {
        val schema = metadataStore.getString(metadataKey(recordId, FIELD_SCHEMA))?.toIntOrNull() ?: return null
        val serverId = metadataStore.getString(metadataKey(recordId, FIELD_SERVER_ID))?.toLongOrNull() ?: return null
        val userId = metadataStore.getString(metadataKey(recordId, FIELD_USER_ID))
            ?.let { value -> runCatching { UUID.fromString(value) }.getOrNull() }
            ?: return null
        val credentialInstanceId = metadataStore.getString(metadataKey(recordId, FIELD_CREDENTIAL_INSTANCE_ID))
            ?.let { value -> runCatching { UUID.fromString(value) }.getOrNull() }
            ?: return null
        return SessionMetadata(schema, recordId, serverId, userId, credentialInstanceId)
            .takeIf { metadata -> metadata.isStructurallyValid() }
    }

    private fun writeCredentials(recordId: String, encrypted: EncryptedSessionToken): Boolean {
        val values = mapOf(
            credentialKey(recordId, FIELD_VERSION) to encrypted.version.wireName,
            credentialKey(recordId, FIELD_IV) to Base64.getEncoder().withoutPadding()
                .encodeToString(encrypted.initializationVector),
            credentialKey(recordId, FIELD_CIPHERTEXT) to Base64.getEncoder().withoutPadding()
                .encodeToString(encrypted.ciphertext),
        )
        if (!credentialStore.commit(puts = values)) return false
        val persisted = readCredentials(recordId) ?: return false
        return persisted.version == encrypted.version &&
            persisted.initializationVector.contentEquals(encrypted.initializationVector) &&
            persisted.ciphertext.contentEquals(encrypted.ciphertext)
    }

    private fun readCredentials(recordId: String): EncryptedSessionToken? = try {
        val version = credentialStore.getString(credentialKey(recordId, FIELD_VERSION))
            ?.let(SessionTokenCipherVersion::fromWireName)
            ?: return null
        val iv = credentialStore.getString(credentialKey(recordId, FIELD_IV))
            ?.let(Base64.getDecoder()::decode)
            ?: return null
        val ciphertext = credentialStore.getString(credentialKey(recordId, FIELD_CIPHERTEXT))
            ?.let(Base64.getDecoder()::decode)
            ?: return null
        EncryptedSessionToken(version, ciphertext, iv)
            .takeIf { encryptedToken -> encryptedToken.isStructurallyValid() }
    } catch (_: IllegalArgumentException) {
        null
    }

    private fun activeRecordId(): String? = metadataStore.getString(KEY_ACTIVE_RECORD_ID)

    private fun setPendingActivationRecordId(recordId: String): Boolean =
        metadataStore.commit(puts = mapOf(KEY_PENDING_ACTIVATION_RECORD_ID to recordId)) &&
            metadataStore.getString(KEY_PENDING_ACTIVATION_RECORD_ID) == recordId

    private fun clearPendingActivationRecordId(recordId: String): Boolean {
        if (KEY_PENDING_ACTIVATION_RECORD_ID !in metadataStore.keys()) return true
        if (metadataStore.getString(KEY_PENDING_ACTIVATION_RECORD_ID) != recordId) return false
        return metadataStore.commit(removals = setOf(KEY_PENDING_ACTIVATION_RECORD_ID)) &&
            KEY_PENDING_ACTIVATION_RECORD_ID !in metadataStore.keys()
    }

    private fun recoverPendingActivationLocked(): PendingActivationRecovery {
        if (KEY_PENDING_ACTIVATION_RECORD_ID !in metadataStore.keys()) {
            return PendingActivationRecovery.RECOVERED
        }
        val pendingId = metadataStore.getString(KEY_PENDING_ACTIVATION_RECORD_ID)
            ?.takeIf { value -> value.isValidRecordId() }
            ?: return PendingActivationRecovery.CORRUPT

        if (activeRecordId() != pendingId) {
            val removed = removeRecord(pendingId)
            val pendingCleared = clearPendingActivationRecordId(pendingId)
            return if (removed && pendingCleared) {
                PendingActivationRecovery.RECOVERED
            } else {
                PendingActivationRecovery.STORAGE_FAILURE
            }
        }

        return when (readSecureRecord(pendingId)) {
            is SecureRecordRead.Available -> {
                if (clearPendingActivationRecordId(pendingId)) {
                    PendingActivationRecovery.RECOVERED
                } else {
                    PendingActivationRecovery.STORAGE_FAILURE
                }
            }

            SecureRecordRead.Corrupt -> PendingActivationRecovery.CORRUPT

            SecureRecordRead.StorageFailure -> PendingActivationRecovery.STORAGE_FAILURE
        }
    }

    private fun setActiveRecordId(recordId: String): Boolean =
        metadataStore.commit(puts = mapOf(KEY_ACTIVE_RECORD_ID to recordId)) && activeRecordId() == recordId

    private fun restoreActiveRecord(recordId: String?): Boolean {
        val committed = if (recordId == null) {
            metadataStore.commit(removals = setOf(KEY_ACTIVE_RECORD_ID))
        } else {
            metadataStore.commit(puts = mapOf(KEY_ACTIVE_RECORD_ID to recordId))
        }
        return committed && activeRecordId() == recordId
    }

    private fun removeRecord(recordId: String): Boolean {
        val metadataKeys = setOf(
            FIELD_SCHEMA,
            FIELD_SERVER_ID,
            FIELD_USER_ID,
            FIELD_CREDENTIAL_INSTANCE_ID,
        ).mapTo(mutableSetOf()) { field -> metadataKey(recordId, field) }
        val credentialKeys = setOf(FIELD_VERSION, FIELD_IV, FIELD_CIPHERTEXT).mapTo(mutableSetOf()) { field ->
            credentialKey(recordId, field)
        }
        val metadataRemoved = metadataStore.commit(removals = metadataKeys) &&
            metadataKeys.none(metadataStore.keys()::contains)
        val credentialsRemoved = credentialStore.commit(removals = credentialKeys) &&
            credentialKeys.none(credentialStore.keys()::contains)
        return metadataRemoved && credentialsRemoved
    }

    private fun cleanupInactiveRecords(activeRecordId: String) {
        val recordIds = (metadataStore.keys() + credentialStore.keys()).mapNotNull(::recordIdFromKey).toSet()
        recordIds.filterNot { it == activeRecordId }.forEach(::removeRecord)
    }

    private fun clearInvalidStateLocked(): SecureSessionReadResult = if (
        armClearBarrierLocked() && finishSecureClearLocked()
    ) {
        SecureSessionReadResult.ClearPending
    } else {
        SecureSessionReadResult.StorageFailure
    }

    private fun clearLocked(afterSecureDeletion: () -> Boolean): Boolean {
        if (!armClearBarrierLocked()) return false
        if (!finishSecureClearLocked()) return false
        if (!runCatching(afterSecureDeletion).getOrDefault(false)) return false
        return disarmClearBarrierLocked()
    }

    private fun finishSecureClearLocked(): Boolean {
        val metadataCleared = metadataStore.commit(clear = true) && metadataStore.keys().isEmpty()
        val credentialsCleared = credentialStore.commit(clear = true) && credentialStore.keys().isEmpty()
        val keyDestroyed = tokenCipher.destroy()
        return metadataCleared && credentialsCleared && keyDestroyed
    }

    private fun disarmClearBarrierLocked(): Boolean {
        val preferenceGuardCleared = clearGuardStore.commit(clear = true) && clearGuardStore.keys().isEmpty()
        if (!preferenceGuardCleared) return false
        return tokenCipher.disarmClearGuard() &&
            tokenCipher.clearGuardStatus() == SecureClearGuardStatus.DISARMED
    }

    private fun armClearBarrierLocked(): Boolean {
        val preferenceGuardArmed = runCatching {
            clearGuardStore.keys().isNotEmpty() ||
                (
                    clearGuardStore.commit(puts = mapOf(KEY_CLEAR_PENDING to VALUE_TRUE)) &&
                        clearGuardStore.getString(KEY_CLEAR_PENDING) == VALUE_TRUE
                    )
        }.getOrDefault(false)
        val keyGuardArmed = tokenCipher.armClearGuard()
        return preferenceGuardArmed || keyGuardArmed
    }

    private fun clearBarrierStatus(): SecureClearGuardStatus {
        val preferenceStatus = runCatching {
            if (clearGuardStore.keys().isEmpty()) {
                SecureClearGuardStatus.DISARMED
            } else {
                SecureClearGuardStatus.ARMED
            }
        }.getOrDefault(SecureClearGuardStatus.UNAVAILABLE)
        val keyStatus = tokenCipher.clearGuardStatus()
        return when {
            preferenceStatus == SecureClearGuardStatus.ARMED || keyStatus == SecureClearGuardStatus.ARMED ->
                SecureClearGuardStatus.ARMED

            preferenceStatus == SecureClearGuardStatus.DISARMED && keyStatus == SecureClearGuardStatus.DISARMED ->
                SecureClearGuardStatus.DISARMED

            else -> SecureClearGuardStatus.UNAVAILABLE
        }
    }

    private fun additionalAuthenticatedData(metadata: SessionMetadata): ByteArray =
        ByteArrayOutputStream().use { buffer ->
            DataOutputStream(buffer).use { output ->
                output.writeInt(metadata.schemaVersion)
                output.writeUTF(metadata.recordId)
                output.writeLong(metadata.serverId)
                output.writeLong(metadata.userId.mostSignificantBits)
                output.writeLong(metadata.userId.leastSignificantBits)
                output.writeLong(metadata.credentialInstanceId.mostSignificantBits)
                output.writeLong(metadata.credentialInstanceId.leastSignificantBits)
            }
            buffer.toByteArray()
        }

    private fun metadataKey(recordId: String, field: String): String = "$RECORD_PREFIX$recordId.$field"

    private fun credentialKey(recordId: String, field: String): String = "$RECORD_PREFIX$recordId.$field"

    private fun recordIdFromKey(key: String): String? = key
        .takeIf { it.startsWith(RECORD_PREFIX) }
        ?.removePrefix(RECORD_PREFIX)
        ?.substringBefore('.')
        ?.takeIf(String::isNotBlank)

    private fun SecureSessionCredential.isStructurallyValid(): Boolean = serverId > 0 &&
        userId != EMPTY_UUID &&
        credentialInstanceId != EMPTY_UUID &&
        accessToken.isNotBlank()

    private fun SessionMetadata.isStructurallyValid(): Boolean = schemaVersion == SESSION_SCHEMA_VERSION &&
        recordId.isValidRecordId() &&
        serverId > 0 &&
        userId != EMPTY_UUID &&
        credentialInstanceId != EMPTY_UUID

    private fun String.isValidRecordId(): Boolean =
        runCatching { UUID.fromString(this).toString() == this }.getOrDefault(false)

    private fun EncryptedSessionToken.isStructurallyValid(): Boolean =
        initializationVector.size in MINIMUM_GCM_IV_BYTES..MAXIMUM_GCM_IV_BYTES &&
            ciphertext.size > GCM_TAG_LENGTH_BYTES

    private data class SessionMetadata(
        val schemaVersion: Int,
        val recordId: String,
        val serverId: Long,
        val userId: UUID,
        val credentialInstanceId: UUID,
    )

    private sealed interface SecureRecordRead {
        data class Available(val credential: SecureSessionCredential) : SecureRecordRead

        data object Corrupt : SecureRecordRead

        data object StorageFailure : SecureRecordRead
    }

    private enum class PendingActivationRecovery {
        RECOVERED,
        CORRUPT,
        STORAGE_FAILURE,
    }

    private companion object {
        val PROCESS_LOCK = Any()
        val EMPTY_UUID: UUID = UUID(0, 0)
        const val SESSION_SCHEMA_VERSION = 2
        const val KEY_ACTIVE_RECORD_ID = "active_record_id"
        const val KEY_PENDING_ACTIVATION_RECORD_ID = "pending_activation_record_id"
        const val KEY_CLEAR_PENDING = "clear_pending"
        const val VALUE_TRUE = "true"
        const val RECORD_PREFIX = "record."
        const val FIELD_SCHEMA = "schema"
        const val FIELD_SERVER_ID = "server_id"
        const val FIELD_USER_ID = "user_id"
        const val FIELD_CREDENTIAL_INSTANCE_ID = "credential_instance_id"
        const val FIELD_VERSION = "version"
        const val FIELD_IV = "iv"
        const val FIELD_CIPHERTEXT = "ciphertext"
        const val MINIMUM_GCM_IV_BYTES = 12
        const val MAXIMUM_GCM_IV_BYTES = 16
        const val GCM_TAG_LENGTH_BYTES = 16
    }
}
