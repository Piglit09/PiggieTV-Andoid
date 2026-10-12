package org.jellyfin.mobile.auth.transport

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import java.security.MessageDigest

/**
 * Minimal bridge between HTTP routing and the application authentication owner.
 *
 * Implementations must compare the supplied snapshot with their current session before accepting
 * a rejection. In particular, [publishAuthenticationRejected] must reject an older snapshot after
 * a logout, server change, or same-token login in a newer epoch.
 */
internal interface AuthTransportSession {
    fun currentSnapshot(): AuthTransportSnapshot?

    /** Captures route configuration and its transport owner under one implementation lock. */
    fun <T> withCurrentSnapshot(block: (AuthTransportSnapshot?) -> T): T = block(currentSnapshot())

    /** Serializes mutable API-route updates with snapshot activation and rejection clearing. */
    fun mutateRoute(mutation: () -> Unit) = mutation()

    fun publishAuthenticationRejected(snapshot: AuthTransportSnapshot): Boolean

    /** Registers one exact-epoch network call so logout/replacement can cancel it before reuse. */
    fun registerAuthenticatedCall(snapshot: AuthTransportSnapshot, owner: Any, cancel: () -> Unit): Boolean =
        currentSnapshot() === snapshot

    fun unregisterAuthenticatedCall(snapshot: AuthTransportSnapshot, owner: Any) = Unit
}

/** Immutable, secret-safe ownership captured immediately before an authenticated request. */
internal class AuthTransportSnapshot(
    val epoch: Long,
    serverUrl: String,
    accessToken: String,
    authorizationHeader: String,
) {
    internal val serverOrigin: HttpUrl? = serverUrl.toHttpUrlOrNull()
    private val accessToken: String = accessToken
        .takeIf(String::isNotBlank)
        .orEmpty()
    internal val authorizationHeader: String = authorizationHeader
        .takeIf(String::isNotBlank)
        .orEmpty()

    internal fun matchesAccessToken(candidate: String?): Boolean = accessToken.securelyMatches(candidate)

    internal fun matchesAuthorization(candidate: String?): Boolean = authorizationHeader.securelyMatches(candidate)

    override fun toString(): String = "AuthTransportSnapshot(" +
        "epoch=$epoch, server=[REDACTED], authorization=[REDACTED])"
}

private fun String.securelyMatches(candidate: String?): Boolean {
    if (isEmpty() || candidate == null) return false
    return MessageDigest.isEqual(
        toByteArray(Charsets.UTF_8),
        candidate.toByteArray(Charsets.UTF_8),
    )
}

/** Optional request routing override. Requests without a tag use exact-origin auto routing. */
internal enum class AuthTransportRealm {
    JELLYFIN,
    LIBRARY,
    EXCLUDED,
}

internal fun Request.Builder.authTransportRealm(realm: AuthTransportRealm?): Request.Builder = apply {
    tag(AuthTransportRealm::class.java, realm)
}

internal fun Request.Builder.authTransportEpoch(epoch: Long?): Request.Builder = apply {
    if (epoch == null) {
        removeHeader(INTERNAL_AUTH_EPOCH_HEADER)
    } else {
        header(INTERNAL_AUTH_EPOCH_HEADER, epoch.toString())
    }
}

internal fun HttpUrl.hasSameOrigin(other: HttpUrl): Boolean =
    scheme == other.scheme && host == other.host && port == other.port

internal const val INTERNAL_AUTH_EPOCH_HEADER = "X-PiggieTV-Internal-Auth-Epoch"
internal const val INTERNAL_LIBRARY_AUTH_HEADER = "X-PiggieTV-Internal-Library-Auth"
internal const val INTERNAL_LIBRARY_AUTH_VALUE = "1"
