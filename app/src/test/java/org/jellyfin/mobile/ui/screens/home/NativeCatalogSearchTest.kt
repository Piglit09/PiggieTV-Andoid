package org.jellyfin.mobile.ui.screens.home

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.jellyfin.sdk.model.api.BaseItemKind
import org.junit.jupiter.api.Test
import java.util.UUID

class NativeCatalogSearchTest {
    @Test
    fun `all search requests media video genres music genres and studios`() {
        NativeCatalogSearch.mediaTypes(NativeSearchFilter.ALL) shouldBe listOf(
            BaseItemKind.MOVIE,
            BaseItemKind.SERIES,
        )
        NativeCatalogSearch.includesVideoGenres(NativeSearchFilter.ALL) shouldBe true
        NativeCatalogSearch.includesMusicGenres(NativeSearchFilter.ALL) shouldBe true
        NativeCatalogSearch.includesStudios(NativeSearchFilter.ALL) shouldBe true
    }

    @Test
    fun `focused genre scope combines video and music genres while retaining types`() {
        val unrelated = item("unrelated", "Comedy", BaseItemKind.GENRE)
        val video = item("rock-video", "Rock & Roll Movies", BaseItemKind.GENRE)
        val music = item("rock-music", "Rock", BaseItemKind.MUSIC_GENRE)
        val duplicateMusic = music.copy(subtitle = "duplicate")

        NativeCatalogSearch.mediaTypes(NativeSearchFilter.GENRES) shouldBe emptyList()
        NativeCatalogSearch.includesVideoGenres(NativeSearchFilter.GENRES) shouldBe true
        NativeCatalogSearch.includesMusicGenres(NativeSearchFilter.GENRES) shouldBe true
        NativeCatalogSearch.includesStudios(NativeSearchFilter.GENRES) shouldBe false
        NativeCatalogSearch.mergeGenres(
            query = "rock",
            videoGenres = listOf(unrelated, video),
            musicGenres = listOf(music, duplicateMusic),
            limit = 10,
        ).shouldContainExactly(music, video)
    }

    @Test
    fun `focused studio scope excludes media and genres`() {
        NativeCatalogSearch.mediaTypes(NativeSearchFilter.STUDIOS) shouldBe emptyList()
        NativeCatalogSearch.includesVideoGenres(NativeSearchFilter.STUDIOS) shouldBe false
        NativeCatalogSearch.includesMusicGenres(NativeSearchFilter.STUDIOS) shouldBe false
        NativeCatalogSearch.includesStudios(NativeSearchFilter.STUDIOS) shouldBe true
    }

    @Test
    fun `category hint query uses one comma-delimited taxonomy parameter`() {
        val userId = UUID.randomUUID()

        val parameters = NativeCatalogSearch.categoryHintQueryParameters(
            userId = userId,
            query = "Rock",
            limit = 14,
        )

        parameters["userId"] shouldBe userId
        parameters["searchTerm"] shouldBe "Rock"
        parameters["includeItemTypes"] shouldBe "Genre,MusicGenre,Studio"
        (parameters["includeItemTypes"] is String) shouldBe true
        parameters["includeMedia"] shouldBe false
        parameters["limit"] shouldBe 14
    }

    @Test
    fun `category-only scopes get resilient budget while all stays bounded`() {
        NativeCatalogSearch.requestBudgets(NativeSearchFilter.ALL) shouldBe NativeSearchRequestBudgets(
            overallMs = 8_000L,
            mediaMs = 5_000L,
            categoriesMs = 3_000L,
        )
        NativeCatalogSearch.requestBudgets(NativeSearchFilter.GENRES) shouldBe NativeSearchRequestBudgets(
            overallMs = 25_000L,
            mediaMs = 0L,
            categoriesMs = 24_000L,
        )
        NativeCatalogSearch.requestBudgets(NativeSearchFilter.STUDIOS) shouldBe NativeSearchRequestBudgets(
            overallMs = 25_000L,
            mediaMs = 0L,
            categoriesMs = 24_000L,
        )
    }

    @Test
    fun `category navigation builds native filtered catalogs`() {
        val videoGenre = item("drama", "Drama", BaseItemKind.GENRE)
        val musicGenre = item("jazz", "Jazz", BaseItemKind.MUSIC_GENRE)
        val studio = item("studio", "Disney Channel", BaseItemKind.STUDIO)

        NativeCatalogSearch.categoryTarget(videoGenre) shouldBe NativeSearchCategoryTarget(
            includeItemTypes = listOf(BaseItemKind.MOVIE, BaseItemKind.SERIES),
            genreIds = listOf(videoGenre.id),
            subtitle = "Movies and shows in Drama",
        )
        NativeCatalogSearch.categoryTarget(musicGenre) shouldBe NativeSearchCategoryTarget(
            includeItemTypes = listOf(BaseItemKind.MUSIC_ALBUM),
            genreIds = listOf(musicGenre.id),
            subtitle = "Albums in Jazz",
        )
        NativeCatalogSearch.categoryTarget(studio) shouldBe NativeSearchCategoryTarget(
            includeItemTypes = listOf(BaseItemKind.MOVIE, BaseItemKind.SERIES),
            studioIds = listOf(studio.id),
            subtitle = "Titles from Disney Channel",
        )
    }

    @Test
    fun `only supported category kinds can open category catalogs`() {
        NativeCatalogSearch.categoryTarget(item("movie", "Drama", BaseItemKind.MOVIE)) shouldBe null
    }

    @Test
    fun `category cache reuses a normalized query for the same server and user`() {
        var now = 10L
        val cache = NativeSearchCategoryCache(ttlNanos = 100L, clockNanos = { now })
        val userId = UUID.randomUUID()
        val rock = item("rock", "Rock", BaseItemKind.MUSIC_GENRE)

        cache.put("server-a", userId, "  ROCK  ", listOf(rock))

        cache.get("server-a", userId, "rock") shouldBe listOf(rock)
        cache.get("server-b", userId, "rock") shouldBe null
    }

    @Test
    fun `category cache expires entries and stays bounded`() {
        var now = 0L
        val cache = NativeSearchCategoryCache(
            ttlNanos = 50L,
            maxEntries = 2,
            clockNanos = { now },
        )
        val userId = UUID.randomUUID()
        val rock = item("rock", "Rock", BaseItemKind.MUSIC_GENRE)

        cache.put("server", userId, "rock", listOf(rock))
        now = 50L
        cache.get("server", userId, "rock") shouldBe null

        cache.put("server", userId, "one", listOf(rock))
        cache.put("server", userId, "two", listOf(rock))
        cache.put("server", userId, "three", listOf(rock))

        cache.get("server", userId, "one") shouldBe null
        cache.get("server", userId, "two") shouldBe listOf(rock)
        cache.get("server", userId, "three") shouldBe listOf(rock)
    }

    private fun item(key: String, title: String, type: BaseItemKind) = NativeMediaItem(
        id = UUID.nameUUIDFromBytes(key.toByteArray()),
        title = title,
        subtitle = null,
        overview = null,
        type = type,
        collectionType = null,
        posterUrl = null,
        backdropUrl = null,
        progress = null,
        isFolder = type in NativeCatalogSearch.categoryTypes,
        isPlayable = false,
    )
}
