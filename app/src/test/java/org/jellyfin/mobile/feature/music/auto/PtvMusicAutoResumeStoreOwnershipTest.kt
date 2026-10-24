package org.jellyfin.mobile.feature.music.auto

import android.content.Context
import android.content.SharedPreferences
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.jellyfin.mobile.app.AppPreferences
import org.jellyfin.mobile.feature.music.MusicItem
import org.jellyfin.mobile.feature.music.MusicPlaybackState
import org.jellyfin.mobile.feature.music.MusicRepeatMode
import org.jellyfin.mobile.feature.music.reorderMusicPlaybackQueue
import org.jellyfin.sdk.model.UUID
import org.junit.jupiter.api.Test

class PtvMusicAutoResumeStoreOwnershipTest {
    @Test
    fun `missing selected owner retains saved queue until the account is available`() {
        val context = mockk<Context>()
        val preferences = mockk<AppPreferences>()
        val sharedPreferences = mockk<SharedPreferences>()
        val editor = mockk<SharedPreferences.Editor>()
        val state = savedState().withOwner(serverId = 1, userId = 2)
        var serverId: Long? = null
        var userId: Long? = 2
        every { context.getSharedPreferences(any(), Context.MODE_PRIVATE) } returns sharedPreferences
        every { preferences.currentServerId } answers { serverId }
        every { preferences.currentUserId } answers { userId }
        every { sharedPreferences.getString(any(), any()) } returns PtvMusicAutoResumeStateSerializer.encode(state)
        every { sharedPreferences.edit() } returns editor
        every { editor.remove(any()) } returns editor
        every { editor.apply() } returns Unit
        val store = PtvMusicAutoResumeStore(context, preferences)

        store.loadPlaybackState() shouldBe null
        serverId = 1
        userId = null
        store.loadPlaybackState() shouldBe null
        verify(exactly = 0) { editor.remove(any()) }

        userId = 2
        store.loadPlaybackState() shouldBe state
        verify(exactly = 0) { sharedPreferences.edit() }
    }

    @Test
    fun `known different selected owner removes the saved queue`() {
        val context = mockk<Context>()
        val preferences = mockk<AppPreferences>()
        val sharedPreferences = mockk<SharedPreferences>()
        val editor = mockk<SharedPreferences.Editor>()
        val state = savedState().withOwner(serverId = 1, userId = 2)
        every { context.getSharedPreferences(any(), Context.MODE_PRIVATE) } returns sharedPreferences
        every { preferences.currentServerId } returns 1
        every { preferences.currentUserId } returns 3
        every { sharedPreferences.getString(any(), any()) } returns PtvMusicAutoResumeStateSerializer.encode(state)
        every { sharedPreferences.edit() } returns editor
        every { editor.remove(any()) } returns editor
        every { editor.apply() } returns Unit

        PtvMusicAutoResumeStore(context, preferences).loadPlaybackState() shouldBe null
        verify(exactly = 1) { editor.remove("playback_state") }
    }

    @Test
    fun `old queue cannot be saved under a newly selected account`() {
        val context = mockk<Context>()
        val preferences = mockk<AppPreferences>()
        val sharedPreferences = mockk<SharedPreferences>()
        val editor = mockk<SharedPreferences.Editor>()
        var serverId = 1L
        var userId = 2L
        val savedValues = mutableListOf<String>()
        every { context.getSharedPreferences(any(), Context.MODE_PRIVATE) } returns sharedPreferences
        every { preferences.currentServerId } answers { serverId }
        every { preferences.currentUserId } answers { userId }
        every { sharedPreferences.edit() } returns editor
        every { sharedPreferences.getString("playback_state", null) } returns null
        every { editor.putString(any(), any()) } answers {
            savedValues += secondArg<String>()
            editor
        }
        every { editor.apply() } returns Unit
        val store = PtvMusicAutoResumeStore(context, preferences)
        val state = PtvMusicAutoResumeState(
            queueItemIds = listOf("11111111-1111-1111-1111-111111111111"),
            currentIndex = 0,
            positionMs = 12_000,
            shuffleEnabled = false,
            repeatMode = MusicRepeatMode.NONE.name,
            lastPlayedTimestampMs = 1,
        )
        val ownerA = PtvMusicAutoQueueOwner(serverId = 1, userId = 2)
        val ownerB = PtvMusicAutoQueueOwner(serverId = 1, userId = 3)

        store.savePlaybackState(state, expectedOwner = ownerA) shouldBe true
        userId = 3
        store.savePlaybackState(state, expectedOwner = ownerA) shouldBe false
        savedValues.size shouldBe 1
        store.savePlaybackState(state, expectedOwner = ownerB) shouldBe true
        savedValues.size shouldBe 2
        PtvMusicAutoResumeStateSerializer.decode(savedValues.last())?.belongsTo(serverId, userId) shouldBe true
        verify(exactly = 2) { sharedPreferences.edit() }
    }

    @Test
    fun `playlist provenance survives position and occurrence updates for the same owner only`() {
        val context = mockk<Context>()
        val preferences = mockk<AppPreferences>()
        val sharedPreferences = mockk<SharedPreferences>()
        val editor = mockk<SharedPreferences.Editor>()
        val owner = PtvMusicAutoQueueOwner(1, 2)
        var selectedUserId = 2L
        val source = PtvMusicAutoQueueProvenance(
            kind = PtvMusicAutoQueueProvenance.PLAYLIST,
            queueSha256 = "queue-hash",
            fullCurrentIndex = 0,
            playlistId = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
        )
        var savedValue = PtvMusicAutoResumeStateSerializer.encode(
            savedState().copy(
                queueItemIds = listOf(
                    "11111111-1111-1111-1111-111111111111",
                    "22222222-2222-2222-2222-222222222222",
                ),
                fullQueueSha256 = source.queueSha256,
                fullCurrentIndex = 0,
                queueProvenance = source,
            ).withOwner(1, 2),
        )
        every { context.getSharedPreferences(any(), Context.MODE_PRIVATE) } returns sharedPreferences
        every { preferences.currentServerId } returns 1
        every { preferences.currentUserId } answers { selectedUserId }
        every { sharedPreferences.getString("playback_state", null) } answers { savedValue }
        every { sharedPreferences.edit() } returns editor
        every { editor.putString("playback_state", any()) } answers {
            savedValue = secondArg()
            editor
        }
        every { editor.apply() } returns Unit
        val store = PtvMusicAutoResumeStore(context, preferences)
        val updated = checkNotNull(PtvMusicAutoResumeStateSerializer.decode(savedValue)).copy(
            currentIndex = 1,
            fullCurrentIndex = 1,
            positionMs = 42_000,
        )

        store.savePlaybackState(updated, owner) shouldBe true
        val retained = checkNotNull(PtvMusicAutoResumeStateSerializer.decode(savedValue))
        retained.queueProvenance shouldBe source.copy(fullCurrentIndex = 1)
        retained.positionMs shouldBe 42_000
        selectedUserId = 3
        store.savePlaybackState(updated, owner) shouldBe false
        PtvMusicAutoResumeStateSerializer.decode(savedValue) shouldBe retained
    }

    @Test
    fun `provenance cannot attach to a stale full queue with the same saved window`() {
        val context = mockk<Context>()
        val preferences = mockk<AppPreferences>()
        val sharedPreferences = mockk<SharedPreferences>()
        val owner = PtvMusicAutoQueueOwner(1, 2)
        val item = mockk<MusicItem>()
        every { item.id } returns UUID.fromString("11111111-1111-1111-1111-111111111111")
        val playbackState = MusicPlaybackState(
            queue = listOf(item),
            currentItem = item,
            currentIndex = 0,
            queueOwner = owner,
        )
        val current = checkNotNull(PtvMusicAutoResumeState.fromPlaybackState(playbackState))
        val stale = current.copy(fullQueueSha256 = "stale-full-queue").withOwner(1, 2)
        val source = PtvMusicAutoQueueProvenance(
            kind = PtvMusicAutoQueueProvenance.PLAYLIST,
            queueSha256 = checkNotNull(current.fullQueueSha256),
            playlistId = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
        )
        every { context.getSharedPreferences(any(), Context.MODE_PRIVATE) } returns sharedPreferences
        every { preferences.currentServerId } returns 1
        every { preferences.currentUserId } returns 2
        every { sharedPreferences.getString("playback_state", null) } returns
            PtvMusicAutoResumeStateSerializer.encode(stale)
        val store = PtvMusicAutoResumeStore(context, preferences)

        store.attachQueueProvenance(source, playbackState, owner) shouldBe false
        verify(exactly = 0) { sharedPreferences.edit() }
    }

    @Test
    fun `new selection clears prior source even when queue track IDs are identical`() {
        val context = mockk<Context>()
        val preferences = mockk<AppPreferences>()
        val sharedPreferences = mockk<SharedPreferences>()
        val editor = mockk<SharedPreferences.Editor>()
        val owner = PtvMusicAutoQueueOwner(1, 2)
        val item = mockk<MusicItem>()
        every { item.id } returns UUID.fromString("11111111-1111-1111-1111-111111111111")
        val playbackState = MusicPlaybackState(
            queue = listOf(item),
            currentItem = item,
            currentIndex = 0,
            queueOwner = owner,
        )
        val snapshot = checkNotNull(PtvMusicAutoResumeState.fromPlaybackState(playbackState))
        val playlistId = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")
        val sources = listOf(
            PtvMusicAutoQueueProvenance.playlist(playlistId, listOf(item), 0),
            PtvMusicAutoQueueProvenance(
                kind = PtvMusicAutoQueueProvenance.RECOMMENDATION,
                queueSha256 = checkNotNull(snapshot.fullQueueSha256),
            ),
        )
        var savedValue = ""
        every { context.getSharedPreferences(any(), Context.MODE_PRIVATE) } returns sharedPreferences
        every { preferences.currentServerId } returns 1
        every { preferences.currentUserId } returns 2
        every { sharedPreferences.getString("playback_state", null) } answers { savedValue }
        every { sharedPreferences.edit() } returns editor
        every { editor.putString("playback_state", any()) } answers {
            savedValue = secondArg()
            editor
        }
        every { editor.apply() } returns Unit
        val store = PtvMusicAutoResumeStore(context, preferences)

        sources.forEach { oldSource ->
            savedValue = PtvMusicAutoResumeStateSerializer.encode(
                snapshot.withOwner(1, 2).copy(queueProvenance = oldSource),
            )
            store.savePlaybackState(snapshot, owner, retainQueueProvenance = false) shouldBe true
            checkNotNull(PtvMusicAutoResumeStateSerializer.decode(savedValue)).queueProvenance shouldBe null
        }
    }

    @Test
    @Suppress("LongMethod") // Both toggle directions and the stale-source guard share one persisted fixture.
    fun `playlist shuffle transition atomically retains the selected repeated occurrence`() {
        val context = mockk<Context>()
        val preferences = mockk<AppPreferences>()
        val sharedPreferences = mockk<SharedPreferences>()
        val editor = mockk<SharedPreferences.Editor>()
        val owner = PtvMusicAutoQueueOwner(1, 2)
        val repeatedId = UUID.fromString("11111111-1111-1111-1111-111111111111")
        val otherId = UUID.fromString("22222222-2222-2222-2222-222222222222")
        val repeated = mockk<MusicItem>()
        val other = mockk<MusicItem>()
        every { repeated.id } returns repeatedId
        every { other.id } returns otherId
        val authored = listOf(repeated, other, repeated)
        val playlistId = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")
        val source = PtvMusicAutoQueueProvenance.playlist(playlistId, authored, 2)
        val initialPlaybackState = MusicPlaybackState(
            queue = authored,
            currentItem = repeated,
            currentIndex = 2,
            queueOwner = owner,
        )
        val initialResumeState = checkNotNull(PtvMusicAutoResumeState.fromPlaybackState(initialPlaybackState))
        var savedValue = PtvMusicAutoResumeStateSerializer.encode(
            initialResumeState.withOwner(1, 2).copy(queueProvenance = source),
        )
        var writeCount = 0
        var selectedUserId = 2L
        every { context.getSharedPreferences(any(), Context.MODE_PRIVATE) } returns sharedPreferences
        every { preferences.currentServerId } returns 1
        every { preferences.currentUserId } answers { selectedUserId }
        every { sharedPreferences.getString("playback_state", null) } answers { savedValue }
        every { sharedPreferences.edit() } returns editor
        every { editor.putString("playback_state", any()) } answers {
            writeCount++
            savedValue = secondArg()
            editor
        }
        every { editor.apply() } returns Unit
        val store = PtvMusicAutoResumeStore(context, preferences)

        val shuffled = checkNotNull(reorderMusicPlaybackQueue(authored, authored.indices.toList(), 2, true))
        val shuffledState = MusicPlaybackState(
            queue = shuffled.queue,
            currentItem = shuffled.queue[shuffled.currentIndex],
            currentIndex = shuffled.currentIndex,
            shuffleEnabled = true,
            queueOwner = owner,
        )
        val shuffledSource = source.forQueue(shuffled.queue, shuffled.currentIndex, authored, 2)
        store.savePlaylistQueueTransition(shuffledState, owner, source, shuffledSource) shouldBe true
        writeCount shouldBe 1
        val persistedShuffle = checkNotNull(PtvMusicAutoResumeStateSerializer.decode(savedValue))
        persistedShuffle.queueProvenance shouldBe shuffledSource
        persistedShuffle.queueItemIds shouldBe shuffled.queue.map { it.id.toString() }
        shuffledSource.selectedAuthoredIndex shouldBe 2

        val unshuffled = checkNotNull(
            reorderMusicPlaybackQueue(authored, shuffled.originalIndices, shuffled.currentIndex, false),
        )
        unshuffled.currentIndex shouldBe 2
        val unshuffledState = MusicPlaybackState(
            queue = unshuffled.queue,
            currentItem = unshuffled.queue[unshuffled.currentIndex],
            currentIndex = unshuffled.currentIndex,
            shuffleEnabled = false,
            queueOwner = owner,
        )
        val unshuffledSource = shuffledSource.forQueue(unshuffled.queue, unshuffled.currentIndex, authored, 2)
        store.savePlaylistQueueTransition(unshuffledState, owner, shuffledSource, unshuffledSource) shouldBe true
        writeCount shouldBe 2
        checkNotNull(PtvMusicAutoResumeStateSerializer.decode(savedValue)).queueProvenance shouldBe unshuffledSource
        unshuffledSource.selectedAuthoredIndex shouldBe 2
        (shuffledSource == unshuffledSource) shouldBe false

        val unchanged = savedValue
        store.savePlaylistQueueTransition(shuffledState, owner, shuffledSource, shuffledSource) shouldBe false
        savedValue shouldBe unchanged
        writeCount shouldBe 2

        selectedUserId = 3
        store.savePlaylistQueueTransition(shuffledState, owner, unshuffledSource, shuffledSource) shouldBe false
        savedValue shouldBe unchanged
        writeCount shouldBe 2
    }

    private fun savedState() = PtvMusicAutoResumeState(
        queueItemIds = listOf("11111111-1111-1111-1111-111111111111"),
        currentIndex = 0,
        positionMs = 12_000,
        shuffleEnabled = false,
        repeatMode = MusicRepeatMode.NONE.name,
        lastPlayedTimestampMs = 1,
    )
}
