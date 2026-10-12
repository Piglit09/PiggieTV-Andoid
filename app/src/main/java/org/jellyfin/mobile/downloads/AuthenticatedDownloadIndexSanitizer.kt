package org.jellyfin.mobile.downloads

import android.net.Uri
import androidx.media3.database.DatabaseProvider
import androidx.media3.exoplayer.offline.DefaultDownloadIndex
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadProgress
import androidx.media3.exoplayer.offline.DownloadRequest
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jellyfin.mobile.auth.transport.stripAuthenticationQueryParameters
import timber.log.Timber

/**
 * One-time, idempotent cleanup of requests persisted by Media3 in exoplayer_internal.db.
 *
 * Requests created before authenticated download ownership existed must not be allowed to resume.
 * Completed rows are retained when their URI can be made persistence-safe so already-downloaded
 * media remains discoverable. No URI or request identifier is ever written to diagnostics.
 */
internal class AuthenticatedDownloadIndexSanitizer(private val downloadIndex: LegacyMedia3DownloadIndex) {
    constructor(databaseProvider: DatabaseProvider) : this(
        downloadIndex = DefaultLegacyMedia3DownloadIndex(databaseProvider),
    )

    @Volatile
    private var cleanupSucceeded = false

    /** Runs during application startup. Failure is remembered so DownloadManager can fail closed. */
    @Synchronized
    fun sanitizeAtStartup(): Boolean {
        if (cleanupSucceeded) return true

        return runCatching {
            var removedCount = 0
            var rewrittenCount = 0

            downloadIndex.readAll().forEach { row ->
                when (
                    val action = LegacyMedia3DownloadPolicy.evaluate(
                        persistedUri = row.persistedUri,
                        state = row.state,
                        hasAuthenticatedOwner = row.hasAuthenticatedOwner,
                    )
                ) {
                    LegacyMedia3DownloadAction.Keep -> Unit

                    LegacyMedia3DownloadAction.Remove -> {
                        downloadIndex.remove(row.id)
                        removedCount++
                    }

                    is LegacyMedia3DownloadAction.Rewrite -> {
                        downloadIndex.rewriteUri(row.id, action.sanitizedUri)
                        rewrittenCount++
                    }
                }
            }

            cleanupSucceeded = true
            Timber.i(
                "Media3 download credential cleanup completed: %d removed, %d rewritten",
                removedCount,
                rewrittenCount,
            )
            true
        }.getOrElse { failure ->
            Timber.e(
                "Media3 download credential cleanup failed (%s); downloads remain disabled",
                failure.javaClass.simpleName,
            )
            false
        }
    }

    /** Prevents DownloadManager from opening the index until credential cleanup has succeeded. */
    fun requireSanitized() {
        check(sanitizeAtStartup()) { "Media3 download credential cleanup did not complete." }
    }
}

internal data class PersistedMedia3DownloadRow(
    val id: String,
    val persistedUri: String,
    val state: Int,
    val hasAuthenticatedOwner: Boolean,
)

internal interface LegacyMedia3DownloadIndex {
    fun readAll(): List<PersistedMedia3DownloadRow>

    fun remove(id: String)

    fun rewriteUri(id: String, sanitizedUri: String)
}

private class DefaultLegacyMedia3DownloadIndex(databaseProvider: DatabaseProvider) : LegacyMedia3DownloadIndex {
    private val downloadIndex = DefaultDownloadIndex(databaseProvider)

    override fun readAll(): List<PersistedMedia3DownloadRow> = downloadIndex.getDownloads().use { cursor ->
        buildList {
            while (cursor.moveToNext()) {
                val download = cursor.download
                add(
                    PersistedMedia3DownloadRow(
                        id = download.request.id,
                        persistedUri = download.request.uri.toString(),
                        state = download.state,
                        hasAuthenticatedOwner = AuthenticatedDownloadOwnership.decode(
                            download.request.customCacheKey,
                        ) != null,
                    ),
                )
            }
        }
    }

    override fun remove(id: String) {
        downloadIndex.removeDownload(id)
    }

    override fun rewriteUri(id: String, sanitizedUri: String) {
        val download = downloadIndex.getDownload(id) ?: return
        downloadIndex.putDownload(download.copyWithUri(sanitizedUri))
    }

    private fun Download.copyWithUri(sanitizedUri: String): Download {
        val originalRequest = request
        val requestBuilder = DownloadRequest.Builder(originalRequest.id, Uri.parse(sanitizedUri))
            .setMimeType(originalRequest.mimeType)
            .setStreamKeys(originalRequest.streamKeys)
            .setData(originalRequest.data.copyOf())

        originalRequest.keySetId?.let { requestBuilder.setKeySetId(it.copyOf()) }
        originalRequest.customCacheKey?.let(requestBuilder::setCustomCacheKey)
        originalRequest.byteRange?.let { requestBuilder.setByteRange(it.offset, it.length) }
        originalRequest.timeRange?.let {
            requestBuilder.setTimeRange(it.startPositionUs, it.durationUs)
        }

        val preservedProgress = DownloadProgress().also {
            it.bytesDownloaded = bytesDownloaded
            it.percentDownloaded = percentDownloaded
        }
        return Download(
            requestBuilder.build(),
            state,
            startTimeMs,
            updateTimeMs,
            contentLength,
            stopReason,
            failureReason,
            preservedProgress,
        )
    }
}

internal sealed interface LegacyMedia3DownloadAction {
    data object Keep : LegacyMedia3DownloadAction

    data object Remove : LegacyMedia3DownloadAction

    data class Rewrite(val sanitizedUri: String) : LegacyMedia3DownloadAction
}

/** Pure policy kept separate from the Android database adapter for deterministic unit coverage. */
internal object LegacyMedia3DownloadPolicy {
    fun evaluate(persistedUri: String, state: Int, hasAuthenticatedOwner: Boolean): LegacyMedia3DownloadAction {
        if (!hasAuthenticatedOwner && state != Download.STATE_COMPLETED) {
            return LegacyMedia3DownloadAction.Remove
        }

        val sanitizedUri = persistedUri.toPersistenceSafeHttpUrl()
            ?: return LegacyMedia3DownloadAction.Remove
        return if (sanitizedUri == persistedUri) {
            LegacyMedia3DownloadAction.Keep
        } else {
            LegacyMedia3DownloadAction.Rewrite(sanitizedUri)
        }
    }

    private fun String.toPersistenceSafeHttpUrl(): String? {
        val parsed = toHttpUrlOrNull() ?: return null
        val sanitized = parsed.newBuilder()
            .username("")
            .password("")
            .fragment(null)
            .build()
            .stripAuthenticationQueryParameters()
        return sanitized.takeIf { url -> url.hasOnlyPersistenceSafeDownloadQuery() }?.toString()
    }
}
