package org.jellyfin.mobile.signup

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.jellyfin.mobile.auth.transport.AuthTransportRealm
import org.jellyfin.mobile.auth.transport.authTransportRealm
import org.jellyfin.mobile.utils.Constants
import org.json.JSONObject
import java.io.IOException

class NativeSignupRepository(private val okHttpClient: OkHttpClient) {
    suspend fun createUser(serverUrl: String, signupRequest: NativeSignupRequest): NativeSignupResult = withContext(
        Dispatchers.IO,
    ) {
        val payload = JSONObject()
            .put("email", signupRequest.email.trim())
            .put("username", signupRequest.username.trim())
            .put("password", signupRequest.password)
            .put("confirmPassword", signupRequest.confirmPassword)
            .put("verificationBaseUrl", signupVerificationBaseUrl(serverUrl))

        postSignupRequest(
            url = signupCreateUserUrl(serverUrl),
            payload = payload,
            successMessage = DEFAULT_SUCCESS_MESSAGE,
            failureMessage = DEFAULT_SIGNUP_FAILURE_MESSAGE,
        )
    }

    suspend fun requestPasswordReset(serverUrl: String, email: String): NativeSignupResult = withContext(
        Dispatchers.IO,
    ) {
        postSignupRequest(
            url = signupPasswordResetRequestUrl(serverUrl),
            payload = JSONObject().put("email", email.trim()),
            successMessage = DEFAULT_RESET_REQUEST_MESSAGE,
            failureMessage = DEFAULT_RESET_REQUEST_FAILURE_MESSAGE,
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
            successMessage = DEFAULT_RESET_CONFIRM_MESSAGE,
            failureMessage = DEFAULT_RESET_CONFIRM_FAILURE_MESSAGE,
        )
    }

    private fun postSignupRequest(
        url: String,
        payload: JSONObject,
        successMessage: String,
        failureMessage: String,
    ): NativeSignupResult {
        val request = Request.Builder()
            .url(url)
            .authTransportRealm(AuthTransportRealm.EXCLUDED)
            .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()

        return okHttpClient.newCall(request).execute().use { response ->
            val responseText = response.body?.string().orEmpty()
            classifyNativeSignupResponse(
                statusCode = response.code,
                responseBody = responseText,
                successMessage = successMessage,
                failureMessage = failureMessage,
            )
        }
    }

    private companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        const val DEFAULT_SUCCESS_MESSAGE = "Check your email for the PiggieTV verification link."
        const val DEFAULT_RESET_REQUEST_MESSAGE = "Check your email for the PiggieTV password reset code."
        const val DEFAULT_RESET_CONFIRM_MESSAGE = "Your PiggieTV password has been reset."
        const val DEFAULT_SIGNUP_FAILURE_MESSAGE = "PiggieTV signup failed. Please check your details and try again."
        const val DEFAULT_RESET_REQUEST_FAILURE_MESSAGE =
            "PiggieTV could not request a password reset. Please try again."
        const val DEFAULT_RESET_CONFIRM_FAILURE_MESSAGE =
            "PiggieTV could not reset your password. Please try again."
    }
}

/** Maps an untrusted response body to fixed operation-owned messages only. */
internal fun classifyNativeSignupResponse(
    statusCode: Int,
    responseBody: String,
    successMessage: String,
    failureMessage: String,
): NativeSignupResult {
    val serverAccepted = responseBody
        .takeIf(String::isNotBlank)
        ?.let { body -> runCatching { JSONObject(body).optBoolean("ok", true) }.getOrDefault(true) }
        ?: true
    if (statusCode !in HTTP_SUCCESS_MIN..HTTP_SUCCESS_MAX || !serverAccepted) {
        throw NativeSignupException(failureMessage)
    }
    return NativeSignupResult(ok = true, message = successMessage)
}

data class NativeSignupRequest(
    val email: String,
    val username: String,
    val password: String,
    val confirmPassword: String,
) {
    override fun toString(): String =
        "NativeSignupRequest(email=[REDACTED], username=[REDACTED], password=[REDACTED], confirmPassword=[REDACTED])"
}

data class NativePasswordResetConfirmRequest(
    val email: String,
    val code: String,
    val password: String,
    val confirmPassword: String,
) {
    override fun toString(): String = "NativePasswordResetConfirmRequest(email=[REDACTED], code=[REDACTED], " +
        "password=[REDACTED], confirmPassword=[REDACTED])"
}

data class NativeSignupResult(val ok: Boolean, val message: String)

class NativeSignupException(message: String) : IOException(message)

private const val HTTP_SUCCESS_MIN = 200
private const val HTTP_SUCCESS_MAX = 299

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
