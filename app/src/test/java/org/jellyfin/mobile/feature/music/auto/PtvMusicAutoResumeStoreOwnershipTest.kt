package org.jellyfin.mobile.feature.music.auto

import android.content.Context
import android.content.SharedPreferences
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.jellyfin.mobile.app.AppPreferences
import org.jellyfin.mobile.feature.music.MusicRepeatMode
import org.junit.jupiter.api.Test

class PtvMusicAutoResumeStoreOwnershipTest {
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
}
