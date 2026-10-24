package org.jellyfin.mobile.auth

import java.util.UUID

/** Authentication methods that may establish a Jellyfin session. */
enum class AuthMethod {
    RESTORE,
    PASSWORD,
    QUICK_CONNECT,
}

/** Shared, secret-safe failure categories for authentication and session recovery. */
enum class AuthFailureCategory {
    AUTH_REJECTED,
    NETWORK_UNAVAILABLE,
    SERVER_UNAVAILABLE,
    REQUEST_FAILED,
    INVALID_LOCAL_SESSION,
    SECURE_STORAGE_FAILURE,
    QUICK_CONNECT_EXPIRED,
    QUICK_CONNECT_CANCELLED,
    QUICK_CONNECT_FAILED,
    AUTH_OPERATION_SUPERSEDED,
}

/** Operations that can establish, replace, or supersede authentication state. */
enum class AuthOperationKind {
    RESTORE,
    PASSWORD_LOGIN,
    QUICK_CONNECT,
    SERVER_SWITCH,
}

/**
 * Process-local authentication state. No state may contain a password, access token, Quick
 * Connect secret, authorization header, or other reusable credential.
 */
sealed interface MobileAuthState {
    data object Unauthenticated : MobileAuthState

    data object Restoring : MobileAuthState

    data object Validating : MobileAuthState

    data class Authenticating(val method: AuthMethod) : MobileAuthState

    data object QuickConnectRequesting : MobileAuthState

    data class QuickConnectWaiting(val displayCode: String, val deadlineElapsedRealtimeMillis: Long) :
        MobileAuthState {
        override fun toString(): String = "QuickConnectWaiting(displayCode=[REDACTED], " +
            "deadlineElapsedRealtimeMillis=$deadlineElapsedRealtimeMillis)"
    }

    data class Authenticated(
        val serverId: String,
        val userId: String,
        val credentialInstanceId: UUID,
        val method: AuthMethod,
        val credentialEpoch: Long,
    ) : MobileAuthState {
        override fun toString(): String =
            "Authenticated(server=[REDACTED], user=[REDACTED], credentialInstance=[REDACTED], " +
                "method=$method, credentialEpoch=$credentialEpoch)"
    }

    data class ConnectivityError(val category: AuthFailureCategory) : MobileAuthState {
        init {
            require(
                category == AuthFailureCategory.NETWORK_UNAVAILABLE ||
                    category == AuthFailureCategory.SERVER_UNAVAILABLE,
            ) { "ConnectivityError requires a connectivity or server availability category." }
        }
    }

    data object StorageError : MobileAuthState

    data class AuthRejected(val credentialEpoch: Long? = null) : MobileAuthState

    data object LoggingOut : MobileAuthState
}
