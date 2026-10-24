package org.jellyfin.mobile.feature.music.auto

import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class PtvMusicAutoDiagnosticReportTest {
    @Test
    fun `export excludes raw status text and media identity`() {
        val sensitive = "token-ABC123 https://private.example/ Track Secret Title"
        val status = PtvMusicAutoDebugStatus(
            serviceRegistered = true,
            lastConnectionTimestampMs = 1_000,
            lastRestoreStatus = sensitive,
            lastPlaybackError = sensitive,
            savedQueueSize = 2,
            savedCurrentIndex = 1,
            controllerInstanceId = sensitive,
            repositoryInstanceId = sensitive,
            playerInstanceId = sensitive,
            mediaSessionInstanceId = sensitive,
            activeQueueCount = 3,
            currentTrackId = sensitive,
            currentTrackTitle = sensitive,
            lastBrowseSource = sensitive,
            lastBrowseItemCount = 4,
            lastSessionCommand = sensitive,
            lastServiceEvent = sensitive,
        )
        val events = listOf(
            PtvMusicAutoDiagnosticEvent(1_001, PtvMusicAutoDiagnosticEventType.CONNECTED),
            PtvMusicAutoDiagnosticEvent(1_002, PtvMusicAutoDiagnosticEventType.BROWSE_ERROR),
        )

        val report = PtvMusicAutoDiagnosticReport.render(
            status = status,
            events = events,
            appVersion = "0.2.72",
            generatedAtMs = 2_000,
        )

        report.contains("token-ABC123") shouldBe false
        report.contains("private.example") shouldBe false
        report.contains("Track Secret Title") shouldBe false
        report.contains("0.2.72") shouldBe true
        report.contains("BROWSE_ERROR") shouldBe true
        report.contains("Saved queue size: 2") shouldBe true
    }

    @Test
    fun `event log has fixed categories and retains only latest bounded entries`() {
        val initial = (0..40).map { index ->
            PtvMusicAutoDiagnosticEvent(index.toLong(), PtvMusicAutoDiagnosticEventType.SERVICE_EVENT)
        }

        val retained = PtvMusicAutoDiagnosticEvents.append(
            current = initial,
            event = PtvMusicAutoDiagnosticEvent(41, PtvMusicAutoDiagnosticEventType.PLAYBACK_ERROR),
        )

        retained shouldHaveSize PtvMusicAutoDiagnosticEvents.MAX_EVENTS
        retained.first().timestampMs shouldBe 10L
        retained.last().timestampMs shouldBe 41L
        PtvMusicAutoDiagnosticEvents.decode(PtvMusicAutoDiagnosticEvents.encode(retained)) shouldBe retained
    }

    @Test
    fun `corrupt event history is discarded instead of copied into export`() {
        PtvMusicAutoDiagnosticEvents.decode("token-ABC123") shouldBe emptyList()
    }
}
