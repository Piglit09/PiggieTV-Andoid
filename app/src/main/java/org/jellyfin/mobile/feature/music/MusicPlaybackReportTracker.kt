package org.jellyfin.mobile.feature.music

import org.jellyfin.sdk.model.UUID
import org.jellyfin.sdk.model.api.PlayMethod

internal data class MusicPlaybackReportSession(
    val itemId: UUID,
    val playMethod: PlayMethod,
    val playSessionId: String,
    val liveStreamId: String?,
    val audioStreamIndex: Int?,
)

internal sealed interface MusicPlaybackReportEvent {
    val session: MusicPlaybackReportSession
    val positionTicks: Long

    data class Playing(
        override val session: MusicPlaybackReportSession,
        override val positionTicks: Long,
        val isPaused: Boolean,
    ) : MusicPlaybackReportEvent

    data class Progress(
        override val session: MusicPlaybackReportSession,
        override val positionTicks: Long,
        val isPaused: Boolean,
    ) : MusicPlaybackReportEvent

    data class Stopped(override val session: MusicPlaybackReportSession, override val positionTicks: Long) :
        MusicPlaybackReportEvent
}

/**
 * Pure lifecycle/deduplication policy for Jellyfin's Sessions/Playing reports.
 *
 * The player emits many callbacks for one state change. Keeping this state outside callback code
 * guarantees one Playing and one Stopped event per resolved stream while still reporting pause
 * changes immediately and regular progress on a bounded cadence.
 */
internal class MusicPlaybackReportTracker(private val progressIntervalMs: Long = DEFAULT_PROGRESS_INTERVAL_MS) {
    private var activeSession: MusicPlaybackReportSession? = null
    private var lastPositionTicks = 0L
    private var lastReportAtMs = 0L
    private var lastPaused = true

    fun transitionTo(
        session: MusicPlaybackReportSession,
        positionTicks: Long,
        isPaused: Boolean,
        nowMs: Long,
    ): List<MusicPlaybackReportEvent> {
        val safePosition = positionTicks.coerceAtLeast(0L)
        val current = activeSession
        if (current == session) {
            lastPositionTicks = safePosition
            return emptyList()
        }

        val events = buildList {
            current?.let { previous ->
                add(MusicPlaybackReportEvent.Stopped(previous, lastPositionTicks))
            }
            add(MusicPlaybackReportEvent.Playing(session, safePosition, isPaused))
        }
        activeSession = session
        lastPositionTicks = safePosition
        lastReportAtMs = nowMs
        lastPaused = isPaused
        return events
    }

    fun progress(
        positionTicks: Long,
        isPaused: Boolean,
        nowMs: Long,
        force: Boolean = false,
    ): List<MusicPlaybackReportEvent> {
        val session = activeSession ?: return emptyList()
        lastPositionTicks = positionTicks.coerceAtLeast(0L)
        val pauseChanged = isPaused != lastPaused
        if (!force && !pauseChanged && nowMs - lastReportAtMs < progressIntervalMs) return emptyList()

        lastReportAtMs = nowMs
        lastPaused = isPaused
        return listOf(
            MusicPlaybackReportEvent.Progress(
                session = session,
                positionTicks = lastPositionTicks,
                isPaused = isPaused,
            ),
        )
    }

    fun finish(positionTicks: Long = lastPositionTicks): List<MusicPlaybackReportEvent> {
        val session = activeSession ?: return emptyList()
        val event = MusicPlaybackReportEvent.Stopped(session, positionTicks.coerceAtLeast(0L))
        activeSession = null
        lastPositionTicks = 0L
        lastReportAtMs = 0L
        lastPaused = true
        return listOf(event)
    }

    companion object {
        const val DEFAULT_PROGRESS_INTERVAL_MS = 15_000L
    }
}
