package org.jellyfin.mobile.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jellyfin.mobile.data.dao.ServerDao
import org.jellyfin.mobile.data.dao.UserDao
import org.jellyfin.mobile.data.entity.ServerEntity
import org.jellyfin.mobile.feature.music.auto.PtvMusicSessionInvalidator
import org.jellyfin.sdk.Jellyfin
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.model.DeviceInfo
import java.util.UUID

class ApiClientController(
    private val appPreferences: AppPreferences,
    private val jellyfin: Jellyfin,
    private val apiClient: ApiClient,
    private val serverDao: ServerDao,
    private val userDao: UserDao,
    private val musicSessionInvalidator: PtvMusicSessionInvalidator,
) {
    @Volatile
    var authenticatedUserId: UUID? = null
        private set

    private val baseDeviceInfo: DeviceInfo
        get() = jellyfin.options.deviceInfo!!

    /**
     * Store server with [hostname] in the database.
     */
    suspend fun setupServer(hostname: String) {
        val previousServerId = appPreferences.currentServerId
        val serverId = withContext(Dispatchers.IO) {
            serverDao.getServerByHostname(hostname)?.id ?: serverDao.insert(hostname)
        }
        if (serverId != previousServerId) {
            musicSessionInvalidator.invalidate("serverChanged")
            appPreferences.currentUserId = null
            resetApiClientUser()
        }
        appPreferences.currentServerId = serverId
        apiClient.update(baseUrl = hostname)
    }

    suspend fun setupUser(serverId: Long, userId: UUID, accessToken: String) {
        val previousUserId = appPreferences.currentUserId
        val localUserId = withContext(Dispatchers.IO) {
            userDao.upsert(serverId, userId, accessToken)
        }
        if (serverId != appPreferences.currentServerId || localUserId != previousUserId) {
            musicSessionInvalidator.invalidate("userChanged")
        }
        appPreferences.currentUserId = localUserId
        configureApiClientUser(userId, accessToken)
    }

    suspend fun logoutCurrentUser() {
        musicSessionInvalidator.invalidate("logout")
        val localUserId = appPreferences.currentUserId
        if (localUserId != null) {
            withContext(Dispatchers.IO) {
                userDao.logout(localUserId)
            }
        }

        appPreferences.currentUserId = null
        resetApiClientUser()
    }

    suspend fun loadSavedServer(): ServerEntity? {
        val server = withContext(Dispatchers.IO) {
            val serverId = appPreferences.currentServerId ?: return@withContext null
            serverDao.getServer(serverId)
        }
        configureApiClientServer(server)
        return server
    }

    suspend fun loadSavedServerUser(): UUID? {
        val (server, serverUser) = withContext(Dispatchers.IO) {
            val serverId = appPreferences.currentServerId ?: return@withContext null to null
            val userId = appPreferences.currentUserId

            serverDao.getServer(serverId) to userId?.let { userDao.getServerUser(serverId, it) }
        }

        configureApiClientServer(serverUser?.server ?: server)

        val savedUser = serverUser?.user
        val accessToken = savedUser?.accessToken
        if (savedUser == null || accessToken == null) {
            musicSessionInvalidator.invalidate("missingSavedUser")
            resetApiClientUser()
            return null
        }

        configureApiClientUser(savedUser.userId, accessToken)
        return savedUser.userId
    }

    suspend fun loadPreviouslyUsedServers(): List<ServerEntity> = withContext(Dispatchers.IO) {
        serverDao.getAllServers().filterNot { server ->
            server.id == appPreferences.currentServerId
        }
    }

    private fun configureApiClientServer(server: ServerEntity?) {
        apiClient.update(baseUrl = server?.hostname)
    }

    private fun configureApiClientUser(userId: UUID, accessToken: String) {
        apiClient.update(
            accessToken = accessToken,
            // Append user id to device id to ensure uniqueness across sessions
            deviceInfo = baseDeviceInfo.copy(id = baseDeviceInfo.id + userId),
        )
        authenticatedUserId = userId
    }

    private fun resetApiClientUser() {
        apiClient.update(
            accessToken = null,
            deviceInfo = baseDeviceInfo,
        )
        authenticatedUserId = null
    }
}
