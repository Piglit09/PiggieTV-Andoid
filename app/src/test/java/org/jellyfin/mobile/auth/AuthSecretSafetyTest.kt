package org.jellyfin.mobile.auth

import io.kotest.matchers.string.shouldNotContain
import org.jellyfin.mobile.feature.library.OpdsAuthConfig
import org.jellyfin.mobile.signup.NativePasswordResetConfirmRequest
import org.jellyfin.mobile.signup.NativeSignupRequest
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class AuthSecretSafetyTest {
    @Test
    fun `credential request diagnostics redact every secret`() {
        val marker = "PTV_SECRET_MARKER_827451"

        NativeSignupRequest(marker, marker, marker, marker).toString() shouldNotContain marker
        NativePasswordResetConfirmRequest(marker, marker, marker, marker).toString() shouldNotContain marker
        OpdsAuthConfig(marker, marker, marker).toString() shouldNotContain marker
    }

    @Test
    fun `passwords and reset codes are excluded from compose saved state`() {
        val source = nativeHomeScreenSource().toFile().readText()
        val prohibited = Regex(
            "var\\s+(password|confirmPassword|resetCode)\\s+by\\s+rememberSaveable",
        )

        assertFalse(prohibited.containsMatchIn(source))
        assertTrue(Regex("var\\s+password\\s+by\\s+remember\\(").containsMatchIn(source))
        assertTrue(Regex("var\\s+resetCode\\s+by\\s+remember\\(").containsMatchIn(source))
    }

    private fun nativeHomeScreenSource(): Path {
        val workingDirectory = Path.of(System.getProperty("user.dir"))
        val fromRoot = workingDirectory.resolve(SOURCE_FROM_ROOT)
        if (Files.isRegularFile(fromRoot)) return fromRoot

        val fromApp = workingDirectory.resolve(SOURCE_FROM_APP)
        check(Files.isRegularFile(fromApp)) {
            "NativeHomeScreen source was not found for the saved-state regression test."
        }
        return fromApp
    }

    private companion object {
        const val SOURCE_FROM_ROOT =
            "app/src/main/java/org/jellyfin/mobile/ui/screens/home/NativeHomeScreen.kt"
        const val SOURCE_FROM_APP =
            "src/main/java/org/jellyfin/mobile/ui/screens/home/NativeHomeScreen.kt"
    }
}
