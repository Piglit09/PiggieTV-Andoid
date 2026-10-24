package org.jellyfin.mobile.feature.music

import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.HttpClientOptions
import org.jellyfin.sdk.api.client.HttpMethod
import org.jellyfin.sdk.api.client.RawResponse
import org.jellyfin.sdk.api.sockets.SocketApi
import org.jellyfin.sdk.api.sockets.SocketApiState
import org.jellyfin.sdk.model.ClientInfo
import org.jellyfin.sdk.model.DeviceInfo
import org.jellyfin.sdk.model.api.OutboundWebSocketMessage
import org.junit.jupiter.api.Test
import java.io.IOException
import java.util.Collections
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.reflect.KClass

class MusicRepositoryPlaylistBrowseTest {
    @Test
    fun `audio tagged playlist remains browsable and cannot be played as a track`() = runBlocking<Unit> {
        val client = PlaylistBrowseApiClient(playlists = listOf(playlist(1, mediaType = "Audio", isFolder = false)))

        val result = MusicRepository(client, savedUserIdProvider = { USER_ID })
            .loadBrowsePage(MusicBrowseKind.PLAYLISTS, startIndex = 0, limit = 10)

        result.items.single().type shouldBe org.jellyfin.sdk.model.api.BaseItemKind.PLAYLIST
        result.items.single().isFolder shouldBe true
        result.items.single().isPlayable shouldBe false
    }

    @Test
    fun `authorized empty playlist and later playlist page retain server positions and total`() = runBlocking<Unit> {
        val client = PlaylistBrowseApiClient(
            playlists = listOf(
                playlist(1, childCount = 0),
                playlist(2, childCount = 12),
                playlist(3, childCount = 13),
            ),
        )
        val repository = MusicRepository(client, savedUserIdProvider = { USER_ID })

        val first = repository.loadBrowsePage(MusicBrowseKind.PLAYLISTS, startIndex = 0, limit = 2)
        val later = repository.loadBrowsePage(MusicBrowseKind.PLAYLISTS, startIndex = 2, limit = 2)

        first.items.map(MusicItem::title) shouldBe listOf("Playlist 1", "Playlist 2")
        first.totalCount shouldBe 3
        first.startIndex shouldBe 0
        later.items.map(MusicItem::title) shouldBe listOf("Playlist 3")
        later.totalCount shouldBe 3
        later.startIndex shouldBe 2
        client.requests.map(Request::path) shouldContain "GET /Items"
        client.requests.any { it.path == "GET /Playlists/{playlistId}/Items" } shouldBe false
    }

    @Test
    fun `playlist children are audio only pages in authored order after video prefix`() = runBlocking<Unit> {
        val playlist = playlist(1, childCount = 122)
        val videoPrefix = (0 until 120).map { index -> child(index, "Video") }
        val firstTrack = child(120, "Audio")
        val secondTrack = child(121, "Audio")
        val client = PlaylistBrowseApiClient(
            playlists = listOf(playlist),
            playlistChildren = mapOf(playlist.id to videoPrefix + firstTrack + secondTrack),
        )
        val repository = MusicRepository(client, savedUserIdProvider = { USER_ID })
        val parent = repository.loadBrowsePage(MusicBrowseKind.PLAYLISTS, 0, 10).items.single()
        client.requests.clear()

        val firstPage = repository.loadChildrenPage(parent, startIndex = 0, limit = 1)
        val secondPage = repository.loadChildrenPage(parent, startIndex = 1, limit = 1)

        firstPage.items.map(MusicItem::id) shouldBe listOf(firstTrack.id)
        secondPage.items.map(MusicItem::id) shouldBe listOf(secondTrack.id)
        firstPage.totalCount shouldBe 2
        secondPage.totalCount shouldBe 2
        firstPage.startIndex shouldBe 0
        secondPage.startIndex shouldBe 1
        firstPage.hasMore shouldBe true
        secondPage.hasMore shouldBe false
        client.requests.any { it.path == "GET /Items" } shouldBe false
        client.requests.all { it.path == "GET /Playlists/{playlistId}/Items" } shouldBe true
        client.requests.all { it.query["userId"] == USER_ID || it.pathParameters["userId"] == USER_ID } shouldBe true
    }

    @Test
    fun `first playlist page stops after its audio window instead of scanning the full playlist`() = runBlocking<Unit> {
        val playlist = playlist(8, childCount = 1_120)
        val children = (0 until 120).map { index -> child(index, "Video") } +
            (120 until 1_120).map { index -> child(if (index == 127) 125 else index, "Audio") }
        val client = PlaylistBrowseApiClient(
            playlists = listOf(playlist),
            playlistChildren = mapOf(playlist.id to children),
        )
        val repository = MusicRepository(client, savedUserIdProvider = { USER_ID })
        val parent = repository.loadBrowsePage(MusicBrowseKind.PLAYLISTS, 0, 10).items.single()
        client.requests.clear()

        val firstPage = repository.loadChildrenPage(parent, startIndex = 0, limit = 10)

        firstPage.items.map(MusicItem::id) shouldBe children.drop(120).take(10).map(ChildRecord::id)
        firstPage.totalCount shouldBe 1_000
        firstPage.hasMore shouldBe true
        client.requests.filter { it.path == "GET /Playlists/{playlistId}/Items" }
            .map { it.query["startIndex"] } shouldBe listOf(0, 100)
    }

    @Test
    fun `full playlist queue scans each raw page once and keeps authored repeats`() = runBlocking<Unit> {
        val playlist = playlist(4, childCount = 351)
        val repeatedTrack = child(150, "Audio")
        val children = (0 until 125).map { index -> child(index, "Video") } +
            (125 until 305).map { index -> child(index, "Audio") } +
            repeatedTrack +
            (306 until 351).map { index -> child(index, "Audio") }
        val client = PlaylistBrowseApiClient(
            playlists = listOf(playlist),
            playlistChildren = mapOf(playlist.id to children),
        )
        val repository = MusicRepository(client, savedUserIdProvider = { USER_ID })
        val parent = repository.loadBrowsePage(MusicBrowseKind.PLAYLISTS, 0, 10).items.single()
        client.requests.clear()

        val queue = repository.loadPlaylistQueue(parent)

        queue.map(MusicItem::id) shouldBe children.filter { it.type == "Audio" }.map(ChildRecord::id)
        queue.size shouldBe 226
        queue.count { it.id == repeatedTrack.id } shouldBe 2
        val childRequests = client.requests.filter { it.path == "GET /Playlists/{playlistId}/Items" }
        childRequests.map { it.query["startIndex"] as Int }.sorted() shouldBe listOf(0, 100, 200, 300)
        childRequests.all { it.query["userId"] == USER_ID || it.pathParameters["userId"] == USER_ID } shouldBe true
        client.requests.size shouldBe 4
    }

    @Test
    fun `full playlist queue overlaps raw page requests without changing authored order`() = runBlocking<Unit> {
        val parent = playlist(10, childCount = 651)
        val repeatedTrack = child(150, "Audio")
        val children = (0 until 651).map { index ->
            if (index == 550) repeatedTrack else child(index, "Audio")
        }
        val client = PlaylistBrowseApiClient(
            playlists = listOf(parent),
            playlistChildren = mapOf(parent.id to children),
            playlistItemsDelayMs = 75,
        )
        val repository = MusicRepository(client, savedUserIdProvider = { USER_ID })
        val playlistItem = repository.loadBrowsePage(MusicBrowseKind.PLAYLISTS, 0, 10).items.single()

        val queue = repository.loadPlaylistQueue(playlistItem)

        queue.map(MusicItem::id) shouldBe children.map(ChildRecord::id)
        queue.count { it.id == repeatedTrack.id } shouldBe 2
        (client.maxPlaylistItemRequestsInFlight.get() in 2..4) shouldBe true
        client.requests.filter { it.path == "GET /Playlists/{playlistId}/Items" }
            .map { it.query["startIndex"] as Int }.sorted() shouldBe listOf(0, 100, 200, 300, 400, 500, 600)
    }

    @Test
    fun `full playlist queue starts another page when one slot finishes while an earlier page is held`() =
        runBlocking<Unit> {
            val parent = playlist(11, childCount = 651)
            val repeatedTrack = child(150, "Audio")
            val children = (0 until 651).map { index ->
                if (index == 550) repeatedTrack else child(index, "Audio")
            }
            val firstWaveStarted = CompletableDeferred<Unit>()
            val nextPageStarted = CompletableDeferred<Unit>()
            val firstWaveCount = AtomicInteger()
            val heldPages = (100..400 step 100).associateWith { CompletableDeferred<Unit>() }
            val client = PlaylistBrowseApiClient(
                playlists = listOf(parent),
                playlistChildren = mapOf(parent.id to children),
                onPlaylistItemsPage = { startIndex ->
                    heldPages[startIndex]?.let { gate ->
                        if (firstWaveCount.incrementAndGet() == heldPages.size) firstWaveStarted.complete(Unit)
                        gate.await()
                    }
                    if (startIndex == 500) nextPageStarted.complete(Unit)
                },
            )
            val repository = MusicRepository(client, savedUserIdProvider = { USER_ID })
            val playlistItem = repository.loadBrowsePage(MusicBrowseKind.PLAYLISTS, 0, 10).items.single()

            val queueRequest = async { repository.loadPlaylistQueue(playlistItem) }
            try {
                withTimeout(5_000) { firstWaveStarted.await() }
                client.maxPlaylistItemRequestsInFlight.get() shouldBe 4
                nextPageStarted.isCompleted shouldBe false

                // A finished page should release one slot without waiting for the still-held earlier pages.
                heldPages.getValue(200).complete(Unit)
                withTimeout(5_000) { nextPageStarted.await() }
                (client.maxPlaylistItemRequestsInFlight.get() <= 4) shouldBe true
            } finally {
                heldPages.values.forEach { it.complete(Unit) }
            }

            val queue = queueRequest.await()
            queue.map(MusicItem::id) shouldBe children.map(ChildRecord::id)
            queue.count { it.id == repeatedTrack.id } shouldBe 2
            client.requests.filter { it.path == "GET /Playlists/{playlistId}/Items" }
                .map { it.query["startIndex"] as Int }.sorted() shouldBe listOf(0, 100, 200, 300, 400, 500, 600)
        }

    @Test
    fun `empty full playlist queue stays empty after one scoped request`() = runBlocking<Unit> {
        val parent = playlist(5, childCount = 0)
        val client = PlaylistBrowseApiClient(playlists = listOf(parent))
        val repository = MusicRepository(client, savedUserIdProvider = { USER_ID })
        val playlistItem = repository.loadBrowsePage(MusicBrowseKind.PLAYLISTS, 0, 10).items.single()
        client.requests.clear()

        repository.loadPlaylistQueue(playlistItem) shouldBe emptyList()
        client.requests.map(Request::path) shouldBe listOf("GET /Playlists/{playlistId}/Items")
    }

    @Test
    fun `failed later raw page does not return a partial playlist queue`() = runBlocking<Unit> {
        val parent = playlist(6, childCount = 250)
        val children = (0 until 250).map { index -> child(index, "Audio") }
        val client = PlaylistBrowseApiClient(
            playlists = listOf(parent),
            playlistChildren = mapOf(parent.id to children),
            failPlaylistItemsFromStartIndex = 200,
        )
        val repository = MusicRepository(client, savedUserIdProvider = { USER_ID })
        val playlistItem = repository.loadBrowsePage(MusicBrowseKind.PLAYLISTS, 0, 10).items.single()

        val failure = runCatching { repository.loadPlaylistQueue(playlistItem) }.exceptionOrNull()

        (failure is IOException) shouldBe true
    }

    @Test
    fun `full playlist queue rejects an account change during pagination`() = runBlocking<Unit> {
        val parent = playlist(7, childCount = 250)
        val selectedUser = AtomicReference(USER_ID)
        val children = (0 until 250).map { index -> child(index, "Audio") }
        val client = PlaylistBrowseApiClient(
            playlists = listOf(parent),
            playlistChildren = mapOf(parent.id to children),
            onPlaylistItemsRequest = { startIndex -> if (startIndex == 100) selectedUser.set(OTHER_USER_ID) },
        )
        val repository = MusicRepository(client, savedUserIdProvider = selectedUser::get)
        val playlistItem = repository.loadBrowsePage(MusicBrowseKind.PLAYLISTS, 0, 10).items.single()

        val failure = runCatching { repository.loadPlaylistQueue(playlistItem) }.exceptionOrNull()

        (failure is IllegalStateException) shouldBe true
        client.requests.filter { it.path == "GET /Playlists/{playlistId}/Items" }
            .all { it.query["userId"] == USER_ID || it.pathParameters["userId"] == USER_ID } shouldBe true
    }

    @Test
    fun `bounded playlist browse rejects an account change before returning the first page`() = runBlocking<Unit> {
        val parent = playlist(9, childCount = 300)
        var selectedUser = USER_ID
        val client = PlaylistBrowseApiClient(
            playlists = listOf(parent),
            playlistChildren = mapOf(parent.id to (0 until 300).map { index -> child(index, "Audio") }),
            onPlaylistItemsRequest = { selectedUser = OTHER_USER_ID },
        )
        val repository = MusicRepository(client, savedUserIdProvider = { selectedUser })
        val playlistItem = repository.loadBrowsePage(MusicBrowseKind.PLAYLISTS, 0, 10).items.single()

        val failure = runCatching { repository.loadChildrenPage(playlistItem, 0, 10) }.exceptionOrNull()

        (failure is IllegalStateException) shouldBe true
    }

    @Test
    fun `uncached playlist listing failure remains a failure`() = runBlocking<Unit> {
        val client = PlaylistBrowseApiClient(failListing = true)
        val repository = MusicRepository(client, savedUserIdProvider = { USER_ID })

        val failure = runCatching { repository.loadBrowsePage(MusicBrowseKind.PLAYLISTS, 0, 10) }.exceptionOrNull()

        (failure is IOException) shouldBe true
    }

    @Test
    fun `failed later playlist page does not return an empty cached window`() = runBlocking<Unit> {
        val client = PlaylistBrowseApiClient(
            playlists = listOf(playlist(1), playlist(2), playlist(3)),
            failListingFromStartIndex = 2,
        )
        val repository = MusicRepository(client, savedUserIdProvider = { USER_ID })
        repository.loadBrowsePage(MusicBrowseKind.PLAYLISTS, 0, 2).items.size shouldBe 2

        val failure = runCatching { repository.loadBrowsePage(MusicBrowseKind.PLAYLISTS, 2, 2) }.exceptionOrNull()

        (failure is IOException) shouldBe true
    }

    private class PlaylistBrowseApiClient(
        val playlists: List<PlaylistRecord> = emptyList(),
        val playlistChildren: Map<UUID, List<ChildRecord>> = emptyMap(),
        val failListing: Boolean = false,
        val failListingFromStartIndex: Int? = null,
        val failPlaylistItemsFromStartIndex: Int? = null,
        val onPlaylistItemsRequest: ((Int) -> Unit)? = null,
        val onPlaylistItemsPage: (suspend (Int) -> Unit)? = null,
        val playlistItemsDelayMs: Long = 0,
    ) : ApiClient() {
        val requests = Collections.synchronizedList(mutableListOf<Request>())
        val maxPlaylistItemRequestsInFlight = AtomicInteger()
        private val playlistItemRequestsInFlight = AtomicInteger()

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

        override fun update(baseUrl: String?, accessToken: String?, clientInfo: ClientInfo, deviceInfo: DeviceInfo) =
            Unit

        override suspend fun request(
            method: HttpMethod,
            pathTemplate: String,
            pathParameters: Map<String, Any?>,
            queryParameters: Map<String, Any?>,
            requestBody: Any?,
        ): RawResponse {
            val path = "${method.name} $pathTemplate"
            requests += Request(path, pathParameters, queryParameters)
            return when (path) {
                "GET /Users/Me" -> jsonResponse(
                    """{"Id":"$USER_ID","Name":"Piggie","HasPassword":false,"HasConfiguredPassword":false,"HasConfiguredEasyPassword":false}""",
                )

                "GET /Items" -> {
                    val start = queryParameters["startIndex"] as? Int ?: 0
                    if (failListing || (failListingFromStartIndex != null && start >= failListingFromStartIndex)) {
                        throw IOException("playlist listing unavailable")
                    }
                    page(playlists, queryParameters) { it.toJson() }
                }

                "GET /Playlists/{playlistId}/Items" -> {
                    val playlistId = pathParameters["playlistId"] as UUID
                    val startIndex = queryParameters["startIndex"] as? Int ?: 0
                    val inFlight = playlistItemRequestsInFlight.incrementAndGet()
                    maxPlaylistItemRequestsInFlight.updateAndGet { maxOf(it, inFlight) }
                    try {
                        onPlaylistItemsRequest?.invoke(startIndex)
                        onPlaylistItemsPage?.invoke(startIndex)
                        if (playlistItemsDelayMs > 0) delay(playlistItemsDelayMs)
                        if (failPlaylistItemsFromStartIndex != null && startIndex >= failPlaylistItemsFromStartIndex) {
                            throw IOException("playlist items unavailable")
                        }
                        page(playlistChildren[playlistId].orEmpty(), queryParameters) { it.toJson() }
                    } finally {
                        playlistItemRequestsInFlight.decrementAndGet()
                    }
                }

                else -> if (path.contains("Views")) {
                    jsonResponse("""{"Items":[],"TotalRecordCount":0,"StartIndex":0}""")
                } else {
                    error("Unexpected request $path")
                }
            }
        }

        private fun <T> page(items: List<T>, query: Map<String, Any?>, toJson: (T) -> String): RawResponse {
            val start = (query["startIndex"] as? Int ?: 0).coerceAtLeast(0)
            val limit = (query["limit"] as? Int ?: items.size).coerceAtLeast(0)
            val sliced = items.drop(start).take(limit)
            val serializedItems = sliced.joinToString(",", transform = toJson)
            return jsonResponse(
                """{"Items":[$serializedItems],"TotalRecordCount":${items.size},"StartIndex":$start}""",
            )
        }

        private fun jsonResponse(json: String) = RawResponse(
            body = json.encodeToByteArray(),
            status = 200,
            headers = emptyMap(),
        )
    }

    private data class Request(val path: String, val pathParameters: Map<String, Any?>, val query: Map<String, Any?>)

    private data class PlaylistRecord(
        val id: UUID,
        val title: String,
        val mediaType: String? = null,
        val isFolder: Boolean = true,
        val childCount: Int = 0,
    ) {
        fun toJson() = """{
            "Id":"$id", "Name":"$title", "Type":"Playlist", "IsFolder":$isFolder,
            "ChildCount":$childCount ${mediaType?.let { ", \"MediaType\":\"$it\"" }.orEmpty()}
        }
        """.trimIndent()
    }

    private data class ChildRecord(val id: UUID, val type: String) {
        fun toJson() = """{"Id":"$id","Name":"$type $id","Type":"$type","MediaType":"$type","IsFolder":false}"""
    }

    private fun playlist(number: Int, mediaType: String? = null, isFolder: Boolean = true, childCount: Int = 0) =
        PlaylistRecord(UUID(0L, number.toLong()), "Playlist $number", mediaType, isFolder, childCount)

    private fun child(number: Int, type: String) = ChildRecord(UUID(1L, number.toLong()), type)

    private companion object {
        val USER_ID: UUID = UUID.fromString("00000000-0000-0000-0000-000000000001")
        val OTHER_USER_ID: UUID = UUID.fromString("00000000-0000-0000-0000-000000000002")
    }
}
