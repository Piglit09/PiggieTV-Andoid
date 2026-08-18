package org.jellyfin.mobile.feature.music

import org.jellyfin.sdk.model.UUID
import org.jellyfin.sdk.model.api.BaseItemKind
import java.util.Locale

internal enum class MusicRecommendationSource(val familiarity: MusicRecommendationFamiliarity, val baseScore: Int) {
    ALBUM_AFFINITY(MusicRecommendationFamiliarity.FAMILIAR, 32),
    ARTIST_AFFINITY(MusicRecommendationFamiliarity.FAMILIAR, 28),
    FAVORITE(MusicRecommendationFamiliarity.FAMILIAR, 24),
    LISTENING_HISTORY(MusicRecommendationFamiliarity.FAMILIAR, 20),
    GENRE_AFFINITY(MusicRecommendationFamiliarity.DISCOVERY, 16),
    RECOMMENDATION(MusicRecommendationFamiliarity.BALANCED, 12),
    DISCOVERY(MusicRecommendationFamiliarity.DISCOVERY, 8),
    FALLBACK(MusicRecommendationFamiliarity.DISCOVERY, 2),
    UNSPECIFIED(MusicRecommendationFamiliarity.BALANCED, 0),
}

internal enum class MusicRecommendationFamiliarity {
    FAMILIAR,
    DISCOVERY,
    BALANCED,
}

internal data class MusicRecommendationGroup(val source: MusicRecommendationSource, val items: List<MusicItem>)

/**
 * Surface policy shared by every native music recommendation entry point.
 *
 * Percentages are deliberately integers so the same inputs always produce the same bucket target
 * on every device. The range is an invariant when both buckets contain enough candidates; a
 * depleted bucket may be relaxed so a queue is never returned half empty.
 */
internal enum class MusicRecommendationSurface(val policy: MusicRecommendationPolicy) {
    HOME(MusicRecommendationPolicy(preferredFamiliarPercent = 70, minFamiliarPercent = 70, maxFamiliarPercent = 70)),
    MADE_FOR_YOU(
        MusicRecommendationPolicy(preferredFamiliarPercent = 70, minFamiliarPercent = 70, maxFamiliarPercent = 70),
    ),
    RADIO(
        MusicRecommendationPolicy(
            preferredFamiliarPercent = 25,
            minFamiliarPercent = 25,
            maxFamiliarPercent = 25,
            seedArtistAffinityBoost = 450L,
            seedAlbumAffinityBoost = 650L,
        ),
    ),
    RELATED(MusicRecommendationPolicy(preferredFamiliarPercent = 60, minFamiliarPercent = 60, maxFamiliarPercent = 60)),
    DISCOVERY(
        MusicRecommendationPolicy(preferredFamiliarPercent = 20, minFamiliarPercent = 20, maxFamiliarPercent = 20),
    ),
}

internal data class MusicRecommendationPolicy(
    val preferredFamiliarPercent: Int,
    val minFamiliarPercent: Int,
    val maxFamiliarPercent: Int,
    val maxConsecutiveArtist: Int = 2,
    val maxConsecutiveAlbum: Int = 2,
    val seedArtistAffinityBoost: Long = 180L,
    val seedAlbumAffinityBoost: Long = 240L,
) {
    init {
        require(minFamiliarPercent in 0..100)
        require(preferredFamiliarPercent in minFamiliarPercent..maxFamiliarPercent)
        require(maxFamiliarPercent in 0..100)
        require(maxConsecutiveArtist > 0)
        require(maxConsecutiveAlbum > 0)
    }

    fun familiarTarget(limit: Int): Int {
        val safeLimit = limit.coerceAtLeast(0)
        val minimum = (safeLimit * minFamiliarPercent + 99) / 100
        val maximum = maxOf(minimum, safeLimit * maxFamiliarPercent / 100)
        val preferred = (safeLimit * preferredFamiliarPercent + 50) / 100
        return preferred.coerceIn(minimum, maximum.coerceAtMost(safeLimit))
    }
}

/**
 * Produces a stable queue from Jellyfin catalog candidates and the user's bounded music signals.
 * The ranker is deliberately pure so the phone UI and Android Auto can share exactly the same
 * ordering and the quality policy can be exercised without an Android or Jellyfin runtime.
 */
internal object MusicRecommendationRanker {
    fun rank(
        seed: MusicItem? = null,
        groups: List<MusicRecommendationGroup>,
        affinityItems: List<MusicItem> = listOfNotNull(seed),
        notInterestedIds: Set<UUID> = emptySet(),
        excludedIds: Set<UUID> = emptySet(),
        maxItems: Int,
        surface: MusicRecommendationSurface = MusicRecommendationSurface.HOME,
    ): List<MusicItem> {
        val limit = maxItems.coerceAtLeast(0)
        if (limit == 0) return emptyList()

        val pinnedSeed = seed?.takeIf(MusicItem::isPlayable)
        val policy = surface.policy
        val profile = buildProfile(seed = seed, affinityItems = affinityItems)
        val mergedCandidates = linkedMapOf<UUID, MergedCandidate>()

        groups.forEach { group ->
            group.items.forEach { item ->
                if (!item.isPlayable || item.id == pinnedSeed?.id) return@forEach
                if (item.id in excludedIds || item.id in notInterestedIds) return@forEach

                val existing = mergedCandidates[item.id]
                if (existing == null) {
                    mergedCandidates[item.id] = MergedCandidate(
                        item = item,
                        sources = linkedSetOf(group.source),
                    )
                } else {
                    existing.sources += group.source
                }
            }
        }

        val candidates = mergedCandidates.values
            .map { candidate -> candidate.rank(profile, policy) }
            .sortedWith(
                compareByDescending<RankedCandidate>(RankedCandidate::score)
                    .thenBy { candidate -> candidate.item.title.normalizeMusicRecommendationText() }
                    .thenBy { candidate -> candidate.item.id.toString() },
            )
            .toMutableList()
        val result = mutableListOf<MusicItem>()
        val sourceCounts = mutableMapOf<MusicRecommendationSource, Int>()
        val artistCounts = mutableMapOf<String, Int>()
        val albumCounts = mutableMapOf<String, Int>()
        val familiarityCounts = mutableMapOf(
            MusicRecommendationBucket.FAMILIAR to 0,
            MusicRecommendationBucket.DISCOVERY to 0,
        )
        val familiarTarget = policy.familiarTarget(limit)
        val discoveryTarget = limit - familiarTarget
        val sourceCap = (((limit * 3) + 9) / 10).coerceIn(2, 8)
        val artistCap = ((limit + 4) / 5).coerceIn(2, 6)
        val albumCap = ((limit + 5) / 6).coerceIn(2, 5)

        pinnedSeed?.let { item ->
            result += item
            recordIdentity(item, artistCounts, albumCounts)
            familiarityCounts[MusicRecommendationBucket.FAMILIAR] = 1
        }

        while (result.size < limit && candidates.isNotEmpty()) {
            val familiarCount = familiarityCounts.getValue(MusicRecommendationBucket.FAMILIAR)
            val discoveryCount = familiarityCounts.getValue(MusicRecommendationBucket.DISCOVERY)
            val selectedBucketCount = familiarCount + discoveryCount
            val expectedFamiliarByNext = ((selectedBucketCount + 1) * familiarTarget + limit - 1) / limit
            val desiredBucket = when {
                familiarCount >= familiarTarget -> MusicRecommendationBucket.DISCOVERY
                discoveryCount >= discoveryTarget -> MusicRecommendationBucket.FAMILIAR
                familiarCount < expectedFamiliarByNext -> MusicRecommendationBucket.FAMILIAR
                else -> MusicRecommendationBucket.DISCOVERY
            }
            val selectedIndex = selectCandidateIndex(
                candidates = candidates,
                desiredBucket = desiredBucket,
                selectedItems = result,
                sourceCounts = sourceCounts,
                artistCounts = artistCounts,
                albumCounts = albumCounts,
                sourceCap = sourceCap,
                artistCap = artistCap,
                albumCap = albumCap,
                maxConsecutiveArtist = policy.maxConsecutiveArtist,
                maxConsecutiveAlbum = policy.maxConsecutiveAlbum,
            )
            if (selectedIndex < 0) break

            val selected = candidates.removeAt(selectedIndex)
            result += selected.item
            selected.primarySource?.let { source ->
                sourceCounts[source] = sourceCounts.getOrDefault(source, 0) + 1
            }
            recordIdentity(selected.item, artistCounts, albumCounts)
            familiarityCounts[selected.bucket] = familiarityCounts.getValue(selected.bucket) + 1
        }

        return result
    }

    private fun selectCandidateIndex(
        candidates: List<RankedCandidate>,
        desiredBucket: MusicRecommendationBucket,
        selectedItems: List<MusicItem>,
        sourceCounts: Map<MusicRecommendationSource, Int>,
        artistCounts: Map<String, Int>,
        albumCounts: Map<String, Int>,
        sourceCap: Int,
        artistCap: Int,
        albumCap: Int,
        maxConsecutiveArtist: Int,
        maxConsecutiveAlbum: Int,
    ): Int {
        val alternateBucket = desiredBucket.other()

        fun find(bucket: MusicRecommendationBucket?, enforceCaps: Boolean, enforceRun: Boolean): Int =
            candidates.indexOfFirst { candidate ->
                (bucket == null || candidate.bucket == bucket) &&
                    (
                        !enforceRun || !wouldExceedConsecutiveCap(
                            item = candidate.item,
                            selectedItems = selectedItems,
                            maxConsecutiveArtist = maxConsecutiveArtist,
                            maxConsecutiveAlbum = maxConsecutiveAlbum,
                        )
                        ) &&
                    (
                        !enforceCaps || candidate.withinCaps(
                            sourceCounts = sourceCounts,
                            artistCounts = artistCounts,
                            albumCounts = albumCounts,
                            sourceCap = sourceCap,
                            artistCap = artistCap,
                            albumCap = albumCap,
                        )
                        )
            }

        return find(desiredBucket, enforceCaps = true, enforceRun = true).takeIf { it >= 0 }
            ?: find(alternateBucket, enforceCaps = true, enforceRun = true).takeIf { it >= 0 }
            ?: find(bucket = null, enforceCaps = true, enforceRun = true).takeIf { it >= 0 }
            ?: find(desiredBucket, enforceCaps = false, enforceRun = true).takeIf { it >= 0 }
            ?: find(alternateBucket, enforceCaps = false, enforceRun = true).takeIf { it >= 0 }
            ?: find(bucket = null, enforceCaps = false, enforceRun = true).takeIf { it >= 0 }
            ?: find(bucket = null, enforceCaps = false, enforceRun = false)
    }

    private fun MergedCandidate.rank(
        profile: MusicAffinityProfile,
        policy: MusicRecommendationPolicy,
    ): RankedCandidate {
        val artistAffinity = item.recommendationArtistKeys()
            .maxOfOrNull { key -> profile.artistWeights[key] ?: 0 }
            ?: 0
        val albumAffinity = item.recommendationAlbumKey()?.let { key -> profile.albumWeights[key] } ?: 0
        val genreAffinity = item.recommendationGenreKeys()
            .map { key -> profile.genreWeights[key] ?: 0 }
            .sortedDescending()
            .take(2)
            .sum()
        val strongestSourceScore = sources.maxOfOrNull(MusicRecommendationSource::baseScore) ?: 0
        val seedArtistMatch = item.recommendationArtistKeys().intersect(profile.seedArtistKeys).isNotEmpty()
        val seedAlbumMatch = profile.seedAlbumKey != null && item.recommendationAlbumKey() == profile.seedAlbumKey
        val score = strongestSourceScore * 10L +
            (sources.size - 1).coerceAtLeast(0) * 15L +
            artistAffinity * 24L +
            albumAffinity * 28L +
            genreAffinity * 10L +
            item.playCount.coerceIn(0, 25) * 3L +
            (if (item.isFavorite) 90L else 0L) +
            (if (item.id in profile.favoriteIds) 110L else 0L) +
            (if (item.id in profile.listeningIds) 80L else 0L) +
            (if (seedArtistMatch) policy.seedArtistAffinityBoost else 0L) +
            (if (seedAlbumMatch) policy.seedAlbumAffinityBoost else 0L)
        val bucket = when {
            sources.any { source -> source.familiarity == MusicRecommendationFamiliarity.FAMILIAR } ->
                MusicRecommendationBucket.FAMILIAR

            item.id in profile.favoriteIds || item.id in profile.listeningIds ->
                MusicRecommendationBucket.FAMILIAR

            artistAffinity > 0 || albumAffinity > 0 -> MusicRecommendationBucket.FAMILIAR

            else -> MusicRecommendationBucket.DISCOVERY
        }
        val primarySource = sources
            .sortedWith(
                compareByDescending<MusicRecommendationSource>(MusicRecommendationSource::baseScore)
                    .thenBy(MusicRecommendationSource::ordinal),
            )
            .firstOrNull()

        return RankedCandidate(
            item = item,
            sources = sources,
            primarySource = primarySource,
            bucket = bucket,
            score = score,
        )
    }

    private fun buildProfile(seed: MusicItem?, affinityItems: List<MusicItem>): MusicAffinityProfile {
        val artistWeights = mutableMapOf<String, Int>()
        val albumWeights = mutableMapOf<String, Int>()
        val genreWeights = mutableMapOf<String, Int>()
        val favoriteIds = mutableSetOf<UUID>()
        val listeningIds = mutableSetOf<UUID>()
        val inputs = (listOfNotNull(seed) + affinityItems).distinctBy(MusicItem::id)

        inputs.forEach { item ->
            val isSeed = item.id == seed?.id
            val hasListeningSignal = item.playCount > 0 || item.progress != null
            val weight = when {
                isSeed -> 12
                item.isFavorite && hasListeningSignal -> 10
                item.isFavorite -> 8
                hasListeningSignal -> 6 + item.playCount.coerceIn(0, 8) / 2
                else -> 3
            }
            item.recommendationArtistKeys().forEach { key ->
                artistWeights[key] = artistWeights.getOrDefault(key, 0) + weight
            }
            item.recommendationAlbumKey()?.let { key ->
                albumWeights[key] = albumWeights.getOrDefault(key, 0) + weight
            }
            item.recommendationGenreKeys().forEach { key ->
                genreWeights[key] = genreWeights.getOrDefault(key, 0) + weight
            }
            if (item.isFavorite) favoriteIds += item.id
            if (hasListeningSignal) listeningIds += item.id
        }

        return MusicAffinityProfile(
            artistWeights = artistWeights,
            albumWeights = albumWeights,
            genreWeights = genreWeights,
            favoriteIds = favoriteIds,
            listeningIds = listeningIds,
            seedArtistKeys = seed?.recommendationArtistKeys().orEmpty(),
            seedAlbumKey = seed?.recommendationAlbumKey(),
        )
    }

    private fun RankedCandidate.withinCaps(
        sourceCounts: Map<MusicRecommendationSource, Int>,
        artistCounts: Map<String, Int>,
        albumCounts: Map<String, Int>,
        sourceCap: Int,
        artistCap: Int,
        albumCap: Int,
    ): Boolean {
        if (primarySource != null && sourceCounts.getOrDefault(primarySource, 0) >= sourceCap) return false
        if (item.recommendationArtistKeys().any { key -> artistCounts.getOrDefault(key, 0) >= artistCap }) {
            return false
        }
        val albumKey = item.recommendationAlbumKey()
        return albumKey == null || albumCounts.getOrDefault(albumKey, 0) < albumCap
    }

    private fun recordIdentity(
        item: MusicItem,
        artistCounts: MutableMap<String, Int>,
        albumCounts: MutableMap<String, Int>,
    ) {
        item.recommendationArtistKeys().forEach { key ->
            artistCounts[key] = artistCounts.getOrDefault(key, 0) + 1
        }
        item.recommendationAlbumKey()?.let { key ->
            albumCounts[key] = albumCounts.getOrDefault(key, 0) + 1
        }
    }

    private fun wouldExceedConsecutiveCap(
        item: MusicItem,
        selectedItems: List<MusicItem>,
        maxConsecutiveArtist: Int,
        maxConsecutiveAlbum: Int,
    ): Boolean {
        val artistKeys = item.recommendationArtistKeys()
        if (artistKeys.isNotEmpty()) {
            val artistRun = selectedItems.asReversed()
                .takeWhile { selected -> selected.recommendationArtistKeys().intersect(artistKeys).isNotEmpty() }
                .size
            if (artistRun >= maxConsecutiveArtist) return true
        }

        val albumKey = item.recommendationAlbumKey() ?: return false
        val albumRun = selectedItems.asReversed()
            .takeWhile { selected -> selected.recommendationAlbumKey() == albumKey }
            .size
        return albumRun >= maxConsecutiveAlbum
    }

    private data class MusicAffinityProfile(
        val artistWeights: Map<String, Int>,
        val albumWeights: Map<String, Int>,
        val genreWeights: Map<String, Int>,
        val favoriteIds: Set<UUID>,
        val listeningIds: Set<UUID>,
        val seedArtistKeys: Set<String>,
        val seedAlbumKey: String?,
    )

    private data class MergedCandidate(val item: MusicItem, val sources: MutableSet<MusicRecommendationSource>)

    private data class RankedCandidate(
        val item: MusicItem,
        val sources: Set<MusicRecommendationSource>,
        val primarySource: MusicRecommendationSource?,
        val bucket: MusicRecommendationBucket,
        val score: Long,
    )

    private enum class MusicRecommendationBucket {
        FAMILIAR,
        DISCOVERY,
        ;

        fun other(): MusicRecommendationBucket = when (this) {
            FAMILIAR -> DISCOVERY
            DISCOVERY -> FAMILIAR
        }
    }
}

private fun MusicItem.recommendationArtistKeys(): Set<String> = when {
    type == BaseItemKind.MUSIC_ARTIST -> setOf("id:$id")
    artistIds.isNotEmpty() -> artistIds.mapTo(linkedSetOf()) { artistId -> "id:$artistId" }
    !artist.isNullOrBlank() -> setOf("name:${artist.normalizeMusicRecommendationText()}")
    else -> emptySet()
}

private fun MusicItem.recommendationAlbumKey(): String? = when {
    type == BaseItemKind.MUSIC_ALBUM -> "id:$id"
    albumId != null -> "id:$albumId"
    !album.isNullOrBlank() -> "name:${album.normalizeMusicRecommendationText()}"
    else -> null
}

private fun MusicItem.recommendationGenreKeys(): Set<String> = genres
    .asSequence()
    .map(String::normalizeMusicRecommendationText)
    .filter(String::isNotBlank)
    .toCollection(linkedSetOf())

private fun String.normalizeMusicRecommendationText(): String = trim()
    .lowercase(Locale.ROOT)
    .replace(Regex("\\s+"), " ")
