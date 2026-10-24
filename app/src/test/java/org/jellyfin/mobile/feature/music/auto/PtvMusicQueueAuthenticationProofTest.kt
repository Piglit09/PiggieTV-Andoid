package org.jellyfin.mobile.feature.music.auto

import io.kotest.matchers.shouldBe
import io.mockk.mockk
import org.jellyfin.mobile.auth.AuthMethod
import org.jellyfin.mobile.auth.MobileAuthState
import org.jellyfin.mobile.feature.music.MusicItem
import org.jellyfin.mobile.feature.music.MusicPlaybackState
import org.junit.jupiter.api.Test
import java.util.UUID

class PtvMusicQueueAuthenticationProofTest {
    @Test
    fun `new auth identity cannot expose old queue while local preference still points to old user`() {
        val ownerA = PtvMusicAutoQueueOwner(serverId = 1, userId = 2)
        val trackA = mockk<MusicItem>()
        val residentA = MusicPlaybackState(queue = listOf(trackA), currentItem = trackA, queueOwner = ownerA)
        val userA = UUID.randomUUID().toString()
        val userB = UUID.randomUUID().toString()
        val authA = authenticated(userA)
        val authB = authenticated(userB)
        val proofA = verifiedPtvMusicQueueAuthenticationProof(residentA, authA, userA)

        isPtvMusicQueueAuthenticationCurrent(residentA, authA, proofA) shouldBe true
        isPtvMusicQueueAuthenticationCurrent(residentA, authB, proofA) shouldBe false
        verifiedPtvMusicQueueAuthenticationProof(residentA, authB, userA) shouldBe null
    }

    @Test
    fun `same user credential refresh reestablishes proof without changing queue owner`() {
        val owner = PtvMusicAutoQueueOwner(serverId = 1, userId = 2)
        val track = mockk<MusicItem>()
        val resident = MusicPlaybackState(queue = listOf(track), currentItem = track, queueOwner = owner)
        val userId = UUID.randomUUID().toString()
        val oldAuth = authenticated(userId)
        val refreshedAuth = authenticated(userId)
        val oldProof = verifiedPtvMusicQueueAuthenticationProof(resident, oldAuth, userId)
        val refreshedProof = verifiedPtvMusicQueueAuthenticationProof(resident, refreshedAuth, userId)

        isPtvMusicQueueAuthenticationCurrent(resident, refreshedAuth, oldProof) shouldBe true
        isPtvMusicQueueAuthenticationCurrent(resident, refreshedAuth, refreshedProof) shouldBe true
    }

    private fun authenticated(userId: String): MobileAuthState.Authenticated = MobileAuthState.Authenticated(
        serverId = "1",
        userId = userId,
        credentialInstanceId = UUID.randomUUID(),
        method = AuthMethod.PASSWORD,
        credentialEpoch = 1,
    )
}
