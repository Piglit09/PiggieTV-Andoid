package org.jellyfin.mobile.signup

import io.kotest.matchers.shouldBe
import org.jellyfin.mobile.utils.Constants
import org.junit.jupiter.api.Test

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
}
