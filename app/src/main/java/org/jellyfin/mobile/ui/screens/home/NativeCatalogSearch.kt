package org.jellyfin.mobile.ui.screens.home

import org.jellyfin.sdk.model.UUID
import org.jellyfin.sdk.model.api.BaseItemKind

enum class NativeSearchFilter(val label: String) {
    ALL("All"),
    MOVIES("Movies"),
    SHOWS("Shows"),
    GENRES("Genres"),
    STUDIOS("Studios"),
}

internal data class NativeSearchResultGroups(
    val media: List<NativeMediaItem> = emptyList(),
    val genres: List<NativeMediaItem> = emptyList(),
    val studios: List<NativeMediaItem> = emptyList(),
) {
    val totalCount: Int
        get() = media.size + genres.size + studios.size
}

internal data class NativeSearchCategoryTarget(
    val includeItemTypes: List<BaseItemKind>,
    val genreIds: List<UUID> = emptyList(),
    val studioIds: List<UUID> = emptyList(),
    val subtitle: String,
)

internal data class NativeSearchRequestBudgets(val overallMs: Long, val mediaMs: Long, val categoriesMs: Long)

/**
 * Shared policy for the native catalog search surface.
 *
 * Genres intentionally combines Jellyfin's separate Genre and MusicGenre directories while
 * retaining each result's concrete type. The type determines which native catalog opens when a
 * result is selected.
 */
internal object NativeCatalogSearch {
    fun mediaTypes(filter: NativeSearchFilter): List<BaseItemKind> = when (filter) {
        NativeSearchFilter.ALL -> listOf(BaseItemKind.MOVIE, BaseItemKind.SERIES)

        NativeSearchFilter.MOVIES -> listOf(BaseItemKind.MOVIE)

        NativeSearchFilter.SHOWS -> listOf(BaseItemKind.SERIES)

        NativeSearchFilter.GENRES,
        NativeSearchFilter.STUDIOS,
        -> emptyList()
    }

    fun includesVideoGenres(filter: NativeSearchFilter) =
        filter == NativeSearchFilter.ALL || filter == NativeSearchFilter.GENRES

    fun includesMusicGenres(filter: NativeSearchFilter) =
        filter == NativeSearchFilter.ALL || filter == NativeSearchFilter.GENRES

    fun includesStudios(filter: NativeSearchFilter) =
        filter == NativeSearchFilter.ALL || filter == NativeSearchFilter.STUDIOS

    fun requestBudgets(filter: NativeSearchFilter): NativeSearchRequestBudgets = if (mediaTypes(filter).isEmpty()) {
        NativeSearchRequestBudgets(
            overallMs = CATEGORY_ONLY_OVERALL_TIMEOUT_MS,
            mediaMs = 0L,
            categoriesMs = CATEGORY_ONLY_BRANCH_TIMEOUT_MS,
        )
    } else {
        NativeSearchRequestBudgets(
            overallMs = STANDARD_OVERALL_TIMEOUT_MS,
            mediaMs = STANDARD_MEDIA_BRANCH_TIMEOUT_MS,
            categoriesMs = STANDARD_CATEGORY_BRANCH_TIMEOUT_MS,
        )
    }

    fun categoryHintQueryParameters(userId: UUID, query: String, limit: Int): Map<String, Any> = linkedMapOf(
        "userId" to userId,
        "searchTerm" to query,
        // Jellyfin documents this value as one comma-delimited parameter. The generated SDK
        // expands collections into repeated parameters, which works on the current server but is
        // not handled consistently by every reverse proxy and server version.
        "includeItemTypes" to categoryTypes.joinToString(",") { type -> type.serialName },
        "includePeople" to false,
        "includeMedia" to false,
        "includeGenres" to true,
        "includeStudios" to true,
        "includeArtists" to false,
        "limit" to limit,
    )

    fun mergeGenres(
        query: String,
        videoGenres: List<NativeMediaItem>,
        musicGenres: List<NativeMediaItem>,
        limit: Int,
    ): List<NativeMediaItem> = rankCategories(
        query = query,
        items = videoGenres + musicGenres,
        limit = limit,
    )

    fun rankCategories(query: String, items: List<NativeMediaItem>, limit: Int): List<NativeMediaItem> {
        val normalizedQuery = query.normalizeSearchText()
        if (normalizedQuery.isBlank() || limit <= 0) return emptyList()

        return items.asSequence()
            .filter { item -> item.type in categoryTypes }
            .distinctBy { item -> item.type to item.id }
            .map { item -> item to item.title.searchScore(normalizedQuery) }
            .filter { (_, score) -> score != NO_MATCH }
            .sortedWith(
                compareBy<Pair<NativeMediaItem, Int>> { (_, score) -> score }
                    .thenBy { (item) -> item.title.length }
                    .thenBy { (item) -> item.title.lowercase() }
                    .thenBy { (item) -> item.type.serialName },
            )
            .map(Pair<NativeMediaItem, Int>::first)
            .take(limit)
            .toList()
    }

    fun categoryTarget(item: NativeMediaItem): NativeSearchCategoryTarget? = when (item.type) {
        BaseItemKind.GENRE -> NativeSearchCategoryTarget(
            includeItemTypes = listOf(BaseItemKind.MOVIE, BaseItemKind.SERIES),
            genreIds = listOf(item.id),
            subtitle = "Movies and shows in ${item.title}",
        )

        BaseItemKind.MUSIC_GENRE -> NativeSearchCategoryTarget(
            includeItemTypes = listOf(BaseItemKind.MUSIC_ALBUM),
            genreIds = listOf(item.id),
            subtitle = "Albums in ${item.title}",
        )

        BaseItemKind.STUDIO -> NativeSearchCategoryTarget(
            includeItemTypes = listOf(BaseItemKind.MOVIE, BaseItemKind.SERIES),
            studioIds = listOf(item.id),
            subtitle = "Titles from ${item.title}",
        )

        else -> null
    }

    val categoryTypes = setOf(BaseItemKind.GENRE, BaseItemKind.MUSIC_GENRE, BaseItemKind.STUDIO)

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
    private const val STANDARD_OVERALL_TIMEOUT_MS = 8_000L
    private const val STANDARD_MEDIA_BRANCH_TIMEOUT_MS = 5_000L
    private const val STANDARD_CATEGORY_BRANCH_TIMEOUT_MS = 3_000L
    private const val CATEGORY_ONLY_OVERALL_TIMEOUT_MS = 25_000L
    private const val CATEGORY_ONLY_BRANCH_TIMEOUT_MS = 24_000L
}

/**
 * Small in-memory cache for taxonomy hints. Genres and studios change infrequently, while users
 * commonly switch between All, Genres, and Studios for the same query. Reusing the consolidated
 * hint response prevents those scope changes from creating another burst of server work.
 */
internal class NativeSearchCategoryCache(
    private val ttlNanos: Long = DEFAULT_TTL_NANOS,
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
    private val clockNanos: () -> Long = System::nanoTime,
) {
    private data class Key(val serverKey: String, val userId: UUID, val query: String)

    private data class Entry(val storedAtNanos: Long, val items: List<NativeMediaItem>)

    private val entries = LinkedHashMap<Key, Entry>(16, 0.75f, true)

    @Synchronized
    fun get(serverKey: String, userId: UUID, query: String): List<NativeMediaItem>? {
        val key = Key(serverKey, userId, query.cacheKey())
        val entry = entries[key] ?: return null
        if (clockNanos() - entry.storedAtNanos >= ttlNanos) {
            entries.remove(key)
            return null
        }
        return entry.items
    }

    @Synchronized
    fun put(serverKey: String, userId: UUID, query: String, items: List<NativeMediaItem>) {
        val key = Key(serverKey, userId, query.cacheKey())
        entries[key] = Entry(clockNanos(), items.toList())
        while (entries.size > maxEntries) {
            entries.remove(entries.entries.first().key)
        }
    }

    @Synchronized
    fun clear() = entries.clear()

    private fun String.cacheKey() = trim().lowercase().replace(Regex("\\s+"), " ")

    internal companion object {
        const val DEFAULT_MAX_ENTRIES = 32
        const val DEFAULT_TTL_NANOS = 120_000_000_000L
    }
}
