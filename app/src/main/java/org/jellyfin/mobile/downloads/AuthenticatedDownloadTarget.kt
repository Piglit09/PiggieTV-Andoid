package org.jellyfin.mobile.downloads

import okhttp3.HttpUrl
import org.jellyfin.mobile.auth.transport.toHttpUrlWithoutAuthenticationQuery

/** Parsed Jellyfin download identity whose URL is safe to persist or hand to Media3. */
internal class AuthenticatedDownloadTarget private constructor(val itemId: String, val url: HttpUrl) {
    override fun toString(): String = "AuthenticatedDownloadTarget(itemId=[REDACTED], url=[REDACTED])"

    companion object {
        fun parse(candidate: String): AuthenticatedDownloadTarget? {
            val sanitizedUrl = candidate.toHttpUrlWithoutAuthenticationQuery() ?: return null
            if (sanitizedUrl.encodedUsername.isNotEmpty() || sanitizedUrl.encodedPassword.isNotEmpty()) return null
            val persistenceSafeUrl = sanitizedUrl.newBuilder().fragment(null).build()
            if (!persistenceSafeUrl.hasOnlyPersistenceSafeDownloadQuery()) return null
            val itemId = ITEM_DOWNLOAD_PATH_REGEX.find(persistenceSafeUrl.encodedPath)
                ?.groupValues
                ?.getOrNull(1)
                ?: return null
            return AuthenticatedDownloadTarget(itemId.lowercase(), persistenceSafeUrl)
        }
    }
}

internal fun HttpUrl.hasOnlyPersistenceSafeDownloadQuery(): Boolean {
    if (querySize == 0) return true
    return querySize == 1 &&
        queryParameterName(0).equals(SAFE_QUALITY_QUERY, ignoreCase = true) &&
        queryParameterValue(0).equals(SAFE_QUALITY_VALUE, ignoreCase = true)
}

/** Prevents a WebView-supplied filename from escaping the selected download directory. */
internal fun String.toSafeDownloadFileName(): String? = trim()
    .substringAfterLast('/')
    .substringAfterLast('\\')
    .takeIf { candidate ->
        candidate.isNotBlank() &&
            candidate != "." &&
            candidate != ".." &&
            '\u0000' !in candidate
    }

private val ITEM_DOWNLOAD_PATH_REGEX = Regex(
    pattern = "(?:^|/)Items/([a-f0-9]{32})/Download(?:/|$)",
    option = RegexOption.IGNORE_CASE,
)
private const val SAFE_QUALITY_QUERY = "quality"
private const val SAFE_QUALITY_VALUE = "original"
