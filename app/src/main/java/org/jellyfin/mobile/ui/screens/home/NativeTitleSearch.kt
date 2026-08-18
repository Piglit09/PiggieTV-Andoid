package org.jellyfin.mobile.ui.screens.home

import org.jellyfin.sdk.model.api.BaseItemKind

/**
 * Search policy for the native Movies and Shows search box.
 *
 * Episodes are intentionally excluded from the server query. A series-title search can match
 * hundreds of episodes through their inherited series metadata, making the request slow and
 * pushing the actual series outside the first result page.
 */
internal object NativeTitleSearch {
    val serverItemTypes = listOf(BaseItemKind.MOVIE, BaseItemKind.SERIES)

    fun loadedMatches(
        home: NativeHomeContent,
        query: String,
        limit: Int,
        itemTypes: List<BaseItemKind> = serverItemTypes,
    ): List<NativeMediaItem> = rank(
        items = home.sections.flatMap(NativeMediaSection::items),
        query = query,
        limit = limit,
        includeUnmatched = false,
        itemTypes = itemTypes,
    )

    fun merge(
        query: String,
        loaded: List<NativeMediaItem>,
        remote: List<NativeMediaItem>,
        limit: Int,
        itemTypes: List<BaseItemKind> = serverItemTypes,
    ): List<NativeMediaItem> = rank(
        items = remote + loaded,
        query = query,
        limit = limit,
        includeUnmatched = true,
        itemTypes = itemTypes,
    )

    private fun rank(
        items: List<NativeMediaItem>,
        query: String,
        limit: Int,
        includeUnmatched: Boolean,
        itemTypes: List<BaseItemKind>,
    ): List<NativeMediaItem> {
        val normalizedQuery = query.normalizeSearchText()
        if (normalizedQuery.isBlank() || limit <= 0) return emptyList()

        return items.asSequence()
            .filter { item -> item.type in itemTypes }
            .filterNot { item -> item.type == BaseItemKind.SERIES && item.childCount == 0 }
            .distinctBy(NativeMediaItem::id)
            .mapNotNull { item ->
                val score = item.title.searchScore(normalizedQuery)
                if (score == NO_MATCH && !includeUnmatched) null else item to score
            }
            .sortedWith(
                compareBy<Pair<NativeMediaItem, Int>> { (_, score) -> score }
                    .thenBy { (item) -> item.title.length }
                    .thenBy { (item) -> item.title.lowercase() },
            )
            .map(Pair<NativeMediaItem, Int>::first)
            .take(limit)
            .toList()
    }

    private fun String.searchScore(query: String): Int {
        val title = normalizeSearchText()
        return when {
            title == query -> EXACT_MATCH
            title.startsWith(query) -> PREFIX_MATCH
            title.contains(query) -> CONTAINS_MATCH
            query.split(' ').all(title::contains) -> ALL_WORDS_MATCH
            else -> NO_MATCH
        }
    }

    private fun String.normalizeSearchText() = trim().lowercase().replace(WHITESPACE, " ")

    private val WHITESPACE = Regex("\\s+")
    private const val EXACT_MATCH = 0
    private const val PREFIX_MATCH = 1
    private const val CONTAINS_MATCH = 2
    private const val ALL_WORDS_MATCH = 3
    private const val NO_MATCH = Int.MAX_VALUE
}
