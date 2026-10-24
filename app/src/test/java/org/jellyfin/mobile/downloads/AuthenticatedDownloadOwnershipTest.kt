package org.jellyfin.mobile.downloads

import org.jellyfin.mobile.auth.AuthMethod
import org.jellyfin.mobile.auth.MobileAuthState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

class AuthenticatedDownloadOwnershipTest {
    @Test
    fun `ownership round trip retains server user credential instance and resource`() {
        val state = authenticatedState(FIRST_CREDENTIAL_INSTANCE_ID, credentialEpoch = 17L)
        val encoded = AuthenticatedDownloadOwnership.encode(state, RESOURCE_KEY)

        assertEquals(
            AuthenticatedDownloadOwner(
                serverId = SERVER_ID,
                userId = USER_ID.toString(),
                credentialInstanceId = FIRST_CREDENTIAL_INSTANCE_ID,
                resourceKey = RESOURCE_KEY,
            ),
            AuthenticatedDownloadOwnership.decode(encoded),
        )
    }

    @Test
    fun `same persisted credential instance remains authorized after process epoch changes`() {
        val queued = AuthenticatedDownloadOwnership.encode(
            authenticatedState(FIRST_CREDENTIAL_INSTANCE_ID, credentialEpoch = 91L),
            RESOURCE_KEY,
        )
        val restored = authenticatedState(FIRST_CREDENTIAL_INSTANCE_ID, credentialEpoch = 1L)

        assertTrue(AuthenticatedDownloadOwnership.isAuthorized(queued, restored))
    }

    @Test
    fun `same user relogin with a new credential instance rejects queued work`() {
        val queued = AuthenticatedDownloadOwnership.encode(
            authenticatedState(FIRST_CREDENTIAL_INSTANCE_ID, credentialEpoch = 4L),
            RESOURCE_KEY,
        )
        val relogged = authenticatedState(SECOND_CREDENTIAL_INSTANCE_ID, credentialEpoch = 5L)

        assertFalse(AuthenticatedDownloadOwnership.isAuthorized(queued, relogged))
    }

    @Test
    fun `legacy ownership without credential instance fails closed`() {
        val legacy = "ptv-auth-download-v1|$SERVER_ID|$USER_ID|$RESOURCE_KEY"

        assertNull(AuthenticatedDownloadOwnership.decode(legacy))
        assertFalse(
            AuthenticatedDownloadOwnership.isAuthorized(
                legacy,
                authenticatedState(FIRST_CREDENTIAL_INSTANCE_ID),
            ),
        )
    }

    @Test
    fun `malformed credential instance and unauthenticated state fail closed`() {
        val malformed = "ptv-auth-download-v2|$SERVER_ID|$USER_ID|not-a-uuid|$RESOURCE_KEY"
        val queued = AuthenticatedDownloadOwnership.encode(
            authenticatedState(FIRST_CREDENTIAL_INSTANCE_ID),
            RESOURCE_KEY,
        )

        assertNull(AuthenticatedDownloadOwnership.decode(malformed))
        assertFalse(AuthenticatedDownloadOwnership.isAuthorized(queued, MobileAuthState.Unauthenticated))
    }

    private fun authenticatedState(credentialInstanceId: UUID, credentialEpoch: Long = 1L) =
        MobileAuthState.Authenticated(
            serverId = SERVER_ID,
            userId = USER_ID.toString(),
            credentialInstanceId = credentialInstanceId,
            method = AuthMethod.RESTORE,
            credentialEpoch = credentialEpoch,
        )

    private companion object {
        const val SERVER_ID = "41"
        const val RESOURCE_KEY = "10000000000000000000000000000001"
        val USER_ID: UUID = UUID.fromString("10000000-0000-0000-0000-000000000001")
        val FIRST_CREDENTIAL_INSTANCE_ID: UUID = UUID.fromString("20000000-0000-0000-0000-000000000002")
        val SECOND_CREDENTIAL_INSTANCE_ID: UUID = UUID.fromString("30000000-0000-0000-0000-000000000003")
    }
}
