package org.jellyfin.mobile.downloads

import androidx.media3.exoplayer.offline.Download
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class LegacyMedia3DownloadPolicyTest {
    @Test
    fun `startup cleanup applies policy once and is idempotent for the process`() {
        val index = RecordingDownloadIndex(
            rows = listOf(
                row("legacy-active", SAFE_URI, Download.STATE_QUEUED, hasOwner = false),
                row(
                    "legacy-complete",
                    "$SAFE_URI&api_key=secret",
                    Download.STATE_COMPLETED,
                    hasOwner = false,
                ),
                row("owned-active", SAFE_URI, Download.STATE_DOWNLOADING, hasOwner = true),
            ),
        )
        val sanitizer = AuthenticatedDownloadIndexSanitizer(index)

        sanitizer.sanitizeAtStartup() shouldBe true
        sanitizer.sanitizeAtStartup() shouldBe true

        index.readCount shouldBe 1
        index.removedIds shouldBe listOf("legacy-active")
        index.rewrittenUris shouldBe mapOf(
            "legacy-complete" to SAFE_URI,
        )
    }

    @Test
    fun `download initialization guard remains closed when index cleanup fails`() {
        val sanitizer = AuthenticatedDownloadIndexSanitizer(
            object : LegacyMedia3DownloadIndex {
                override fun readAll(): List<PersistedMedia3DownloadRow> = error("database unavailable")

                override fun remove(id: String) = Unit

                override fun rewriteUri(id: String, sanitizedUri: String) = Unit
            },
        )

        sanitizer.sanitizeAtStartup() shouldBe false
        shouldThrow<IllegalStateException> {
            sanitizer.requireSanitized()
        }
    }

    @Test
    fun `completed legacy row is preserved with every URL credential carrier removed`() {
        val action = LegacyMedia3DownloadPolicy.evaluate(
            persistedUri = "https://user:pass@media.example.test/Items/id/Download" +
                "?api%5Fkey=secret&X-Emby-Token=other&quality=original#token-fragment",
            state = Download.STATE_COMPLETED,
            hasAuthenticatedOwner = false,
        )

        action shouldBe LegacyMedia3DownloadAction.Rewrite(
            "https://media.example.test/Items/id/Download?quality=original",
        )
    }

    @Test
    fun `every noncompleted legacy state is removed before DownloadManager can retry it`() {
        val noncompletedStates = listOf(
            Download.STATE_QUEUED,
            Download.STATE_STOPPED,
            Download.STATE_DOWNLOADING,
            Download.STATE_FAILED,
            Download.STATE_REMOVING,
            Download.STATE_RESTARTING,
        )

        noncompletedStates.forEach { state ->
            LegacyMedia3DownloadPolicy.evaluate(
                persistedUri = SAFE_URI,
                state = state,
                hasAuthenticatedOwner = false,
            ) shouldBe LegacyMedia3DownloadAction.Remove
        }
    }

    @Test
    fun `owned resumable row is retained only after its URL is persistence safe`() {
        LegacyMedia3DownloadPolicy.evaluate(
            persistedUri = "https://name:password@media.example.test/Items/id/Download?token=secret",
            state = Download.STATE_QUEUED,
            hasAuthenticatedOwner = true,
        ) shouldBe LegacyMedia3DownloadAction.Rewrite(
            "https://media.example.test/Items/id/Download",
        )

        LegacyMedia3DownloadPolicy.evaluate(
            persistedUri = SAFE_URI,
            state = Download.STATE_DOWNLOADING,
            hasAuthenticatedOwner = true,
        ) shouldBe LegacyMedia3DownloadAction.Keep
    }

    @Test
    fun `legacy signed and unknown download queries are removed before Media3 opens`() {
        listOf(
            "X-Amz-Credential=credential&X-Amz-Signature=secret-signature",
            "sig=azure-sas-secret&se=2030-01-01",
            "X-Goog-Signature=secret-signature",
            "future-signed-key=secret",
        ).forEach { query ->
            LegacyMedia3DownloadPolicy.evaluate(
                persistedUri = "https://media.example.test/Items/id/Download?$query",
                state = Download.STATE_COMPLETED,
                hasAuthenticatedOwner = true,
            ) shouldBe LegacyMedia3DownloadAction.Remove
        }
    }

    @Test
    fun `unparseable rows fail closed even when completed or marked owned`() {
        LegacyMedia3DownloadPolicy.evaluate(
            persistedUri = "not a safe HTTP URL?api_key=secret",
            state = Download.STATE_COMPLETED,
            hasAuthenticatedOwner = false,
        ) shouldBe LegacyMedia3DownloadAction.Remove

        LegacyMedia3DownloadPolicy.evaluate(
            persistedUri = "content://downloads/item?token=secret",
            state = Download.STATE_QUEUED,
            hasAuthenticatedOwner = true,
        ) shouldBe LegacyMedia3DownloadAction.Remove
    }

    private companion object {
        const val SAFE_URI = "https://media.example.test/Items/id/Download?quality=original"

        fun row(id: String, uri: String, state: Int, hasOwner: Boolean) =
            PersistedMedia3DownloadRow(id, uri, state, hasOwner)
    }

    private class RecordingDownloadIndex(private val rows: List<PersistedMedia3DownloadRow>) :
        LegacyMedia3DownloadIndex {
        var readCount = 0
            private set
        val removedIds = mutableListOf<String>()
        val rewrittenUris = mutableMapOf<String, String>()

        override fun readAll(): List<PersistedMedia3DownloadRow> {
            readCount++
            return rows
        }

        override fun remove(id: String) {
            removedIds += id
        }

        override fun rewriteUri(id: String, sanitizedUri: String) {
            rewrittenUris[id] = sanitizedUri
        }
    }
}
