package org.jellyfin.mobile.reporting

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class MediaReportSenderTest {
    @Test
    fun `report endpoint stays on the configured server and preserves its base path`() {
        buildMediaReportEndpoint("https://ptv.example/jellyfin?old=query#fragment").toString() shouldBe
            "https://ptv.example/jellyfin/Ptv/v1/reports/media"
    }

    @Test
    fun `invalid or missing server URL disables reporting`() {
        buildMediaReportEndpoint(null) shouldBe null
        buildMediaReportEndpoint("") shouldBe null
        buildMediaReportEndpoint("not a URL") shouldBe null
    }

    @Test
    fun `payload uses stable values limits user text and omits user identity`() {
        val payload = buildMediaReportPayload(
            target = MediaReportTarget(
                itemId = " item-1 ",
                title = " Title ",
                subtitle = " Subtitle ",
                type = "Movie",
                source = MediaReportSource.PLAYBACK,
                playbackPositionMs = -1,
                mediaSourceId = "source-1",
                playMethod = "DirectPlay",
            ),
            reason = MediaReportReason.SUBTITLE_SYNC,
            details = "x".repeat(2_100),
        )

        payload["schemaVersion"]?.toString() shouldBe "1"
        payload["itemId"]?.toString() shouldBe "\"item-1\""
        payload["source"]?.toString() shouldBe "\"playback\""
        payload["reason"]?.toString() shouldBe "\"subtitle-sync\""
        payload["playbackPositionMs"]?.toString() shouldBe "0"
        payload["details"]?.toString()?.length shouldBe 2_002
        payload.containsKey("userName") shouldBe false
        payload.containsKey("destination") shouldBe false
    }

    @Test
    fun `server responses map to truthful delivery results`() {
        classifyMediaReportResponse(204) shouldBe MediaReportDeliveryResult.SENT
        classifyMediaReportResponse(404) shouldBe MediaReportDeliveryResult.UNAVAILABLE
        classifyMediaReportResponse(503) shouldBe MediaReportDeliveryResult.UNAVAILABLE
        classifyMediaReportResponse(429) shouldBe MediaReportDeliveryResult.RATE_LIMITED
        classifyMediaReportResponse(401) shouldBe MediaReportDeliveryResult.FAILED
        classifyMediaReportResponse(500) shouldBe MediaReportDeliveryResult.FAILED
    }
}
