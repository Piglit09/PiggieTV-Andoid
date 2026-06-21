package org.jellyfin.mobile.feature.music

import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.HttpClientOptions
import org.jellyfin.sdk.api.client.HttpMethod
import org.jellyfin.sdk.api.client.RawResponse
import org.jellyfin.sdk.api.sockets.SocketApi
import org.jellyfin.sdk.api.sockets.SocketApiState
import org.jellyfin.sdk.model.ClientInfo
import org.jellyfin.sdk.model.DeviceInfo
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.CreatePlaylistDto
import org.jellyfin.sdk.model.api.OutboundWebSocketMessage
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.reflect.KClass

class MusicRepositoryAutoPlaylistTest {
    @Test
    fun `existing auto playlist adds current track`() {
        runBlocking {
            val apiClient = FakePlaylistApiClient(
                playlists = mutableListOf(PlaylistRecord(AUTO_PLAYLIST_ID, AUTO_PLAYLIST_NAME)),
                playlistItems = mutableMapOf(AUTO_PLAYLIST_ID to mutableSetOf()),
            )
            val message = MusicRepository(apiClient).addToAutoPlaylist(trackItem(), source = "phone")

            message shouldBe "Added to $AUTO_PLAYLIST_NAME."
            apiClient.playlistItems[AUTO_PLAYLIST_ID].orEmpty() shouldContain TRACK_ID
            apiClient.paths shouldContain "GET /Users/Me"
            apiClient.paths shouldContain "GET /Items"
            apiClient.paths shouldContain "GET /Playlists/{playlistId}/Items"
            apiClient.paths shouldContain "POST /Playlists/{playlistId}/Items"
            apiClient.paths shouldNotContain "POST /Playlists"
        }
    }

    @Test
    fun `missing auto playlist creates then adds current track`() {
        runBlocking {
            val apiClient = FakePlaylistApiClient()
            val message = MusicRepository(apiClient).addToAutoPlaylist(trackItem(), source = "androidAuto")

            message shouldBe "Added to $AUTO_PLAYLIST_NAME."
            apiClient.playlists.map(PlaylistRecord::id) shouldContain AUTO_PLAYLIST_ID
            apiClient.playlistItems[AUTO_PLAYLIST_ID].orEmpty() shouldContain TRACK_ID
            apiClient.paths shouldContain "POST /Playlists"
            apiClient.paths shouldContain "POST /Playlists/{playlistId}/Items"
            apiClient.createdPlaylistIds shouldContain TRACK_ID
        }
    }

    @Test
    fun `duplicate auto playlist item skips add request`() {
        runBlocking {
            val apiClient = FakePlaylistApiClient(
                playlists = mutableListOf(PlaylistRecord(AUTO_PLAYLIST_ID, AUTO_PLAYLIST_NAME)),
                playlistItems = mutableMapOf(AUTO_PLAYLIST_ID to mutableSetOf(TRACK_ID)),
            )
            val message = MusicRepository(apiClient).addToAutoPlaylist(trackItem(), source = "phone")

            message shouldBe "Already in $AUTO_PLAYLIST_NAME."
            apiClient.paths shouldContain "GET /Playlists/{playlistId}/Items"
            apiClient.paths shouldNotContain "POST /Playlists/{playlistId}/Items"
        }
    }

    private class FakePlaylistApiClient(
        val playlists: MutableList<PlaylistRecord> = mutableListOf(),
        val playlistItems: MutableMap<UUID, MutableSet<UUID>> = mutableMapOf(),
    ) : ApiClient() {
        val paths = mutableListOf<String>()
        val createdPlaylistIds = mutableListOf<UUID>()

        override val baseUrl: String = "https://jellyfin.example.invalid"
        override val accessToken: String = "test-token"
        override val clientInfo: ClientInfo = ClientInfo(name = "test", version = "1")
        override val deviceInfo: DeviceInfo = DeviceInfo(id = "test-device", name = "test")
        override val httpClientOptions: HttpClientOptions = HttpClientOptions()
        override val webSocket: SocketApi = object : SocketApi {
            override val state = MutableStateFlow<SocketApiState>(SocketApiState.Disconnected())

            override fun subscribeAll(): Flow<OutboundWebSocketMessage> = emptyFlow()

            override fun <T : OutboundWebSocketMessage> subscribe(messageType: KClass<T>): Flow<T> = emptyFlow()
        }

        override fun update(
            baseUrl: String?,
            accessToken: String?,
            clientInfo: ClientInfo,
            deviceInfo: DeviceInfo,
        ) = Unit

        override suspend fun request(
            method: HttpMethod,
            pathTemplate: String,
            pathParameters: Map<String, Any?>,
            queryParameters: Map<String, Any?>,
            requestBody: Any?,
        ): RawResponse {
            paths += "${method.name} $pathTemplate"
            return when ("${method.name} $pathTemplate") {
                "GET /Users/Me" -> jsonResponse(
                    """
                    {
                      "Id": "$USER_ID",
                      "Name": "Piggie",
                      "HasPassword": false,
                      "HasConfiguredPassword": false,
                      "HasConfiguredEasyPassword": false
                    }
                    """,
                )

                "GET /Items" -> jsonResponse(
                    """
                    {
                      "Items": [${playlists.joinToString(",") { it.toItemJson() }}],
                      "TotalRecordCount": ${playlists.size},
                      "StartIndex": 0
                    }
                    """,
                )

                "POST /Playlists" -> {
                    val dto = requestBody as CreatePlaylistDto
                    createdPlaylistIds += dto.ids
                    playlists += PlaylistRecord(AUTO_PLAYLIST_ID, dto.name)
                    playlistItems.getOrPut(AUTO_PLAYLIST_ID) { mutableSetOf() }
                    jsonResponse("""{"Id":"$AUTO_PLAYLIST_ID"}""")
                }

                "GET /Playlists/{playlistId}/Items" -> {
                    val playlistId = pathParameters["playlistId"] as UUID
                    val ids = playlistItems[playlistId].orEmpty()
                    jsonResponse(
                        """
                        {
                          "Items": [${ids.joinToString(",") { id -> audioItemJson(id) }}],
                          "TotalRecordCount": ${ids.size},
                          "StartIndex": 0
                        }
                        """,
                    )
                }

                "POST /Playlists/{playlistId}/Items" -> {
                    val playlistId = pathParameters["playlistId"] as UUID
                    @Suppress("UNCHECKED_CAST")
                    val ids = queryParameters["ids"] as Collection<UUID>
                    playlistItems.getOrPut(playlistId) { mutableSetOf() }.addAll(ids)
                    RawResponse(ByteArray(0), status = 204, headers = emptyMap())
                }

                else -> error("Unexpected request ${method.name} $pathTemplate")
            }
        }

        private fun jsonResponse(json: String) = RawResponse(
            body = json.trimIndent().encodeToByteArray(),
            status = 200,
            headers = emptyMap(),
        )

        private fun audioItemJson(id: UUID): String = """
            {
              "Id": "$id",
              "Name": "Track",
              "Type": "Audio",
              "MediaType": "Audio",
              "IsFolder": false
            }
        """.trimIndent()
    }

    private data class PlaylistRecord(val id: UUID, val title: String) {
        fun toItemJson(): String = """
            {
              "Id": "$id",
              "Name": "$title",
              "Type": "Playlist",
              "IsFolder": true,
              "ChildCount": 1
            }
        """.trimIndent()
    }

    private fun trackItem() = MusicItem(
        id = TRACK_ID,
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

    private companion object {
        const val AUTO_PLAYLIST_NAME = "PTV Auto Picks"
        val USER_ID: UUID = UUID.fromString("00000000-0000-0000-0000-000000000001")
        val TRACK_ID: UUID = UUID.fromString("11111111-1111-1111-1111-111111111111")
        val AUTO_PLAYLIST_ID: UUID = UUID.fromString("22222222-2222-2222-2222-222222222222")
    }
}
