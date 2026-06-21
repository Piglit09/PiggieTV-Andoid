@file:Suppress("ClassSignature", "FunctionSignature")

package org.jellyfin.mobile.feature.library

internal data class LibraryBitmapCachePolicy(
    val budgetBytes: Long,
    val lookBehindPages: Int,
    val lookAheadPages: Int,
)

internal fun calculateLibraryBitmapCachePolicy(
    maxMemoryBytes: Long,
    requestedLookAheadPages: Int,
): LibraryBitmapCachePolicy {
    val safeMaxMemory = maxMemoryBytes.coerceAtLeast(MIN_BITMAP_CACHE_BYTES)
    val budget = (safeMaxMemory * BITMAP_CACHE_MEMORY_FRACTION)
        .toLong()
        .coerceIn(MIN_BITMAP_CACHE_BYTES, MAX_BITMAP_CACHE_BYTES)
    val pageWindow = when {
        budget < LOW_MEMORY_BITMAP_CACHE_BYTES -> 1
        budget < MEDIUM_MEMORY_BITMAP_CACHE_BYTES -> 2
        else -> requestedLookAheadPages.coerceIn(2, MAX_PREFETCH_PAGES)
    }

    return LibraryBitmapCachePolicy(
        budgetBytes = budget,
        lookBehindPages = pageWindow,
        lookAheadPages = pageWindow,
    )
}

internal fun calculateLibraryImageSampleSize(
    sourceWidth: Int,
    targetWidth: Int,
): Int {
    if (sourceWidth <= 0 || targetWidth <= 0) return 1
    var sampleSize = 1
    while (sourceWidth / (sampleSize * 2) >= targetWidth) {
        sampleSize *= 2
    }
    return sampleSize.coerceAtLeast(1)
}

private const val BITMAP_CACHE_MEMORY_FRACTION = 0.12
private const val MIN_BITMAP_CACHE_BYTES = 16L * 1024L * 1024L
private const val LOW_MEMORY_BITMAP_CACHE_BYTES = 28L * 1024L * 1024L
private const val MEDIUM_MEMORY_BITMAP_CACHE_BYTES = 56L * 1024L * 1024L
private const val MAX_BITMAP_CACHE_BYTES = 96L * 1024L * 1024L
private const val MAX_PREFETCH_PAGES = 6
