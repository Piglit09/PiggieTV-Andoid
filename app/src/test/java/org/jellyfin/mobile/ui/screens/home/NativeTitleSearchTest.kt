package org.jellyfin.mobile.ui.screens.home

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import org.jellyfin.sdk.model.api.BaseItemKind
import org.junit.jupiter.api.Test
import java.util.UUID

class NativeTitleSearchTest {
    @Test
    fun `server search targets movies and series without querying every episode`() {
        NativeTitleSearch.serverItemTypes shouldBe listOf(BaseItemKind.MOVIE, BaseItemKind.SERIES)
        NativeTitleSearch.serverItemTypes shouldNotContain BaseItemKind.EPISODE
    }

    @Test
    fun `exact series match ranks before prefix and unrelated server matches`() {
        val unrelatedRemoteMatch = item("remote", "A server metadata match", BaseItemKind.MOVIE)
        val prefixMatch = item("prefix", "Severance Aftershow", BaseItemKind.SERIES)
        val exactSeries = item("exact", "Severance", BaseItemKind.SERIES)

        val results = NativeTitleSearch.merge(
            query = "  severance  ",
            loaded = listOf(prefixMatch),
            remote = listOf(unrelatedRemoteMatch, exactSeries),
            limit = 10,
        )

        results.shouldContainExactly(exactSeries, prefixMatch, unrelatedRemoteMatch)
    }

    @Test
    fun `merge removes duplicate loaded and remote titles by item id`() {
        val remote = item("series", "The Bear", BaseItemKind.SERIES)
        val loadedCopy = remote.copy(subtitle = "Loaded copy")

        NativeTitleSearch.merge(
            query = "bear",
            loaded = listOf(loadedCopy),
            remote = listOf(remote),
            limit = 10,
        ) shouldBe listOf(remote)
    }

    @Test
    fun `series proven empty is excluded while indexed duplicate remains searchable`() {
        val emptyFolderSeries = item("empty", "The Office", BaseItemKind.SERIES, childCount = 0)
        val indexedSeries = item("indexed", "The Office", BaseItemKind.SERIES, childCount = 201)

        NativeTitleSearch.merge(
            query = "the office",
            loaded = emptyList(),
            remote = listOf(emptyFolderSeries, indexedSeries),
            limit = 10,
        ) shouldBe listOf(indexedSeries)
    }

    private fun item(key: String, title: String, type: BaseItemKind, childCount: Int? = null) = NativeMediaItem(
        id = UUID.nameUUIDFromBytes(key.toByteArray()),
        title = title,
        subtitle = null,
        overview = null,
        type = type,
        collectionType = null,
        posterUrl = null,
        backdropUrl = null,
        progress = null,
        isFolder = type == BaseItemKind.SERIES,
        isPlayable = type == BaseItemKind.MOVIE,
        childCount = childCount,
    )
}
