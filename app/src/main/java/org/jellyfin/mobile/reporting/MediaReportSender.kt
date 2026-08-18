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
import okhttp3.RequestBody.Companion.toRequestBody
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.util.AuthorizationHeaderBuilder
import timber.log.Timber
import java.io.IOException
import java.util.concurrent.TimeUnit

class MediaReportSender(
    private val okHttpClient: OkHttpClient,
    private val apiClient: ApiClient,
    private val ioDispatcher: CoroutineDispatcher,
) {
    private val reportClient = okHttpClient.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .callTimeout(REPORT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    suspend fun send(
        target: MediaReportTarget,
        reason: MediaReportReason,
        details: String?,
    ): MediaReportDeliveryResult = withContext(ioDispatcher) {
        val endpoint = buildMediaReportEndpoint(apiClient.baseUrl)
            ?: return@withContext MediaReportDeliveryResult.UNAVAILABLE
        val accessToken = apiClient.accessToken?.takeIf(String::isNotBlank)
            ?: return@withContext MediaReportDeliveryResult.UNAVAILABLE
        val authorizationHeader = AuthorizationHeaderBuilder.buildHeader(
            clientName = apiClient.clientInfo.name,
            clientVersion = apiClient.clientInfo.version,
            deviceId = apiClient.deviceInfo.id,
            deviceName = apiClient.deviceInfo.name,
            accessToken = accessToken,
        )
        val request = Request.Builder()
            .url(endpoint)
            .header("Authorization", authorizationHeader)
            .header("Accept", "application/json")
            .post(buildMediaReportPayload(target, reason, details).toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()

        try {
            reportClient.newCall(request).execute().use { response ->
                classifyMediaReportResponse(response.code)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: IOException) {
            Timber.w(error, "Could not deliver PiggieTV media report")
            MediaReportDeliveryResult.FAILED
        }
    }
}

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
    put("title", target.title.trim().ifBlank { "Unknown" }.limit(TITLE_LIMIT))
    put("source", target.source.wireName)
    put("reason", reason.wireName)

    target.subtitle?.trim()?.takeIf(String::isNotBlank)?.let { put("subtitle", it.limit(TITLE_LIMIT)) }
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

private const val MEDIA_REPORT_ROUTE = "Ptv/v1/reports/media"
private const val MEDIA_REPORT_SCHEMA_VERSION = 1
private const val ITEM_ID_LIMIT = 128
private const val TITLE_LIMIT = 256
private const val TYPE_LIMIT = 64
private const val DETAILS_LIMIT = 2_000
private const val REPORT_TIMEOUT_SECONDS = 15L
private const val HTTP_SUCCESS_MIN = 200
private const val HTTP_SUCCESS_MAX = 299
private const val HTTP_NOT_FOUND = 404
private const val HTTP_TOO_MANY_REQUESTS = 429
private const val HTTP_NOT_IMPLEMENTED = 501
private const val HTTP_SERVICE_UNAVAILABLE = 503
private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
