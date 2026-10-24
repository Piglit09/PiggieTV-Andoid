package org.jellyfin.mobile.reporting

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink
import org.jellyfin.mobile.auth.transport.AuthTransportRealm
import org.jellyfin.mobile.auth.transport.AuthTransportSession
import org.jellyfin.mobile.auth.transport.INTERNAL_AUTH_EPOCH_HEADER
import org.jellyfin.mobile.auth.transport.authTransportRealm
import org.jellyfin.mobile.auth.transport.hasSameOrigin
import org.jellyfin.sdk.api.client.ApiClient
import timber.log.Timber
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.TimeUnit

class MediaReportSender internal constructor(
    private val okHttpClient: OkHttpClient,
    private val apiClient: ApiClient,
    private val authTransportSession: AuthTransportSession,
    private val ioDispatcher: CoroutineDispatcher,
) {
    private val reportClient = okHttpClient.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .callTimeout(REPORT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    suspend fun send(
        target: MediaReportTarget,
        reason: MediaReportReason,
        details: String?,
    ): MediaReportDeliveryResult = withContext(ioDispatcher) {
        val route = authTransportSession.withCurrentSnapshot { snapshot ->
            val endpoint = buildMediaReportEndpoint(apiClient.baseUrl)
                ?: return@withCurrentSnapshot null
            snapshot
                ?.takeIf { candidate ->
                    candidate.serverOrigin?.hasSameOrigin(endpoint) == true &&
                        candidate.matchesAccessToken(apiClient.accessToken)
                }
                ?.let { MediaReportRoute(endpoint, it) }
        } ?: return@withContext MediaReportDeliveryResult.UNAVAILABLE
        val request = Request.Builder()
            .url(route.endpoint)
            .header(INTERNAL_AUTH_EPOCH_HEADER, route.snapshot.epoch.toString())
            .header("Accept", "application/json")
            .post(buildMediaReportPayload(target, reason, details).toString().toOneShotReportBody())
            .authTransportRealm(AuthTransportRealm.JELLYFIN)
            .build()

        try {
            reportClient.newCall(request).execute().use { response ->
                classifyMediaReportResponse(response.code)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: InterruptedIOException) {
            if (
                error.message == AUTH_OPERATION_SUPERSEDED ||
                authTransportSession.currentSnapshot() !== route.snapshot
            ) {
                throw CancellationException(AUTH_OPERATION_SUPERSEDED).also { it.initCause(error) }
            }
            Timber.w("Could not deliver PiggieTV media report: NETWORK_UNAVAILABLE")
            MediaReportDeliveryResult.FAILED
        } catch (error: IOException) {
            if (authTransportSession.currentSnapshot() !== route.snapshot) {
                throw CancellationException(AUTH_OPERATION_SUPERSEDED).also { it.initCause(error) }
            }
            Timber.w("Could not deliver PiggieTV media report: NETWORK_UNAVAILABLE")
            MediaReportDeliveryResult.FAILED
        }
    }
}

private data class MediaReportRoute(
    val endpoint: HttpUrl,
    val snapshot: org.jellyfin.mobile.auth.transport.AuthTransportSnapshot,
)

enum class MediaReportDeliveryResult {
    SENT,
    UNAVAILABLE,
    RATE_LIMITED,
    FAILED,
}

internal fun buildMediaReportEndpoint(baseUrl: String?): HttpUrl? {
    val base = baseUrl?.toHttpUrlOrNull() ?: return null
    val basePath = base.encodedPath.trimEnd('/')
    return base.newBuilder()
        .username("")
        .password("")
        .encodedPath("$basePath/$MEDIA_REPORT_ROUTE")
        .query(null)
        .fragment(null)
        .build()
}

internal fun buildMediaReportPayload(
    target: MediaReportTarget,
    reason: MediaReportReason,
    details: String?,
): JsonObject = buildJsonObject {
    put("schemaVersion", MEDIA_REPORT_SCHEMA_VERSION)
    put("itemId", target.itemId.trim().limit(ITEM_ID_LIMIT))
    put("source", target.source.wireName)
    put("reason", reason.wireName)

    target.type?.trim()?.takeIf(String::isNotBlank)?.let { put("itemType", it.limit(TYPE_LIMIT)) }
    target.playbackPositionMs?.coerceAtLeast(0)?.let { put("playbackPositionMs", it) }
    target.mediaSourceId?.trim()?.takeIf(String::isNotBlank)?.let { put("mediaSourceId", it.limit(ITEM_ID_LIMIT)) }
    target.playMethod?.trim()?.takeIf(String::isNotBlank)?.let { put("playMethod", it.limit(TYPE_LIMIT)) }
    details?.trim()?.takeIf(String::isNotBlank)?.let { put("details", it.limit(DETAILS_LIMIT)) }
}

internal fun classifyMediaReportResponse(statusCode: Int): MediaReportDeliveryResult = when (statusCode) {
    in HTTP_SUCCESS_MIN..HTTP_SUCCESS_MAX -> MediaReportDeliveryResult.SENT
    HTTP_NOT_FOUND, HTTP_NOT_IMPLEMENTED, HTTP_SERVICE_UNAVAILABLE -> MediaReportDeliveryResult.UNAVAILABLE
    HTTP_TOO_MANY_REQUESTS -> MediaReportDeliveryResult.RATE_LIMITED
    else -> MediaReportDeliveryResult.FAILED
}

private fun String.limit(maxLength: Int): String = if (length <= maxLength) this else take(maxLength)

private fun String.toOneShotReportBody(): RequestBody {
    val bytes = toByteArray(Charsets.UTF_8)
    return object : RequestBody() {
        override fun contentType() = JSON_MEDIA_TYPE

        override fun contentLength(): Long = bytes.size.toLong()

        override fun isOneShot(): Boolean = true

        override fun writeTo(sink: BufferedSink) {
            sink.write(bytes)
        }
    }
}

private const val MEDIA_REPORT_ROUTE = "Ptv/v1/reports/media"
private const val MEDIA_REPORT_SCHEMA_VERSION = 1
private const val ITEM_ID_LIMIT = 128
private const val TYPE_LIMIT = 64
private const val DETAILS_LIMIT = 1_024
private const val REPORT_TIMEOUT_SECONDS = 15L
private const val AUTH_OPERATION_SUPERSEDED = "AUTH_OPERATION_SUPERSEDED"
private const val HTTP_SUCCESS_MIN = 200
private const val HTTP_SUCCESS_MAX = 299
private const val HTTP_NOT_FOUND = 404
private const val HTTP_TOO_MANY_REQUESTS = 429
private const val HTTP_NOT_IMPLEMENTED = 501
private const val HTTP_SERVICE_UNAVAILABLE = 503
private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
