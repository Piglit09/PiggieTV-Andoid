package org.jellyfin.mobile.player

import androidx.media3.common.PlaybackException
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class PlaybackRecoveryPolicyTest {
    @Test
    fun `transient network failures are retryable`() {
        PlaybackRecoveryPolicy.isRetryableNetworkError(
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            null,
        ) shouldBe true
        PlaybackRecoveryPolicy.isRetryableNetworkError(
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
            null,
        ) shouldBe true
        PlaybackRecoveryPolicy.isRetryableNetworkError(
            PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
            null,
        ) shouldBe true
    }

    @Test
    fun `only transient HTTP status failures are retryable`() {
        listOf(408, 429, 500, 503, 599).forEach { status ->
            PlaybackRecoveryPolicy.isRetryableNetworkError(
                PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
                status,
            ) shouldBe true
        }
        listOf(null, 400, 401, 403, 404, 600).forEach { status ->
            PlaybackRecoveryPolicy.isRetryableNetworkError(
                PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
                status,
            ) shouldBe false
        }
    }

    @Test
    fun `content decoder and permission failures are not retried as network faults`() {
        listOf(
            PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
            PlaybackException.ERROR_CODE_IO_NO_PERMISSION,
            PlaybackException.ERROR_CODE_DECODING_FAILED,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
        ).forEach { errorCode ->
            PlaybackRecoveryPolicy.isRetryableNetworkError(errorCode, null) shouldBe false
        }
    }

    @Test
    fun `retry backoff is bounded`() {
        PlaybackRecoveryPolicy.retryDelayMs(1) shouldBe 750L
        PlaybackRecoveryPolicy.retryDelayMs(2) shouldBe 1_500L
        PlaybackRecoveryPolicy.retryDelayMs(20) shouldBe 1_500L
        PlaybackRecoveryPolicy.MAX_NETWORK_RETRY_ATTEMPTS shouldBe 2
    }
}
