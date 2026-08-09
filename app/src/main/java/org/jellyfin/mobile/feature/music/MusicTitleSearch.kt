package org.jellyfin.mobile.feature.music

internal object MusicTitleSearch {
    fun loadedMatches(home: MusicHome, query: String, limit: Int): List<MusicItem> = rank(
        items = listOf(
            home.recentlyAddedAlbums,
            home.albums,
            home.artists,
            home.songs,
            home.genres,
            home.playlists,
            home.favorites,
            home.recentlyPlayed,
            home.recommendations,
        ).flatten(),
        query = query,
        limit = limit,
        includeUnmatched = false,
    )

    fun merge(query: String, loaded: List<MusicItem>, remote: List<MusicItem>, limit: Int,): List<MusicItem> = rank(
        items = remote + loaded,
        query = query,
        limit = limit,
        includeUnmatched = true,
    )

    private fun rank(items: List<MusicItem>, query: String, limit: Int, includeUnmatched: Boolean,): List<MusicItem> {
        val normalizedQuery = query.normalizeSearchText()
        if (normalizedQuery.isBlank() || limit <= 0) return emptyList()

        return items.asSequence()
            .distinctBy(MusicItem::id)
            .mapNotNull { item ->
                val score = item.searchScore(normalizedQuery)
                if (score == NO_MATCH && !includeUnmatched) null else item to score
            }
            .sortedWith(
                compareBy<Pair<MusicItem, Int>> { (_, score) -> score }
                    .thenBy { (item) -> item.title.length }
                    .thenBy { (item) -> item.title.lowercase() },
            )
            .map(Pair<MusicItem, Int>::first)
            .take(limit)
            .toList()
    }

    private fun MusicItem.searchScore(query: String): Int {
        val title = title.normalizeSearchText()
        val secondaryValues = listOfNotNull(artist, album, subtitle).map { value -> value.normalizeSearchText() }
        val allValues = listOf(title) + secondaryValues

        return when {
            title == query -> EXACT_TITLE_MATCH
            title.startsWith(query) -> TITLE_PREFIX_MATCH
            title.contains(query) -> TITLE_CONTAINS_MATCH
            secondaryValues.any { value -> value == query || value.startsWith(query) } -> SECONDARY_PREFIX_MATCH
            allValues.any { value -> value.contains(query) } -> ANY_FIELD_MATCH
            query.split(' ').all { word -> allValues.any { value -> value.contains(word) } } -> ALL_WORDS_MATCH
            else -> NO_MATCH
        }
    }

    private fun String.normalizeSearchText() = trim().lowercase().replace(WHITESPACE, " ")

    private val WHITESPACE = Regex("\\s+")
    private const val EXACT_TITLE_MATCH = 0
    private const val TITLE_PREFIX_MATCH = 1
    private const val TITLE_CONTAINS_MATCH = 2
    private const val SECONDARY_PREFIX_MATCH = 3
    private const val ANY_FIELD_MATCH = 4
    private const val ALL_WORDS_MATCH = 5
    private const val NO_MATCH = Int.MAX_VALUE
}
