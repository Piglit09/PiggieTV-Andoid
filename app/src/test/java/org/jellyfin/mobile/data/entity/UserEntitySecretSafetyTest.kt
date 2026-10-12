package org.jellyfin.mobile.data.entity

import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import java.util.UUID

class UserEntitySecretSafetyTest {
    @Test
    fun `legacy migration token is redacted from entity representations`() {
        val token = "sentinel-legacy-token"
        val userId = UUID.randomUUID()
        val user = UserEntity(
            id = 7,
            serverId = 3,
            userId = userId,
            legacyAccessToken = token,
            lastLoginTimestamp = 11,
        )
        val serverUser = ServerUser(
            user = user,
            server = ServerEntity(id = 3, hostname = "https://example.test", lastUsedTimestamp = 5),
        )

        user.toString() shouldNotContain token
        user.toString() shouldNotContain userId.toString()
        serverUser.toString() shouldNotContain token
        serverUser.toString() shouldNotContain userId.toString()
    }
}
