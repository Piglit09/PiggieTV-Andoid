package org.jellyfin.mobile.ui.screens.home

import io.kotest.matchers.shouldBe
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.NameGuidPair
import org.jellyfin.sdk.model.api.UserItemDataDto
import org.junit.jupiter.api.Test
import java.util.UUID

class PtvRecommendationQualityRankerTest {
    @Test
    fun `filters played and current items and keeps the first duplicate`() {
        val current = item("current")
        val played = item("played", played = true)
        val first = item("keep", name = "Server first")
        val duplicate = first.copy(name = "Later duplicate")

        val ranked = PtvRecommendationQualityRanker.rank(
            candidates = listOf(current, played, first, duplicate),
            maxItems = 10,
            currentItemId = current.id,
        )

        ranked shouldBe listOf(first)
    }

    @Test
    fun `preserves server order when candidates are already diverse`() {
        val candidates = listOf(
            item("one", genres = listOf("Action"), studio = "studio-a"),
            item("two", genres = listOf("Comedy"), studio = "studio-b"),
            item("three", genres = listOf("Drama"), studio = "studio-c"),
        )

        PtvRecommendationQualityRanker.rank(candidates, maxItems = 3) shouldBe candidates
    }

    @Test
    fun `defers a third item from the same series when an alternative is nearby`() {
        val seriesA = uuid("series-a")
        val seriesB = uuid("series-b")
        val first = item("a-one", seriesId = seriesA)
        val second = item("a-two", seriesId = seriesA)
        val third = item("a-three", seriesId = seriesA)
        val alternative = item("b-one", seriesId = seriesB)

        val ranked = PtvRecommendationQualityRanker.rank(
            candidates = listOf(first, second, third, alternative),
            maxItems = 4,
        )

        ranked shouldBe listOf(first, second, alternative, third)
    }

    @Test
    fun `uses genre and studio metadata to break repeated runs`() {
        val first = item("one", genres = listOf("Action"), studio = "studio-a")
        val second = item("two", genres = listOf("Action"), studio = "studio-a")
        val repeated = item("three", genres = listOf("Action"), studio = "studio-a")
        val alternative = item("four", genres = listOf("Comedy"), studio = "studio-b")

        val ranked = PtvRecommendationQualityRanker.rank(
            candidates = listOf(first, second, repeated, alternative),
            maxItems = 4,
        )

        ranked shouldBe listOf(first, second, alternative, repeated)
    }

    @Test
    fun `keeps deterministic server order when no diverse alternative exists`() {
        val candidates = listOf(
            item("one", genres = listOf("Horror"), studio = "studio-a"),
            item("two", genres = listOf("Horror"), studio = "studio-a"),
            item("three", genres = listOf("Horror"), studio = "studio-a"),
            item("four", genres = listOf("Horror"), studio = "studio-a"),
        )

        val first = PtvRecommendationQualityRanker.rank(candidates, maxItems = 4)
        val second = PtvRecommendationQualityRanker.rank(candidates, maxItems = 4)

        first shouldBe candidates
        second shouldBe first
    }

    private fun item(
        key: String,
        name: String = key,
        played: Boolean = false,
        seriesId: UUID? = null,
        genres: List<String> = emptyList(),
        studio: String? = null,
    ): BaseItemDto {
        val id = uuid(key)
        return BaseItemDto(
            id = id,
            type = BaseItemKind.MOVIE,
            name = name,
            seriesId = seriesId,
            genres = genres,
            studios = studio?.let { studioKey ->
                listOf(NameGuidPair(id = uuid(studioKey), name = studioKey))
            },
            userData = UserItemDataDto(
                playbackPositionTicks = 0,
                playCount = if (played) 1 else 0,
                isFavorite = false,
                played = played,
                key = key,
                itemId = id,
            ),
        )
    }

    private fun uuid(key: String): UUID = UUID.nameUUIDFromBytes(key.toByteArray())
}
