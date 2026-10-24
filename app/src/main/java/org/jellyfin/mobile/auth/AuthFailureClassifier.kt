package org.jellyfin.mobile.auth

import kotlinx.coroutines.CancellationException
import org.jellyfin.sdk.api.client.exception.ApiClientException
import org.jellyfin.sdk.api.client.exception.InvalidContentException
import org.jellyfin.sdk.api.client.exception.InvalidStatusException
import org.jellyfin.sdk.api.client.exception.MissingBaseUrlException
import org.jellyfin.sdk.api.client.exception.SecureConnectionException
import java.io.IOException
import org.jellyfin.sdk.api.client.exception.TimeoutException as ApiTimeoutException

/** The operation in which a failure occurred; status semantics differ by context. */
enum class AuthFailureContext {
    SESSION_VALIDATION,
    USER_SPECIFIC_SESSION_VALIDATION,
    PASSWORD_AUTHENTICATION,
    QUICK_CONNECT_POLLING,
    QUICK_CONNECT_AUTHENTICATION,
    RUNTIME_REQUEST,
}

/** Context-aware, fail-closed classification for Jellyfin authentication failures. */
object AuthFailureClassifier {
    fun classify(error: Throwable, context: AuthFailureContext): AuthFailureCategory = when (error) {
        is CancellationException -> when (context) {
            AuthFailureContext.QUICK_CONNECT_POLLING,
            AuthFailureContext.QUICK_CONNECT_AUTHENTICATION,
            -> AuthFailureCategory.QUICK_CONNECT_CANCELLED

            else -> AuthFailureCategory.AUTH_OPERATION_SUPERSEDED
        }

        is InvalidStatusException -> classifyStatus(error.status, context)

        is ApiTimeoutException,
        is SecureConnectionException,
        is IOException,
        -> AuthFailureCategory.NETWORK_UNAVAILABLE

        is MissingBaseUrlException -> AuthFailureCategory.INVALID_LOCAL_SESSION

        is InvalidContentException -> AuthFailureCategory.SERVER_UNAVAILABLE

        is ApiClientException -> when {
            error.hasNetworkCause() -> AuthFailureCategory.NETWORK_UNAVAILABLE
            else -> AuthFailureCategory.SERVER_UNAVAILABLE
        }

        else -> when {
            error.hasNetworkCause() -> AuthFailureCategory.NETWORK_UNAVAILABLE
            else -> AuthFailureCategory.REQUEST_FAILED
        }
    }

    fun classifyStatus(status: Int, context: AuthFailureContext): AuthFailureCategory {
        if (status in SERVER_UNAVAILABLE_STATUSES || status >= HTTP_SERVER_ERROR_MINIMUM) {
            return AuthFailureCategory.SERVER_UNAVAILABLE
        }

        return when (context) {
            AuthFailureContext.SESSION_VALIDATION -> when (status) {
                HTTP_UNAUTHORIZED,
                HTTP_FORBIDDEN,
                -> AuthFailureCategory.AUTH_REJECTED

                else -> AuthFailureCategory.SERVER_UNAVAILABLE
            }

            AuthFailureContext.USER_SPECIFIC_SESSION_VALIDATION -> when (status) {
                HTTP_UNAUTHORIZED,
                HTTP_FORBIDDEN,
                HTTP_NOT_FOUND,
                -> AuthFailureCategory.AUTH_REJECTED

                else -> AuthFailureCategory.SERVER_UNAVAILABLE
            }

            AuthFailureContext.PASSWORD_AUTHENTICATION -> when (status) {
                HTTP_BAD_REQUEST,
                HTTP_UNAUTHORIZED,
                HTTP_FORBIDDEN,
                -> AuthFailureCategory.AUTH_REJECTED

                else -> AuthFailureCategory.REQUEST_FAILED
            }

            AuthFailureContext.QUICK_CONNECT_POLLING -> when (status) {
                HTTP_NOT_FOUND -> AuthFailureCategory.QUICK_CONNECT_EXPIRED

                HTTP_BAD_REQUEST,
                HTTP_UNAUTHORIZED,
                HTTP_FORBIDDEN,
                -> AuthFailureCategory.QUICK_CONNECT_FAILED

                else -> AuthFailureCategory.REQUEST_FAILED
            }

            AuthFailureContext.QUICK_CONNECT_AUTHENTICATION -> when (status) {
                HTTP_BAD_REQUEST,
                HTTP_UNAUTHORIZED,
                HTTP_FORBIDDEN,
                HTTP_NOT_FOUND,
                -> AuthFailureCategory.QUICK_CONNECT_FAILED

                else -> AuthFailureCategory.REQUEST_FAILED
            }

            AuthFailureContext.RUNTIME_REQUEST -> when (status) {
                HTTP_UNAUTHORIZED -> AuthFailureCategory.AUTH_REJECTED
                else -> AuthFailureCategory.REQUEST_FAILED
            }
        }
    }

    private fun Throwable.hasNetworkCause(): Boolean {
        var current = cause
        while (current != null && current !== this) {
            if (current is IOException || current is ApiTimeoutException || current is SecureConnectionException) {
                return true
            }
            current = current.cause
        }
        return false
    }

    private const val HTTP_BAD_REQUEST = 400
    private const val HTTP_REQUEST_TIMEOUT = 408
    private const val HTTP_TOO_EARLY = 425
    private const val HTTP_UNAUTHORIZED = 401
    private const val HTTP_FORBIDDEN = 403
    private const val HTTP_NOT_FOUND = 404
    private const val HTTP_TOO_MANY_REQUESTS = 429
    private const val HTTP_SERVER_ERROR_MINIMUM = 500
    private val SERVER_UNAVAILABLE_STATUSES = setOf(HTTP_REQUEST_TIMEOUT, HTTP_TOO_EARLY, HTTP_TOO_MANY_REQUESTS)
}
