package org.jellyfin.mobile.auth.transport

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/** Performs only the retiring session's bounded server-side logout request. */
internal fun interface JellyfinSessionRevoker {
    fun revoke(serverUrl: String, authorizationHeader: String): Boolean
}

/** Keeps a retiring credential off the application's shared mutable Jellyfin SDK route. */
internal class OkHttpJellyfinSessionRevoker(baseClient: OkHttpClient) : JellyfinSessionRevoker {
    private val client = baseClient.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .callTimeout(REVOCATION_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    override fun revoke(serverUrl: String, authorizationHeader: String): Boolean {
        val base = serverUrl.toHttpUrlOrNull() ?: return false
        val logoutUrl = base.newBuilder()
            .addPathSegment("Sessions")
            .addPathSegment("Logout")
            .build()
        val request = Request.Builder()
            .url(logoutUrl)
            .authTransportRealm(AuthTransportRealm.EXCLUDED)
            .header("Authorization", authorizationHeader)
            .post(ByteArray(0).toRequestBody(CONTENT_TYPE_JSON))
            .build()

        return runCatching {
            client.newCall(request).execute().use { response -> response.isSuccessful }
        }.getOrDefault(false)
    }

    private companion object {
        const val REVOCATION_TIMEOUT_SECONDS = 5L
        val CONTENT_TYPE_JSON = "application/json".toMediaTypeOrNull()
    }
}
