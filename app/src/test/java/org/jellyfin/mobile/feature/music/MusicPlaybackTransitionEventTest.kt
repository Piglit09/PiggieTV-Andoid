package org.jellyfin.mobile.feature.music

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.jellyfin.mobile.feature.music.auto.PtvMusicAutoQueueOwner
import org.jellyfin.sdk.model.api.BaseItemKind
import org.junit.jupiter.api.Test
import java.util.UUID

class MusicPlaybackTransitionEventTest {
    private val owner = PtvMusicAutoQueueOwner(1, 2)
    private val first = track(1)
    private val second = track(2)

    @Test
    fun `natural transition reports exact source and destination including repeated IDs`() = runBlocking<Unit> {
        val emitter = MusicPlaybackTransitionEmitter()
        val received = async(start = CoroutineStart.UNDISPATCHED) {
            withTimeout(1_000) { emitter.events.first() }
        }

        emitter.emit(
            queue = listOf(first, second, first),
            previousIndex = 1,
            nextIndex = 2,
            queueOwner = owner,
            currentOwner = owner,
            reason = MusicPlaybackTransitionReason.NATURAL,
        ) shouldBe true

        received.await() shouldBe MusicPlaybackTransitionEvent(
            owner = owner,
            previousItemId = second.id,
            nextItemId = first.id,
            previousIndex = 1,
            nextIndex = 2,
            reason = MusicPlaybackTransitionReason.NATURAL,
        )
    }

    @Test
    fun `explicit skip between repeated occurrences retains both queue indices`() = runBlocking<Unit> {
        val emitter = MusicPlaybackTransitionEmitter()
        val received = async(start = CoroutineStart.UNDISPATCHED) {
            withTimeout(1_000) { emitter.events.first() }
        }

        emitter.emit(
            queue = listOf(first, first),
            previousIndex = 0,
            nextIndex = 1,
            queueOwner = owner,
            currentOwner = owner,
            reason = MusicPlaybackTransitionReason.EXPLICIT_SKIP,
        ) shouldBe true

        received.await().let { event ->
            event.previousItemId shouldBe first.id
            event.nextItemId shouldBe first.id
            event.previousIndex shouldBe 0
            event.nextIndex shouldBe 1
            event.reason shouldBe MusicPlaybackTransitionReason.EXPLICIT_SKIP
        }
    }

    @Test
    fun `unowned or invalid transitions are rejected before emission`() {
        val emitter = MusicPlaybackTransitionEmitter()
        val queue = listOf(first, second)

        emitter.emit(
            queue,
            0,
            1,
            owner,
            PtvMusicAutoQueueOwner(1, 3),
            MusicPlaybackTransitionReason.NATURAL,
        ) shouldBe false
        emitter.emit(queue, 0, 1, null, owner, MusicPlaybackTransitionReason.NATURAL) shouldBe false
        emitter.emit(queue, 0, 0, owner, owner, MusicPlaybackTransitionReason.NATURAL) shouldBe false
        emitter.emit(queue, 0, 2, owner, owner, MusicPlaybackTransitionReason.EXPLICIT_SKIP) shouldBe false
        emitter.emit(queue, -1, 1, owner, owner, MusicPlaybackTransitionReason.EXPLICIT_SKIP) shouldBe false
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
