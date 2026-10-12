package org.jellyfin.mobile.signup

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.jellyfin.mobile.utils.Constants
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class NativeSignupRepositoryTest {
    @Test
    fun `signup create user endpoint uses selected server`() {
        signupCreateUserUrl("https://piggietv.com/") shouldBe
            "${Constants.PIGGIETV_DEFAULT_SERVER_URL}${Constants.PIGGIETV_SIGNUP_CREATE_USER_PATH}"
    }

    @Test
    fun `signup verification url keeps web app signup route`() {
        signupVerificationBaseUrl("https://piggietv.com/") shouldBe Constants.PIGGIETV_SIGNUP_URL
    }

    @Test
    fun `password reset endpoints use selected server`() {
        signupPasswordResetRequestUrl("https://testing.piggietv.com/") shouldBe
            "${Constants.PIGGIETV_TESTING_SERVER_URL}${Constants.PIGGIETV_SIGNUP_PASSWORD_RESET_REQUEST_PATH}"
        signupPasswordResetConfirmUrl("https://testing.piggietv.com/") shouldBe
            "${Constants.PIGGIETV_TESTING_SERVER_URL}${Constants.PIGGIETV_SIGNUP_PASSWORD_RESET_CONFIRM_PATH}"
    }

    @Test
    fun `untrusted server messages cannot echo submitted secrets into results or errors`() {
        val echoedSecret = "password-and-reset-code-secret"
        val responseBody = """{"ok":true,"message":"$echoedSecret","username":"$echoedSecret"}"""

        val success = classifyNativeSignupResponse(
            statusCode = 200,
            responseBody = responseBody,
            successMessage = "Safe success message.",
            failureMessage = "Safe failure message.",
        )
        val failure = shouldThrow<NativeSignupException> {
            classifyNativeSignupResponse(
                statusCode = 400,
                responseBody = responseBody,
                successMessage = "Safe success message.",
                failureMessage = "Safe failure message.",
            )
        }

        success.message shouldBe "Safe success message."
        success.toString().contains(echoedSecret) shouldBe false
        failure.message shouldBe "Safe failure message."
        failure.toString().contains(echoedSecret) shouldBe false
    }

    @Test
    fun `signup and reset UI never saves raw repository messages`() {
        val source = sourceFile(NATIVE_HOME_SCREEN_SOURCE).toFile().readText()
        val resetScreen = source
            .substringAfter("private fun NativePasswordResetScreen")
            .substringBefore("private fun NativeSignupScreen")
        val signupScreen = source
            .substringAfter("private fun NativeSignupScreen")
            .substringBefore("private fun HomeContent")

        resetScreen.contains("error.message") shouldBe false
        resetScreen.contains("result.message") shouldBe false
        signupScreen.contains("error.message") shouldBe false
        signupScreen.contains("result.message") shouldBe false
    }

    private fun sourceFile(relativePath: String): Path {
        val workingDirectory = Path.of(System.getProperty("user.dir"))
        val fromRoot = workingDirectory.resolve("app").resolve(relativePath)
        if (Files.isRegularFile(fromRoot)) return fromRoot

        val fromApp = workingDirectory.resolve(relativePath)
        check(Files.isRegularFile(fromApp)) { "Source file was not found: $relativePath" }
        return fromApp
    }

    private companion object {
        const val NATIVE_HOME_SCREEN_SOURCE =
            "src/main/java/org/jellyfin/mobile/ui/screens/home/NativeHomeScreen.kt"
    }
}
