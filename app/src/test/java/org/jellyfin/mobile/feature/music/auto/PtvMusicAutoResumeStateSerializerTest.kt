package org.jellyfin.mobile.feature.music.auto

import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.jellyfin.mobile.feature.music.MusicItem
import org.jellyfin.mobile.feature.music.MusicPlaybackState
import org.jellyfin.mobile.feature.music.MusicRepeatMode
import org.jellyfin.sdk.model.api.BaseItemKind
import org.junit.jupiter.api.Test
import java.util.UUID

class PtvMusicAutoResumeStateSerializerTest {
    @Test
    fun `resume state serializes and deserializes playback queue`() {
        val state = PtvMusicAutoResumeState(
            queueItemIds = listOf(
                "11111111-1111-1111-1111-111111111111",
                "22222222-2222-2222-2222-222222222222",
            ),
            currentIndex = 1,
            positionMs = 42_000,
            shuffleEnabled = true,
            repeatMode = MusicRepeatMode.ALL.name,
            lastPlayedTimestampMs = 123_456,
            ownerServerId = 12,
            ownerUserId = 34,
        )

        val decoded = PtvMusicAutoResumeStateSerializer.decode(
            PtvMusicAutoResumeStateSerializer.encode(state),
        )

        decoded shouldBe state
        decoded.shouldNotBeNull()
        decoded.parsedRepeatMode shouldBe MusicRepeatMode.ALL
        decoded.queueUuids shouldHaveSize 2
        decoded.belongsTo(serverId = 12, userId = 34) shouldBe true
        decoded.belongsTo(serverId = 12, userId = 35) shouldBe false
    }

    @Test
    fun `invalid resume state is ignored`() {
        PtvMusicAutoResumeStateSerializer.decode("not-json").shouldBeNull()
    }

    @Test
    fun `legacy ownerless state never matches an authenticated account`() {
        val state = PtvMusicAutoResumeState(
            queueItemIds = listOf("11111111-1111-1111-1111-111111111111"),
            currentIndex = 0,
            positionMs = 0,
            shuffleEnabled = false,
            repeatMode = MusicRepeatMode.NONE.name,
            lastPlayedTimestampMs = 1,
        )

        state.belongsTo(serverId = 1, userId = 1) shouldBe false
        state.withOwner(serverId = 1, userId = 2).belongsTo(serverId = 1, userId = 2) shouldBe true
    }

    @Test
    fun `old saved state without new fields still decodes under the same owner`() {
        val decoded = PtvMusicAutoResumeStateSerializer.decode(
            """
                {
                    "queueItemIds": [
                        "11111111-1111-1111-1111-111111111111",
                        "22222222-2222-2222-2222-222222222222"
                    ],
                    "currentIndex": 1,
                    "positionMs": 5000,
                    "shuffleEnabled": false,
                    "repeatMode": "NONE",
                    "lastPlayedTimestampMs": 7,
                    "ownerServerId": 12,
                    "ownerUserId": 34
                }
            """.trimIndent(),
        )

        decoded.shouldNotBeNull()
        decoded.queueItemIds shouldHaveSize 2
        decoded.currentIndex shouldBe 1
        decoded.belongsTo(12, 34) shouldBe true
        decoded.belongsTo(12, 35) shouldBe false
    }

    @Test
    fun `saved repeated track retains its selected occurrence through serialization`() {
        val firstId = "11111111-1111-1111-1111-111111111111"
        val secondId = "22222222-2222-2222-2222-222222222222"
        val state = PtvMusicAutoResumeState(
            queueItemIds = listOf(firstId, secondId, firstId),
            currentIndex = 2,
            positionMs = 12_000,
            shuffleEnabled = false,
            repeatMode = MusicRepeatMode.NONE.name,
            lastPlayedTimestampMs = 7,
            ownerServerId = 12,
            ownerUserId = 34,
        )

        val decoded = PtvMusicAutoResumeStateSerializer.decode(PtvMusicAutoResumeStateSerializer.encode(state))

        decoded.shouldNotBeNull()
        decoded.queueItemIds shouldBe listOf(firstId, secondId, firstId)
        decoded.currentIndex shouldBe 2
        decoded.currentItemId shouldBe firstId
    }

    @Test
    fun `trimmed queue reindexes selected occurrence after blank entries`() {
        val firstId = "11111111-1111-1111-1111-111111111111"
        val secondId = "22222222-2222-2222-2222-222222222222"
        val state = PtvMusicAutoResumeState(
            queueItemIds = listOf(firstId, " ", secondId, firstId),
            currentIndex = 3,
            positionMs = -1,
            shuffleEnabled = false,
            repeatMode = MusicRepeatMode.NONE.name,
            lastPlayedTimestampMs = 7,
        )

        state.trimmed().queueItemIds shouldBe listOf(firstId, secondId, firstId)
        state.trimmed().currentIndex shouldBe 2
        state.trimmed().positionMs shouldBe 0
    }

    @Test
    fun `playback snapshot keeps the selected repeated occurrence`() {
        val first = musicItem(UUID.fromString("11111111-1111-1111-1111-111111111111"))
        val second = musicItem(UUID.fromString("22222222-2222-2222-2222-222222222222"))
        val queue = listOf(first, second, first)

        val saved = PtvMusicAutoResumeState.fromPlaybackState(
            MusicPlaybackState(queue = queue, currentItem = first, currentIndex = 2),
            nowMs = 7,
        )

        saved.shouldNotBeNull()
        saved.queueItemIds shouldBe queue.map { it.id.toString() }
        saved.currentIndex shouldBe 2
    }

    @Test
    fun `playback state serialization keeps current item when queue is capped`() {
        val queue = (0 until 110).map { index ->
            musicItem(UUID.fromString("00000000-0000-0000-0000-${index.toString().padStart(12, '0')}"))
        }
        val currentItem = queue.last()

        val resumeState = PtvMusicAutoResumeState.fromPlaybackState(
            playbackState = MusicPlaybackState(
                queue = queue,
                currentItem = currentItem,
                currentIndex = queue.lastIndex,
                positionMs = 9_500,
                shuffleEnabled = true,
                repeatMode = MusicRepeatMode.ONE,
            ),
            nowMs = 77,
        )

        resumeState.shouldNotBeNull()
        resumeState.queueItemIds shouldHaveSize PtvMusicAutoResumeState.MAX_PERSISTED_QUEUE_ITEMS
        resumeState.currentItemId shouldBe currentItem.id.toString()
        resumeState.queueItemIds shouldBe queue.drop(10).map { it.id.toString() }
        resumeState.currentIndex shouldBe 99
        resumeState.positionMs shouldBe 9_500
        resumeState.shuffleEnabled shouldBe true
        resumeState.parsedRepeatMode shouldBe MusicRepeatMode.ONE
        resumeState.lastPlayedTimestampMs shouldBe 77
    }

    @Test
    fun `recommendation provenance survives serialization without storing an unbounded queue`() {
        val queue = (0 until 120).map { index ->
            musicItem(UUID.fromString("00000000-0000-0000-0000-${index.toString().padStart(12, '0')}"))
        }
        val source = PtvMusicAutoQueueProvenance.recommendation(
            queue = queue,
            snapshot = PtvMusicAutoRecommendationSnapshot(
                sourceMediaId = PtvMusicAutoIds.AUTO_PICKS,
                selectedMode = "BALANCED_DISCOVERY",
                seedId = queue.first().id.toString(),
                recentPlayedIds = listOf(queue[1].id.toString()),
                skippedIds = listOf(queue[2].id.toString()),
                recommendedIds = queue.map { it.id.toString() },
                catalogOffset = 25,
                catalogExhausted = false,
                revision = 3,
            ),
        )
        val state = PtvMusicAutoResumeState.fromPlaybackState(
            MusicPlaybackState(queue = queue, currentItem = queue[110], currentIndex = 110),
            nowMs = 7,
        ).shouldNotBeNull().copy(ownerServerId = 1, ownerUserId = 2, queueProvenance = source)

        val decoded = PtvMusicAutoResumeStateSerializer.decode(PtvMusicAutoResumeStateSerializer.encode(state))

        decoded.shouldNotBeNull()
        decoded.queueItemIds shouldHaveSize 100
        decoded.queueProvenance shouldBe source
        decoded.queueProvenance?.recommendation?.selectedMode shouldBe "BALANCED_DISCOVERY"
    }

    private fun musicItem(id: UUID) = MusicItem(
        id = id,
        title = "Track",
        subtitle = "Artist",
        album = "Album",
        albumId = null,
        artist = "Artist",
        artistIds = emptyList(),
        genres = emptyList(),
        type = BaseItemKind.AUDIO,
        collectionType = null,
        posterUrl = null,
        backdropUrl = null,
        container = "mp3",
        codec = "mp3",
        playCount = 0,
        progress = null,
        isFavorite = false,
        isFolder = false,
        isPlayable = true,
    )
}
