package org.jellyfin.mobile.downloads

import org.jellyfin.mobile.auth.MobileAuthState
import java.util.UUID

/** Non-secret persistent ownership for work queued in Media3's download database. */
internal data class AuthenticatedDownloadOwner(
    val serverId: String,
    val userId: String,
    val credentialInstanceId: UUID,
    val resourceKey: String,
)

internal object AuthenticatedDownloadOwnership {
    fun encode(state: MobileAuthState.Authenticated, resourceKey: String): String {
        require(state.serverId.toLongOrNull()?.let { id -> id > 0L } == true) {
            "Invalid authenticated download server id."
        }
        require(state.userId.isCanonicalUuid()) { "Invalid authenticated download user id." }
        require(state.credentialInstanceId != EMPTY_UUID) { "Invalid authenticated credential instance id." }
        require(resourceKey.isValidResourceKey()) { "Invalid authenticated download resource key." }
        return listOf(
            PREFIX,
            state.serverId,
            state.userId,
            state.credentialInstanceId.toString(),
            resourceKey,
        ).joinToString(SEPARATOR)
    }

    fun decode(value: String?): AuthenticatedDownloadOwner? {
        val parts = value?.split(SEPARATOR, limit = PART_COUNT) ?: return null
        if (parts.size != PART_COUNT || parts[0] != PREFIX) return null
        val serverId = parts[1].takeIf { it.toLongOrNull()?.let { id -> id > 0L } == true } ?: return null
        val userId = parts[2].takeIf { it.isCanonicalUuid() } ?: return null
        val credentialInstanceId = parts[3].toCanonicalUuidOrNull() ?: return null
        val resourceKey = parts[4].takeIf { value -> value.isValidResourceKey() } ?: return null
        return AuthenticatedDownloadOwner(serverId, userId, credentialInstanceId, resourceKey)
    }

    fun isAuthorized(value: String?, state: MobileAuthState): Boolean {
        val owner = decode(value) ?: return false
        val authenticated = state as? MobileAuthState.Authenticated ?: return false
        return owner.serverId == authenticated.serverId &&
            owner.userId == authenticated.userId &&
            owner.credentialInstanceId == authenticated.credentialInstanceId
    }

    private fun String.isCanonicalUuid(): Boolean =
        runCatching { UUID.fromString(this).toString() == lowercase() }.getOrDefault(false)

    private fun String.toCanonicalUuidOrNull(): UUID? = runCatching { UUID.fromString(this) }
        .getOrNull()
        ?.takeIf { parsed -> parsed.toString() == lowercase() && parsed != EMPTY_UUID }

    private fun String.isValidResourceKey(): Boolean =
        isNotBlank() && length <= MAX_RESOURCE_KEY_LENGTH && SEPARATOR !in this

    private val EMPTY_UUID: UUID = UUID(0L, 0L)
    private const val PREFIX = "ptv-auth-download-v2"
    private const val SEPARATOR = "|"
    private const val PART_COUNT = 5
    private const val MAX_RESOURCE_KEY_LENGTH = 128
}

internal const val AUTHENTICATED_DOWNLOAD_DATA_SOURCE = "AuthenticatedDownloadDataSource"
