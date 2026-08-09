package org.jellyfin.mobile.core

import com.piggietv.core.PtvClientCapability
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PtvCorePlatformAdapterTest {
    @Test
    fun `reports implemented mobile capabilities conservatively`() {
        val report = PtvCorePlatformAdapter.capabilityReport()

        assertTrue(report.supports(PtvClientCapability.VIDEO_PLAYBACK))
        assertTrue(report.supports(PtvClientCapability.ANDROID_AUTO))
        assertTrue(report.supports(PtvClientCapability.READER))
        assertFalse(report.supports(PtvClientCapability.QUICK_CONNECT))
        assertFalse(report.supports(PtvClientCapability.DEEP_LINKS))
        assertEquals(
            PtvClientCapability.entries.map(PtvClientCapability::wireName).toSet(),
            report.capabilities.keys,
        )
        val unsupported = setOf(
            PtvClientCapability.TV_FOCUS_NAVIGATION,
            PtvClientCapability.POINTER_INPUT,
            PtvClientCapability.REMOTE_INPUT,
            PtvClientCapability.TELEMETRY,
            PtvClientCapability.QUICK_CONNECT,
            PtvClientCapability.DEEP_LINKS,
        )
        PtvClientCapability.entries.forEach { capability ->
            assertEquals(capability !in unsupported, report.supports(capability), capability.wireName)
        }
    }
}
