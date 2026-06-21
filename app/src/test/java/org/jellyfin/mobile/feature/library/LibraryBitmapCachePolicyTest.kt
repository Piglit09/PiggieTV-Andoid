package org.jellyfin.mobile.feature.library

import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.longs.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class LibraryBitmapCachePolicyTest {
    @Test
    fun `bitmap cache budget is capped for large heaps`() {
        val policy = calculateLibraryBitmapCachePolicy(
            maxMemoryBytes = 2L * 1024L * 1024L * 1024L,
            requestedLookAheadPages = 10,
        )

        policy.budgetBytes shouldBe 96L * 1024L * 1024L
        policy.lookAheadPages shouldBe 6
        policy.lookBehindPages shouldBe 6
    }

    @Test
    fun `bitmap cache budget keeps a safe minimum for small heaps`() {
        val policy = calculateLibraryBitmapCachePolicy(
            maxMemoryBytes = 8L * 1024L * 1024L,
            requestedLookAheadPages = 6,
        )

        policy.budgetBytes shouldBe 16L * 1024L * 1024L
        policy.lookAheadPages shouldBe 1
        policy.lookBehindPages shouldBe 1
    }

    @Test
    fun `medium cache allows at least two pages around current page`() {
        val policy = calculateLibraryBitmapCachePolicy(
            maxMemoryBytes = 384L * 1024L * 1024L,
            requestedLookAheadPages = 6,
        )

        policy.budgetBytes shouldBeLessThanOrEqual 96L * 1024L * 1024L
        policy.lookAheadPages shouldBeGreaterThanOrEqual 2
        policy.lookBehindPages shouldBeGreaterThanOrEqual 2
    }

    @Test
    fun `image sample size avoids decoding oversized comic pages at full resolution`() {
        calculateLibraryImageSampleSize(sourceWidth = 8000, targetWidth = 1200) shouldBe 4
        calculateLibraryImageSampleSize(sourceWidth = 1600, targetWidth = 1200) shouldBe 1
        calculateLibraryImageSampleSize(sourceWidth = 0, targetWidth = 1200) shouldBe 1
    }
}
