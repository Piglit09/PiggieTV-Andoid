package org.jellyfin.mobile.feature.music.auto

import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import androidx.media3.common.C
import androidx.media3.common.Player
import org.jellyfin.mobile.feature.music.MusicItem
import org.jellyfin.mobile.feature.music.MusicSongAction
import org.jellyfin.sdk.model.api.BaseItemKind
import org.junit.jupiter.api.Test
import java.util.UUID

class PtvMusicAutoModelsTest {
    @Test
    fun `category media ids are stable and resolvable`() {
        PtvMusicAutoCategory.entries.map(PtvMusicAutoCategory::mediaId).toSet().size shouldBe
            PtvMusicAutoCategory.entries.size

        PtvMusicAutoCategory.entries.forEach { category ->
            PtvMusicAutoCategory.fromMediaId(category.mediaId) shouldBe category
            category.toPtvMusicAutoDescriptor().isBrowsable.shouldBeTrue()
            category.toPtvMusicAutoDescriptor().isPlayable.shouldBeFalse()
        }
    }

    @Test
    fun `root items expose driver safe music rows`() {
        PTV_MUSIC_AUTO_ROOT_ITEMS.map(PtvMusicAutoRootItem::mediaId) shouldBe listOf(
            PtvMusicAutoIds.RECOMMENDED_MIXES,
            PtvMusicAutoMix.LATEST.mediaId,
            PtvMusicAutoCategory.FAVORITES.mediaId,
            PtvMusicAutoCategory.RECENTLY_PLAYED.mediaId,
            PtvMusicAutoCategory.GENRES.mediaId,
            PtvMusicAutoCategory.ALBUMS.mediaId,
            PtvMusicAutoCategory.ARTISTS.mediaId,
            PtvMusicAutoCategory.PLAYLISTS.mediaId,
            PtvMusicAutoIds.AUTO_PICKS,
            PtvMusicAutoIds.MORE,
        )
        PTV_MUSIC_AUTO_ROOT_ITEMS.any { item -> item.mediaId == PtvMusicAutoCategory.SONGS.mediaId }
            .shouldBeFalse()
    }

    @Test
    fun `recommended mixes have stable ids`() {
        PtvMusicAutoMix.fromMediaId(PtvMusicAutoMix.FOR_YOU.mediaId) shouldBe PtvMusicAutoMix.FOR_YOU
        PtvMusicAutoMix.fromMediaId(PtvMusicAutoMix.RECENTLY_PLAYED.mediaId) shouldBe PtvMusicAutoMix.RECENTLY_PLAYED
        PtvMusicAutoMix.fromMediaId(PtvMusicAutoMix.LIKED.mediaId) shouldBe PtvMusicAutoMix.LIKED
        PtvMusicAutoMix.fromMediaId(PtvMusicAutoMix.CURRENT_ARTIST.mediaId) shouldBe PtvMusicAutoMix.CURRENT_ARTIST
        PtvMusicAutoMix.fromMediaId(PtvMusicAutoMix.CURRENT_GENRE.mediaId) shouldBe PtvMusicAutoMix.CURRENT_GENRE
        PtvMusicAutoMix.fromMediaId(PtvMusicAutoMix.HEAVY_ROTATION.mediaId) shouldBe PtvMusicAutoMix.HEAVY_ROTATION
        PtvMusicAutoMix.fromMediaId(PtvMusicAutoMix.LATEST.mediaId) shouldBe PtvMusicAutoMix.LATEST
    }

    @Test
    fun `more actions use stable media ids`() {
        PtvMusicAutoMoreItem.GENRES.mediaId shouldBe PtvMusicAutoCategory.GENRES.mediaId
        PtvMusicAutoMoreItem.SHUFFLE_ALL.mediaId shouldBe PtvMusicAutoIds.SHUFFLE_ALL
        PtvMusicAutoMoreItem.MORE_LIKE_THIS.mediaId shouldBe PtvMusicAutoIds.MORE_LIKE_THIS
        PtvMusicAutoMoreItem.ALL_SONGS.mediaId shouldBe PtvMusicAutoCategory.SONGS.mediaId
        PtvMusicAutoMoreItem.SHUFFLE_ALL.isPlayable.shouldBeTrue()
        PtvMusicAutoMoreItem.MORE_LIKE_THIS.isPlayable.shouldBeTrue()
        PtvMusicAutoMoreItem.GENRES.isBrowsable.shouldBeTrue()
    }

    @Test
    fun `playable music item maps to playable track descriptor`() {
        val itemId = UUID.fromString("11111111-1111-1111-1111-111111111111")
        val descriptor = playableItem(itemId).toPtvMusicAutoDescriptor()

        descriptor.mediaId shouldBe PtvMusicAutoIds.track(itemId)
        descriptor.isPlayable.shouldBeTrue()
        descriptor.isBrowsable.shouldBeFalse()
        PtvMusicAutoIds.itemIdFromMediaId(descriptor.mediaId) shouldBe itemId
    }

    @Test
    fun `folder music item maps to browsable item descriptor`() {
        val itemId = UUID.fromString("22222222-2222-2222-2222-222222222222")
        val descriptor = folderItem(itemId).toPtvMusicAutoDescriptor()

        descriptor.mediaId shouldBe PtvMusicAutoIds.item(itemId)
        descriptor.isPlayable.shouldBeFalse()
        descriptor.isBrowsable.shouldBeTrue()
        PtvMusicAutoIds.itemIdFromMediaId(descriptor.mediaId) shouldBe itemId
    }

    @Test
    fun `auto player seek commands are gated by seek support`() {
        val seekableCommands = ptvMusicAutoPlayerCommandIds(canSeek = true)
        val nonSeekableCommands = ptvMusicAutoPlayerCommandIds(canSeek = false)

        (Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM in seekableCommands).shouldBeTrue()
        (Player.COMMAND_SEEK_FORWARD in seekableCommands).shouldBeTrue()
        (Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM in nonSeekableCommands).shouldBeFalse()
        (Player.COMMAND_SEEK_FORWARD in nonSeekableCommands).shouldBeFalse()
        (Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM in nonSeekableCommands).shouldBeTrue()
    }

    @Test
    fun `auto custom command actions are registered for now playing buttons`() {
        PtvMusicAutoCommand.actions shouldBe listOf(
            PtvMusicAutoCommand.ACTION_TOGGLE_SHUFFLE,
            PtvMusicAutoCommand.ACTION_CYCLE_REPEAT,
            PtvMusicAutoCommand.ACTION_TOGGLE_FAVORITE,
            PtvMusicAutoCommand.ACTION_ADD_TO_PLAYLIST,
            PtvMusicAutoCommand.ACTION_GENERATE_QUEUE,
        )
    }

    @Test
    fun `auto current song commands route to shared song actions`() {
        PtvMusicAutoCommand.songActionFor(PtvMusicAutoCommand.ACTION_TOGGLE_FAVORITE) shouldBe
            MusicSongAction.TOGGLE_FAVORITE
        PtvMusicAutoCommand.songActionFor(PtvMusicAutoCommand.ACTION_ADD_TO_PLAYLIST) shouldBe
            MusicSongAction.ADD_TO_PLAYLIST
        PtvMusicAutoCommand.songActionFor(PtvMusicAutoCommand.ACTION_GENERATE_QUEUE) shouldBe
            MusicSongAction.START_MIX
        PtvMusicAutoCommand.songActionFor(PtvMusicAutoCommand.ACTION_TOGGLE_SHUFFLE) shouldBe null
    }

    @Test
    fun `auto progress values sanitize unknown and negative times`() {
        C.TIME_UNSET.sanitizeAutoDurationMs() shouldBe 0
        (-1L).sanitizeAutoDurationMs() shouldBe 0
        175_000L.sanitizeAutoDurationMs() shouldBe 175_000L
        C.TIME_UNSET.sanitizeAutoPositionMs() shouldBe null
        (-1L).sanitizeAutoPositionMs() shouldBe null
        6_000L.sanitizeAutoPositionMs() shouldBe 6_000L
    }

    private fun playableItem(id: UUID) = musicItem(
        id = id,
        isPlayable = true,
        isFolder = false,
        type = BaseItemKind.AUDIO,
    )

    private fun folderItem(id: UUID) = musicItem(
        id = id,
        isPlayable = false,
        isFolder = true,
        type = BaseItemKind.MUSIC_ALBUM,
    )

    @Suppress("FunctionExpressionBody")
    private fun musicItem(id: UUID, isPlayable: Boolean, isFolder: Boolean, type: BaseItemKind): MusicItem {
        return MusicItem(
            id = id,
            title = "Track",
            subtitle = "Artist",
            album = "Album",
            albumId = null,
            artist = "Artist",
            artistIds = emptyList(),
            genres = emptyList(),
            type = type,
            collectionType = null,
            posterUrl = "https://example.invalid/image.jpg",
            backdropUrl = null,
            container = "mp3",
            codec = "mp3",
            playCount = 0,
            progress = null,
            isFavorite = false,
            isFolder = isFolder,
            isPlayable = isPlayable,
        )
    }
}
