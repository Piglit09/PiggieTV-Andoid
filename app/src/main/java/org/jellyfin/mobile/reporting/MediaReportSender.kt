package org.jellyfin.mobile.reporting

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

class MediaReportSender(private val okHttpClient: OkHttpClient) {
    suspend fun send(target: MediaReportTarget, reason: MediaReportReason, details: String?) {
        val request = Request.Builder()
            .url(DISCORD_WEBHOOK_URL)
            .post(buildPayload(target, reason, details).toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()

        withContext(Dispatchers.IO) {
            okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IOException("Discord report failed with HTTP ${response.code}")
                }
            }
        }
    }

    private fun buildPayload(target: MediaReportTarget, reason: MediaReportReason, details: String?): JSONObject {
        val fields = JSONArray()
            .put(field("Reason", reason.displayName, true))
            .put(field("Title", target.title.limit(DISCORD_FIELD_VALUE_LIMIT), false))
            .put(field("Item ID", target.itemId, true))
            .put(field("Source", target.source, true))

        target.subtitle?.takeIf(String::isNotBlank)?.let { fields.put(field("Subtitle", it.limit(DISCORD_FIELD_VALUE_LIMIT), false)) }
        target.type?.takeIf(String::isNotBlank)?.let { fields.put(field("Type", it, true)) }
        target.userName?.takeIf(String::isNotBlank)?.let { fields.put(field("User", it.limit(DISCORD_FIELD_VALUE_LIMIT), true)) }
        target.playbackPositionMs?.let { fields.put(field("Position", it.toPlaybackTime(), true)) }
        target.mediaSourceId?.takeIf(String::isNotBlank)?.let { fields.put(field("Media Source", it.limit(DISCORD_FIELD_VALUE_LIMIT), true)) }
        target.playMethod?.takeIf(String::isNotBlank)?.let { fields.put(field("Play Method", it, true)) }
        details?.trim()?.takeIf(String::isNotBlank)?.let { fields.put(field("Details", it.limit(DISCORD_FIELD_VALUE_LIMIT), false)) }

        val embed = JSONObject()
            .put("title", "PiggieTV media report")
            .put("color", DISCORD_EMBED_COLOR)
            .put("fields", fields)

        return JSONObject()
            .put("username", "PiggieTV Reports")
            .put("embeds", JSONArray().put(embed))
    }

    private fun field(name: String, value: String, inline: Boolean) = JSONObject()
        .put("name", name)
        .put("value", value.ifBlank { "Unknown" })
        .put("inline", inline)

    private fun String.limit(maxLength: Int) = when {
        length <= maxLength -> this
        else -> take(maxLength - 3) + "..."
    }

    private fun Long.toPlaybackTime(): String {
        val totalSeconds = this / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60

        return if (hours > 0) {
            "%d:%02d:%02d".format(hours, minutes, seconds)
        } else {
            "%d:%02d".format(minutes, seconds)
        }
    }

    private companion object {
        const val DISCORD_WEBHOOK_URL =
            "https://discord.com/api/webhooks/1509330050322010205/C11EUYAj6m9YGBYvMg-kliQ8wvaE9bx2kcyZ28EFmvOxmzU3NB8gdNzTFdUTtsSnvVO3"
        const val DISCORD_EMBED_COLOR = 0xFF43D1
        const val DISCORD_FIELD_VALUE_LIMIT = 1024
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
