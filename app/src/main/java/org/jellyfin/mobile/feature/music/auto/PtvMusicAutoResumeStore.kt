package org.jellyfin.mobile.feature.music.auto

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.jellyfin.mobile.feature.music.MusicPlaybackState
import org.jellyfin.mobile.feature.music.MusicRepeatMode
import org.jellyfin.sdk.model.UUID
import org.jellyfin.sdk.model.serializer.toUUIDOrNull
import timber.log.Timber

class PtvMusicAutoResumeStore(context: Context) {
    private val sharedPreferences: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    @Suppress("FunctionExpressionBody")
    fun loadPlaybackState(): PtvMusicAutoResumeState? {
        return sharedPreferences.getString(KEY_PLAYBACK_STATE, null)
            ?.let(PtvMusicAutoResumeStateSerializer::decode)
    }

    fun savePlaybackState(state: PtvMusicAutoResumeState) {
        sharedPreferences.edit {
            putString(
                KEY_PLAYBACK_STATE,
                PtvMusicAutoResumeStateSerializer.encode(state.trimmed()),
            )
        }
    }

    fun savePlaybackState(playbackState: MusicPlaybackState, nowMs: Long = System.currentTimeMillis()) {
        PtvMusicAutoResumeState.fromPlaybackState(playbackState, nowMs)?.let(::savePlaybackState)
    }

    fun clearPlaybackState() {
        sharedPreferences.edit { remove(KEY_PLAYBACK_STATE) }
    }

    fun markAutoConnection(nowMs: Long = System.currentTimeMillis()) {
        sharedPreferences.edit { putLong(KEY_LAST_CONNECTION_MS, nowMs) }
    }

    fun markRestoreStatus(message: String) {
        sharedPreferences.edit { putString(KEY_LAST_RESTORE_STATUS, message) }
    }

    fun markLastPlaybackError(message: String?) {
        sharedPreferences.edit {
            if (message.isNullOrBlank()) {
                remove(KEY_LAST_PLAYBACK_ERROR)
            } else {
                putString(KEY_LAST_PLAYBACK_ERROR, message)
            }
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
    }

    fun markBrowseStatus(source: String, itemCount: Int) {
        sharedPreferences.edit {
            putString(KEY_LAST_BROWSE_SOURCE, source)
            putInt(KEY_LAST_BROWSE_ITEM_COUNT, itemCount)
        }
    }

    fun markSessionCommand(command: String) {
        sharedPreferences.edit { putString(KEY_LAST_SESSION_COMMAND, command) }
    }

    fun markServiceLifecycle(event: String) {
        sharedPreferences.edit { putString(KEY_LAST_SERVICE_EVENT, event) }
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
            lastBrowseItemCount = sharedPreferences.getInt(KEY_LAST_BROWSE_ITEM_COUNT, -1).takeIf { count -> count >= 0 },
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
    }
}

@Serializable
data class PtvMusicAutoResumeState(
    val queueItemIds: List<String>,
    val currentIndex: Int,
    val positionMs: Long,
    val shuffleEnabled: Boolean,
    val repeatMode: String,
    val lastPlayedTimestampMs: Long,
) {
    val currentItemId: String?
        get() = queueItemIds.getOrNull(currentIndex)

    val parsedRepeatMode: MusicRepeatMode
        get() = runCatching { MusicRepeatMode.valueOf(repeatMode) }.getOrDefault(MusicRepeatMode.NONE)

    val queueUuids: List<UUID>
        get() = queueItemIds.mapNotNull(String::toUUIDOrNull)

    fun trimmed(): PtvMusicAutoResumeState = copy(
        queueItemIds = queueItemIds
            .map(String::trim)
            .filter(String::isNotBlank)
            .distinct()
            .take(MAX_PERSISTED_QUEUE_ITEMS),
        currentIndex = currentIndex.coerceAtLeast(0),
        positionMs = positionMs.coerceAtLeast(0),
    )

    companion object {
        const val MAX_PERSISTED_QUEUE_ITEMS = 100

        fun fromPlaybackState(
            playbackState: MusicPlaybackState,
            nowMs: Long = System.currentTimeMillis(),
        ): PtvMusicAutoResumeState? {
            val currentItemId = playbackState.currentItem?.id?.toString() ?: return null
            val distinctQueueIds = playbackState.queue
                .map { item -> item.id.toString() }
                .distinct()
            val queueIds = when {
                currentItemId in distinctQueueIds.take(MAX_PERSISTED_QUEUE_ITEMS) ->
                    distinctQueueIds.take(MAX_PERSISTED_QUEUE_ITEMS)

                else -> (listOf(currentItemId) + distinctQueueIds.filterNot { itemId -> itemId == currentItemId })
                    .take(MAX_PERSISTED_QUEUE_ITEMS)
            }

            val currentItemIndex = queueIds.indexOf(currentItemId)
                .takeIf { index -> index >= 0 }
                ?: playbackState.currentIndex.coerceIn(0, queueIds.lastIndex)

            return PtvMusicAutoResumeState(
                queueItemIds = queueIds,
                currentIndex = currentItemIndex,
                positionMs = playbackState.positionMs.coerceAtLeast(0),
                shuffleEnabled = playbackState.shuffleEnabled,
                repeatMode = playbackState.repeatMode.name,
                lastPlayedTimestampMs = nowMs,
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
