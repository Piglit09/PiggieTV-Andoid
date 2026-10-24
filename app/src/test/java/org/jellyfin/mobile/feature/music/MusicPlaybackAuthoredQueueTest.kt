package org.jellyfin.mobile.feature.music

import io.kotest.matchers.shouldBe
import org.jellyfin.sdk.model.api.BaseItemKind
import org.junit.jupiter.api.Test
import java.util.UUID

class MusicPlaybackAuthoredQueueTest {
    private val first = track(1)
    private val second = track(2)
    private val third = track(3)

    @Test
    fun `playlist selection keeps authored repeats and the selected occurrence`() {
        val queue = listOf(first, second, first, third)

        val selection = requireNotNull(
            selectAuthoredMusicPlaybackQueue(first, queue, selectedIndex = 2, shuffleEnabled = false),
        )

        selection.originalQueue.map(MusicItem::id) shouldBe listOf(first.id, second.id, first.id, third.id)
        selection.playbackQueue.map(MusicItem::id) shouldBe listOf(first.id, second.id, first.id, third.id)
        selection.currentIndex shouldBe 2
        selection.selectedItem.id shouldBe first.id
    }

    @Test
    fun `playlist selection rejects an invalid index item or unplayable entry`() {
        val queue = listOf(first, second, first)

        selectAuthoredMusicPlaybackQueue(first, queue, 3, shuffleEnabled = false) shouldBe null
        selectAuthoredMusicPlaybackQueue(second, queue, 2, shuffleEnabled = false) shouldBe null
        selectAuthoredMusicPlaybackQueue(
            first,
            listOf(first, second.copy(isPlayable = false), first),
            2,
            shuffleEnabled = false,
        ) shouldBe null
    }

    @Test
    fun `playlist shuffle retains every repeated occurrence`() {
        val queue = listOf(first, second, first, third)

        val selection = requireNotNull(
            selectAuthoredMusicPlaybackQueue(first, queue, selectedIndex = 2, shuffleEnabled = true),
        )

        selection.currentIndex shouldBe 0
        selection.playbackQueue.first().id shouldBe first.id
        val playbackCounts = selection.playbackQueue.groupingBy(MusicItem::id).eachCount()
        val authoredCounts = queue.groupingBy(MusicItem::id).eachCount()
        playbackCounts shouldBe authoredCounts
    }

    @Test
    fun `media item sync retains current repeat and finds the next occurrence`() {
        val queue = listOf(first, second, first, third)

        musicQueueIndexForMediaId(queue, currentIndex = 2, mediaId = first.id.toString()) shouldBe 2
        musicQueueIndexForMediaId(queue, currentIndex = 1, mediaId = first.id.toString()) shouldBe 2
        musicQueueIndexForMediaId(queue, currentIndex = 3, mediaId = first.id.toString()) shouldBe 0
        musicQueueIndexForMediaId(queue, currentIndex = 2, mediaId = UUID.randomUUID().toString()) shouldBe 2
    }

    @Test
    fun `consecutive duplicate waits for indexed track end instead of an ambiguous auto transition`() {
        shouldBufferNextMusicQueueOccurrence(
            listOf(first, first, second),
            currentIndex = 0,
            nextIndex = 1,
        ) shouldBe false
        shouldBufferNextMusicQueueOccurrence(
            listOf(first, second, first),
            currentIndex = 1,
            nextIndex = 2,
        ) shouldBe true
    }

    @Test
    fun `restored controller queue keeps every repeated occurrence and its selected index`() {
        val restored = requireNotNull(selectRestoredMusicPlaybackQueue(listOf(first, second, first), currentIndex = 2))

        restored.queue.map(MusicItem::id) shouldBe listOf(first.id, second.id, first.id)
        restored.currentIndex shouldBe 2
        selectRestoredMusicPlaybackQueue(listOf(first, second.copy(isPlayable = false)), 1) shouldBe null
    }

    @Test
    fun `shuffle round trip keeps the selected authored occurrence`() {
        val authored = listOf(first, second, first, third)
        val shuffled = requireNotNull(
            reorderMusicPlaybackQueue(authored, authored.indices.toList(), currentIndex = 2, shuffleEnabled = true),
        )

        shuffled.currentIndex shouldBe 0
        shuffled.originalIndices[shuffled.currentIndex] shouldBe 2
        shuffled.originalIndices.sorted() shouldBe authored.indices.toList()
        shuffled.queue.mapIndexed { index, item -> item.id to shuffled.originalIndices[index] } shouldBe
            shuffled.originalIndices.map { sourceIndex -> authored[sourceIndex].id to sourceIndex }

        val otherRepeatIndex = shuffled.originalIndices.indexOf(0)
        val unshuffled = requireNotNull(
            reorderMusicPlaybackQueue(
                authored,
                shuffled.originalIndices,
                currentIndex = otherRepeatIndex,
                shuffleEnabled = false,
            ),
        )
        unshuffled.queue.map(MusicItem::id) shouldBe authored.map(MusicItem::id)
        unshuffled.currentIndex shouldBe 0
        unshuffled.originalIndices shouldBe authored.indices.toList()
    }

    @Test
    fun `retry selects exact repeated occurrence while legacy queue uses its original path`() {
        val authored = listOf(first, second, first)

        retryAuthoredMusicQueueIndex(authored, listOf(2, 1, 0), currentIndex = 0, failedItem = first) shouldBe 2
        retryAuthoredMusicQueueIndex(authored, listOf(2, 1, 0), currentIndex = 2, failedItem = first) shouldBe 0
        retryAuthoredMusicQueueIndex(authored, listOf(2, 1, 0), currentIndex = 0, failedItem = second) shouldBe null
        retryAuthoredMusicQueueIndex(listOf(first, second), listOf(1, 0), 1, first) shouldBe null
    }

    @Test
    fun `recommendation refill appends without changing shuffled playback prefix or current index`() {
        val shuffledPlayback = listOf(third, first, second)
        val replacement = selectPlaybackOrderPreservingAppend(
            currentQueue = shuffledPlayback,
            currentIndex = 1,
            proposedQueue = shuffledPlayback + track(4),
        )

        replacement?.queue?.map(MusicItem::id) shouldBe (shuffledPlayback + track(4)).map(MusicItem::id)
        replacement?.currentIndex shouldBe 1
        replacement?.originalIndices shouldBe (0..3).toList()
        selectPlaybackOrderPreservingAppend(shuffledPlayback, 1, listOf(first, second, third, track(4))) shouldBe null
    }

    @Test
    fun `recommendation window drops only consumed prefix and rebases the current index`() {
        val fourth = track(4)
        val fifth = track(5)
        val playback = listOf(first, second, third, fourth)

        val replacement = selectPlaybackOrderPreservingAppend(
            currentQueue = playback,
            currentIndex = 2,
            proposedQueue = listOf(second, third, fourth, fifth),
        )

        replacement?.queue?.map(MusicItem::id) shouldBe listOf(second, third, fourth, fifth).map(MusicItem::id)
        replacement?.currentIndex shouldBe 1
        replacement?.originalIndices shouldBe (0..3).toList()
        selectPlaybackOrderPreservingAppend(playback, 2, listOf(fourth, fifth)) shouldBe null
        selectPlaybackOrderPreservingAppend(playback, 2, listOf(second, fourth, third, fifth)) shouldBe null
        selectPlaybackOrderPreservingAppend(playback, 2, listOf(second, third, fourth, first)) shouldBe null
    }

    @Test
    fun `play next keeps repeated playlist entries and the selected occurrence`() {
        val fourth = track(4)
        val playback = listOf(first, second, first, third)

        val inserted = MusicQueueActions.playNext(playback, currentIndex = 2, item = fourth)
        inserted.queue.map(MusicItem::id) shouldBe listOf(first, second, first, fourth, third).map(MusicItem::id)
        inserted.currentIndex shouldBe 2

        val moved = MusicQueueActions.playNext(playback, currentIndex = 2, item = second)
        moved.queue.map(MusicItem::id) shouldBe listOf(first, first, second, third).map(MusicItem::id)
        moved.currentIndex shouldBe 1
    }

    @Test
    fun `play next moves one upcoming repeat without removing other occurrences`() {
        val playback = listOf(first, second, first, second, third)

        val result = MusicQueueActions.playNext(playback, currentIndex = 2, item = second)

        result.queue.map(MusicItem::id) shouldBe playback.map(MusicItem::id)
        result.currentIndex shouldBe 2
    }

    @Test
    fun `play next keeps unique queue move behavior`() {
        val playback = listOf(first, second, third, track(4))

        val result = MusicQueueActions.playNext(playback, currentIndex = 2, item = second)

        result.queue.map(MusicItem::id) shouldBe listOf(first, third, second, track(4)).map(MusicItem::id)
        result.currentIndex shouldBe 1
    }

    @Test
    fun `add to queue preserves selected repeated occurrence in controller update`() {
        val playback = listOf(first, second, first, third)
        val result = MusicQueueActions.addToQueue(playback, currentIndex = 2, item = track(4))

        result.queue.map(MusicItem::id) shouldBe (playback + track(4)).map(MusicItem::id)
        result.currentIndex shouldBe 2
        selectMusicQueueCurrentIndexAfterUpdate(result.queue, first, result.currentIndex) shouldBe 2

        val existing = MusicQueueActions.addToQueue(playback, currentIndex = 2, item = first)
        existing.queue.map(MusicItem::id) shouldBe playback.map(MusicItem::id)
        selectMusicQueueCurrentIndexAfterUpdate(existing.queue, first, existing.currentIndex) shouldBe 2
    }

    private fun track(suffix: Int): MusicItem = MusicItem(
        id = UUID.fromString("00000000-0000-0000-0000-${suffix.toString().padStart(12, '0')}"),
        title = "Track $suffix",
        subtitle = null,
        album = null,
        albumId = null,
        artist = null,
        artistIds = emptyList(),
        genres = emptyList(),
        type = BaseItemKind.AUDIO,
        collectionType = null,
        posterUrl = null,
        backdropUrl = null,
        container = null,
        codec = null,
        playCount = 0,
        progress = null,
        isFavorite = false,
        isFolder = false,
        isPlayable = true,
    )
}
