package org.jellyfin.mobile.feature.music

import org.jellyfin.sdk.model.UUID
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.CollectionType

data class MusicHome(
    val library: MusicLibrary?,
    val recentlyAddedAlbums: List<MusicItem>,
    val albums: List<MusicItem>,
    val albumsTotalCount: Int,
    val artists: List<MusicItem>,
    val artistsTotalCount: Int,
    val songs: List<MusicItem>,
    val songsTotalCount: Int,
    val genres: List<MusicItem>,
    val playlists: List<MusicItem>,
    val favorites: List<MusicItem>,
    val recentlyPlayed: List<MusicItem>,
    val recommendations: List<MusicItem>,
    val sourceErrors: Map<MusicHomeSource, String> = emptyMap(),
    val sourceCacheHits: Set<MusicHomeSource> = emptySet(),
) {
    val hasMoreSongs: Boolean
        get() = songs.size < songsTotalCount

    val songsError: String?
        get() = sourceErrors[MusicHomeSource.SONGS]

    val sections: List<MusicSection>
        get() = listOf(
            MusicSection(
                id = "recently-added",
                title = "Recently Added",
                subtitle = "Fresh albums in your PiggieTV library",
                kind = MusicSectionKind.ALBUM,
                items = recentlyAddedAlbums,
            ),
            MusicSection(
                id = "recommendations",
                title = "Recommended For You",
                subtitle = "Built from liked songs, recent plays, and shared genres",
                kind = MusicSectionKind.TRACK,
                items = recommendations,
            ),
            MusicSection(
                id = "recently-played",
                title = "Recently Played",
                subtitle = "Resume listening without digging",
                kind = MusicSectionKind.TRACK,
                items = recentlyPlayed,
            ),
            MusicSection(
                id = "albums",
                title = "Albums",
                subtitle = "Browse covers and collections",
                kind = MusicSectionKind.ALBUM,
                items = albums,
            ),
            MusicSection(
                id = "artists",
                title = "Artists",
                subtitle = "Jump into artist pages",
                kind = MusicSectionKind.ARTIST,
                items = artists,
            ),
            MusicSection(
                id = "playlists",
                title = "Playlists",
                subtitle = "Your Jellyfin playlists",
                kind = MusicSectionKind.PLAYLIST,
                items = playlists,
            ),
            MusicSection(
                id = "favorites",
                title = "Liked Songs",
                subtitle = "Favorites from your profile",
                kind = MusicSectionKind.TRACK,
                items = favorites,
            ),
        ).filter { section -> section.items.isNotEmpty() }
}

data class MusicLibrary(val id: UUID, val name: String)

enum class MusicHomeSource {
    RECENTLY_ADDED_ALBUMS,
    ALBUMS,
    ARTISTS,
    SONGS,
    GENRES,
    PLAYLISTS,
    FAVORITES,
    RECENTLY_PLAYED,
    RECOMMENDATIONS,
}

internal object MusicPagingDefaults {
    const val AUTO_PAGE_LIMIT = 100
    const val HOME_SONG_LIMIT = 36
}

data class MusicPage(val items: List<MusicItem>, val totalCount: Int, val startIndex: Int) {
    val nextStartIndex: Int
        get() = startIndex + items.size

    val hasMore: Boolean
        get() = nextStartIndex < totalCount
}

data class MusicSection(
    val id: String,
    val title: String,
    val subtitle: String?,
    val kind: MusicSectionKind,
    val items: List<MusicItem>,
)

enum class MusicSectionKind {
    ALBUM,
    ARTIST,
    GENRE,
    PLAYLIST,
    TRACK,
}

enum class MusicBrowseKind {
    RECENTLY_ADDED,
    ALBUMS,
    ARTISTS,
    SONGS,
    GENRES,
    PLAYLISTS,
    FAVORITES,
    RECENTLY_PLAYED,
    RECOMMENDATIONS,
}

data class MusicItem(
    val id: UUID,
    val title: String,
    val subtitle: String?,
    val album: String?,
    val albumId: UUID?,
    val artist: String?,
    val artistIds: List<UUID>,
    val genres: List<String>,
    val type: BaseItemKind,
    val collectionType: CollectionType?,
    val posterUrl: String?,
    val backdropUrl: String?,
    val container: String?,
    val codec: String?,
    val playCount: Int,
    val progress: Float?,
    val isFavorite: Boolean,
    val isFolder: Boolean,
    val isPlayable: Boolean,
)

data class MusicPlaybackState(
    val queue: List<MusicItem> = emptyList(),
    val currentItem: MusicItem? = null,
    val currentIndex: Int = -1,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val isEnded: Boolean = false,
    val durationMs: Long = 0,
    val positionMs: Long = 0,
    val bufferedPositionMs: Long = 0,
    val shuffleEnabled: Boolean = false,
    val repeatMode: MusicRepeatMode = MusicRepeatMode.NONE,
    val canSeek: Boolean = false,
    val codecCapability: MusicCodecCapability? = null,
    val errorMessage: String? = null,
    val failedItem: MusicItem? = null,
    val canRetry: Boolean = false,
    val isAppInForeground: Boolean = true,
    val isReleased: Boolean = false,
) {
    val remainingMs: Long
        get() = (durationMs - positionMs).coerceAtLeast(0)

    val hasCurrent: Boolean
        get() = currentItem != null
}

internal object MusicPlaybackFormatting {
    fun progressFraction(positionMs: Long, durationMs: Long): Float {
        if (durationMs <= 0) return 0f
        return (positionMs.coerceAtLeast(0).toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
    }

    fun elapsedTime(positionMs: Long): String = formatTime(positionMs.coerceAtLeast(0))

    fun durationTime(durationMs: Long): String = when {
        durationMs > 0 -> formatTime(durationMs)
        else -> UNKNOWN_TIME
    }

    fun remainingTime(durationMs: Long, positionMs: Long): String = when {
        durationMs > 0 -> {
            val remainingMs = (durationMs - positionMs).coerceAtLeast(0)
            when {
                remainingMs == 0L -> formatTime(0)
                else -> "-${formatTime(remainingMs)}"
            }
        }

        else -> UNKNOWN_TIME
    }

    private fun formatTime(valueMs: Long): String {
        val totalSeconds = (valueMs / 1000).coerceAtLeast(0)
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60

        return if (hours > 0) {
            "%d:%02d:%02d".format(hours, minutes, seconds)
        } else {
            "%d:%02d".format(minutes, seconds)
        }
    }

    private const val UNKNOWN_TIME = "--:--"
}

data class MusicCodecCapability(
    val label: String,
    val message: String,
    val isDirectPath: Boolean,
    val status: MusicCodecStatus = MusicCodecStatus.UNKNOWN,
    val container: String? = null,
    val codec: String? = null,
    val nativePlaybackExpected: Boolean = false,
)

enum class MusicCodecStatus {
    NATIVE,
    DIRECT_STREAM,
    TRANSCODE,
    LIMITED,
    UNKNOWN,
    UNSUPPORTED,
}

enum class MusicRepeatMode {
    NONE,
    ALL,
    ONE,
}

internal object MusicQueueNavigation {
    fun hasReachedPlaybackEnd(positionMs: Long, durationMs: Long, toleranceMs: Long): Boolean {
        if (durationMs <= 0 || toleranceMs < 0) return false
        return positionMs.coerceAtLeast(0) >= durationMs.saturatingSubtract(toleranceMs)
    }

    fun trackEndDecision(
        currentIndex: Int,
        queueSize: Int,
        repeatMode: MusicRepeatMode,
        failedIndexes: Set<Int> = emptySet(),
    ): MusicTrackEndDecision {
        if (repeatMode == MusicRepeatMode.ONE && currentIndex in 0 until queueSize) {
            return MusicTrackEndDecision.RestartCurrent
        }

        val nextIndex = nextIndex(
            currentIndex = currentIndex,
            queueSize = queueSize,
            repeatMode = repeatMode,
            failedIndexes = failedIndexes,
        )
        return nextIndex?.let(MusicTrackEndDecision::AdvanceTo) ?: MusicTrackEndDecision.Stop
    }

    fun nextIndex(
        currentIndex: Int,
        queueSize: Int,
        repeatMode: MusicRepeatMode,
        failedIndexes: Set<Int> = emptySet(),
    ): Int? {
        if (queueSize <= 0 || currentIndex !in 0 until queueSize) return null
        val allowWrap = repeatMode == MusicRepeatMode.ALL
        val upperBound = if (allowWrap) queueSize else queueSize - currentIndex - 1
        for (step in 1..upperBound) {
            val index = (currentIndex + step) % queueSize
            if (!allowWrap && index <= currentIndex) return null
            if (index !in failedIndexes) return index
        }
        return null
    }

    fun previousIndex(currentIndex: Int, queueSize: Int, repeatMode: MusicRepeatMode): Int? = when {
        queueSize <= 0 || currentIndex !in 0 until queueSize -> null
        currentIndex > 0 -> currentIndex - 1
        repeatMode == MusicRepeatMode.ALL -> queueSize - 1
        else -> null
    }
}

private fun Long.saturatingSubtract(value: Long): Long = (this - value).coerceAtLeast(0)

internal sealed interface MusicTrackEndDecision {
    data object RestartCurrent : MusicTrackEndDecision
    data class AdvanceTo(val index: Int) : MusicTrackEndDecision
    data object Stop : MusicTrackEndDecision
}

data class MusicPlaylistActionState(
    val isVisible: Boolean = false,
    val target: MusicPlaylistTarget? = null,
    val playlists: List<MusicItem> = emptyList(),
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val defaultName: String = "",
)

data class MusicPlaylistTarget(val item: MusicItem, val type: MusicPlaylistTargetType) {
    val title: String
        get() = when (type) {
            MusicPlaylistTargetType.CURRENT_TRACK -> "Add ${item.title}"
            MusicPlaylistTargetType.ALBUM -> "Playlist from ${item.title}"
            MusicPlaylistTargetType.ARTIST -> "Playlist from ${item.title}"
            MusicPlaylistTargetType.GENRE -> "Playlist from ${item.title}"
        }

    val createButtonText: String
        get() = when (type) {
            MusicPlaylistTargetType.CURRENT_TRACK -> "Create Playlist"
            MusicPlaylistTargetType.ALBUM -> "Create From Album"
            MusicPlaylistTargetType.ARTIST -> "Create From Artist"
            MusicPlaylistTargetType.GENRE -> "Create From Genre"
        }
}

enum class MusicPlaylistTargetType {
    CURRENT_TRACK,
    ALBUM,
    ARTIST,
    GENRE,
}
