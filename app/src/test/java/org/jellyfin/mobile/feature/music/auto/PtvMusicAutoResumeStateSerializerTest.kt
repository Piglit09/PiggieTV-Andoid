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
        resumeState.positionMs shouldBe 9_500
        resumeState.shuffleEnabled shouldBe true
        resumeState.parsedRepeatMode shouldBe MusicRepeatMode.ONE
        resumeState.lastPlayedTimestampMs shouldBe 77
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
