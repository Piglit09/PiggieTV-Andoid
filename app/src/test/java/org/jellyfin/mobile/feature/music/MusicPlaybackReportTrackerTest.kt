package org.jellyfin.mobile.feature.music

import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.types.shouldBeInstanceOf
import org.jellyfin.sdk.model.api.PlayMethod
import org.junit.jupiter.api.Test
import java.util.UUID

class MusicPlaybackReportTrackerTest {
    @Test
    fun `one session reports playing cadence progress and one stopped event`() {
        val tracker = MusicPlaybackReportTracker(progressIntervalMs = 10_000L)
        val session = session("one")

        tracker.transitionTo(session, positionTicks = 0L, isPaused = false, nowMs = 1_000L)
            .single().shouldBeInstanceOf<MusicPlaybackReportEvent.Playing>()
        tracker.progress(positionTicks = 30_000L, isPaused = false, nowMs = 5_000L) shouldHaveSize 0
        tracker.progress(positionTicks = 60_000L, isPaused = false, nowMs = 11_000L)
            .single().shouldBeInstanceOf<MusicPlaybackReportEvent.Progress>()
        tracker.finish(positionTicks = 90_000L)
            .single().shouldBeInstanceOf<MusicPlaybackReportEvent.Stopped>()
        tracker.finish(positionTicks = 100_000L) shouldHaveSize 0
    }

    @Test
    fun `track transition stops the old stream before starting the new one`() {
        val tracker = MusicPlaybackReportTracker()
        val first = session("first")
        val second = session("second")

        tracker.transitionTo(first, 0L, isPaused = false, nowMs = 0L)
        tracker.progress(80_000L, isPaused = false, nowMs = 1_000L)
        val events = tracker.transitionTo(second, 0L, isPaused = false, nowMs = 2_000L)

        events shouldHaveSize 2
        events[0].shouldBeInstanceOf<MusicPlaybackReportEvent.Stopped>()
        events[1].shouldBeInstanceOf<MusicPlaybackReportEvent.Playing>()
    }

    @Test
    fun `pause changes force progress without waiting for cadence`() {
        val tracker = MusicPlaybackReportTracker(progressIntervalMs = 30_000L)
        tracker.transitionTo(session("pause"), 0L, isPaused = false, nowMs = 0L)

        tracker.progress(10_000L, isPaused = true, nowMs = 500L)
            .single().shouldBeInstanceOf<MusicPlaybackReportEvent.Progress>()
    }

    private fun session(key: String) = MusicPlaybackReportSession(
        itemId = UUID.nameUUIDFromBytes(key.toByteArray()),
        playMethod = PlayMethod.DIRECT_PLAY,
        playSessionId = "session-$key",
        liveStreamId = null,
        audioStreamIndex = null,
    )
}
