package org.jellyfin.mobile.signup

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.jellyfin.mobile.utils.Constants
import org.json.JSONObject
import java.io.IOException

class NativeSignupRepository(private val okHttpClient: OkHttpClient) {
    suspend fun createUser(serverUrl: String, signupRequest: NativeSignupRequest): NativeSignupResult = withContext(Dispatchers.IO) {
        val payload = JSONObject()
            .put("email", signupRequest.email.trim())
            .put("username", signupRequest.username.trim())
            .put("password", signupRequest.password)
            .put("confirmPassword", signupRequest.confirmPassword)
            .put("verificationBaseUrl", signupVerificationBaseUrl(serverUrl))

        val request = Request.Builder()
            .url(signupCreateUserUrl(serverUrl))
            .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()

        okHttpClient.newCall(request).execute().use { response ->
            val responseText = response.body?.string().orEmpty()
            val json = responseText
                .takeIf(String::isNotBlank)
                ?.let { runCatching { JSONObject(it) }.getOrNull() }
            val message = json?.optionalString("message")

            if (!response.isSuccessful) {
                throw NativeSignupException(message ?: "PiggieTV signup failed with HTTP ${response.code}.")
            }

            val result = NativeSignupResult(
                ok = json?.optBoolean("ok", true) ?: true,
                message = message ?: DEFAULT_SUCCESS_MESSAGE,
                userId = json?.optionalString("userId"),
                username = json?.optionalString("username"),
            )

            if (!result.ok) {
                throw NativeSignupException(result.message)
            }

            result
        }
    }

    private fun JSONObject.optionalString(name: String): String? = optString(name)
        .takeIf { value -> value.isNotBlank() && value != "null" }

    private companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        const val DEFAULT_SUCCESS_MESSAGE = "Check your email for the PiggieTV verification link."
    }
}

data class NativeSignupRequest(
    val email: String,
    val username: String,
    val password: String,
    val confirmPassword: String,
)

data class NativeSignupResult(
    val ok: Boolean,
    val message: String,
    val userId: String?,
    val username: String?,
)

class NativeSignupException(message: String) : IOException(message)

internal fun signupCreateUserUrl(serverUrl: String): String {
    val baseUrl = serverUrl.trim().trimEnd('/')
    return "$baseUrl${Constants.PIGGIETV_SIGNUP_CREATE_USER_PATH}"
}

internal fun signupVerificationBaseUrl(serverUrl: String): String {
    val baseUrl = serverUrl.trim().trimEnd('/')
    return "$baseUrl${Constants.PIGGIETV_SIGNUP_WEB_PATH}"
}
