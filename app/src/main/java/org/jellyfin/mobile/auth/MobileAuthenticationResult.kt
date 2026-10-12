package org.jellyfin.mobile.auth

import org.jellyfin.sdk.model.api.UserDto

/** Secret-free result of a native password, restore, or Quick Connect operation. */
sealed interface MobileAuthenticationResult {
    class Authenticated(val user: UserDto, val method: AuthMethod) : MobileAuthenticationResult {
        override fun toString(): String = "Authenticated(user=[REDACTED], method=$method, credential=[REDACTED])"
    }

    data object LoginRequired : MobileAuthenticationResult

    data class RecoverableFailure(val category: AuthFailureCategory) : MobileAuthenticationResult

    data object SecureStorageFailure : MobileAuthenticationResult

    data object Superseded : MobileAuthenticationResult

    data object QuickConnectAlreadyRunning : MobileAuthenticationResult
}

enum class ServerRevocationResult {
    SUCCEEDED,
    FAILED,
    NOT_ATTEMPTED,
}

data class MobileLogoutResult(val localCredentialsCleared: Boolean, val serverRevocation: ServerRevocationResult)
