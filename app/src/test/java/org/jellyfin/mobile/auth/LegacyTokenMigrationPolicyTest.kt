package org.jellyfin.mobile.auth

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class LegacyTokenMigrationPolicyTest {
    @Test
    fun `in-place update is eligible for one-time legacy migration`() {
        assertEquals(
            LegacyTokenMigrationEligibility.ALLOWED_IN_PLACE_UPDATE,
            LegacyTokenMigrationPolicy.evaluate(1_000L, 2_000L),
        )
    }

    @Test
    fun `fresh or restored install is definitively ineligible`() {
        assertEquals(
            LegacyTokenMigrationEligibility.DENIED_FRESH_INSTALL,
            LegacyTokenMigrationPolicy.evaluate(2_000L, 2_000L),
        )
    }

    @Test
    fun `unreliable package metadata preserves legacy evidence for retry`() {
        assertEquals(
            LegacyTokenMigrationEligibility.UNKNOWN,
            LegacyTokenMigrationPolicy.evaluate(0L, 2_000L),
        )
        assertEquals(
            LegacyTokenMigrationEligibility.UNKNOWN,
            LegacyTokenMigrationPolicy.evaluate(3_000L, 2_000L),
        )
    }
}
