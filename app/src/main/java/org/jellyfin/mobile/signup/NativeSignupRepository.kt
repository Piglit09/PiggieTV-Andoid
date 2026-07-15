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
    suspend fun createUser(serverUrl: String, signupRequest: NativeSignupRequest): NativeSignupResult = withContext(
        Dispatchers.IO
    ) {
        val payload = JSONObject()
            .put("email", signupRequest.email.trim())
            .put("username", signupRequest.username.trim())
            .put("password", signupRequest.password)
            .put("confirmPassword", signupRequest.confirmPassword)
            .put("verificationBaseUrl", signupVerificationBaseUrl(serverUrl))

        postSignupRequest(signupCreateUserUrl(serverUrl), payload, DEFAULT_SUCCESS_MESSAGE)
    }

    suspend fun requestPasswordReset(serverUrl: String, email: String): NativeSignupResult = withContext(
        Dispatchers.IO
    ) {
        postSignupRequest(
            url = signupPasswordResetRequestUrl(serverUrl),
            payload = JSONObject().put("email", email.trim()),
            defaultMessage = DEFAULT_RESET_REQUEST_MESSAGE,
        )
    }

    suspend fun confirmPasswordReset(
        serverUrl: String,
        resetRequest: NativePasswordResetConfirmRequest,
    ): NativeSignupResult = withContext(Dispatchers.IO) {
        val payload = JSONObject()
            .put("email", resetRequest.email.trim())
            .put("code", resetRequest.code.trim())
            .put("password", resetRequest.password)
            .put("confirmPassword", resetRequest.confirmPassword)

        postSignupRequest(
            url = signupPasswordResetConfirmUrl(serverUrl),
            payload = payload,
            defaultMessage = DEFAULT_RESET_CONFIRM_MESSAGE,
        )
    }

    private fun postSignupRequest(url: String, payload: JSONObject, defaultMessage: String): NativeSignupResult {
        val request = Request.Builder()
            .url(url)
            .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()

        return okHttpClient.newCall(request).execute().use { response ->
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
                message = message ?: defaultMessage,
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
        const val DEFAULT_RESET_REQUEST_MESSAGE = "Check your email for the PiggieTV password reset code."
        const val DEFAULT_RESET_CONFIRM_MESSAGE = "Your PiggieTV password has been reset."
    }
}

data class NativeSignupRequest(
    val email: String,
    val username: String,
    val password: String,
    val confirmPassword: String,
)

data class NativePasswordResetConfirmRequest(
    val email: String,
    val code: String,
    val password: String,
    val confirmPassword: String,
)

data class NativeSignupResult(val ok: Boolean, val message: String, val userId: String?, val username: String?,)

class NativeSignupException(message: String) : IOException(message)

internal fun signupCreateUserUrl(serverUrl: String): String {
    val baseUrl = serverUrl.trim().trimEnd('/')
    return "$baseUrl${Constants.PIGGIETV_SIGNUP_CREATE_USER_PATH}"
}

internal fun signupVerificationBaseUrl(serverUrl: String): String {
    val baseUrl = serverUrl.trim().trimEnd('/')
    return "$baseUrl${Constants.PIGGIETV_SIGNUP_WEB_PATH}"
}

internal fun signupPasswordResetRequestUrl(serverUrl: String): String {
    val baseUrl = serverUrl.trim().trimEnd('/')
    return "$baseUrl${Constants.PIGGIETV_SIGNUP_PASSWORD_RESET_REQUEST_PATH}"
}

internal fun signupPasswordResetConfirmUrl(serverUrl: String): String {
    val baseUrl = serverUrl.trim().trimEnd('/')
    return "$baseUrl${Constants.PIGGIETV_SIGNUP_PASSWORD_RESET_CONFIRM_PATH}"
}
