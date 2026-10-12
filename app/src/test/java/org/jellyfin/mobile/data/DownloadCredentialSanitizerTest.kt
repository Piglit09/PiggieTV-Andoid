@file:Suppress("MaximumLineLength")

package org.jellyfin.mobile.data

import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.jellyfin.mobile.data.entity.LocalJellyfinMediaSourceConverter
import org.jellyfin.mobile.player.source.LocalJellyfinMediaSource
import org.jellyfin.sdk.model.api.IsoType
import org.jellyfin.sdk.model.api.MediaProtocol
import org.jellyfin.sdk.model.api.MediaSourceInfo
import org.jellyfin.sdk.model.api.MediaSourceType
import org.jellyfin.sdk.model.api.MediaStreamProtocol
import org.jellyfin.sdk.model.api.TransportStreamTimestamp
import org.jellyfin.sdk.model.api.Video3dFormat
import org.jellyfin.sdk.model.api.VideoType
import org.junit.jupiter.api.Test
import java.util.UUID

class DownloadCredentialSanitizerTest {
    @Test
    fun `legacy serialized download URLs lose all credential carriers`() {
        val credentialUrl = "https://user:pass@media.test/Items/id/Download" +
            "?api_key=secret&OpenToken=other&quality=1#token"
        val source = """
            {
              "downloadedFileUri":"$credentialUrl",
              "sourceInfo":{"mediaStreams":[{"deliveryUrl":"https://media.test/sub?X-Emby-Token=secret"}]},
              "name":"Basic Instinct"
            }
        """.trimIndent()

        val sanitized = requireNotNull(DownloadCredentialSanitizer.sanitizeSerializedMediaSource(source))

        sanitized.contains("secret") shouldBe false
        sanitized.contains("user:pass") shouldBe false
        sanitized.contains("quality=1") shouldBe true
        sanitized.contains("Basic Instinct") shouldBe true
    }

    @Test
    fun `malformed serialized data is never overwritten`() {
        DownloadCredentialSanitizer.sanitizeSerializedMediaSource("not-json") shouldBe null
    }

    @Test
    fun `nested credential and header contents are removed regardless of spelling`() {
        val source = """
            {
              "sourceInfo": {
                "RequiredHttpHeaders": {
                  "Authorization": "Bearer header-secret",
                  "X-Custom": "safe-header"
                },
                "OpenToken": "open-secret",
                "nested": {
                  "api_key": "api-secret",
                  "credentialBlob": "credential-secret",
                  "safeValue": "preserved"
                }
              }
            }
        """.trimIndent()

        val sanitized = requireNotNull(DownloadCredentialSanitizer.sanitizeSerializedMediaSource(source))

        sanitized.contains("secret") shouldBe false
        sanitized.contains("preserved") shouldBe true
    }

    @Test
    fun `unparseable token bearing URL fails closed so migration can delete the row`() {
        val source = """{"remoteFileUri":"/Items/id/Download?api%5Fkey=secret"}"""

        DownloadCredentialSanitizer.sanitizeSerializedMediaSource(source) shouldBe null
    }

    @Test
    fun `signed query carriers are removed from Room download metadata`() {
        val source = """
            {
              "remoteFileUri":"https://media.test/Items/id/Download?X-Amz-Credential=credential&X-Amz-Signature=secret-signature&quality=original",
              "sourceInfo":{"path":"https://media.test/video?sig=sas-secret&future-signed-key=other&quality=original"}
            }
        """.trimIndent()

        val sanitized = requireNotNull(DownloadCredentialSanitizer.sanitizeSerializedMediaSource(source))

        sanitized.contains("X-Amz-Credential") shouldBe false
        sanitized.contains("X-Amz-Signature") shouldBe false
        sanitized.contains("secret-signature") shouldBe false
        sanitized.contains("sig=") shouldBe false
        sanitized.contains("future-signed-key") shouldBe false
        sanitized.contains("quality=original") shouldBe true
    }

    @Test
    fun `unparseable signed URL fails closed so Room row can be deleted`() {
        val source = """{"remoteFileUri":"/Items/id/Download?sig=secret-signature"}"""

        DownloadCredentialSanitizer.sanitizeSerializedMediaSource(source) shouldBe null
    }

    @Test
    fun `Room converter sanitizes newly persisted media source`() {
        val sourceInfo = mockk<MediaSourceInfo>(relaxed = true) {
            every { protocol } returns MediaProtocol.HTTP
            every { id } returns "10000000-0000-0000-0000-000000000001"
            every { encoderProtocol } returns MediaProtocol.HTTP
            every { type } returns MediaSourceType.DEFAULT
            every { videoType } returns VideoType.VIDEO_FILE
            every { isoType } returns IsoType.DVD
            every { video3dFormat } returns Video3dFormat.MVC
            every { timestamp } returns TransportStreamTimestamp.NONE
            every { transcodingSubProtocol } returns MediaStreamProtocol.HTTP
            every { path } returns "https://media.test/video?api_key=path-secret&quality=original"
            every { openToken } returns "open-secret"
            every { requiredHttpHeaders } returns mapOf(
                "Authorization" to "Bearer header-secret",
                "X-Custom" to "safe-header",
            )
        }
        val source = LocalJellyfinMediaSource(
            itemId = UUID.fromString("10000000-0000-0000-0000-000000000001"),
            item = null,
            sourceInfo = sourceInfo,
            playSessionId = "safe-play-session",
            localDirectoryUri = "/downloads/item",
            remoteFileUri = "https://user:pass@media.test/Items/id/Download?X-Emby-Token=url-secret&quality=1",
            downloadSize = 42L,
        )

        val persisted = LocalJellyfinMediaSourceConverter().fromLocalJellyfinMediaSource(source)

        persisted.contains("secret") shouldBe false
        persisted.contains("user:pass") shouldBe false
        persisted.contains("quality=1") shouldBe true
        persisted.contains("quality=original") shouldBe true

        val restored = LocalJellyfinMediaSourceConverter().toLocalJellyfinMediaSource(persisted)
        restored.sourceInfo.openToken shouldBe ""
        restored.sourceInfo.requiredHttpHeaders shouldBe emptyMap()
    }
}
