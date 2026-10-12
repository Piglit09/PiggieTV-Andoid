package org.jellyfin.mobile.ui.screens.home

import org.jellyfin.mobile.auth.AuthFailureCategory
import org.jellyfin.mobile.auth.AuthMethod
import org.jellyfin.mobile.auth.MobileAuthState
import org.jellyfin.mobile.auth.MobileAuthenticationResult
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NativeAuthPresentationTest {
    @Test
    fun `restore connectivity failures preserve the saved session recovery path`() {
        listOf(
            AuthFailureCategory.NETWORK_UNAVAILABLE,
            AuthFailureCategory.SERVER_UNAVAILABLE,
        ).forEach { category ->
            val result = MobileAuthenticationResult.RecoverableFailure(category)

            assertInstanceOf(
                NativeAuthPresentation.RecoveryFailure::class.java,
                result.presentation(NativeAuthRequest.RESTORE),
            )
        }
    }

    @Test
    fun `interactive connectivity failures stay on login`() {
        val result = MobileAuthenticationResult.RecoverableFailure(
            AuthFailureCategory.NETWORK_UNAVAILABLE,
        )

        assertInstanceOf(
            NativeAuthPresentation.LoginFailure::class.java,
            result.presentation(NativeAuthRequest.PASSWORD),
        )
        assertInstanceOf(
            NativeAuthPresentation.LoginFailure::class.java,
            result.presentation(NativeAuthRequest.QUICK_CONNECT),
        )
    }

    @Test
    fun `storage failure is recoverable without treating it as login rejection`() {
        assertInstanceOf(
            NativeAuthPresentation.RecoveryFailure::class.java,
            MobileAuthenticationResult.SecureStorageFailure.presentation(NativeAuthRequest.RESTORE),
        )
    }

    @Test
    fun `superseded and duplicate Quick Connect results never replace newer UI`() {
        assertEquals(
            NativeAuthPresentation.Ignore,
            MobileAuthenticationResult.Superseded.presentation(NativeAuthRequest.PASSWORD),
        )
        assertEquals(
            NativeAuthPresentation.Ignore,
            MobileAuthenticationResult.QuickConnectAlreadyRunning.presentation(NativeAuthRequest.QUICK_CONNECT),
        )
    }

    @Test
    fun `user cancellation clears Quick Connect without an error message`() {
        val result = MobileAuthenticationResult.RecoverableFailure(
            AuthFailureCategory.QUICK_CONNECT_CANCELLED,
        )
        val presentation = assertInstanceOf(
            NativeAuthPresentation.LoginFailure::class.java,
            result.presentation(NativeAuthRequest.QUICK_CONNECT),
        )

        assertNull(presentation.message)
    }

    @Test
    fun `only bounded Quick Connect phases are reported in progress`() {
        assertTrue(MobileAuthState.QuickConnectRequesting.isQuickConnectInProgress())
        assertTrue(MobileAuthState.QuickConnectWaiting("123456", 10L).isQuickConnectInProgress())
        assertTrue(MobileAuthState.Authenticating(AuthMethod.QUICK_CONNECT).isQuickConnectInProgress())
        assertFalse(MobileAuthState.Authenticating(AuthMethod.PASSWORD).isQuickConnectInProgress())
        assertFalse(MobileAuthState.Unauthenticated.isQuickConnectInProgress())
    }
}
