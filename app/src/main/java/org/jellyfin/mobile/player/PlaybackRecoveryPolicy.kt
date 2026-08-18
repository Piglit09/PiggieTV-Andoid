package org.jellyfin.mobile.player

import androidx.media3.common.PlaybackException

internal object PlaybackRecoveryPolicy {
    const val MAX_NETWORK_RETRY_ATTEMPTS = 2
    const val STABLE_PLAYBACK_RESET_DELAY_MS = 30_000L

    fun retryDelayMs(attempt: Int): Long = when (attempt.coerceAtLeast(1)) {
        1 -> 750L
        else -> 1_500L
    }

    fun isRetryableNetworkError(errorCode: Int, httpStatusCode: Int?): Boolean = when (errorCode) {
        PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
        -> true

        PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS ->
            httpStatusCode == 408 || httpStatusCode == 429 || httpStatusCode?.let { it in 500..599 } == true

        else -> false
    }
}
