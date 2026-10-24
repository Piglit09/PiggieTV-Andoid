package org.jellyfin.mobile.feature.music

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.jellyfin.sdk.model.api.BaseItemKind
import org.junit.jupiter.api.Test
import java.util.UUID

class MusicRecommendationSessionRestoreTest {
    @Test
    fun `restored recommendation context retains bounded mode seed feedback and catalog cursor`() {
        val seed = track(1)
        val played = track(2)
        val skipped = track(3)
        val recommended = track(4)
        val owner = MusicRecommendationSessionOwner("https://music.example", UUID(1L, 2L))

        val restored = MusicRecommendationSessionContext.restore(
            owner = owner,
            seed = seed,
            mode = MusicListeningMode.BALANCED_DISCOVERY,
            recentPlayedItems = listOf(played),
            skippedIds = listOf(skipped.id),
            recommendedIds = listOf(seed.id, played.id, skipped.id, recommended.id),
            catalogOffset = 50,
            catalogExhausted = false,
            revision = 7,
        )

        restored.owner shouldBe owner
        restored.seed.id shouldBe seed.id
        restored.selectedMode shouldBe MusicListeningMode.BALANCED_DISCOVERY
        restored.recentPlayedIds shouldBe listOf(played.id)
        restored.skippedIds shouldBe listOf(skipped.id)
        restored.recommendedIds shouldBe listOf(seed.id, played.id, skipped.id, recommended.id)
        restored.catalogOffset shouldBe 50
        restored.revision shouldBe 7
    }

    @Test
    fun `oversized or unplayable restored context is rejected`() {
        val seed = track(1)
        val owner = MusicRecommendationSessionOwner("https://music.example", UUID(1L, 2L))

        shouldThrow<IllegalArgumentException> {
            MusicRecommendationSessionContext.restore(
                owner, seed, MusicListeningMode.BALANCED_DISCOVERY,
                emptyList(), emptyList(), List(513) { UUID(0L, it.toLong()) }, 0, false, 0,
            )
        }
        shouldThrow<IllegalArgumentException> {
            MusicRecommendationSessionContext.restore(
                owner, seed.copy(isPlayable = false), MusicListeningMode.BALANCED_DISCOVERY,
                emptyList(), emptyList(), listOf(seed.id), 0, false, 0,
            )
        }
    }

    private fun track(index: Long) = MusicItem(
        id = UUID(0L, index), title = "Track $index", subtitle = "Artist", album = "Album",
        albumId = null, artist = "Artist", artistIds = emptyList(), genres = listOf("Metal"),
        type = BaseItemKind.AUDIO, collectionType = null, posterUrl = null, backdropUrl = null,
        container = "mp3", codec = "mp3", playCount = 0, progress = null,
        isFavorite = false, isFolder = false, isPlayable = true,
    )
}
