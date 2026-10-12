package org.jellyfin.mobile.auth

/**
 * Plaintext Room credentials are accepted only during an in-place application upgrade.
 * A freshly installed package may have received its database through Android restore, so its
 * device-bound credential must be rejected instead of silently reactivated.
 */
internal object LegacyTokenMigrationPolicy {
    fun evaluate(firstInstallTimeMillis: Long, lastUpdateTimeMillis: Long): LegacyTokenMigrationEligibility = when {
        firstInstallTimeMillis <= 0L || lastUpdateTimeMillis < firstInstallTimeMillis ->
            LegacyTokenMigrationEligibility.UNKNOWN

        lastUpdateTimeMillis == firstInstallTimeMillis -> LegacyTokenMigrationEligibility.DENIED_FRESH_INSTALL

        else -> LegacyTokenMigrationEligibility.ALLOWED_IN_PLACE_UPDATE
    }
}

internal enum class LegacyTokenMigrationEligibility {
    ALLOWED_IN_PLACE_UPDATE,
    DENIED_FRESH_INSTALL,
    UNKNOWN,
}
