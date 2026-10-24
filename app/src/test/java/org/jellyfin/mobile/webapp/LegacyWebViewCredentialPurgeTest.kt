package org.jellyfin.mobile.webapp

import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class LegacyWebViewCredentialPurgeTest {
    @Test
    fun `startup purges dormant WebView credential stores`() {
        val source = sourceFile(APPLICATION_SOURCE_FROM_ROOT, APPLICATION_SOURCE_FROM_APP).toFile().readText()

        source shouldContain "WebStorage.getInstance().deleteAllData()"
        source shouldContain "clearHttpAuthUsernamePassword()"
        source shouldContain "CookieManager.getInstance().removeAllCookies"
    }

    @Test
    fun `WebView client never extracts or logs credential-bearing content`() {
        val client = sourceFile(CLIENT_SOURCE_FROM_ROOT, CLIENT_SOURCE_FROM_APP).toFile().readText()
        val chrome = sourceFile(CHROME_SOURCE_FROM_ROOT, CHROME_SOURCE_FROM_APP).toFile().readText()

        client shouldNotContain "jellyfin_credentials"
        client shouldNotContain "errorResponse.data"
        client shouldNotContain "request.url.toString()"
        client shouldNotContain "error.toString()"
        chrome shouldNotContain "consoleMessage.message()"
        chrome shouldNotContain "consoleMessage.sourceId()"
    }

    private fun sourceFile(fromRoot: String, fromApp: String): Path {
        val workingDirectory = Path.of(System.getProperty("user.dir"))
        return workingDirectory.resolve(fromRoot).takeIf { path -> Files.isRegularFile(path) }
            ?: workingDirectory.resolve(fromApp).also { path ->
                check(Files.isRegularFile(path)) { "Expected source file was not found." }
            }
    }

    private companion object {
        const val APPLICATION_SOURCE_FROM_ROOT =
            "app/src/main/java/org/jellyfin/mobile/JellyfinApplication.kt"
        const val APPLICATION_SOURCE_FROM_APP =
            "src/main/java/org/jellyfin/mobile/JellyfinApplication.kt"
        const val CLIENT_SOURCE_FROM_ROOT =
            "app/src/main/java/org/jellyfin/mobile/webapp/JellyfinWebViewClient.kt"
        const val CLIENT_SOURCE_FROM_APP =
            "src/main/java/org/jellyfin/mobile/webapp/JellyfinWebViewClient.kt"
        const val CHROME_SOURCE_FROM_ROOT =
            "app/src/main/java/org/jellyfin/mobile/webapp/JellyfinWebChromeClient.kt"
        const val CHROME_SOURCE_FROM_APP =
            "src/main/java/org/jellyfin/mobile/webapp/JellyfinWebChromeClient.kt"
    }
}
