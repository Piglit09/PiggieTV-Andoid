@file:Suppress("MaximumLineLength")

package org.jellyfin.mobile.app

import android.content.Context
import android.content.SharedPreferences
import io.mockk.every
import io.mockk.mockk
import org.jellyfin.mobile.utils.Constants
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class LibraryServerBaseUrlPolicyTest {
    @Test
    fun `credential-bearing library server URLs are rejected before persistence`() {
        assertNull(normalizeLibraryServerBaseUrl("https://u:p@host.example/opds"))
        assertNull(normalizeLibraryServerBaseUrl("https://host.example/opds?token=secret"))
        assertNull(normalizeLibraryServerBaseUrl("https://host.example/opds#credential"))
        assertNull(normalizeLibraryServerBaseUrl("https://[broken.invalid/opds?token=secret"))
    }

    @Test
    fun `safe library server URLs are canonicalized without adding secret material`() {
        assertEquals("", normalizeLibraryServerBaseUrl("  "))
        assertEquals("https://host.example/opds", normalizeLibraryServerBaseUrl(" https://HOST.example/opds/ "))
        assertEquals("http://192.168.1.2:8080/opds", normalizeLibraryServerBaseUrl("http://192.168.1.2:8080/opds"))
    }

    @Test
    fun `legacy unsafe preference is synchronously cleaned on construction and read`() {
        val source = sourceFile(APP_PREFERENCES_SOURCE).toFile().readText()
        val propertySource = source
            .substringAfter("var libraryServerBaseUrl")
            .substringBefore("var libraryUsername")

        assertTrue(source.contains("init {\n        readAndCleanLibraryServerBaseUrl()\n    }"))
        assertTrue(propertySource.contains("normalizeLibraryServerBaseUrl(value)"))
        assertTrue(source.contains("val committed = editor.commit()"))
        assertTrue(source.contains("check(committed && verified)"))
        assertFalse(propertySource.contains("putString(Constants.PREF_LIBRARY_SERVER_BASE_URL, value"))
    }

    @Test
    fun `failed legacy credential URL removal fails closed`() {
        val context = mockk<Context>()
        val ordinaryPreferences = mockk<SharedPreferences>()
        val securePreferences = mockk<SharedPreferences>(relaxed = true)
        val editor = mockk<SharedPreferences.Editor>()
        every { context.packageName } returns "org.jellyfin.mobile"
        every { context.applicationContext } returns context
        every {
            context.getSharedPreferences("org.jellyfin.mobile_preferences", Context.MODE_PRIVATE)
        } returns ordinaryPreferences
        every {
            context.getSharedPreferences("library_secure_credentials", Context.MODE_PRIVATE)
        } returns securePreferences
        every {
            ordinaryPreferences.getString(
                Constants.PREF_LIBRARY_SERVER_BASE_URL,
                Constants.PIGGIETV_LIBRARY_DEFAULT_URL,
            )
        } returns "https://u:p@host.example/opds?token=secret#fragment"
        every { ordinaryPreferences.edit() } returns editor
        every { editor.remove(Constants.PREF_LIBRARY_SERVER_BASE_URL) } returns editor
        every { editor.commit() } returns false
        every { ordinaryPreferences.contains(Constants.PREF_LIBRARY_SERVER_BASE_URL) } returns true

        assertThrows(IllegalStateException::class.java) { AppPreferences(context) }
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
        const val APP_PREFERENCES_SOURCE = "src/main/java/org/jellyfin/mobile/app/AppPreferences.kt"
    }
}
