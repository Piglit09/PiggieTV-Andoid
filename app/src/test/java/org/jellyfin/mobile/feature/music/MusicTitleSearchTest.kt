package org.jellyfin.mobile.feature.music

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.jellyfin.sdk.model.api.BaseItemKind
import org.junit.jupiter.api.Test
import java.util.UUID

class MusicTitleSearchTest {
    @Test
    fun `loaded matches include artist and album metadata immediately`() {
        val artistMatch = item("artist", title = "HUMAN", artist = "elijah")
        val albumMatch = item("album", title = "Afterglow", album = "Fighting Myself")
        val unrelated = item("other", title = "Elsewhere", artist = "Someone Else")
        val home = emptyHome().copy(songs = listOf(unrelated, albumMatch, artistMatch))

        MusicTitleSearch.loadedMatches(home, query = "elijah", limit = 10) shouldBe listOf(artistMatch)
        MusicTitleSearch.loadedMatches(home, query = "fighting myself", limit = 10) shouldBe listOf(albumMatch)
    }

    @Test
    fun `exact title ranks first and remote metadata matches remain available`() {
        val metadataMatch = item("metadata", title = "Track 4", artist = "Muse")
        val prefixMatch = item("prefix", title = "Muse Live")
        val exactMatch = item("exact", title = "Muse")

        MusicTitleSearch.merge(
            query = "muse",
            loaded = listOf(prefixMatch),
            remote = listOf(metadataMatch, exactMatch),
            limit = 10,
        ).shouldContainExactly(exactMatch, prefixMatch, metadataMatch)
    }

    private fun emptyHome() = MusicHome(
        library = null,
        recentlyAddedAlbums = emptyList(),
        albums = emptyList(),
        albumsTotalCount = 0,
        artists = emptyList(),
        artistsTotalCount = 0,
        songs = emptyList(),
        songsTotalCount = 0,
        genres = emptyList(),
        playlists = emptyList(),
        favorites = emptyList(),
        recentlyPlayed = emptyList(),
        recommendations = emptyList(),
    )

    private fun item(
        key: String,
        title: String,
        artist: String? = null,
        album: String? = null,
    ) = MusicItem(
        id = UUID.nameUUIDFromBytes(key.toByteArray()),
        title = title,
        subtitle = artist,
        album = album,
        albumId = null,
        artist = artist,
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
