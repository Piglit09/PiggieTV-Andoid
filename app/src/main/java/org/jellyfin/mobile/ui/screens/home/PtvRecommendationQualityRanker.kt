package org.jellyfin.mobile.ui.screens.home

import org.jellyfin.sdk.model.UUID
import org.jellyfin.sdk.model.api.BaseItemDto

/**
 * Applies a small deterministic quality pass without replacing the server's candidate order.
 * Candidates only move when a nearby alternative improves an obvious metadata repetition.
 */
internal object PtvRecommendationQualityRanker {
    private const val DIVERSITY_LOOKAHEAD = 12

    fun rank(candidates: List<BaseItemDto>, maxItems: Int, currentItemId: UUID? = null): List<BaseItemDto> {
        if (maxItems <= 0) return emptyList()

        val seenIds = mutableSetOf<UUID>()
        val remaining = candidates.filterTo(mutableListOf()) { item ->
            item.id != currentItemId &&
                item.userData?.played != true &&
                seenIds.add(item.id)
        }
        val ranked = ArrayList<BaseItemDto>(minOf(maxItems, remaining.size))

        while (remaining.isNotEmpty() && ranked.size < maxItems) {
            val firstPenalty = remaining.first().diversityPenalty(ranked)
            val lookaheadSize = minOf(DIVERSITY_LOOKAHEAD, remaining.size)
            var selectedIndex = 0
            var selectedPenalty = firstPenalty

            for (index in 1 until lookaheadSize) {
                val penalty = remaining[index].diversityPenalty(ranked)
                if (penalty < selectedPenalty) {
                    selectedIndex = index
                    selectedPenalty = penalty
                    if (penalty == 0) break
                }
            }

            ranked += remaining.removeAt(selectedIndex)
        }

        return ranked
    }

    private fun BaseItemDto.diversityPenalty(selected: List<BaseItemDto>): Int {
        if (selected.size < 2) return 0

        val previous = selected[selected.lastIndex]
        val beforePrevious = selected[selected.lastIndex - 1]
        var penalty = 0

        val repeatedSeries = previous.seriesDiversityKey()
            ?.takeIf { key -> beforePrevious.seriesDiversityKey() == key }
        if (repeatedSeries != null && seriesDiversityKey() == repeatedSeries) penalty += 4

        val repeatedStudios = previous.studioDiversityKeys()
            .intersect(beforePrevious.studioDiversityKeys())
        if (studioDiversityKeys().any(repeatedStudios::contains)) penalty += 2

        val repeatedGenres = previous.genreDiversityKeys()
            .intersect(beforePrevious.genreDiversityKeys())
        if (genreDiversityKeys().any(repeatedGenres::contains)) penalty += 1

        return penalty
    }
}

private fun BaseItemDto.seriesDiversityKey(): String? = when {
    seriesId != null -> "id:$seriesId"

    else ->
        seriesName
            ?.takeIf(String::isNotBlank)
            ?.let { name -> "name:${name.normalizePtvText()}" }
}

private fun BaseItemDto.studioDiversityKeys(): Set<String> = studios.orEmpty()
    .map { studio -> studio.id.toString() }
    .toSet()

private fun BaseItemDto.genreDiversityKeys(): Set<String> = genres.orEmpty()
    .map(String::normalizePtvText)
    .filter(String::isNotBlank)
    .toSet()
