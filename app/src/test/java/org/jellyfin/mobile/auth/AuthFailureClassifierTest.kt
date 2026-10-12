package org.jellyfin.mobile.auth

import kotlinx.coroutines.CancellationException
import org.jellyfin.sdk.api.client.exception.InvalidStatusException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test
import java.io.IOException

class AuthFailureClassifierTest {
    @Test
    fun `validation rejects 401 and 403 but generic 404 preserves credentials`() {
        listOf(401, 403).forEach { status ->
            assertEquals(
                AuthFailureCategory.AUTH_REJECTED,
                AuthFailureClassifier.classifyStatus(status, AuthFailureContext.SESSION_VALIDATION),
            )
        }
        assertEquals(
            AuthFailureCategory.SERVER_UNAVAILABLE,
            AuthFailureClassifier.classifyStatus(404, AuthFailureContext.SESSION_VALIDATION),
        )
    }

    @Test
    fun `user-specific validation additionally rejects deleted-user 404`() {
        assertEquals(
            AuthFailureCategory.AUTH_REJECTED,
            AuthFailureClassifier.classifyStatus(404, AuthFailureContext.USER_SPECIFIC_SESSION_VALIDATION),
        )
    }

    @Test
    fun `runtime rejection is only a 401`() {
        assertEquals(
            AuthFailureCategory.AUTH_REJECTED,
            AuthFailureClassifier.classifyStatus(401, AuthFailureContext.RUNTIME_REQUEST),
        )
        listOf(400, 403, 404, 429, 500).forEach { status ->
            assertNotEquals(
                AuthFailureCategory.AUTH_REJECTED,
                AuthFailureClassifier.classifyStatus(status, AuthFailureContext.RUNTIME_REQUEST),
                "status=$status",
            )
        }
    }

    @Test
    fun `SDK invalid status uses operation context`() {
        val error = InvalidStatusException(404)

        assertEquals(
            AuthFailureCategory.AUTH_REJECTED,
            AuthFailureClassifier.classify(error, AuthFailureContext.USER_SPECIFIC_SESSION_VALIDATION),
        )
        assertEquals(
            AuthFailureCategory.REQUEST_FAILED,
            AuthFailureClassifier.classify(error, AuthFailureContext.RUNTIME_REQUEST),
        )
    }

    @Test
    fun `network errors preserve credentials and cancellation is not rejection`() {
        assertEquals(
            AuthFailureCategory.NETWORK_UNAVAILABLE,
            AuthFailureClassifier.classify(IOException("offline"), AuthFailureContext.SESSION_VALIDATION),
        )
        assertEquals(
            AuthFailureCategory.AUTH_OPERATION_SUPERSEDED,
            AuthFailureClassifier.classify(CancellationException(), AuthFailureContext.SESSION_VALIDATION),
        )
        assertEquals(
            AuthFailureCategory.QUICK_CONNECT_CANCELLED,
            AuthFailureClassifier.classify(CancellationException(), AuthFailureContext.QUICK_CONNECT_POLLING),
        )
    }

    @Test
    fun `Quick Connect polling 404 is expiration not session rejection`() {
        assertEquals(
            AuthFailureCategory.QUICK_CONNECT_EXPIRED,
            AuthFailureClassifier.classifyStatus(404, AuthFailureContext.QUICK_CONNECT_POLLING),
        )
    }
}
