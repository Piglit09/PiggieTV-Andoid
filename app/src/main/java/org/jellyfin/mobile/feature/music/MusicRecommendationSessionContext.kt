package org.jellyfin.mobile.feature.music

import org.jellyfin.sdk.model.UUID

/** The owner is captured when Android Auto starts a recommendation session. */
internal data class MusicRecommendationSessionOwner(val serverUrl: String, val userId: UUID)

/** A bounded snapshot that can be checked before an asynchronous queue refill is applied. */
internal class MusicRecommendationSessionContext private constructor(
    val owner: MusicRecommendationSessionOwner,
    val seed: MusicItem,
    val selectedMode: MusicListeningMode,
    val recentPlayedItems: List<MusicItem>,
    val skippedIds: List<UUID>,
    val recommendedIds: List<UUID>,
    val catalogOffset: Int,
    val catalogExhausted: Boolean,
    val revision: Long,
) {
    val recentPlayedIds: List<UUID> get() = recentPlayedItems.map(MusicItem::id)

    fun recordPlayed(item: MusicItem): MusicRecommendationSessionContext = updated(
        recentPlayedItems = (recentPlayedItems.filterNot { it.id == item.id } + item).takeLast(MAX_RECENT_PLAYED),
    )

    fun recordSkipped(itemId: UUID): MusicRecommendationSessionContext = updated(
        skippedIds = (skippedIds.filterNot { it == itemId } + itemId).takeLast(MAX_SKIPPED),
    )

    internal fun withBatch(
        items: List<MusicItem>,
        nextCatalogOffset: Int,
        exhausted: Boolean,
    ): MusicRecommendationSessionContext = updated(
        recommendedIds = (recommendedIds + items.map(MusicItem::id))
            .distinct()
            .takeLast(MAX_RECOMMENDED),
        catalogOffset = nextCatalogOffset,
        catalogExhausted = exhausted,
    )

    private fun updated(
        recentPlayedItems: List<MusicItem> = this.recentPlayedItems,
        skippedIds: List<UUID> = this.skippedIds,
        recommendedIds: List<UUID> = this.recommendedIds,
        catalogOffset: Int = this.catalogOffset,
        catalogExhausted: Boolean = this.catalogExhausted,
    ) = MusicRecommendationSessionContext(
        owner = owner,
        seed = seed,
        selectedMode = selectedMode,
        recentPlayedItems = recentPlayedItems,
        skippedIds = skippedIds,
        recommendedIds = recommendedIds,
        catalogOffset = catalogOffset,
        catalogExhausted = catalogExhausted,
        revision = revision + 1,
    )

    companion object {
        internal fun restore(
            owner: MusicRecommendationSessionOwner,
            seed: MusicItem,
            mode: MusicListeningMode,
            recentPlayedItems: List<MusicItem>,
            skippedIds: List<UUID>,
            recommendedIds: List<UUID>,
            catalogOffset: Int,
            catalogExhausted: Boolean,
            revision: Long,
        ): MusicRecommendationSessionContext {
            require(owner.serverUrl.isNotBlank() && seed.isPlayable && recentPlayedItems.all(MusicItem::isPlayable))
            require(
                recentPlayedItems.size <= MAX_RECENT_PLAYED && skippedIds.size <= MAX_SKIPPED &&
                    recommendedIds.isNotEmpty() && recommendedIds.size <= MAX_RECOMMENDED,
            )
            require(recentPlayedItems.map(MusicItem::id).distinct().size == recentPlayedItems.size)
            require(
                skippedIds.distinct().size == skippedIds.size &&
                    recommendedIds.distinct().size == recommendedIds.size,
            )
            require(catalogOffset >= 0 && revision >= 0)
            return MusicRecommendationSessionContext(
                owner = owner,
                seed = seed,
                selectedMode = mode,
                recentPlayedItems = recentPlayedItems,
                skippedIds = skippedIds,
                recommendedIds = recommendedIds,
                catalogOffset = catalogOffset,
                catalogExhausted = catalogExhausted,
                revision = revision,
            )
        }

        internal fun start(
            owner: MusicRecommendationSessionOwner,
            seed: MusicItem,
            initialQueue: List<MusicItem>,
            mode: MusicListeningMode,
        ): MusicRecommendationSessionContext = MusicRecommendationSessionContext(
            owner = owner,
            seed = seed,
            selectedMode = mode,
            recentPlayedItems = emptyList(),
            skippedIds = emptyList(),
            recommendedIds = (listOf(seed) + initialQueue)
                .filter(MusicItem::isPlayable)
                .map(MusicItem::id)
                .distinct()
                .takeLast(MAX_RECOMMENDED),
            catalogOffset = 0,
            catalogExhausted = false,
            revision = 0,
        )

        private const val MAX_RECENT_PLAYED = 32
        private const val MAX_SKIPPED = 64
        private const val MAX_RECOMMENDED = 512
    }
}

internal data class MusicRecommendationBatch(
    val items: List<MusicItem>,
    val context: MusicRecommendationSessionContext,
)
