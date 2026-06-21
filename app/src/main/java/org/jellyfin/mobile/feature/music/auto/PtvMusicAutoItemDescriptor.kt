package org.jellyfin.mobile.feature.music.auto

import org.jellyfin.mobile.feature.music.MusicItem
import org.jellyfin.mobile.feature.music.MusicPage

internal data class PtvMusicAutoItemDescriptor(
    val mediaId: String,
    val title: String,
    val subtitle: String?,
    val artist: String?,
    val album: String?,
    val artworkUri: String?,
    val isBrowsable: Boolean,
    val isPlayable: Boolean,
    val grid: Boolean = false,
)

@Suppress("FunctionExpressionBody")
internal fun PtvMusicAutoCategory.toPtvMusicAutoDescriptor(): PtvMusicAutoItemDescriptor {
    return PtvMusicAutoItemDescriptor(
        mediaId = mediaId,
        title = title,
        subtitle = subtitle,
        artist = null,
        album = null,
        artworkUri = null,
        isBrowsable = true,
        isPlayable = false,
        grid = grid,
    )
}

@Suppress("FunctionExpressionBody")
internal fun MusicItem.toPtvMusicAutoDescriptor(): PtvMusicAutoItemDescriptor {
    return PtvMusicAutoItemDescriptor(
        mediaId = if (isPlayable) PtvMusicAutoIds.track(id) else PtvMusicAutoIds.item(id),
        title = title,
        subtitle = subtitle,
        artist = artist,
        album = album,
        artworkUri = posterUrl,
        isBrowsable = isFolder && !isPlayable,
        isPlayable = isPlayable,
    )
}

internal fun MusicPage.toPtvMusicAutoDescriptors(): List<PtvMusicAutoItemDescriptor> =
    items.map(MusicItem::toPtvMusicAutoDescriptor)
