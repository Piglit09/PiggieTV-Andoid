package org.jellyfin.mobile.auth.transport

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.Locale

/** Removes decoded authentication query keys, including percent-encoded spellings. */
internal fun HttpUrl.stripAuthenticationQueryParameters(): HttpUrl {
    if (querySize == 0) return this

    val sanitized = newBuilder().query(null)
    repeat(querySize) { index ->
        val name = queryParameterName(index)
        if (!name.isAuthenticationQueryParameter()) {
            sanitized.addQueryParameter(name, queryParameterValue(index))
        }
    }
    return sanitized.build()
}

/** Returns null rather than returning a potentially secret-bearing value that cannot be parsed. */
internal fun String.toHttpUrlWithoutAuthenticationQuery(): HttpUrl? =
    toHttpUrlOrNull()?.stripAuthenticationQueryParameters()

private fun String.isAuthenticationQueryParameter(): Boolean = lowercase(Locale.ROOT) in AUTHENTICATION_QUERY_PARAMETERS

private val AUTHENTICATION_QUERY_PARAMETERS = setOf(
    "apikey",
    "api_key",
    "access_token",
    "accesstoken",
    "authorization",
    "mediabrowsertoken",
    "token",
    "x-emby-authorization",
    "x-emby-token",
    "x-mediabrowser-authorization",
    "x-mediabrowser-token",
)
