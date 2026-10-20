package org.jellyfin.mobile.feature.music

import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.nulls.shouldBeNull
import io.mockk.mockk
import org.jellyfin.mobile.feature.music.auto.PtvMusicAutoQueueOwner
import org.junit.jupiter.api.Test

class MusicPlaybackQueueOwnershipTest {
    @Test
    fun `foreign active queue must be discarded without clearing new account resume`() {
        val ownerA = PtvMusicAutoQueueOwner(serverId = 1, userId = 2)
        val ownerB = PtvMusicAutoQueueOwner(serverId = 1, userId = 3)
        val track = mockk<MusicItem>()
        val activeA = MusicPlaybackState(
            queue = listOf(track),
            currentItem = track,
            queueOwner = ownerA,
            isPlaying = true,
        )

        shouldDiscardUnownedMusicQueue(activeA, ownerB).shouldBeTrue()
        shouldDiscardUnownedMusicQueue(activeA, null).shouldBeTrue()
        shouldDiscardUnownedMusicQueue(activeA, ownerA).shouldBeFalse()
        shouldClearSavedMusicQueueOnStop(activeA, ownerB).shouldBeFalse()
        shouldClearSavedMusicQueueOnStop(activeA, ownerA).shouldBeTrue()
        shouldDiscardUnownedMusicQueue(MusicPlaybackState(), ownerB).shouldBeFalse()
        shouldClearSavedMusicQueueOnStop(
            MusicPlaybackState(),
            ownerB,
            pendingResidentClear = true,
        ).shouldBeFalse()
    }

    @Test
    fun `later play and stop commands supersede queued earlier commands`() {
        val order = MusicPlaybackCommandOrder()
        val oldPlay = order.issue()
        val stop = order.issue()
        order.isCurrent(oldPlay).shouldBeFalse()
        order.isCurrent(stop).shouldBeTrue()

        val newPlay = order.issue()
        order.isCurrent(stop).shouldBeFalse()
        order.isCurrent(newPlay).shouldBeTrue()
    }

    @Test
    fun `restoring second account cancels pending first account discard`() {
        val order = MusicPlaybackCommandOrder()
        val pendingDiscard = order.markForeignDiscardPending()
        order.hasPendingForeignDiscard().shouldBeTrue()

        val restore = order.issue()
        order.isCurrent(pendingDiscard).shouldBeFalse()
        order.isCurrent(restore).shouldBeTrue()
        order.hasPendingForeignDiscard().shouldBeTrue()

        order.completeResidentClear()
        order.hasPendingForeignDiscard().shouldBeFalse()

        val stop = order.issueStop()
        order.isCurrent(restore).shouldBeFalse()
        order.isCurrent(stop).shouldBeTrue()
        order.hasPendingResidentClear().shouldBeTrue()
        order.completeResidentClear()
        order.hasPendingResidentClear().shouldBeFalse()
    }

    @Test
    fun `stop publishes no previous account track metadata before player cleanup`() {
        val track = mockk<MusicItem>()
        val activeA = MusicPlaybackState(
            queue = listOf(track),
            currentItem = track,
            queueOwner = PtvMusicAutoQueueOwner(1, 2),
            isPlaying = true,
            isAppInForeground = true,
        )

        val stopped = emptyMusicPlaybackStateAfterStop(activeA)
        stopped.queue.isEmpty().shouldBeTrue()
        stopped.currentItem.shouldBeNull()
        stopped.queueOwner.shouldBeNull()
        stopped.isPlaying.shouldBeFalse()
        stopped.isAppInForeground.shouldBeTrue()
    }

    @Test
    fun `account switch invalidates resident queue and its persistence`() {
        val ownerA = PtvMusicAutoQueueOwner(serverId = 1, userId = 2)
        val ownerB = PtvMusicAutoQueueOwner(serverId = 1, userId = 3)
        val track = mockk<MusicItem>()
        val residentQueue = MusicPlaybackState(queue = listOf(track), currentItem = track, queueOwner = ownerA)

        isMusicPlaybackQueueOwnedBy(residentQueue, ownerA).shouldBeTrue()
        isMusicPlaybackQueueOwnedBy(residentQueue, ownerB).shouldBeFalse()
        isMusicPlaybackQueueOwnedBy(residentQueue, null).shouldBeFalse()
        isMusicPlaybackQueueOwnedBy(residentQueue.copy(queueOwner = null), ownerA).shouldBeFalse()
        ownerA.toString().contains("userId").shouldBeFalse()
    }

    @Test
    fun `new playback under second account establishes only its own queue ownership`() {
        val ownerA = PtvMusicAutoQueueOwner(serverId = 1, userId = 2)
        val ownerB = PtvMusicAutoQueueOwner(serverId = 1, userId = 3)
        val trackA = mockk<MusicItem>()
        val trackB = mockk<MusicItem>()
        val residentQueue = MusicPlaybackState(queue = listOf(trackA), currentItem = trackA, queueOwner = ownerA)
        val newQueue = residentQueue.copy(queue = listOf(trackB), currentItem = trackB, queueOwner = ownerB)

        isMusicPlaybackQueueOwnedBy(residentQueue, ownerB).shouldBeFalse()
        isMusicPlaybackQueueOwnedBy(newQueue, ownerB).shouldBeTrue()
        isMusicPlaybackQueueOwnedBy(newQueue, ownerA).shouldBeFalse()
        isMusicPlaybackQueueOwnedBy(newQueue.copy(queue = emptyList()), ownerB).shouldBeFalse()
    }

    @Test
    fun `queued command cannot follow a later account into its new queue`() {
        val ownerA = PtvMusicAutoQueueOwner(serverId = 1, userId = 2)
        val ownerB = PtvMusicAutoQueueOwner(serverId = 1, userId = 3)
        val trackA = mockk<MusicItem>()
        val trackB = mockk<MusicItem>()
        val queueA = MusicPlaybackState(queue = listOf(trackA), currentItem = trackA, queueOwner = ownerA)
        val queueB = MusicPlaybackState(queue = listOf(trackB), currentItem = trackB, queueOwner = ownerB)

        isMusicPlaybackCommandAuthorized(ownerA, queueA, ownerA).shouldBeTrue()
        isMusicPlaybackCommandAuthorized(ownerA, queueA, ownerB).shouldBeFalse()
        isMusicPlaybackCommandAuthorized(ownerA, queueB, ownerB).shouldBeFalse()
        isMusicPlaybackCommandAuthorized(ownerB, queueB, ownerB).shouldBeTrue()
        isMusicPlaybackCommandAuthorized(null, queueB, ownerB).shouldBeFalse()
    }

    @Test
    fun `resident queue mutation rejects previous account without blocking a fresh queue`() {
        val ownerA = PtvMusicAutoQueueOwner(serverId = 1, userId = 2)
        val ownerB = PtvMusicAutoQueueOwner(serverId = 1, userId = 3)
        val track = mockk<MusicItem>()
        val residentA = MusicPlaybackState(queue = listOf(track), currentItem = track, queueOwner = ownerA)
        val residentB = residentA.copy(queueOwner = ownerB)

        isMusicPlaybackResidentMutationAuthorized(true, residentA, ownerB).shouldBeFalse()
        isMusicPlaybackResidentMutationAuthorized(true, residentB, ownerB).shouldBeTrue()
        isMusicPlaybackResidentMutationAuthorized(false, MusicPlaybackState(), ownerB).shouldBeTrue()
    }

    @Test
    fun `resolved stream is rejected after local account changes before auth notification`() {
        val ownerA = PtvMusicAutoQueueOwner(serverId = 1, userId = 2)
        val ownerB = PtvMusicAutoQueueOwner(serverId = 1, userId = 3)

        isResolvedMusicSourceStillOwned(
            expectedOwner = ownerA,
            currentOwner = ownerB,
            residentOwner = ownerA,
            currentLoad = true,
        ).shouldBeFalse()

        isResolvedMusicSourceStillOwned(
            expectedOwner = ownerA,
            currentOwner = ownerA,
            residentOwner = ownerA,
            currentLoad = true,
        ).shouldBeTrue()
        isResolvedMusicSourceStillOwned(ownerA, ownerA, ownerA, currentLoad = false).shouldBeFalse()
        isResolvedMusicSourceStillOwned(ownerA, ownerA, ownerB, currentLoad = true).shouldBeFalse()
    }
}
