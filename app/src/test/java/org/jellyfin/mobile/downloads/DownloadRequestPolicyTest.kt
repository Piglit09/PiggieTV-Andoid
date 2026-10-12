package org.jellyfin.mobile.downloads

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class DownloadRequestPolicyTest {
    @Test
    fun `download target strips every authentication query spelling before persistence`() {
        val target = AuthenticatedDownloadTarget.parse(
            "https://media.example.test/jellyfin/Items/$ITEM_ID/Download" +
                "?api%5Fkey=secret&X-Emby-Token=other&quality=original#token-fragment",
        )

        target?.itemId shouldBe ITEM_ID
        target?.url?.queryParameterNames shouldBe setOf("quality")
        target?.url?.queryParameter("quality") shouldBe "original"
        target?.url?.fragment shouldBe null
        target.toString().contains("secret") shouldBe false
    }

    @Test
    fun `download target rejects malformed and non-Jellyfin download paths`() {
        AuthenticatedDownloadTarget.parse("not a URL?api_key=secret") shouldBe null
        AuthenticatedDownloadTarget.parse("https://media.example.test/Items/$ITEM_ID/Images/Primary") shouldBe null
        AuthenticatedDownloadTarget.parse(
            "https://secret@media.example.test/Items/$ITEM_ID/Download",
        ) shouldBe null
    }

    @Test
    fun `signed and unknown acquisition queries are rejected before download persistence`() {
        listOf(
            "X-Amz-Credential=credential&X-Amz-Signature=secret-signature",
            "sig=azure-sas-secret&se=2030-01-01",
            "X-Goog-Credential=credential&X-Goog-Signature=secret-signature",
            "future-signed-key=secret",
        ).forEach { query ->
            AuthenticatedDownloadTarget.parse(
                "https://media.example.test/Items/$ITEM_ID/Download?$query",
            ) shouldBe null
        }
    }

    @Test
    fun `download target validation precedes download manager and database access`() {
        val source = sourceFile(DOWNLOAD_UTILS_SOURCE).toFile().readText()
        val validationIndex = source.indexOf("AuthenticatedDownloadTarget.parse(downloadURL)")

        (validationIndex >= 0) shouldBe true
        (validationIndex < source.indexOf("private val downloadDao")) shouldBe true
        (validationIndex < source.indexOf("getDownloadTracker()")) shouldBe true
    }

    @Test
    fun `download filename cannot traverse out of the selected directory`() {
        "../../Movies/example.mkv".toSafeDownloadFileName() shouldBe "example.mkv"
        "..\\Secrets\\example.mkv".toSafeDownloadFileName() shouldBe "example.mkv"
        "..".toSafeDownloadFileName() shouldBe null
        "\u0000secret".toSafeDownloadFileName() shouldBe null
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
        const val ITEM_ID = "0123456789abcdef0123456789abcdef"
        const val DOWNLOAD_UTILS_SOURCE = "src/main/java/org/jellyfin/mobile/downloads/DownloadUtils.kt"
    }
}
