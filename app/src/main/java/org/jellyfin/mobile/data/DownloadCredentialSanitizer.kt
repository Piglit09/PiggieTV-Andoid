package org.jellyfin.mobile.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.Locale

/** Removes credential material from serialized download metadata before it reaches Room. */
internal object DownloadCredentialSanitizer {
    fun sanitizeSerializedMediaSource(serialized: String): String? = runCatching {
        val original = Json.parseToJsonElement(serialized) as? JsonObject ?: return null
        val sanitized = original.sanitize() ?: return null
        if (sanitized == original) serialized else sanitized.toString()
    }.getOrNull()

    private fun JsonElement.sanitize(): JsonElement? = when (this) {
        is JsonObject -> sanitizeObject()
        is JsonArray -> sanitizeArray()
        is JsonPrimitive -> sanitizeStringValue()
    }

    private fun JsonObject.sanitizeObject(): JsonObject? {
        val sanitized = linkedMapOf<String, JsonElement>()
        for ((key, value) in this) {
            sanitized[key] = if (key.isCredentialBearingFieldName()) {
                value.withCredentialRemoved()
            } else {
                value.sanitize() ?: return null
            }
        }
        return JsonObject(sanitized)
    }

    private fun JsonArray.sanitizeArray(): JsonArray? {
        val sanitized = ArrayList<JsonElement>(size)
        for (element in this) {
            sanitized += element.sanitize() ?: return null
        }
        return JsonArray(sanitized)
    }

    private fun JsonPrimitive.sanitizeStringValue(): JsonPrimitive? {
        if (!isString) return this
        val original = contentOrNull ?: return this
        val parsed = original.toHttpUrlOrNull()
            ?: return if (original.hasRawCredentialCarrier()) null else this
        val sanitizedBuilder = parsed.newBuilder()
            .username("")
            .password("")
            .fragment(null)
            .query(null)
        repeat(parsed.querySize) { index ->
            val name = parsed.queryParameterName(index)
            if (!name.isCredentialBearingFieldName()) {
                sanitizedBuilder.addQueryParameter(name, parsed.queryParameterValue(index))
            }
        }
        val sanitized = sanitizedBuilder.build().toString()
        return if (sanitized == original) this else JsonPrimitive(sanitized)
    }

    private fun JsonElement.withCredentialRemoved(): JsonElement = when (this) {
        is JsonObject -> JsonObject(emptyMap())
        is JsonArray -> JsonArray(emptyList())
        is JsonPrimitive -> if (isString) JsonPrimitive("") else JsonNull
    }

    private fun String.isCredentialBearingFieldName(): Boolean {
        val normalized = lowercase(Locale.ROOT).filter(Char::isLetterOrDigit)
        return normalized in SENSITIVE_FIELD_NAMES ||
            SENSITIVE_FIELD_SUFFIXES.any { suffix -> normalized.endsWith(suffix) } ||
            SENSITIVE_FIELD_FRAGMENTS.any { fragment -> normalized.contains(fragment) }
    }

    private fun String.hasRawCredentialCarrier(): Boolean {
        val normalizedSeparators = lowercase(Locale.ROOT)
            .replace("%5f", "_")
            .replace("%2d", "-")
        return RAW_CREDENTIAL_CARRIER.containsMatchIn(normalizedSeparators)
    }

    private val RAW_CREDENTIAL_CARRIER = Regex(
        """(?:^|[?&;\s])(?:(?:api[_-]?key|""" +
            """sig|[a-z0-9_-]*(?:credential|password|secret|signature|signed|token)[a-z0-9_-]*)\s*=""" +
            """|(?:authorization|cookie|set[_-]?cookie)\s*:)""",
    )
    private val SENSITIVE_FIELD_NAMES = setOf("apikey", "cookie", "setcookie", "sig")
    private val SENSITIVE_FIELD_SUFFIXES = setOf("header", "headers")
    private val SENSITIVE_FIELD_FRAGMENTS = setOf(
        "authorization",
        "credential",
        "password",
        "secret",
        "signature",
        "signed",
        "token",
    )
}
