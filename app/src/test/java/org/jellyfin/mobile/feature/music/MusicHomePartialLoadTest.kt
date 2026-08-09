package org.jellyfin.mobile.feature.music

import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import org.jellyfin.sdk.model.api.BaseItemKind
import org.junit.jupiter.api.Test
import java.util.UUID

class MusicHomePartialLoadTest {
    @Test
    fun `songs source failure does not hide loaded albums and artists`() {
        val album = musicItem(
            id = UUID.fromString("11111111-1111-1111-1111-111111111111"),
            type = BaseItemKind.MUSIC_ALBUM
        )
        val artist = musicItem(
            id = UUID.fromString("22222222-2222-2222-2222-222222222222"),
            type = BaseItemKind.MUSIC_ARTIST
        )
        val home = musicHome(
            albums = listOf(album),
            albumsTotalCount = 1,
            artists = listOf(artist),
            artistsTotalCount = 1,
            sourceErrors = mapOf(MusicHomeSource.SONGS to "PTV music home.songs timed out after 20000ms"),
        )

        home.sections.map(MusicSection::id) shouldContain "albums"
        home.sections.map(MusicSection::id) shouldContain "artists"
        home.sections.map(MusicSection::id) shouldNotContain "songs"
        home.songsError shouldBe "PTV music home.songs timed out after 20000ms"
    }

    @Test
    fun `source errors are isolated to the failed row`() {
        val home = musicHome(
            albums = listOf(musicItem(id = UUID.fromString("33333333-3333-3333-3333-333333333333"))),
            albumsTotalCount = 1,
            sourceErrors = mapOf(MusicHomeSource.SONGS to "songs failed"),
        )

        home.sourceErrors[MusicHomeSource.SONGS] shouldBe "songs failed"
        home.sourceErrors[MusicHomeSource.ALBUMS] shouldBe null
        home.albums.size shouldBe 1
        home.songs.size shouldBe 0
    }

    @Test
    fun `cached source hits can preserve successful partial data`() {
        val cachedAlbum = musicItem(id = UUID.fromString("44444444-4444-4444-4444-444444444444"))
        val home = musicHome(
            albums = listOf(cachedAlbum),
            albumsTotalCount = 1,
            sourceErrors = mapOf(MusicHomeSource.SONGS to "songs failed"),
            sourceCacheHits = setOf(MusicHomeSource.ALBUMS),
        )

        home.sourceCacheHits shouldContain MusicHomeSource.ALBUMS
        home.sourceCacheHits shouldNotContain MusicHomeSource.SONGS
        home.albums.single() shouldBe cachedAlbum
    }

    @Test
    fun `home songs initial load uses bounded paging`() {
        MusicPagingDefaults.HOME_SONG_LIMIT shouldBeLessThanOrEqual 100
    }

    private fun musicHome(
        albums: List<MusicItem> = emptyList(),
        albumsTotalCount: Int = albums.size,
        artists: List<MusicItem> = emptyList(),
        artistsTotalCount: Int = artists.size,
        songs: List<MusicItem> = emptyList(),
        songsTotalCount: Int = songs.size,
        sourceErrors: Map<MusicHomeSource, String> = emptyMap(),
        sourceCacheHits: Set<MusicHomeSource> = emptySet(),
    ) = MusicHome(
        library = null,
        recentlyAddedAlbums = emptyList(),
        albums = albums,
        albumsTotalCount = albumsTotalCount,
        artists = artists,
        artistsTotalCount = artistsTotalCount,
        songs = songs,
        songsTotalCount = songsTotalCount,
        genres = emptyList(),
        playlists = emptyList(),
        favorites = emptyList(),
        recentlyPlayed = emptyList(),
        recommendations = emptyList(),
        sourceErrors = sourceErrors,
        sourceCacheHits = sourceCacheHits,
    )

    private fun musicItem(id: UUID, type: BaseItemKind = BaseItemKind.MUSIC_ALBUM,) = MusicItem(
        id = id,
        title = "Item",
        subtitle = null,
        album = null,
        albumId = null,
        artist = null,
        artistIds = emptyList(),
        genres = emptyList(),
        type = type,
        collectionType = null,
        posterUrl = null,
        backdropUrl = null,
        container = null,
        codec = null,
        playCount = 0,
        progress = null,
        isFavorite = false,
        isFolder = type != BaseItemKind.AUDIO,
        isPlayable = type == BaseItemKind.AUDIO,
    )
}
