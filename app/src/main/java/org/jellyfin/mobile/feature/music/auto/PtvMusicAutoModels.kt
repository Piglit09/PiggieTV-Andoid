package org.jellyfin.mobile.feature.music.auto

import org.jellyfin.mobile.feature.music.MusicBrowseKind
import org.jellyfin.sdk.model.UUID
import org.jellyfin.sdk.model.serializer.toUUIDOrNull

internal enum class PtvMusicAutoCategory(
    val mediaId: String,
    val title: String,
    val subtitle: String,
    val browseKind: MusicBrowseKind,
    val grid: Boolean = false,
) {
    RECENTLY_ADDED(
        mediaId = "ptv:auto:category:recently-added",
        title = "Latest Music",
        subtitle = "Fresh music from your PTV library",
        browseKind = MusicBrowseKind.RECENTLY_ADDED,
        grid = false,
    ),
    ALBUMS(
        mediaId = "ptv:auto:category:albums",
        title = "Albums",
        subtitle = "Browse album covers",
        browseKind = MusicBrowseKind.ALBUMS,
        grid = false,
    ),
    ARTISTS(
        mediaId = "ptv:auto:category:artists",
        title = "Artists",
        subtitle = "Play by artist",
        browseKind = MusicBrowseKind.ARTISTS,
    ),
    SONGS(
        mediaId = "ptv:auto:category:songs",
        title = "Songs",
        subtitle = "All songs",
        browseKind = MusicBrowseKind.SONGS,
    ),
    GENRES(
        mediaId = "ptv:auto:category:genres",
        title = "Genres",
        subtitle = "Browse by genre",
        browseKind = MusicBrowseKind.GENRES,
    ),
    PLAYLISTS(
        mediaId = "ptv:auto:category:playlists",
        title = "Playlists",
        subtitle = "Your Jellyfin playlists",
        browseKind = MusicBrowseKind.PLAYLISTS,
    ),
    FAVORITES(
        mediaId = "ptv:auto:category:favorites",
        title = "Liked Songs",
        subtitle = "Favorites from your profile",
        browseKind = MusicBrowseKind.FAVORITES,
    ),
    RECENTLY_PLAYED(
        mediaId = "ptv:auto:category:recently-played",
        title = "Recently Played",
        subtitle = "Resume listening",
        browseKind = MusicBrowseKind.RECENTLY_PLAYED,
    ),
    RECOMMENDATIONS(
        mediaId = "ptv:auto:category:recommendations",
        title = "For You",
        subtitle = "PTV picks from your library",
        browseKind = MusicBrowseKind.RECOMMENDATIONS,
    ),
    ;

    companion object {
        fun fromMediaId(mediaId: String): PtvMusicAutoCategory? = entries.firstOrNull { category ->
            category.mediaId == mediaId
        }
    }
}

internal enum class PtvMusicAutoMoreItem(
    val mediaId: String,
    val title: String,
    val subtitle: String,
    val isPlayable: Boolean,
    val isBrowsable: Boolean,
) {
    GENRES(
        mediaId = PtvMusicAutoCategory.GENRES.mediaId,
        title = PtvMusicAutoCategory.GENRES.title,
        subtitle = PtvMusicAutoCategory.GENRES.subtitle,
        isPlayable = false,
        isBrowsable = true,
    ),
    SHUFFLE_ALL(
        mediaId = PtvMusicAutoIds.SHUFFLE_ALL,
        title = "Shuffle All",
        subtitle = "Start a shuffled queue from songs",
        isPlayable = true,
        isBrowsable = false,
    ),
    MORE_LIKE_THIS(
        mediaId = PtvMusicAutoIds.MORE_LIKE_THIS,
        title = "Play More Like This",
        subtitle = "Start PTV Radio from the current song",
        isPlayable = true,
        isBrowsable = false,
    ),
    ALL_SONGS(
        mediaId = PtvMusicAutoCategory.SONGS.mediaId,
        title = "All Songs",
        subtitle = "Full song list",
        isPlayable = false,
        isBrowsable = true,
    ),
    ;
}

internal enum class PtvMusicAutoMix(
    val mediaId: String,
    val title: String,
    val subtitle: String,
) {
    FOR_YOU(
        mediaId = "ptv:auto:mix:for-you",
        title = "For You",
        subtitle = "Recommended tracks from your library",
    ),
    RECENTLY_PLAYED(
        mediaId = "ptv:auto:mix:recently-played",
        title = "Recently Played Mix",
        subtitle = "A quick queue from recent listens",
    ),
    LIKED(
        mediaId = "ptv:auto:mix:liked",
        title = "Liked Songs Mix",
        subtitle = "Favorites from your Jellyfin profile",
    ),
    CURRENT_ARTIST(
        mediaId = "ptv:auto:mix:current-artist",
        title = "Current Artist Mix",
        subtitle = "More from the artist playing now",
    ),
    CURRENT_GENRE(
        mediaId = "ptv:auto:mix:current-genre",
        title = "Current Genre Mix",
        subtitle = "More music in this style",
    ),
    HEAVY_ROTATION(
        mediaId = "ptv:auto:mix:heavy-rotation",
        title = "Heavy Rotation",
        subtitle = "Favorites and recent plays",
    ),
    LATEST(
        mediaId = "ptv:auto:mix:latest",
        title = "Recently Added Mix",
        subtitle = "Newest tracks in your library",
    ),
    ;

    companion object {
        fun fromMediaId(mediaId: String): PtvMusicAutoMix? = entries.firstOrNull { mix ->
            mix.mediaId == mediaId
        }
    }
}

internal data class PtvMusicAutoRootItem(
    val mediaId: String,
    val title: String,
    val subtitle: String,
    val isPlayable: Boolean = false,
    val isBrowsable: Boolean = true,
)

internal val PTV_MUSIC_AUTO_ROOT_ITEMS: List<PtvMusicAutoRootItem> = listOf(
    PtvMusicAutoRootItem(
        mediaId = PtvMusicAutoIds.RECOMMENDED_MIXES,
        title = "Recommended Mixes",
        subtitle = "Driving-safe PTV queues",
    ),
    PtvMusicAutoRootItem(
        mediaId = PtvMusicAutoMix.LATEST.mediaId,
        title = "Latest Music",
        subtitle = "Newest tracks in your library",
    ),
    PtvMusicAutoRootItem(
        mediaId = PtvMusicAutoCategory.FAVORITES.mediaId,
        title = PtvMusicAutoCategory.FAVORITES.title,
        subtitle = PtvMusicAutoCategory.FAVORITES.subtitle,
    ),
    PtvMusicAutoRootItem(
        mediaId = PtvMusicAutoCategory.RECENTLY_PLAYED.mediaId,
        title = PtvMusicAutoCategory.RECENTLY_PLAYED.title,
        subtitle = PtvMusicAutoCategory.RECENTLY_PLAYED.subtitle,
    ),
    PtvMusicAutoRootItem(
        mediaId = PtvMusicAutoCategory.GENRES.mediaId,
        title = PtvMusicAutoCategory.GENRES.title,
        subtitle = PtvMusicAutoCategory.GENRES.subtitle,
    ),
    PtvMusicAutoRootItem(
        mediaId = PtvMusicAutoCategory.ALBUMS.mediaId,
        title = PtvMusicAutoCategory.ALBUMS.title,
        subtitle = PtvMusicAutoCategory.ALBUMS.subtitle,
    ),
    PtvMusicAutoRootItem(
        mediaId = PtvMusicAutoCategory.ARTISTS.mediaId,
        title = PtvMusicAutoCategory.ARTISTS.title,
        subtitle = PtvMusicAutoCategory.ARTISTS.subtitle,
    ),
    PtvMusicAutoRootItem(
        mediaId = PtvMusicAutoCategory.PLAYLISTS.mediaId,
        title = PtvMusicAutoCategory.PLAYLISTS.title,
        subtitle = PtvMusicAutoCategory.PLAYLISTS.subtitle,
    ),
    PtvMusicAutoRootItem(
        mediaId = PtvMusicAutoIds.AUTO_PICKS,
        title = "PTV Auto Picks",
        subtitle = "Your saved PTV driving playlist",
    ),
    PtvMusicAutoRootItem(
        mediaId = PtvMusicAutoIds.MORE,
        title = "More",
        subtitle = "All songs, shuffle, and radio",
    ),
)

internal object PtvMusicAutoIds {
    const val ROOT = "ptv:auto:root"
    const val MORE = "ptv:auto:more"
    const val RECOMMENDED_MIXES = "ptv:auto:recommended-mixes"
    const val AUTO_PICKS = "ptv:auto:auto-picks"
    const val SHUFFLE_ALL = "ptv:auto:action:shuffle-all"
    const val MORE_LIKE_THIS = "ptv:auto:action:more-like-this"
    private const val ITEM_PREFIX = "ptv:auto:item:"
    private const val TRACK_PREFIX = "ptv:auto:track:"

    fun item(id: UUID): String = "$ITEM_PREFIX$id"

    fun track(id: UUID): String = "$TRACK_PREFIX$id"

    fun itemIdFromMediaId(mediaId: String): UUID? = when {
        mediaId.startsWith(ITEM_PREFIX) -> mediaId.removePrefix(ITEM_PREFIX).toUUIDOrNull()
        mediaId.startsWith(TRACK_PREFIX) -> mediaId.removePrefix(TRACK_PREFIX).toUUIDOrNull()
        else -> null
    }

    fun isPlayableTrack(mediaId: String): Boolean = mediaId.startsWith(TRACK_PREFIX)
}
