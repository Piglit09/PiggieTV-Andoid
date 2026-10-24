package org.jellyfin.mobile.feature.music.auto

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.jellyfin.mobile.app.AppPreferences
import org.jellyfin.mobile.feature.music.MusicItem
import org.jellyfin.mobile.feature.music.MusicPlaybackState
import org.jellyfin.mobile.feature.music.MusicRepeatMode
import org.jellyfin.sdk.model.UUID
import org.jellyfin.sdk.model.serializer.toUUIDOrNull
import timber.log.Timber
import java.security.MessageDigest

class PtvMusicAutoResumeStore(context: Context, private val appPreferences: AppPreferences) {
    private val sharedPreferences: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    internal fun currentLocalQueueOwner(): PtvMusicAutoQueueOwner? {
        val serverId = appPreferences.currentServerId ?: return null
        val userId = appPreferences.currentUserId ?: return null
        return PtvMusicAutoQueueOwner(serverId, userId)
    }

    @Suppress("FunctionExpressionBody")
    fun loadPlaybackState(): PtvMusicAutoResumeState? {
        val state = sharedPreferences.getString(KEY_PLAYBACK_STATE, null)
            ?.let(PtvMusicAutoResumeStateSerializer::decode)
            ?: return null
        val serverId = appPreferences.currentServerId
        val userId = appPreferences.currentUserId
        // Session restoration can temporarily have no selected local owner. Keep the queue
        // private and intact until an owner is available to prove whether it matches.
        if (serverId == null || userId == null) return null
        if (!state.belongsTo(serverId, userId)) {
            clearPlaybackState()
            return null
        }
        return state
    }

    fun savePlaybackState(
        state: PtvMusicAutoResumeState,
        expectedOwner: PtvMusicAutoQueueOwner,
        retainQueueProvenance: Boolean = true,
        selectedAuthoredIndex: Int? = null,
    ): Boolean {
        if (currentLocalQueueOwner() != expectedOwner) return false
        val previous = loadPlaybackState()
        val retainedSource = previous?.queueProvenance
            ?.takeIf { source -> retainQueueProvenance && source.queueSha256 == state.fullQueueSha256 }
            ?.let { source ->
                source.copy(
                    fullCurrentIndex = state.fullCurrentIndex,
                    selectedAuthoredIndex = if (source.kind == PtvMusicAutoQueueProvenance.PLAYLIST) {
                        selectedAuthoredIndex ?: source.selectedAuthoredIndex
                    } else {
                        null
                    },
                )
            }
        sharedPreferences.edit {
            putString(
                KEY_PLAYBACK_STATE,
                PtvMusicAutoResumeStateSerializer.encode(
                    state.withOwner(expectedOwner.serverId, expectedOwner.userId)
                        .copy(queueProvenance = retainedSource).trimmed(),
                ),
            )
        }
        return true
    }

    fun attachQueueProvenance(
        provenance: PtvMusicAutoQueueProvenance,
        playbackState: MusicPlaybackState,
        expectedOwner: PtvMusicAutoQueueOwner,
    ): Boolean {
        if (currentLocalQueueOwner() != expectedOwner || playbackState.queueOwner != expectedOwner) return false
        val saved = loadPlaybackState() ?: return false
        val snapshot = PtvMusicAutoResumeState.fromPlaybackState(playbackState) ?: return false
        val queueIdentityMatches = provenance.queueSha256 == snapshot.fullQueueSha256 &&
            saved.fullQueueSha256 == snapshot.fullQueueSha256
        val savedWindowMatches = saved.queueItemIds == snapshot.queueItemIds &&
            saved.currentIndex == snapshot.currentIndex
        if (!queueIdentityMatches || !savedWindowMatches) {
            return false
        }
        sharedPreferences.edit {
            putString(
                KEY_PLAYBACK_STATE,
                PtvMusicAutoResumeStateSerializer.encode(
                    saved.copy(queueProvenance = provenance.copy(fullCurrentIndex = snapshot.fullCurrentIndex)),
                ),
            )
        }
        return true
    }

    /** Writes a playlist queue reorder and its source proof in one preference update. */
    @Suppress("CyclomaticComplexMethod") // The transition requires every owner, source, and queue check.
    fun savePlaylistQueueTransition(
        playbackState: MusicPlaybackState,
        expectedOwner: PtvMusicAutoQueueOwner,
        previousSource: PtvMusicAutoQueueProvenance,
        updatedSource: PtvMusicAutoQueueProvenance,
        nowMs: Long = System.currentTimeMillis(),
    ): Boolean {
        if (currentLocalQueueOwner() != expectedOwner || playbackState.queueOwner != expectedOwner) return false
        val previous = loadPlaybackState() ?: return false
        val snapshot = PtvMusicAutoResumeState.fromPlaybackState(playbackState, nowMs) ?: return false
        val sourceIdentityMatches = previous.queueProvenance == previousSource &&
            previous.fullQueueSha256 == previousSource.queueSha256 &&
            previousSource.kind == PtvMusicAutoQueueProvenance.PLAYLIST &&
            !previousSource.playlistId.isNullOrBlank() &&
            !previousSource.authoredQueueSha256.isNullOrBlank() &&
            updatedSource.kind == PtvMusicAutoQueueProvenance.PLAYLIST &&
            updatedSource.playlistId == previousSource.playlistId &&
            updatedSource.authoredQueueSha256 == previousSource.authoredQueueSha256
        val updatedQueueMatches = updatedSource.queueSha256 == snapshot.fullQueueSha256 &&
            updatedSource.fullCurrentIndex == snapshot.fullCurrentIndex &&
            updatedSource.selectedAuthoredIndex != null
        if (!sourceIdentityMatches || !updatedQueueMatches) return false
        sharedPreferences.edit {
            putString(
                KEY_PLAYBACK_STATE,
                PtvMusicAutoResumeStateSerializer.encode(
                    snapshot.withOwner(expectedOwner.serverId, expectedOwner.userId)
                        .copy(queueProvenance = updatedSource).trimmed(),
                ),
            )
        }
        return true
    }

    fun savePlaybackState(
        playbackState: MusicPlaybackState,
        expectedOwner: PtvMusicAutoQueueOwner,
        nowMs: Long = System.currentTimeMillis(),
        retainQueueProvenance: Boolean = true,
        selectedAuthoredIndex: Int? = null,
    ): Boolean = PtvMusicAutoResumeState.fromPlaybackState(playbackState, nowMs)
        ?.let { state -> savePlaybackState(state, expectedOwner, retainQueueProvenance, selectedAuthoredIndex) }
        ?: false

    fun clearPlaybackState() {
        sharedPreferences.edit { remove(KEY_PLAYBACK_STATE) }
    }

    fun markAutoConnection(nowMs: Long = System.currentTimeMillis()) {
        val previouslyConnected = sharedPreferences.getLong(KEY_LAST_CONNECTION_MS, -1) > 0
        sharedPreferences.edit { putLong(KEY_LAST_CONNECTION_MS, nowMs) }
        val eventType = if (previouslyConnected) {
            PtvMusicAutoDiagnosticEventType.RECONNECTED
        } else {
            PtvMusicAutoDiagnosticEventType.CONNECTED
        }
        recordDiagnosticEvent(
            eventType,
            nowMs,
        )
    }

    fun markRestoreStatus(message: String) {
        sharedPreferences.edit { putString(KEY_LAST_RESTORE_STATUS, message) }
        recordDiagnosticEvent(
            when {
                message.startsWith("Restored ") -> PtvMusicAutoDiagnosticEventType.RESTORE_SUCCEEDED
                message == "Android Auto queue restore failed." -> PtvMusicAutoDiagnosticEventType.RESTORE_FAILED
                else -> PtvMusicAutoDiagnosticEventType.RESTORE_STATUS
            },
        )
    }

    fun markLastPlaybackError(message: String?) {
        val hadError = !sharedPreferences.getString(KEY_LAST_PLAYBACK_ERROR, null).isNullOrBlank()
        sharedPreferences.edit {
            if (message.isNullOrBlank()) {
                remove(KEY_LAST_PLAYBACK_ERROR)
            } else {
                putString(KEY_LAST_PLAYBACK_ERROR, message)
            }
        }
        when {
            !message.isNullOrBlank() -> recordDiagnosticEvent(PtvMusicAutoDiagnosticEventType.PLAYBACK_ERROR)
            hadError -> recordDiagnosticEvent(PtvMusicAutoDiagnosticEventType.PLAYBACK_RECOVERED)
        }
    }

    fun markRuntimeStatus(
        controllerInstanceId: String,
        repositoryInstanceId: String,
        playerInstanceId: String,
        mediaSessionInstanceId: String?,
        activeQueueCount: Int,
        currentTrackId: String?,
        currentTrackTitle: String?,
    ) {
        val oldQueueCount = sharedPreferences.getInt(KEY_ACTIVE_QUEUE_COUNT, 0)
        sharedPreferences.edit {
            putString(KEY_CONTROLLER_INSTANCE_ID, controllerInstanceId)
            putString(KEY_REPOSITORY_INSTANCE_ID, repositoryInstanceId)
            putString(KEY_PLAYER_INSTANCE_ID, playerInstanceId)
            if (mediaSessionInstanceId.isNullOrBlank()) {
                remove(KEY_MEDIA_SESSION_INSTANCE_ID)
            } else {
                putString(KEY_MEDIA_SESSION_INSTANCE_ID, mediaSessionInstanceId)
            }
            putInt(KEY_ACTIVE_QUEUE_COUNT, activeQueueCount)
            if (currentTrackId.isNullOrBlank()) {
                remove(KEY_CURRENT_TRACK_ID)
            } else {
                putString(KEY_CURRENT_TRACK_ID, currentTrackId)
            }
            if (currentTrackTitle.isNullOrBlank()) {
                remove(KEY_CURRENT_TRACK_TITLE)
            } else {
                putString(KEY_CURRENT_TRACK_TITLE, currentTrackTitle)
            }
        }
        if (activeQueueCount != oldQueueCount) {
            recordDiagnosticEvent(PtvMusicAutoDiagnosticEventType.PLAYER_QUEUE_CHANGED)
        }
    }

    fun markBrowseStatus(source: String, itemCount: Int) {
        sharedPreferences.edit {
            putString(KEY_LAST_BROWSE_SOURCE, source)
            putInt(KEY_LAST_BROWSE_ITEM_COUNT, itemCount)
        }
        recordDiagnosticEvent(
            when {
                source.startsWith("cache-failure:") -> PtvMusicAutoDiagnosticEventType.BROWSE_ERROR

                source.startsWith("cache-") || source.startsWith("fallback-") ->
                    PtvMusicAutoDiagnosticEventType.BROWSE_CACHE

                source.startsWith("fresh") -> PtvMusicAutoDiagnosticEventType.BROWSE_FRESH

                else -> PtvMusicAutoDiagnosticEventType.BROWSE_EVENT
            },
        )
    }

    fun markSessionCommand(command: String) {
        sharedPreferences.edit { putString(KEY_LAST_SESSION_COMMAND, command) }
        recordDiagnosticEvent(PtvMusicAutoDiagnosticEventType.SESSION_COMMAND)
    }

    fun markServiceLifecycle(event: String) {
        sharedPreferences.edit { putString(KEY_LAST_SERVICE_EVENT, event) }
        recordDiagnosticEvent(
            when {
                event.startsWith("created ") -> PtvMusicAutoDiagnosticEventType.SERVICE_STARTED
                event.startsWith("destroyed ") -> PtvMusicAutoDiagnosticEventType.SERVICE_STOPPED
                else -> PtvMusicAutoDiagnosticEventType.SERVICE_EVENT
            },
        )
    }

    /** This report intentionally omits every stored free-form string and media/account identifier. */
    fun diagnosticReport(appVersion: String, nowMs: Long = System.currentTimeMillis()): String =
        PtvMusicAutoDiagnosticReport.render(
            status = debugStatus(),
            events = PtvMusicAutoDiagnosticEvents.decode(
                sharedPreferences.getString(KEY_DIAGNOSTIC_EVENTS, null),
            ),
            appVersion = appVersion,
            generatedAtMs = nowMs,
        )

    private fun recordDiagnosticEvent(
        type: PtvMusicAutoDiagnosticEventType,
        nowMs: Long = System.currentTimeMillis(),
    ) {
        runCatching {
            synchronized(sharedPreferences) {
                val current = PtvMusicAutoDiagnosticEvents.decode(
                    sharedPreferences.getString(KEY_DIAGNOSTIC_EVENTS, null),
                )
                val updated = PtvMusicAutoDiagnosticEvents.append(
                    current,
                    PtvMusicAutoDiagnosticEvent(nowMs, type),
                )
                sharedPreferences.edit {
                    putString(KEY_DIAGNOSTIC_EVENTS, PtvMusicAutoDiagnosticEvents.encode(updated))
                }
            }
        }.onFailure {
            // Diagnostics must never interrupt media playback, and exception text may contain private data.
            Timber.w("PTV Music Auto could not store a diagnostic event")
        }
    }

    fun debugStatus(): PtvMusicAutoDebugStatus {
        val savedState = loadPlaybackState()
        return PtvMusicAutoDebugStatus(
            serviceRegistered = true,
            lastConnectionTimestampMs = sharedPreferences.getLong(KEY_LAST_CONNECTION_MS, -1)
                .takeIf { timestamp -> timestamp > 0 },
            lastRestoreStatus = sharedPreferences.getString(KEY_LAST_RESTORE_STATUS, null),
            lastPlaybackError = sharedPreferences.getString(KEY_LAST_PLAYBACK_ERROR, null),
            savedQueueSize = savedState?.queueItemIds?.size ?: 0,
            savedCurrentIndex = savedState?.currentIndex,
            controllerInstanceId = sharedPreferences.getString(KEY_CONTROLLER_INSTANCE_ID, null),
            repositoryInstanceId = sharedPreferences.getString(KEY_REPOSITORY_INSTANCE_ID, null),
            playerInstanceId = sharedPreferences.getString(KEY_PLAYER_INSTANCE_ID, null),
            mediaSessionInstanceId = sharedPreferences.getString(KEY_MEDIA_SESSION_INSTANCE_ID, null),
            activeQueueCount = sharedPreferences.getInt(KEY_ACTIVE_QUEUE_COUNT, 0),
            currentTrackId = sharedPreferences.getString(KEY_CURRENT_TRACK_ID, null),
            currentTrackTitle = sharedPreferences.getString(KEY_CURRENT_TRACK_TITLE, null),
            lastBrowseSource = sharedPreferences.getString(KEY_LAST_BROWSE_SOURCE, null),
            lastBrowseItemCount = sharedPreferences.getInt(
                KEY_LAST_BROWSE_ITEM_COUNT,
                -1,
            ).takeIf { count -> count >= 0 },
            lastSessionCommand = sharedPreferences.getString(KEY_LAST_SESSION_COMMAND, null),
            lastServiceEvent = sharedPreferences.getString(KEY_LAST_SERVICE_EVENT, null),
        )
    }

    private companion object {
        const val PREFS_NAME = "ptv_music_auto_resume"
        const val KEY_PLAYBACK_STATE = "playback_state"
        const val KEY_LAST_CONNECTION_MS = "last_connection_ms"
        const val KEY_LAST_RESTORE_STATUS = "last_restore_status"
        const val KEY_LAST_PLAYBACK_ERROR = "last_playback_error"
        const val KEY_CONTROLLER_INSTANCE_ID = "controller_instance_id"
        const val KEY_REPOSITORY_INSTANCE_ID = "repository_instance_id"
        const val KEY_PLAYER_INSTANCE_ID = "player_instance_id"
        const val KEY_MEDIA_SESSION_INSTANCE_ID = "media_session_instance_id"
        const val KEY_ACTIVE_QUEUE_COUNT = "active_queue_count"
        const val KEY_CURRENT_TRACK_ID = "current_track_id"
        const val KEY_CURRENT_TRACK_TITLE = "current_track_title"
        const val KEY_LAST_BROWSE_SOURCE = "last_browse_source"
        const val KEY_LAST_BROWSE_ITEM_COUNT = "last_browse_item_count"
        const val KEY_LAST_SESSION_COMMAND = "last_session_command"
        const val KEY_LAST_SERVICE_EVENT = "last_service_event"
        const val KEY_DIAGNOSTIC_EVENTS = "diagnostic_events"
    }
}

/** Local account row identities only. Never log or export their raw values. */
data class PtvMusicAutoQueueOwner(val serverId: Long, val userId: Long) {
    override fun toString(): String = "PtvMusicAutoQueueOwner([REDACTED])"
}

@Serializable
data class PtvMusicAutoResumeState(
    val queueItemIds: List<String>,
    val currentIndex: Int,
    val positionMs: Long,
    val shuffleEnabled: Boolean,
    val repeatMode: String,
    val lastPlayedTimestampMs: Long,
    val ownerServerId: Long? = null,
    val ownerUserId: Long? = null,
    val fullQueueSha256: String? = null,
    val fullCurrentIndex: Int? = null,
    val queueProvenance: PtvMusicAutoQueueProvenance? = null,
) {
    val currentItemId: String?
        get() = queueItemIds.getOrNull(currentIndex)

    val parsedRepeatMode: MusicRepeatMode
        get() = runCatching { MusicRepeatMode.valueOf(repeatMode) }.getOrDefault(MusicRepeatMode.NONE)

    val queueUuids: List<UUID>
        get() = queueItemIds.mapNotNull(String::toUUIDOrNull)

    fun trimmed(): PtvMusicAutoResumeState {
        val indexedIds = queueItemIds.mapIndexedNotNull { index, rawId ->
            rawId.trim().takeIf(String::isNotBlank)?.let { index to it }
        }
        if (indexedIds.isEmpty()) {
            return copy(queueItemIds = emptyList(), currentIndex = 0, positionMs = positionMs.coerceAtLeast(0))
        }
        val selectedIndex = indexedIds.indexOfFirst { (sourceIndex, _) -> sourceIndex == currentIndex }
            .takeIf { it >= 0 }
            ?: indexedIds.indexOfFirst { (sourceIndex, _) -> sourceIndex > currentIndex }
                .takeIf { it >= 0 }
            ?: indexedIds.lastIndex
        val bounded = boundResumeQueue(indexedIds.map { (_, id) -> id }, selectedIndex)
        return copy(
            queueItemIds = bounded.first,
            currentIndex = bounded.second,
            positionMs = positionMs.coerceAtLeast(0),
        )
    }

    fun belongsTo(serverId: Long, userId: Long): Boolean = ownerServerId == serverId && ownerUserId == userId

    internal fun queueOwnerOrNull(): PtvMusicAutoQueueOwner? =
        ownerServerId?.let { serverId -> ownerUserId?.let { userId -> PtvMusicAutoQueueOwner(serverId, userId) } }

    fun withOwner(serverId: Long, userId: Long): PtvMusicAutoResumeState = copy(
        ownerServerId = serverId,
        ownerUserId = userId,
    )

    companion object {
        const val MAX_PERSISTED_QUEUE_ITEMS = 100

        fun fromPlaybackState(
            playbackState: MusicPlaybackState,
            nowMs: Long = System.currentTimeMillis(),
        ): PtvMusicAutoResumeState? {
            val currentItemId = playbackState.currentItem?.id?.toString() ?: return null
            val queueIds = playbackState.queue.map { item -> item.id.toString() }
            val selectedIndex = playbackState.currentIndex
                .takeIf { index -> queueIds.getOrNull(index) == currentItemId }
                ?: queueIds.indexOf(currentItemId).takeIf { index -> index >= 0 }
            val completeQueue = if (selectedIndex == null) listOf(currentItemId) + queueIds else queueIds
            val bounded = boundResumeQueue(completeQueue, selectedIndex ?: 0)

            return PtvMusicAutoResumeState(
                queueItemIds = bounded.first,
                currentIndex = bounded.second,
                positionMs = playbackState.positionMs.coerceAtLeast(0),
                shuffleEnabled = playbackState.shuffleEnabled,
                repeatMode = playbackState.repeatMode.name,
                lastPlayedTimestampMs = nowMs,
                fullQueueSha256 = queueSha256(completeQueue),
                fullCurrentIndex = selectedIndex ?: 0,
            )
        }
    }
}

data class PtvMusicAutoDebugStatus(
    val serviceRegistered: Boolean,
    val lastConnectionTimestampMs: Long?,
    val lastRestoreStatus: String?,
    val lastPlaybackError: String?,
    val savedQueueSize: Int,
    val savedCurrentIndex: Int?,
    val controllerInstanceId: String?,
    val repositoryInstanceId: String?,
    val playerInstanceId: String?,
    val mediaSessionInstanceId: String?,
    val activeQueueCount: Int,
    val currentTrackId: String?,
    val currentTrackTitle: String?,
    val lastBrowseSource: String?,
    val lastBrowseItemCount: Int?,
    val lastSessionCommand: String?,
    val lastServiceEvent: String?,
)

@Serializable
internal data class PtvMusicAutoDiagnosticEvent(val timestampMs: Long, val type: PtvMusicAutoDiagnosticEventType)

/** Only fixed categories are persisted. Caller-supplied messages, media IDs and URLs never enter this log. */
internal enum class PtvMusicAutoDiagnosticEventType {
    CONNECTED,
    RECONNECTED,
    RESTORE_STATUS,
    RESTORE_SUCCEEDED,
    RESTORE_FAILED,
    PLAYBACK_ERROR,
    PLAYBACK_RECOVERED,
    PLAYER_QUEUE_CHANGED,
    BROWSE_EVENT,
    BROWSE_FRESH,
    BROWSE_CACHE,
    BROWSE_ERROR,
    SESSION_COMMAND,
    SERVICE_STARTED,
    SERVICE_STOPPED,
    SERVICE_EVENT,
}

@Serializable
data class PtvMusicAutoRecommendationSnapshot(
    val sourceMediaId: String,
    val selectedMode: String,
    val seedId: String,
    val recentPlayedIds: List<String>,
    val skippedIds: List<String>,
    val recommendedIds: List<String>,
    val catalogOffset: Int,
    val catalogExhausted: Boolean,
    val revision: Long,
)

@Serializable
data class PtvMusicAutoQueueProvenance(
    val kind: String,
    val queueSha256: String,
    val fullCurrentIndex: Int? = null,
    val playlistId: String? = null,
    val authoredQueueSha256: String? = null,
    val selectedAuthoredIndex: Int? = null,
    val recommendation: PtvMusicAutoRecommendationSnapshot? = null,
) {
    fun matchesQueue(queue: List<MusicItem>): Boolean = queueSha256 == queueSha256(queue.map { it.id.toString() })

    fun matchesAuthoredQueue(queue: List<MusicItem>): Boolean =
        authoredQueueSha256 != null && authoredQueueSha256 == queueSha256(queue.map { it.id.toString() })

    fun forQueue(
        queue: List<MusicItem>,
        currentIndex: Int,
        authoredQueue: List<MusicItem>? = null,
        authoredIndex: Int? = null,
    ): PtvMusicAutoQueueProvenance = copy(
        queueSha256 = queueSha256(queue.map { it.id.toString() }),
        fullCurrentIndex = currentIndex,
        authoredQueueSha256 = if (kind == PLAYLIST && authoredQueue != null) {
            queueSha256(authoredQueue.map { it.id.toString() })
        } else {
            authoredQueueSha256
        },
        selectedAuthoredIndex = if (kind == PLAYLIST) authoredIndex ?: selectedAuthoredIndex else null,
    )

    companion object {
        const val PLAYLIST = "playlist"
        const val RECOMMENDATION = "recommendation"

        fun playlist(playlistId: UUID, queue: List<MusicItem>, fullCurrentIndex: Int): PtvMusicAutoQueueProvenance =
            PtvMusicAutoQueueProvenance(
                kind = PLAYLIST,
                queueSha256 = queueSha256(queue.map { it.id.toString() }),
                fullCurrentIndex = fullCurrentIndex,
                playlistId = playlistId.toString(),
                authoredQueueSha256 = queueSha256(queue.map { it.id.toString() }),
                selectedAuthoredIndex = fullCurrentIndex,
            )

        fun recommendation(
            queue: List<MusicItem>,
            snapshot: PtvMusicAutoRecommendationSnapshot,
        ): PtvMusicAutoQueueProvenance = PtvMusicAutoQueueProvenance(
            kind = RECOMMENDATION,
            queueSha256 = queueSha256(queue.map { it.id.toString() }),
            recommendation = snapshot,
        )
    }
}

private fun queueSha256(ids: List<String>): String {
    val digest = MessageDigest.getInstance("SHA-256")
    ids.forEach { id ->
        digest.update(id.toByteArray(Charsets.UTF_8))
        digest.update(0)
    }
    return digest.digest().joinToString("") { byte -> "%02x".format(byte.toInt() and UNSIGNED_BYTE_MASK) }
}

private const val UNSIGNED_BYTE_MASK = 0xff

internal object PtvMusicAutoDiagnosticEvents {
    const val MAX_EVENTS = 32

    private val json = Json { ignoreUnknownKeys = true }

    fun append(
        current: List<PtvMusicAutoDiagnosticEvent>,
        event: PtvMusicAutoDiagnosticEvent,
    ): List<PtvMusicAutoDiagnosticEvent> = (current + event).takeLast(MAX_EVENTS)

    fun encode(events: List<PtvMusicAutoDiagnosticEvent>): String = json.encodeToString(events.takeLast(MAX_EVENTS))

    fun decode(value: String?): List<PtvMusicAutoDiagnosticEvent> = value?.let { stored ->
        runCatching { json.decodeFromString<List<PtvMusicAutoDiagnosticEvent>>(stored) }
            .getOrNull()
            ?.takeLast(MAX_EVENTS)
    }.orEmpty()
}

internal object PtvMusicAutoDiagnosticReport {
    fun render(
        status: PtvMusicAutoDebugStatus,
        events: List<PtvMusicAutoDiagnosticEvent>,
        appVersion: String,
        generatedAtMs: Long,
    ): String = buildString {
        val safeVersion = appVersion.take(32).filter { character ->
            character.isDigit() || character == '.' || character == '-' || character.isLetter()
        }
        appendLine("PiggieTV Android Auto diagnostics")
        appendLine("Generated (epoch ms): $generatedAtMs")
        appendLine("App version: $safeVersion")
        appendLine("Service registered: ${status.serviceRegistered}")
        appendLine("Last connection (epoch ms): ${status.lastConnectionTimestampMs ?: "none"}")
        appendLine("Saved queue size: ${status.savedQueueSize}")
        appendLine("Active queue size: ${status.activeQueueCount}")
        appendLine("Last browse result count: ${status.lastBrowseItemCount ?: "none"}")
        appendLine("Restore status present: ${!status.lastRestoreStatus.isNullOrBlank()}")
        appendLine("Playback error present: ${!status.lastPlaybackError.isNullOrBlank()}")
        appendLine("Recent events (oldest first, maximum ${PtvMusicAutoDiagnosticEvents.MAX_EVENTS}):")
        events.takeLast(PtvMusicAutoDiagnosticEvents.MAX_EVENTS).forEach { event ->
            appendLine("${event.timestampMs} ${event.type.name}")
        }
        appendLine("Raw errors, account and server details, media identity, and URLs are excluded.")
    }
}

private fun boundResumeQueue(queueIds: List<String>, selectedIndex: Int): Pair<List<String>, Int> {
    val safeIndex = selectedIndex.coerceIn(0, queueIds.lastIndex)
    val start = (safeIndex - PtvMusicAutoResumeState.MAX_PERSISTED_QUEUE_ITEMS / 2)
        .coerceIn(0, (queueIds.size - PtvMusicAutoResumeState.MAX_PERSISTED_QUEUE_ITEMS).coerceAtLeast(0))
    return queueIds.drop(start).take(PtvMusicAutoResumeState.MAX_PERSISTED_QUEUE_ITEMS) to safeIndex - start
}

internal object PtvMusicAutoResumeStateSerializer {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun encode(state: PtvMusicAutoResumeState): String = json.encodeToString(state.trimmed())

    fun decode(value: String): PtvMusicAutoResumeState? = runCatching {
        json.decodeFromString<PtvMusicAutoResumeState>(value).trimmed()
    }.onFailure { error ->
        if (error is SerializationException || error is IllegalArgumentException) {
            Timber.w(error, "PTV Music Auto could not decode saved playback state")
        } else {
            Timber.e(error, "PTV Music Auto saved playback state failed unexpectedly")
        }
    }.getOrNull()
}
