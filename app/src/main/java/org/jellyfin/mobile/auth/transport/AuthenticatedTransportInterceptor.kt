package org.jellyfin.mobile.auth.transport

import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.buffer
import java.io.InterruptedIOException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Routes exact-origin Jellyfin credentials and observes terminal authentication rejection.
 *
 * This interceptor never retries. It also removes [INTERNAL_AUTH_EPOCH_HEADER] before every
 * network call, including excluded and foreign-origin requests.
 */
internal class AuthenticatedTransportInterceptor(private val authSession: AuthTransportSession) : Interceptor {
    @Suppress("CyclomaticComplexMethod", "LongMethod", "TooGenericExceptionCaught")
    override fun intercept(chain: Interceptor.Chain): Response {
        val incomingRequest = chain.request()
        val realm = incomingRequest.tag(AuthTransportRealm::class.java)
        val requestEpoch = incomingRequest.header(INTERNAL_AUTH_EPOCH_HEADER)
        val requestBuilder = incomingRequest.newBuilder()
            .removeHeader(INTERNAL_AUTH_EPOCH_HEADER)
            .removeHeader(INTERNAL_LIBRARY_AUTH_HEADER)

        if (realm == AuthTransportRealm.EXCLUDED || realm == AuthTransportRealm.LIBRARY) {
            return chain.proceed(requestBuilder.build())
        }

        val snapshot = authSession.currentSnapshot()
        if (
            requestEpoch != null &&
            (snapshot == null || requestEpoch.toLongOrNull() != snapshot.epoch)
        ) {
            requestBuilder.removeJellyfinCredentials()
            throw InterruptedIOException(AUTH_OPERATION_SUPERSEDED)
        }

        var ticket = snapshot?.takeIf { candidate ->
            candidate.serverOrigin?.hasSameOrigin(incomingRequest.url) == true &&
                requestEpoch.matchesEpoch(candidate.epoch) &&
                requestBuilder.routeAuthorization(incomingRequest, candidate)
        }

        if (
            ticket != null &&
            !authSession.registerAuthenticatedCall(ticket, chain) { chain.call().cancel() }
        ) {
            requestBuilder.removeJellyfinCredentials()
            ticket = null
        }

        val exactOrigin = incomingRequest.isExactOriginOf(snapshot)
        val carriesCurrentAuthorization = snapshot?.let { candidate ->
            candidate.matchesAuthorization(incomingRequest.header(AUTHORIZATION_HEADER)) ||
                candidate.matchesAuthorization(incomingRequest.header(X_EMBY_AUTHORIZATION_HEADER)) ||
                candidate.matchesAuthorization(incomingRequest.header(X_MEDIA_BROWSER_AUTHORIZATION_HEADER)) ||
                candidate.matchesAccessToken(incomingRequest.header(X_EMBY_TOKEN_HEADER)) ||
                candidate.matchesAccessToken(incomingRequest.header(X_MEDIA_BROWSER_TOKEN_HEADER))
        } == true
        val isForeignUnownedRoute = ticket == null && !exactOrigin
        val carriesJellyfinOwnership = realm == AuthTransportRealm.JELLYFIN ||
            requestEpoch != null ||
            carriesCurrentAuthorization ||
            incomingRequest.carriesJellyfinCredential()
        if (isForeignUnownedRoute && carriesJellyfinOwnership) {
            requestBuilder.removeJellyfinCredentials()
        }

        val response = try {
            chain.proceed(requestBuilder.build())
        } catch (error: Throwable) {
            ticket?.let { authSession.unregisterAuthenticatedCall(it, chain) }
            throw error
        }
        if (
            response.code == HTTP_UNAUTHORIZED &&
            ticket?.serverOrigin?.hasSameOrigin(response.request.url) == true
        ) {
            authSession.publishAuthenticationRejected(ticket)
        }
        val responseBody = response.body
        val registeredTicket = ticket
        if (registeredTicket == null || responseBody == null || responseBody.contentLength() == 0L) {
            registeredTicket?.let { authSession.unregisterAuthenticatedCall(it, chain) }
            return response
        }
        return response.newBuilder()
            .body(
                RegisteredAuthenticatedResponseBody(responseBody) {
                    authSession.unregisterAuthenticatedCall(registeredTicket, chain)
                },
            )
            .build()
    }

    private fun String?.matchesEpoch(epoch: Long): Boolean = when (this) {
        null -> true
        else -> toLongOrNull() == epoch
    }

    private fun Request.Builder.routeAuthorization(
        incomingRequest: Request,
        snapshot: AuthTransportSnapshot,
    ): Boolean {
        val existingAuthorization = incomingRequest.header(AUTHORIZATION_HEADER)
        return when {
            snapshot.authorizationHeader.isEmpty() -> false

            existingAuthorization == null -> {
                header(AUTHORIZATION_HEADER, snapshot.authorizationHeader)
                true
            }

            snapshot.matchesAuthorization(existingAuthorization) -> true

            else -> {
                removeJellyfinCredentials()
                header(AUTHORIZATION_HEADER, snapshot.authorizationHeader)
                true
            }
        }
    }

    private fun Request.isExactOriginOf(snapshot: AuthTransportSnapshot?): Boolean =
        snapshot?.serverOrigin?.hasSameOrigin(url) == true

    private fun Request.carriesJellyfinCredential(): Boolean = header(X_EMBY_AUTHORIZATION_HEADER) != null ||
        header(X_MEDIA_BROWSER_AUTHORIZATION_HEADER) != null ||
        header(X_EMBY_TOKEN_HEADER) != null ||
        header(X_MEDIA_BROWSER_TOKEN_HEADER) != null ||
        header(AUTHORIZATION_HEADER)
            ?.trimStart()
            ?.startsWith(MEDIA_BROWSER_AUTHORIZATION_SCHEME, ignoreCase = true) == true

    private fun Request.Builder.removeJellyfinCredentials() {
        removeHeader(AUTHORIZATION_HEADER)
        removeHeader(X_EMBY_AUTHORIZATION_HEADER)
        removeHeader(X_MEDIA_BROWSER_AUTHORIZATION_HEADER)
        removeHeader(X_EMBY_TOKEN_HEADER)
        removeHeader(X_MEDIA_BROWSER_TOKEN_HEADER)
    }

    private companion object {
        const val HTTP_UNAUTHORIZED = 401
        const val AUTHORIZATION_HEADER = "Authorization"
        const val X_EMBY_AUTHORIZATION_HEADER = "X-Emby-Authorization"
        const val X_MEDIA_BROWSER_AUTHORIZATION_HEADER = "X-MediaBrowser-Authorization"
        const val X_EMBY_TOKEN_HEADER = "X-Emby-Token"
        const val X_MEDIA_BROWSER_TOKEN_HEADER = "X-MediaBrowser-Token"
        const val MEDIA_BROWSER_AUTHORIZATION_SCHEME = "MediaBrowser "
        const val AUTH_OPERATION_SUPERSEDED = "AUTH_OPERATION_SUPERSEDED"
    }
}

/** Keeps an authenticated call registered until its streaming body reaches EOF or is closed. */
@Suppress("TooGenericExceptionCaught")
private class RegisteredAuthenticatedResponseBody(private val delegate: ResponseBody, unregister: () -> Unit) :
    ResponseBody() {
    private val completed = AtomicBoolean(false)
    private val complete = { if (completed.compareAndSet(false, true)) unregister() }
    private val registeredSource: BufferedSource = object : ForwardingSource(delegate.source()) {
        override fun read(sink: Buffer, byteCount: Long): Long = try {
            super.read(sink, byteCount).also { read -> if (read == -1L) complete() }
        } catch (error: Throwable) {
            complete()
            throw error
        }

        override fun close() {
            try {
                super.close()
            } finally {
                complete()
            }
        }
    }.buffer()

    override fun contentLength(): Long = delegate.contentLength()

    override fun contentType() = delegate.contentType()

    override fun source(): BufferedSource = registeredSource
}
